/// LIKE and DISLIKE feed recommendations; UNSEEN is saved but only keeps the movie out of Swipe
/// and Genie (C3). Raw values are the names stored in `user.sqlite`, as on Android.
public enum RatingValue: String, CaseIterable, Sendable, Codable {
    case like = "LIKE"
    case dislike = "DISLIKE"
    case unseen = "UNSEEN"
}

/// The user's own rating of one movie. `updatedAt` is epoch milliseconds.
public struct Rating: Sendable, Equatable, Hashable {
    public var movieId: String
    public var value: RatingValue
    public var updatedAt: Int64

    public init(movieId: String, value: RatingValue, updatedAt: Int64) {
        self.movieId = movieId
        self.value = value
        self.updatedAt = updatedAt
    }

    /// History order: most recently changed first, then movieId in byte order
    /// (SQLite `ORDER BY updatedAt DESC, movieId`, BINARY collation).
    static func historyOrder(_ a: Rating, _ b: Rating) -> Bool {
        if a.updatedAt != b.updatedAt { return a.updatedAt > b.updatedAt }
        return a.movieId.utf8.lexicographicallyPrecedes(b.movieId.utf8)
    }
}

/// One rating change, kept so it can be undone. `previous` is nil for a first rating; undoing it
/// removes the rating. `previousAt` lets undo restore the old rating exactly, time included.
public struct RatingEvent: Sendable, Equatable {
    public var id: Int64
    public var movieId: String
    public var previous: RatingValue?
    public var previousAt: Int64?
    public var value: RatingValue
    public var createdAt: Int64

    public init(id: Int64, movieId: String, previous: RatingValue?, previousAt: Int64?, value: RatingValue, createdAt: Int64) {
        self.id = id
        self.movieId = movieId
        self.previous = previous
        self.previousAt = previousAt
        self.value = value
        self.createdAt = createdAt
    }
}
