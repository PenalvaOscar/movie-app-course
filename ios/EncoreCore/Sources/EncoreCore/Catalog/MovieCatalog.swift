/// Sort orders offered in Explore. Missing values (nil rating, year, popularity) always sort last.
public enum MovieSort: String, CaseIterable, Sendable, Codable {
    case popularity, rating, newest, title
}

/// What Explore asks the catalog for. `text` matches title or director, accent- and case-insensitive.
public struct MovieQuery: Equatable, Sendable {
    public var text: String
    public var genre: String?
    public var sort: MovieSort

    public init(text: String = "", genre: String? = nil, sort: MovieSort = .popularity) {
        self.text = text
        self.genre = genre
        self.sort = sort
    }

    public var isFiltered: Bool { !text.isKotlinBlank || genre != nil }
}

/// Read access to the local catalog. Every call works offline. Async so that screen models
/// can be tested against fakes and so queries run off the main actor.
public protocol MovieCatalog: Sendable {
    /// Genre names as they appear in the source data, most common first.
    func genres() async throws -> [String]

    func search(_ query: MovieQuery) async throws -> [MovieSummary]

    /// The most popular movies that have a poster and a synopsis: the pool `Featured.pick` draws from.
    func featuredCandidates(limit: Int) async throws -> [MovieSummary]

    func movie(id: String) async throws -> Movie?

    /// Every movie id, most popular first (then id): the Swipe queue before rated movies are removed.
    func swipeOrder() async throws -> [String]

    /// Cards for `ids`, in no particular order. Ids no longer in the catalog are absent (C10).
    func summaries(ids: [String]) async throws -> [MovieSummary]

    /// Genres, director, cast and popularity of every movie: Genie's input.
    func recommendationFeatures() async throws -> [MovieFeatures]
}
