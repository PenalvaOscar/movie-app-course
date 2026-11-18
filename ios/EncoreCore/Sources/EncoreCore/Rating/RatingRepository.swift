import Foundation
import Observation

/// The only writer of ratings (Swipe, Details and History all go through here, C4). Every change
/// is written together with its undo step in one transaction, so the undo history always matches
/// the ratings, including after the app restarts.
///
/// After each committed write it publishes the new state twice: as observable properties (for
/// views and synchronous reads such as `canUndo`) and on every `updates()` stream (for screen
/// models that react with async work). Because it is the only writer, nothing else needs to
/// watch the database.
@MainActor
@Observable
public final class RatingRepository {
    /// Every rating, most recently changed first. Includes movies no longer in the catalog (C10).
    public private(set) var ratings: [Rating] = []
    public private(set) var canUndo = false
    /// True once the stored ratings have been read (by `load()` or by any write).
    public private(set) var isLoaded = false
    private var values: [String: RatingValue] = [:]

    @ObservationIgnored private let store: any RatingStore
    @ObservationIgnored private let clock: @MainActor () -> Int64
    @ObservationIgnored private var revision: UInt64?
    @ObservationIgnored private var loadTask: Task<Void, any Error>?
    @ObservationIgnored private var subscribers: [Int: AsyncStream<[Rating]>.Continuation] = [:]
    @ObservationIgnored private var nextSubscriber = 0

    public init(store: any RatingStore, clock: @escaping @MainActor () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
        self.store = store
        self.clock = clock
    }

    /// The user's rating of `movieId`, or nil. Observable: a view reading it updates on any change.
    public func value(for movieId: String) -> RatingValue? { values[movieId] }

    /// Reads the stored ratings once. Concurrent callers share one read; after a failure the next
    /// call tries again.
    public func load() async throws {
        if isLoaded { return }
        if let loadTask { return try await loadTask.value }
        let store = store
        let task = Task { [weak self] in
            let state = try await store.state()
            self?.apply(state)
        }
        loadTask = task
        do {
            try await task.value
        } catch {
            loadTask = nil
            throw error
        }
    }

    /// The ratings now (once loaded) and after every change. Each call gets its own stream;
    /// a slow reader skips to the newest list rather than queueing old ones.
    public func updates() -> AsyncStream<[Rating]> {
        let (stream, continuation) = AsyncStream.makeStream(of: [Rating].self, bufferingPolicy: .bufferingNewest(1))
        let id = nextSubscriber
        nextSubscriber += 1
        subscribers[id] = continuation
        continuation.onTermination = { [weak self] _ in
            Task { @MainActor in self?.subscribers[id] = nil }
        }
        if isLoaded { continuation.yield(ratings) }
        return stream
    }

    /// Live `updates()` streams (tests check that released readers are removed).
    var subscriberCount: Int { subscribers.count }

    /// Returns false, writing nothing, when the movie already has `value`.
    @discardableResult
    public func setRating(_ value: RatingValue, for movieId: String) async throws -> Bool {
        let now = clock()
        let write = try await store.write { tx in
            let current = try tx.rating(movieId: movieId)
            if current?.value == value { return false }
            try tx.upsert(Rating(movieId: movieId, value: value, updatedAt: now))
            try tx.appendEvent(RatingEvent(id: 0, movieId: movieId, previous: current?.value, previousAt: current?.updatedAt, value: value, createdAt: now))
            return true
        }
        apply(write.state)
        return write.result
    }

    /// Reverts the most recent change, whichever screen made it: restores the previous value with
    /// its original time, or removes the rating if it was the movie's first. Returns the reverted
    /// change, or nil when there is nothing to undo.
    @discardableResult
    public func undo() async throws -> RatingEvent? {
        let now = clock()
        let write = try await store.write { tx -> RatingEvent? in
            guard let event = try tx.latestEvent() else { return nil }
            if let previous = event.previous {
                try tx.upsert(Rating(movieId: event.movieId, value: previous, updatedAt: event.previousAt ?? now))
            } else {
                try tx.delete(movieId: event.movieId)
            }
            try tx.deleteEvent(id: event.id)
            return event
        }
        apply(write.state)
        return write.result
    }

    /// Writes finish on the store's actor in order, but their continuations can resume here in
    /// any order: the revision keeps an older state from overwriting a newer one.
    private func apply(_ state: RatingState) {
        if let revision, state.revision <= revision { return }
        revision = state.revision
        let changed = !isLoaded || state.ratings != ratings
        if changed {
            ratings = state.ratings
            values = Dictionary(uniqueKeysWithValues: state.ratings.map { ($0.movieId, $0.value) })
        }
        if canUndo != state.hasHistory { canUndo = state.hasHistory }
        if !isLoaded { isLoaded = true }
        if changed { subscribers.values.forEach { $0.yield(ratings) } }
    }
}
