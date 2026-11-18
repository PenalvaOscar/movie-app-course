import Foundation
import Testing
@testable import EncoreCore

/// Port of Android `SwipeViewModelTest` (9). Updates travel through actors and an `AsyncStream`,
/// so assertions wait with `eventually` (bounded) instead of a virtual-time scheduler.
@MainActor
@Suite struct SwipeModelTests {
    private let ids = ["p1", "p2", "p3"]
    private let catalog: FakeMovieCatalog
    private let store = InMemoryRatingStore()
    private let ratings: RatingRepository

    init() {
        catalog = FakeMovieCatalog(
            movies: ids.map { summary($0) },
            details: Dictionary(uniqueKeysWithValues: ids.map { ($0, movie($0)) })
        )
        ratings = RatingRepository(store: store, clock: { 1 })
    }

    private func newModel() async -> SwipeModel {
        let vm = SwipeModel(catalog: catalog, ratings: ratings)
        #expect(await eventually { vm.state != .loading })
        return vm
    }

    private func current(_ vm: SwipeModel) -> String? {
        if case let .content(current, _, _) = vm.state { current.id } else { nil }
    }

    private func next(_ vm: SwipeModel) -> String? {
        if case let .content(_, next, _) = vm.state { next?.id } else { nil }
    }

    private func remaining(_ vm: SwipeModel) -> Int? {
        if case let .content(_, _, remaining) = vm.state { remaining } else { nil }
    }

    @Test func showsTheMostPopularMovieFirstWithTheNextOneBehindIt() async {
        let vm = await newModel()

        #expect(current(vm) == "p1")
        #expect(next(vm) == "p2")
        #expect(remaining(vm) == 3)
    }

    @Test func moviesRatedAnywhereUnseenIncludedNeverEnterTheQueue() async throws {
        try await ratings.setRating(.unseen, for: "p1")
        try await ratings.setRating(.dislike, for: "p2")

        let vm = await newModel()

        #expect(current(vm) == "p3")
        #expect(next(vm) == nil)
        #expect(remaining(vm) == 1)
    }

    @Test func ratingSavesTheValueAndMovesToTheNextMovie() async {
        let vm = await newModel()

        vm.rate("p1", .like)

        #expect(await eventually { ratings.value(for: "p1") == .like && next(vm) == "p3" })
        #expect(current(vm) == "p2")
        #expect(remaining(vm) == 2)
        #expect(vm.canUndo)
    }

    @Test func theCardLeavesAtOnceBeforeTheWriteFinishes() async {
        let vm = await newModel()

        vm.rate("p1", .like)

        #expect(current(vm) == "p2")
        #expect(remaining(vm) == 2)
    }

    @Test func undoBringsTheMovieBackToTheFrontAndReportsWhatItDid() async {
        let vm = await newModel()
        vm.rate("p1", .dislike)
        #expect(await eventually { vm.canUndo })

        vm.undo()

        #expect(await eventually { vm.notice != nil && current(vm) == "p1" })
        #expect(remaining(vm) == 3)
        #expect(vm.canUndo == false)
        #expect(vm.notice == SwipeNotice(id: 1, message: .undone(title: "Film p1", restored: nil)))
    }

    @Test func undoOfAChangeMadeInDetailsReportsTheRestoredValue() async throws {
        try await ratings.setRating(.like, for: "p3")
        try await ratings.setRating(.dislike, for: "p3")
        let vm = await newModel()

        vm.undo()

        #expect(await eventually { vm.notice != nil })
        #expect(ratings.value(for: "p3") == .like)
        #expect(vm.notice?.message == .undone(title: "Film p3", restored: .like))
        #expect(remaining(vm) == 2)
    }

    @Test func whenEverythingIsRatedTheQueueIsDone() async {
        let vm = await newModel()

        ids.forEach { vm.rate($0, .like) }

        #expect(await eventually { vm.state == .allRated && ratings.ratings.count == 3 })
    }

    @Test func aFailedSavePutsTheCardBackAndSaysSo() async {
        let vm = await newModel()
        await store.setFailWrites(TestError())

        vm.rate("p1", .like)

        #expect(await eventually { vm.notice != nil && current(vm) == "p1" })
        #expect(vm.notice == SwipeNotice(id: 1, message: .saveFailed))
        #expect(remaining(vm) == 3)
    }

    @Test func aCatalogReadFailureShowsTheErrorAndRetryRecovers() async {
        catalog.failWith = TestError()
        let vm = await newModel()
        #expect(vm.state == .error)

        catalog.failWith = nil
        vm.retry()
        #expect(vm.state == .loading)

        #expect(await eventually { current(vm) == "p1" })
    }

    // MARK: iOS

    @Test func aCardRatedHereAndUndoneElsewhereComesBack() async {
        let vm = await newModel()
        vm.rate("p1", .like)
        #expect(await eventually { ratings.value(for: "p1") == .like })

        _ = try? await ratings.undo() // e.g. from History or a shell toolbar, not SwipeModel

        #expect(await eventually { current(vm) == "p1" && remaining(vm) == 3 })
    }

    @Test func rateThenImmediateUndoEndsWithTheCardBack() async {
        let vm = await newModel()

        vm.rate("p1", .dislike)
        vm.undo()

        #expect(await eventually { current(vm) == "p1" && remaining(vm) == 3 && ratings.ratings.isEmpty })
    }

    @Test func dismissingANoticeClearsOnlyThatNotice() async {
        let vm = await newModel()
        await store.setFailWrites(TestError())
        vm.rate("p1", .like)
        #expect(await eventually { vm.notice?.id == 1 })

        vm.dismissNotice(id: 99)
        #expect(vm.notice?.id == 1)
        vm.dismissNotice(id: 1)
        #expect(vm.notice == nil)
    }
}

/// Port of Android `HistoryViewModelTest` (6).
@MainActor
@Suite struct HistoryModelTests {
    private let catalog = FakeMovieCatalog(movies: [summary("a"), summary("b"), summary("c")])
    private let ratings = RatingRepository(store: InMemoryRatingStore(), clock: TestClock(1, step: 1).read)

    private func ids(_ vm: HistoryModel) -> [String]? {
        if case let .content(items) = vm.items { items.map(\.movie.id) } else { nil }
    }

    @Test func listsRatedMoviesNewestFirstWithTheirValuesAndCounts() async throws {
        try await ratings.setRating(.like, for: "a")
        try await ratings.setRating(.unseen, for: "b")
        try await ratings.setRating(.like, for: "c")

        let vm = HistoryModel(catalog: catalog, ratings: ratings)

        #expect(await eventually { vm.items != .loading })
        guard case let .content(items) = vm.items else { Issue.record("no content"); return }
        #expect(items.map { "\($0.movie.id):\($0.value)" } == ["c:like", "b:unseen", "a:like"])
        #expect(vm.counts == [.like: 2, .unseen: 1])
    }

    @Test func aFilterShowsOnlyThatValue() async throws {
        try await ratings.setRating(.like, for: "a")
        try await ratings.setRating(.dislike, for: "b")
        let vm = HistoryModel(catalog: catalog, ratings: ratings)
        #expect(await eventually { vm.items != .loading })

        vm.selectFilter(.dislike)

        #expect(ids(vm) == ["b"])
        #expect(vm.filter == .dislike)
        #expect(vm.counts == [.like: 1, .dislike: 1]) // counts ignore the filter
    }

    @Test func aFilterWithNoMatchesIsEmptyNotLoading() async throws {
        try await ratings.setRating(.like, for: "a")
        let vm = HistoryModel(catalog: catalog, ratings: ratings)
        #expect(await eventually { vm.items != .loading })

        vm.selectFilter(.unseen)

        #expect(vm.items == .empty)
    }

    @Test func followsNewRatingsAndUndoLive() async throws {
        let vm = HistoryModel(catalog: catalog, ratings: ratings)
        #expect(await eventually { vm.items == .empty })

        try await ratings.setRating(.like, for: "a")
        #expect(await eventually { ids(vm) == ["a"] })

        try await ratings.undo()
        #expect(await eventually { vm.items == .empty })
    }

    @Test func ratingsForMoviesNoLongerInTheCatalogAreKeptButHidden() async throws {
        try await ratings.setRating(.like, for: "gone")
        try await ratings.setRating(.dislike, for: "a")

        let vm = HistoryModel(catalog: catalog, ratings: ratings)

        #expect(await eventually { vm.items != .loading })
        #expect(ids(vm) == ["a"])
        #expect(vm.counts == [.dislike: 1])
        #expect(ratings.ratings.count == 2)
    }

    @Test func aCatalogReadFailureShowsTheError() async throws {
        try await ratings.setRating(.like, for: "a")
        catalog.failWith = TestError()

        let vm = HistoryModel(catalog: catalog, ratings: ratings)

        #expect(await eventually { vm.items == .error })
    }
}

/// The two rating cases of Android `MovieDetailsViewModelTest`.
@MainActor
@Suite struct DetailsRatingTests {
    private let ratings = RatingRepository(store: InMemoryRatingStore(), clock: { 1 })
    private let catalog = FakeMovieCatalog(details: ["7": movie("7")])

    @Test func showsTheUsersCurrentRatingAndChangesIt() async throws {
        try await ratings.setRating(.unseen, for: "7")
        let vm = DetailsModel(movieId: "7", catalog: catalog, ratings: ratings)
        await vm.loadTask?.value
        #expect(vm.userRating == .unseen)

        vm.setRating(.like)

        #expect(await eventually { vm.userRating == .like })
        #expect(ratings.value(for: "7") == .like)
    }

    @Test func anUnratedMovieHasNoRatingSelected() async {
        let vm = DetailsModel(movieId: "7", catalog: catalog, ratings: ratings)
        await vm.loadTask?.value
        #expect(await eventually { ratings.isLoaded })
        #expect(vm.userRating == nil)
    }
}
