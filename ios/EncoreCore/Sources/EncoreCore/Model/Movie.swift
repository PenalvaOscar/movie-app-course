/// A catalog movie. Values the source marks as missing (0 for year, runtime, rating, votes,
/// popularity; blank strings) are nil here, so the UI never shows a fabricated "0".
public struct Movie: Sendable, Equatable, Identifiable {
    public var id: String
    public var title: String
    public var year: Int?
    public var synopsis: String
    public var genres: [String]
    public var director: String?
    public var cast: [String]
    public var runtimeMinutes: Int?
    public var rating: Double?
    public var posterUrl: String?
    public var backdropUrl: String?
    public var sourceUrl: String?
    public var releaseDate: String?
    public var voteCount: Int?
    public var popularity: Double?
    public var originalLanguage: String?
    public var datasetSourceUrl: String?

    public init(
        id: String, title: String, year: Int?, synopsis: String, genres: [String], director: String?,
        cast: [String], runtimeMinutes: Int?, rating: Double?, posterUrl: String?, backdropUrl: String?,
        sourceUrl: String?, releaseDate: String?, voteCount: Int?, popularity: Double?,
        originalLanguage: String?, datasetSourceUrl: String?
    ) {
        self.id = id
        self.title = title
        self.year = year
        self.synopsis = synopsis
        self.genres = genres
        self.director = director
        self.cast = cast
        self.runtimeMinutes = runtimeMinutes
        self.rating = rating
        self.posterUrl = posterUrl
        self.backdropUrl = backdropUrl
        self.sourceUrl = sourceUrl
        self.releaseDate = releaseDate
        self.voteCount = voteCount
        self.popularity = popularity
        self.originalLanguage = originalLanguage
        self.datasetSourceUrl = datasetSourceUrl
    }

    public var summary: MovieSummary {
        MovieSummary(id: id, title: title, year: year, director: director, rating: rating, posterUrl: posterUrl)
    }
}

/// A four-option trivia question about one catalog movie. Text is English-only in the source data.
public struct Trivia: Sendable, Equatable, Identifiable {
    public var id: String
    public var movieId: String
    public var kind: String
    public var question: String
    public var options: [String]
    public var answerIndex: Int
    public var clue: String?

    public init(id: String, movieId: String, kind: String, question: String, options: [String], answerIndex: Int, clue: String?) {
        self.id = id
        self.movieId = movieId
        self.kind = kind
        self.question = question
        self.options = options
        self.answerIndex = answerIndex
        self.clue = clue
    }
}

/// The fields a poster card needs; the full `Movie` is read only for Details.
public struct MovieSummary: Sendable, Hashable, Identifiable {
    public var id: String
    public var title: String
    public var year: Int?
    public var director: String?
    public var rating: Double?
    public var posterUrl: String?

    public init(id: String, title: String, year: Int?, director: String?, rating: Double?, posterUrl: String?) {
        self.id = id
        self.title = title
        self.year = year
        self.director = director
        self.rating = rating
        self.posterUrl = posterUrl
    }
}

/// Genres, director, cast and popularity of one movie: Genie's input (Stage 3).
public struct MovieFeatures: Sendable, Equatable {
    public var id: String
    public var genres: [String]
    public var director: String?
    public var cast: [String]
    public var popularity: Double?

    public init(id: String, genres: [String], director: String?, cast: [String], popularity: Double?) {
        self.id = id
        self.genres = genres
        self.director = director
        self.cast = cast
        self.popularity = popularity
    }
}
