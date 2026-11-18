/// Everything a screen needs after a committed change.
public struct RatingState: Sendable, Equatable {
    /// Every rating, most recently changed first (`Rating.historyOrder`).
    public var ratings: [Rating]
    /// True while at least one change can be undone.
    public var hasHistory: Bool
    /// Increases with every committed write on this store instance, so a reader can drop a state
    /// that arrives after a newer one.
    public var revision: UInt64

    public init(ratings: [Rating], hasHistory: Bool, revision: UInt64) {
        self.ratings = ratings
        self.hasHistory = hasHistory
        self.revision = revision
    }
}

/// A committed write: the body's result and the state right after it.
public struct RatingWrite<Result: Sendable>: Sendable {
    public var result: Result
    public var state: RatingState
}

/// Storage primitives available inside one `RatingStore.write`. Synchronous on purpose: the body
/// runs to completion inside the store's actor, so no other call can interleave with it.
public protocol RatingTransaction {
    func rating(movieId: String) throws -> Rating?
    func upsert(_ rating: Rating) throws
    func delete(movieId: String) throws
    /// Stores `event` under a new id (the event's own id is ignored).
    func appendEvent(_ event: RatingEvent) throws
    func latestEvent() throws -> RatingEvent?
    func deleteEvent(id: Int64) throws
}

/// Ratings and their undo history. `SQLiteRatingStore` in the app, `InMemoryRatingStore` in tests
/// and previews. Both have the same contract: writes are serialized, and a body that throws
/// leaves both tables exactly as they were.
public protocol RatingStore: Sendable {
    func state() async throws -> RatingState

    /// Runs `body` atomically. On success returns its result and the committed state.
    func write<Result: Sendable>(_ body: @Sendable (any RatingTransaction) throws -> Result) async throws -> RatingWrite<Result>
}

public enum RatingStoreError: Error, Equatable, Sendable {
    /// The file was written by a newer app version; it is left untouched (no destructive fallback).
    case newerSchema(found: Int, supported: Int)
    /// A row holds a value this version cannot read. Reported, never guessed.
    case unreadableRow(String)
    case closed
}
