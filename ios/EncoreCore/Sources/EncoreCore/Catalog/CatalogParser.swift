import Foundation

/// A validated catalog. Rejected counts say how many records were dropped, so nothing is silent.
public struct CatalogImport: Sendable {
    public var movies: [Movie]
    public var trivia: [Trivia]
    public var rejectedMovies: Int
    public var rejectedTrivia: Int
}

/// Why a whole catalog was refused (Android `InvalidCatalogException.Reason`).
public enum InvalidCatalog: Error, Equatable, Sendable {
    case malformed, missingMovies, emptyMovies, missingTrivia, duplicateMovieIds
}

/// Turns the normalized catalog JSON (`{"movies":[...],"trivia":[...]}`, the shape `api/` serves
/// at /catalog) into validated domain objects, with the same rules as Android's `CatalogParser`.
///
/// Whole-catalog rules: both arrays present, at least one valid movie, unique movie ids. Record
/// rules: a movie needs an id and a title; a trivia item needs a unique id, a known movie, a
/// question, exactly four non-blank options and an answer index inside them. Records that break a
/// record rule are counted and skipped. A value of the wrong JSON type fails the whole catalog,
/// because it means the source format changed. Explicit JSON `null` means "use the default".
public enum CatalogParser {
    public static func parse(_ data: Data) throws(InvalidCatalog) -> CatalogImport {
        let dto: CatalogDTO
        do {
            dto = try JSONDecoder().decode(CatalogDTO.self, from: data)
        } catch {
            throw .malformed
        }
        guard let movieDTOs = dto.movies else { throw .missingMovies }
        guard let triviaDTOs = dto.trivia else { throw .missingTrivia }

        let movies = movieDTOs.compactMap { $0.toMovie() }
        if movies.isEmpty { throw .emptyMovies }
        let movieIds = Set(movies.map(\.id))
        if movieIds.count != movies.count { throw .duplicateMovieIds }

        var triviaIds = Set<String>()
        let trivia = triviaDTOs.compactMap { dto -> Trivia? in
            guard let item = dto.toTrivia(movieIds: movieIds), triviaIds.insert(item.id).inserted else { return nil }
            return item
        }

        return CatalogImport(
            movies: movies,
            trivia: trivia,
            rejectedMovies: movieDTOs.count - movies.count,
            rejectedTrivia: triviaDTOs.count - trivia.count
        )
    }
}

// MARK: - Wire format

private struct CatalogDTO: Decodable {
    let movies: [MovieDTO]?
    let trivia: [TriviaDTO]?

    init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: Keys.self)
        movies = try c.decodeIfPresent([MovieDTO].self, forKey: .movies)
        trivia = try c.decodeIfPresent([TriviaDTO].self, forKey: .trivia)
    }

    private enum Keys: String, CodingKey { case movies, trivia }
}

private struct MovieDTO: Decodable {
    let id: String
    let title: String
    let year: Int
    let synopsis: String
    let genres: [String]
    let director: String
    let cast: [String]
    let runtime: Int
    let rating: Double
    let posterUrl: String?
    let backdropUrl: String?
    let sourceUrl: String?
    let releaseDate: String?
    let voteCount: Int?
    let popularity: Double?
    let originalLanguage: String?
    let datasetSourceUrl: String?

    // decodeIfPresent everywhere: absent and explicit null both fall back to the default,
    // a wrong type still throws (and fails the catalog).
    init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: Keys.self)
        id = try c.decodeIfPresent(String.self, forKey: .id) ?? ""
        title = try c.decodeIfPresent(String.self, forKey: .title) ?? ""
        year = try c.decodeIfPresent(Int.self, forKey: .year) ?? 0
        synopsis = try c.decodeIfPresent(String.self, forKey: .synopsis) ?? ""
        genres = try c.decodeIfPresent([String].self, forKey: .genres) ?? []
        director = try c.decodeIfPresent(String.self, forKey: .director) ?? ""
        cast = try c.decodeIfPresent([String].self, forKey: .cast) ?? []
        runtime = try c.decodeIfPresent(Int.self, forKey: .runtime) ?? 0
        rating = try c.decodeIfPresent(Double.self, forKey: .rating) ?? 0
        posterUrl = try c.decodeIfPresent(String.self, forKey: .posterUrl)
        backdropUrl = try c.decodeIfPresent(String.self, forKey: .backdropUrl)
        sourceUrl = try c.decodeIfPresent(String.self, forKey: .sourceUrl)
        releaseDate = try c.decodeIfPresent(String.self, forKey: .releaseDate)
        voteCount = try c.decodeIfPresent(Int.self, forKey: .voteCount)
        popularity = try c.decodeIfPresent(Double.self, forKey: .popularity)
        originalLanguage = try c.decodeIfPresent(String.self, forKey: .originalLanguage)
        datasetSourceUrl = try c.decodeIfPresent(String.self, forKey: .datasetSourceUrl)
    }

    private enum Keys: String, CodingKey {
        case id, title, year, synopsis, genres, director, cast, runtime, rating, posterUrl, backdropUrl
        case sourceUrl, releaseDate, voteCount, popularity, originalLanguage, datasetSourceUrl
    }

    func toMovie() -> Movie? {
        let id = id.kotlinTrimmed()
        let title = title.kotlinTrimmed()
        if id.isEmpty || title.isEmpty { return nil }
        return Movie(
            id: id,
            title: title,
            year: year > 0 ? year : nil,
            synopsis: synopsis.kotlinTrimmed(),
            genres: Self.cleaned(genres),
            director: director.trimmedOrNil(),
            cast: Self.cleaned(cast),
            runtimeMinutes: runtime > 0 ? runtime : nil,
            rating: rating > 0 ? rating : nil,
            posterUrl: posterUrl?.trimmedOrNil(),
            backdropUrl: backdropUrl?.trimmedOrNil(),
            sourceUrl: sourceUrl?.trimmedOrNil(),
            releaseDate: releaseDate?.trimmedOrNil(),
            voteCount: voteCount.flatMap { $0 > 0 ? $0 : nil },
            popularity: popularity.flatMap { $0 > 0 ? $0 : nil },
            originalLanguage: originalLanguage?.trimmedOrNil(),
            datasetSourceUrl: datasetSourceUrl?.trimmedOrNil()
        )
    }

    /// Trimmed, blanks removed, duplicates removed keeping the first occurrence.
    private static func cleaned(_ names: [String]) -> [String] {
        var seen = Set<String>()
        return names.compactMap { $0.trimmedOrNil() }.filter { seen.insert($0).inserted }
    }
}

private struct TriviaDTO: Decodable {
    let id: String
    let movieId: String
    let kind: String
    let question: String
    let options: [String]
    let answerIndex: Int
    let clue: String?

    init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: Keys.self)
        id = try c.decodeIfPresent(String.self, forKey: .id) ?? ""
        movieId = try c.decodeIfPresent(String.self, forKey: .movieId) ?? ""
        kind = try c.decodeIfPresent(String.self, forKey: .kind) ?? ""
        question = try c.decodeIfPresent(String.self, forKey: .question) ?? ""
        options = try c.decodeIfPresent([String].self, forKey: .options) ?? []
        answerIndex = try c.decodeIfPresent(Int.self, forKey: .answerIndex) ?? -1
        clue = try c.decodeIfPresent(String.self, forKey: .clue)
    }

    private enum Keys: String, CodingKey { case id, movieId, kind, question, options, answerIndex, clue }

    func toTrivia(movieIds: Set<String>) -> Trivia? {
        let id = id.kotlinTrimmed()
        let movieId = movieId.kotlinTrimmed() // movie ids are trimmed too, so compare like with like
        guard !id.isEmpty,
              movieIds.contains(movieId),
              !kind.isKotlinBlank,
              !question.isKotlinBlank,
              options.count == 4, !options.contains(where: \.isKotlinBlank),
              options.indices.contains(answerIndex)
        else { return nil }
        return Trivia(
            id: id, movieId: movieId, kind: kind.kotlinTrimmed(), question: question.kotlinTrimmed(),
            options: options.map { $0.kotlinTrimmed() }, answerIndex: answerIndex, clue: clue?.trimmedOrNil()
        )
    }
}
