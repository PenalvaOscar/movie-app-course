/// `RatingStore` kept in memory, with the same contract as `SQLiteRatingStore`: writes are
/// serialized by the actor, and a body that throws changes nothing (it works on copies that are
/// kept only on success). Used by screen-model tests and SwiftUI previews.
public actor InMemoryRatingStore: RatingStore {
    private var ratings: [String: Rating] = [:]
    private var events: [RatingEvent] = []
    private var nextEventId: Int64 = 1
    private var revision: UInt64 = 0
    /// When set, every write primitive throws it (tests only).
    private var failWrites: (any Error)?

    public init() {}

    func setFailWrites(_ error: (any Error)?) { failWrites = error }

    var eventLog: [RatingEvent] { events }

    public func state() -> RatingState {
        RatingState(ratings: ratings.values.sorted(by: Rating.historyOrder), hasHistory: !events.isEmpty, revision: revision)
    }

    public func write<Result: Sendable>(_ body: @Sendable (any RatingTransaction) throws -> Result) throws -> RatingWrite<Result> {
        let tx = Transaction(ratings: ratings, events: events, nextEventId: nextEventId, failWrites: failWrites)
        let result = try body(tx)
        ratings = tx.ratings
        events = tx.events
        nextEventId = tx.nextEventId
        revision += 1
        return RatingWrite(result: result, state: state())
    }

    private final class Transaction: RatingTransaction {
        var ratings: [String: Rating]
        var events: [RatingEvent]
        var nextEventId: Int64
        let failWrites: (any Error)?

        init(ratings: [String: Rating], events: [RatingEvent], nextEventId: Int64, failWrites: (any Error)?) {
            self.ratings = ratings
            self.events = events
            self.nextEventId = nextEventId
            self.failWrites = failWrites
        }

        private func checkWrite() throws {
            if let failWrites { throw failWrites }
        }

        func rating(movieId: String) throws -> Rating? { ratings[movieId] }

        func upsert(_ rating: Rating) throws {
            try checkWrite()
            ratings[rating.movieId] = rating
        }

        func delete(movieId: String) throws {
            try checkWrite()
            ratings[movieId] = nil
        }

        func appendEvent(_ event: RatingEvent) throws {
            try checkWrite()
            var stored = event
            stored.id = nextEventId
            nextEventId += 1
            events.append(stored)
        }

        func latestEvent() throws -> RatingEvent? { events.max { $0.id < $1.id } }

        func deleteEvent(id: Int64) throws {
            events.removeAll { $0.id == id }
        }
    }
}
