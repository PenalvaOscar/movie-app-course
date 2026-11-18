import Testing
@testable import EncoreCore

/// Port of Android `GenieViewModelTest` (12). Updates travel through actors and an `AsyncStream`,
/// so assertions wait with `eventually` (bounded) instead of a virtual-time scheduler.
@MainActor
@Suite struct GenieModelTests {
    private let catalog: FakeMovieCatalog
    private let ratings: RatingRepository

    init() {
        catalog = FakeMovieCatalog(movies: ["liked", "a", "b", "c", "horror"].map { summary($0) })
        catalog.features = [
            MovieFeatures(id: "liked", genres: ["Drama"], director: "Ada Vale", cast: [], popularity: 5.0),
            MovieFeatures(id: "a", genres: ["Drama"], director: nil, cast: [], popularity: 9.0), // 1
            MovieFeatures(id: "b", genres: ["Drama"], director: "Ada Vale", cast: [], popularity: 1.0), // 4
            MovieFeatures(id: "c", genres: ["Comedy"], director: nil, cast: [], popularity: 8.0), // 0
            MovieFeatures(id: "horror", genres: ["Horror"], director: nil, cast: [], popularity: 7.0),
        ]
        let clock = TestClock(1, step: 1)
        ratings = RatingRepository(store: InMemoryRatingStore(), clock: { clock.read() })
    }

    private func newModel() async -> GenieModel {
        let vm = GenieModel(catalog: catalog, ratings: ratings)
        #expect(await eventually { vm.state != .loading })
        return vm
    }

    private func ids(_ vm: GenieModel) -> [String]? {
        if case let .content(items, _, _) = vm.state { items.map(\.movie.id) } else { nil }
    }

    @Test func withoutLikesOrDislikesGenieExplainsHowToStart() async {
        #expect(await newModel().state == .needsRatings)
    }

    @Test func unseenRatingsAloneAreNotEnough() async throws {
        try await ratings.setRating(.unseen, for: "a")

        #expect(await newModel().state == .needsRatings)
    }

    @Test func aLikeShowsScoredRecommendationsWithReasonsAndCounts() async throws {
        try await ratings.setRating(.like, for: "liked")

        let vm = await newModel()

        guard case let .content(items, liked, disliked) = vm.state else {
            Issue.record("expected content, got \(vm.state)")
            return
        }
        #expect(items.map(\.movie.id) == ["b", "a"])
        #expect(items.first?.movie.title == "Film b")
        #expect(items.first?.reasons == [ReasonLine(kind: .director, names: ["Ada Vale"]), ReasonLine(kind: .genre, names: ["Drama"])])
        #expect(liked == 1)
        #expect(disliked == 0)
    }

    @Test func ratingARecommendationAnywhereRemovesItLive() async throws {
        try await ratings.setRating(.like, for: "liked")
        let vm = await newModel()

        try await ratings.setRating(.unseen, for: "b")

        #expect(await eventually { ids(vm) == ["a"] })
    }

    @Test func undoBringsARecommendationBack() async throws {
        try await ratings.setRating(.like, for: "liked")
        let vm = await newModel()
        try await ratings.setRating(.dislike, for: "b") // cancels Drama and Ada Vale: nothing scores
        #expect(await eventually { vm.state == .noMatches(likedCount: 1, dislikedCount: 1) })

        try await ratings.undo()

        #expect(await eventually { ids(vm) == ["b", "a"] })
    }

    @Test func preferencesWithNoPositiveMatchShowTheNoMatchesState() async throws {
        try await ratings.setRating(.dislike, for: "horror")

        #expect(await newModel().state == .noMatches(likedCount: 0, dislikedCount: 1))
    }

    @Test func catalogFeaturesAreLoadedOnceNotOnEveryRatingChange() async throws {
        try await ratings.setRating(.like, for: "liked")
        let vm = await newModel()

        try await ratings.setRating(.dislike, for: "a")
        #expect(await eventually { ids(vm) == ["b"] })
        try await ratings.setRating(.like, for: "c")
        #expect(await eventually {
            if case let .content(_, liked, _) = vm.state { liked == 2 } else { false }
        })

        #expect(catalog.featureLoads == 1)
    }

    @Test func aRatingChangeDuringTheFirstCatalogReadDoesNotRestartIt() async throws {
        try await ratings.setRating(.like, for: "liked")
        let gate = Gate()
        catalog.featureGate = gate
        let vm = GenieModel(catalog: catalog, ratings: ratings)
        #expect(await eventually { catalog.featureLoads == 1 })
        #expect(vm.state == .loading)

        try await ratings.setRating(.unseen, for: "b")
        await settle()
        gate.open()

        #expect(await eventually { ids(vm) == ["a"] })
        #expect(catalog.featureLoads == 1)
    }

    @Test func likesOfMoviesNoLongerInTheCatalogAreNotCounted() async throws {
        try await ratings.setRating(.like, for: "gone")
        try await ratings.setRating(.like, for: "liked")

        let vm = await newModel()

        guard case let .content(_, liked, _) = vm.state else {
            Issue.record("expected content, got \(vm.state)")
            return
        }
        #expect(liked == 1)
    }

    @Test func aRecommendationWhoseCardIsMissingIsSkipped() async throws {
        catalog.movies.removeAll { $0.id == "b" }
        try await ratings.setRating(.like, for: "liked")

        #expect(ids(await newModel()) == ["a"])
    }

    @Test func aReadFailureShowsTheErrorStateAndRetryRecovers() async throws {
        try await ratings.setRating(.like, for: "liked")
        catalog.failWith = TestError()
        let vm = await newModel()
        #expect(vm.state == .error)

        catalog.failWith = nil
        vm.retry()

        #expect(await eventually { ids(vm) == ["b", "a"] })
    }

    // MARK: iOS additions (review gate)

    @Test func aRatingsReadFailureShowsTheErrorStateAndRetryRecovers() async throws {
        let store = FailingReadStore()
        let repo = RatingRepository(store: store, clock: { 1 })
        try await repo.setRating(.like, for: "liked") // the write works; only reads fail
        let unloaded = RatingRepository(store: store, clock: { 1 })
        let vm = GenieModel(catalog: catalog, ratings: unloaded, logError: { _, _ in })
        #expect(await eventually { vm.state == .error })

        store.failReads = false
        vm.retry()

        #expect(await eventually { ids(vm) == ["b", "a"] })
    }

    @Test func anOlderComputationFinishingLastNeverReplacesTheNewerResult() async throws {
        let older = Gate()
        let newer = Gate()
        catalog.summariesGates = [older, newer]
        try await ratings.setRating(.like, for: "liked")
        let vm = GenieModel(catalog: catalog, ratings: ratings)
        #expect(await eventually { catalog.summariesGates.count == 1 }) // the first list waits for cards

        try await ratings.setRating(.unseen, for: "b")
        #expect(await eventually { catalog.summariesGates.isEmpty })
        newer.open()
        #expect(await eventually { ids(vm) == ["a"] })
        older.open()
        await settle(200)

        #expect(ids(vm) == ["a"])
    }

    @Test func aFailedSharedFeatureReadIsReadAgainOnRetry() async throws {
        try await ratings.setRating(.like, for: "liked")
        let gate = Gate()
        catalog.featureGate = gate
        catalog.failWith = TestError()
        let vm = GenieModel(catalog: catalog, ratings: ratings, logError: { _, _ in })
        #expect(await eventually { catalog.featureLoads == 1 })
        try await ratings.setRating(.dislike, for: "horror") // a second computation joins the same read
        await settle()

        gate.open()
        #expect(await eventually { vm.state == .error })
        #expect(catalog.featureLoads == 1)
        catalog.failWith = nil
        vm.retry()

        #expect(await eventually { ids(vm) == ["b", "a"] })
        #expect(catalog.featureLoads == 2)
    }

    @Test func reasonLinesMergeNamesOfTheSameKindInOrder() {
        let lines = ReasonLine.lines(from: [
            Reason(kind: .genre, name: "Drama", points: 2),
            Reason(kind: .genre, name: "Romance", points: 1),
        ])

        #expect(lines == [ReasonLine(kind: .genre, names: ["Drama", "Romance"])])
        #expect(ReasonLine.lines(from: []).isEmpty)
    }
}

/// Writes work; `state()` throws while `failReads` is set.
@MainActor
final class FailingReadStore: RatingStore {
    private let inner = InMemoryRatingStore()
    var failReads = true

    func state() async throws -> RatingState {
        if failReads { throw TestError() }
        return await inner.state()
    }

    func write<Result: Sendable>(_ body: @Sendable (any RatingTransaction) throws -> Result) async throws -> RatingWrite<Result> {
        try await inner.write(body)
    }
}
