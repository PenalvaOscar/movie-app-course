import Foundation
import Testing
@testable import EncoreCore

/// Port of Android `RatingRepositoryTest` (10) on `InMemoryRatingStore`, plus iOS cases for the
/// published state (stream, concurrent writes).
@MainActor
@Suite struct RatingRepositoryTests {
    private let clock = TestClock(1_000)
    private let store = InMemoryRatingStore()
    private var repo: RatingRepository { RatingRepository(store: store, clock: clock.read) }

    @Test func aFirstRatingIsSavedAndLoggedWithNoPreviousValue() async throws {
        let repo = repo
        #expect(try await repo.setRating(.like, for: "a"))

        #expect(repo.ratings == [Rating(movieId: "a", value: .like, updatedAt: 1_000)])
        #expect(repo.value(for: "a") == .like)
        let log = await store.eventLog
        #expect(log.count == 1)
        #expect(log.first?.movieId == "a")
        #expect(log.first?.previous == nil)
        #expect(log.first?.value == .like)
    }

    @Test func changingARatingLogsThePreviousValueAndTime() async throws {
        let repo = repo
        try await repo.setRating(.like, for: "a")
        clock.now = 2_000
        try await repo.setRating(.dislike, for: "a")

        #expect(repo.ratings == [Rating(movieId: "a", value: .dislike, updatedAt: 2_000)])
        let last = await store.eventLog.last
        #expect(last?.previous == .like)
        #expect(last?.previousAt == 1_000)
        #expect(last?.value == .dislike)
    }

    @Test func ratingWithTheSameValueAgainChangesNothingAndAddsNoUndoStep() async throws {
        let repo = repo
        try await repo.setRating(.unseen, for: "a")
        clock.now = 2_000
        #expect(try await repo.setRating(.unseen, for: "a") == false)

        #expect(await store.eventLog.count == 1)
        #expect(repo.ratings.map(\.updatedAt) == [1_000])
    }

    @Test func undoOfAFirstRatingRemovesIt() async throws {
        let repo = repo
        try await repo.setRating(.like, for: "a")

        let undone = try await repo.undo()

        #expect(undone?.movieId == "a")
        #expect(repo.ratings.isEmpty)
        #expect(repo.value(for: "a") == nil)
        #expect(repo.canUndo == false)
    }

    @Test func undoOfAChangeRestoresThePreviousValueWithItsOriginalTime() async throws {
        let repo = repo
        try await repo.setRating(.like, for: "a")
        clock.now = 5_000
        try await repo.setRating(.unseen, for: "a")

        try await repo.undo()

        #expect(repo.ratings == [Rating(movieId: "a", value: .like, updatedAt: 1_000)])
    }

    @Test func undoStepsBackOneChangeAtATimeNewestFirstAcrossMovies() async throws {
        let repo = repo
        try await repo.setRating(.like, for: "a")
        clock.now = 2_000
        try await repo.setRating(.dislike, for: "b")
        clock.now = 3_000
        try await repo.setRating(.dislike, for: "a")

        #expect(try await repo.undo()?.movieId == "a")
        #expect(Set(repo.ratings) == [Rating(movieId: "a", value: .like, updatedAt: 1_000), Rating(movieId: "b", value: .dislike, updatedAt: 2_000)])
        #expect(try await repo.undo()?.movieId == "b")
        #expect(repo.ratings == [Rating(movieId: "a", value: .like, updatedAt: 1_000)])
        #expect(try await repo.undo()?.movieId == "a")
        #expect(repo.ratings.isEmpty)
        #expect(try await repo.undo() == nil)
    }

    @Test func undoWithNoHistoryDoesNothing() async throws {
        let repo = repo
        try await repo.load()
        #expect(repo.canUndo == false)
        #expect(try await repo.undo() == nil)
    }

    @Test func ratingsAreListedNewestFirst() async throws {
        let repo = repo
        try await repo.setRating(.like, for: "old")
        clock.now = 2_000
        try await repo.setRating(.dislike, for: "new")

        #expect(repo.ratings.map(\.movieId) == ["new", "old"])
    }

    @Test func historySurvivesANewRepositoryOnTheSameStore() async throws {
        let repo = repo
        try await repo.setRating(.like, for: "a")
        try await repo.setRating(.dislike, for: "a")

        let reopened = RatingRepository(store: store, clock: clock.read)
        try await reopened.load()

        #expect(reopened.canUndo)
        try await reopened.undo()
        #expect(reopened.value(for: "a") == .like)
    }

    @Test func aFailedWriteLeavesRatingAndHistoryUnchanged() async throws {
        let repo = repo
        try await repo.setRating(.like, for: "a")
        await store.setFailWrites(TestError())

        await #expect(throws: TestError.self) { try await repo.setRating(.dislike, for: "a") }

        await store.setFailWrites(nil)
        #expect(repo.value(for: "a") == .like)
        #expect(await store.eventLog.count == 1)
    }

    // MARK: iOS

    @Test func updatesStartWithTheCurrentRatingsAndFollowEachChange() async throws {
        let repo = repo
        try await repo.setRating(.like, for: "a")
        var iterator = repo.updates().makeAsyncIterator()
        #expect(await iterator.next()?.map(\.movieId) == ["a"])

        try await repo.undo()
        #expect(await iterator.next() == [])
    }

    @Test func concurrentWritesAreSerializedAndAllLogged() async throws {
        let repo = repo
        let writes = (0..<20).map { i in Task { _ = try await repo.setRating(.like, for: "m\(i)") } }
        for write in writes { try await write.value }

        #expect(repo.ratings.count == 20)
        #expect(await store.eventLog.count == 20)
        for _ in 0..<20 { try await repo.undo() }
        #expect(repo.ratings.isEmpty)
        #expect(repo.canUndo == false)
    }

    @Test func aStaleLoadArrivingAfterAWriteDoesNotOverwriteIt() async throws {
        let gated = GatedStateStore()
        let repo = RatingRepository(store: gated, clock: clock.read)
        let load = Task { try await repo.load() }
        #expect(await eventually { gated.waiting })

        try await repo.setRating(.like, for: "a")
        gated.open()
        try await load.value

        #expect(repo.ratings == [Rating(movieId: "a", value: .like, updatedAt: 1_000)])
        #expect(repo.canUndo)
    }

    @Test func aReleasedReaderLeavesTheSubscriberList() async throws {
        let repo = repo
        try await repo.load()
        var model: HistoryModel? = HistoryModel(catalog: FakeMovieCatalog(), ratings: repo)
        #expect(await eventually { repo.subscriberCount == 1 && model?.items == .empty })

        model = nil

        #expect(await eventually { repo.subscriberCount == 0 })
    }
}

/// A store whose `state()` reads an (empty, revision 0) snapshot, then waits until `open()`:
/// a load that started before a write but finishes after it.
@MainActor
final class GatedStateStore: RatingStore {
    private let inner = InMemoryRatingStore()
    private var gate: CheckedContinuation<Void, Never>?
    private(set) var waiting = false

    func open() {
        gate?.resume()
        gate = nil
    }

    func state() async throws -> RatingState {
        let stale = await inner.state()
        waiting = true
        await withCheckedContinuation { gate = $0 }
        return stale
    }

    func write<Result: Sendable>(_ body: @Sendable (any RatingTransaction) throws -> Result) async throws -> RatingWrite<Result> {
        try await inner.write(body)
    }
}
