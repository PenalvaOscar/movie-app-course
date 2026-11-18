import Foundation


/// The whole catalog in memory, indexed once at load (plan A-1). Immutable and `Sendable`, so any
/// thread can query it; a catalog update builds a new snapshot instead of mutating this one.
///
/// Every query reproduces the Room/SQLite order Android uses: DESC puts missing values last, ties
/// break on id by UTF-16 code units (plan I4), and title sort folds ASCII case only (`NOCASE`).
public struct CatalogSnapshot: MovieCatalog {
    public let trivia: [Trivia]
    private let movies: [Movie]
    private let indexById: [String: Int]
    private let searchTitle: [String]
    private let searchDirector: [String?]
    private let byPopularity: [Int]
    private let byRating: [Int]
    private let byNewest: [Int]
    private let byTitle: [Int]
    private let genreMembers: [String: Set<Int>]
    private let genreList: [String]
    private let features: [MovieFeatures]

    public var movieCount: Int { movies.count }

    public init(_ catalog: CatalogImport) {
        self.init(movies: catalog.movies, trivia: catalog.trivia)
    }

    /// `movies` must have unique ids (guaranteed by `CatalogParser`).
    public init(movies: [Movie], trivia: [Trivia]) {
        self.movies = movies
        self.trivia = trivia
        indexById = Dictionary(uniqueKeysWithValues: movies.enumerated().map { ($1.id, $0) })
        searchTitle = movies.map { SearchText.normalize($0.title) }
        searchDirector = movies.map { $0.director.map(SearchText.normalize) }

        let all = Array(movies.indices)
        func sorted(_ areInOrder: (Movie, Movie) -> ComparisonResult) -> [Int] {
            all.sorted { i, j in
                switch areInOrder(movies[i], movies[j]) {
                case .orderedAscending: true
                case .orderedDescending: false
                case .orderedSame: utf16Less(movies[i].id, movies[j].id)
                }
            }
        }
        byPopularity = sorted { descendingNilLast($0.popularity, $1.popularity) }
        byRating = sorted {
            let r = descendingNilLast($0.rating, $1.rating)
            return r != .orderedSame ? r : descendingNilLast($0.voteCount, $1.voteCount)
        }
        byNewest = sorted {
            let y = descendingNilLast($0.year, $1.year)
            return y != .orderedSame ? y : descendingNilLast($0.releaseDate.map(UTF16Key.init), $1.releaseDate.map(UTF16Key.init))
        }
        byTitle = sorted { asciiCaseInsensitiveCompare($0.title, $1.title) }

        var members: [String: Set<Int>] = [:]
        for (index, movie) in movies.enumerated() {
            for genre in movie.genres { members[genre, default: []].insert(index) }
        }
        genreMembers = members
        genreList = members.sorted { a, b in
            a.value.count != b.value.count ? a.value.count > b.value.count : utf16Less(a.key, b.key)
        }.map(\.key)

        features = movies.map { MovieFeatures(id: $0.id, genres: $0.genres, director: $0.director, cast: $0.cast, popularity: $0.popularity) }
    }

    public func genres() -> [String] { genreList }

    public func search(_ query: MovieQuery) -> [MovieSummary] {
        let needle = SearchText.needle(query.text)
        let members: Set<Int>? = query.genre.map { genreMembers[$0] ?? [] }
        return order(query.sort).compactMap { i in
            if let members, !members.contains(i) { return nil }
            if let needle {
                let hit = SearchText.matches(searchTitle[i], needle: needle)
                    || (searchDirector[i].map { SearchText.matches($0, needle: needle) } ?? false)
                if !hit { return nil }
            }
            return movies[i].summary
        }
    }

    public func featuredCandidates(limit: Int) -> [MovieSummary] {
        byPopularity.lazy
            .map { movies[$0] }
            .filter { $0.posterUrl != nil && !$0.synopsis.isEmpty }
            .prefix(max(0, limit))
            .map(\.summary)
    }

    /// Genres come back alphabetically, as Room's `ORDER BY genre` returned them on Android.
    public func movie(id: String) -> Movie? {
        guard let index = indexById[id] else { return nil }
        var movie = movies[index]
        movie.genres.sort(by: utf16Less)
        return movie
    }

    public func swipeOrder() -> [String] { byPopularity.map { movies[$0].id } }

    public func summaries(ids: [String]) -> [MovieSummary] {
        var seen = Set<String>()
        return ids.compactMap { id in
            guard seen.insert(id).inserted, let index = indexById[id] else { return nil }
            return movies[index].summary
        }
    }

    public func recommendationFeatures() -> [MovieFeatures] { features }

    public func triviaQuestions() -> [Trivia] { trivia }

    private func order(_ sort: MovieSort) -> [Int] {
        switch sort {
        case .popularity: byPopularity
        case .rating: byRating
        case .newest: byNewest
        case .title: byTitle
        }
    }
}

/// SQLite `ORDER BY x DESC`: larger first, NULL last.
private func descendingNilLast<T: Comparable>(_ a: T?, _ b: T?) -> ComparisonResult {
    switch (a, b) {
    case (nil, nil): .orderedSame
    case (nil, _): .orderedDescending
    case (_, nil): .orderedAscending
    case let (x?, y?): x == y ? .orderedSame : (x > y ? .orderedAscending : .orderedDescending)
    }
}

/// Wraps a string so `Comparable` uses JVM/SQLite code-unit order instead of Swift's.
private struct UTF16Key: Comparable {
    let value: String
    init(_ value: String) { self.value = value }
    static func < (a: UTF16Key, b: UTF16Key) -> Bool { utf16Less(a.value, b.value) }
    static func == (a: UTF16Key, b: UTF16Key) -> Bool { a.value.utf16.elementsEqual(b.value.utf16) }
}
