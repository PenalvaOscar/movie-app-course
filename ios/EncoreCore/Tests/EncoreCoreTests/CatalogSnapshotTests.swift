import Testing
@testable import EncoreCore

/// Port of the Android instrumented `RoomMovieCatalogTest` (9 + 2): the same queries,
/// run against the in-memory snapshot instead of Room. Fictional fixtures only.
@Suite struct CatalogSnapshotTests {
    private static func movie(
        _ id: String,
        _ title: String,
        director: String? = nil,
        genres: [String] = [],
        year: Int? = nil,
        rating: Double? = nil,
        votes: Int? = nil,
        popularity: Double? = nil,
        poster: String? = nil,
        synopsis: String = "S",
        releaseDate: String? = nil
    ) -> Movie {
        Movie(
            id: id, title: title, year: year, synopsis: synopsis, genres: genres, director: director,
            cast: ["Bo Lin", "Cy Moss"], runtimeMinutes: 90, rating: rating, posterUrl: poster, backdropUrl: nil,
            sourceUrl: "https://example.test/\(id)", releaseDate: releaseDate, voteCount: votes, popularity: popularity,
            originalLanguage: "en", datasetSourceUrl: nil
        )
    }

    private let movies = [
        movie("a", "Amélie's Garden", director: "Pia Núñez", genres: ["Comedy", "Romance"], year: 2001, rating: 7.9, votes: 50, popularity: 9.0, poster: "https://p.test/a.jpg"),
        movie("b", "zebra crossing", director: "Ola Berg", genres: ["Drama"], year: 1990, rating: 7.9, votes: 900, popularity: 5.0, poster: "https://p.test/b.jpg"),
        movie("c", "Bright 100% Days", director: nil, genres: ["Drama", "Comedy"], year: nil, rating: nil, popularity: nil),
        movie("d", "Under_score", director: "Ana Núñez", genres: ["Drama"], year: 2010, rating: 6.0, popularity: 7.0, poster: "https://p.test/d.jpg", synopsis: ""),
    ]

    private var catalog: CatalogSnapshot { CatalogSnapshot(movies: movies, trivia: []) }

    private func ids(_ query: MovieQuery) -> [String] { catalog.search(query).map(\.id) }

    @Test func defaultQueryReturnsEverythingByPopularityWithMissingLast() {
        #expect(ids(MovieQuery()) == ["a", "d", "b", "c"])
    }

    @Test func ratingSortBreaksTiesOnVotesAndPutsMissingRatingsLast() {
        #expect(ids(MovieQuery(sort: .rating)) == ["b", "a", "d", "c"])
    }

    @Test func newestAndTitleSorts() {
        #expect(ids(MovieQuery(sort: .newest)) == ["d", "a", "b", "c"])
        #expect(ids(MovieQuery(sort: .title)) == ["a", "c", "d", "b"]) // case-insensitive
    }

    @Test func searchMatchesTitleOrDirectorIgnoringCaseAndAccents() {
        #expect(ids(MovieQuery(text: "AMELIE")) == ["a"])
        #expect(ids(MovieQuery(text: "nunez")) == ["a", "d"])
        #expect(ids(MovieQuery(text: "berg")) == ["b"])
    }

    @Test func wildcardCharactersInTheSearchAreLiteral() {
        #expect(ids(MovieQuery(text: "100%")) == ["c"])
        #expect(ids(MovieQuery(text: "_")) == ["d"])
    }

    @Test func genreFilterCombinesWithSearchAndSort() {
        #expect(ids(MovieQuery(genre: "Comedy")) == ["a", "c"])
        #expect(ids(MovieQuery(text: "bright", genre: "Comedy")) == ["c"])
        #expect(ids(MovieQuery(genre: "Drama", sort: .rating)) == ["b", "d", "c"])
        #expect(ids(MovieQuery(genre: "Western")) == [])
    }

    @Test func genresAreListedMostCommonFirst() {
        #expect(catalog.genres() == ["Drama", "Comedy", "Romance"])
    }

    @Test func featuredCandidatesNeedAPosterAndASynopsis() {
        #expect(catalog.featuredCandidates(limit: 10).map(\.id) == ["a", "b"])
        #expect(catalog.featuredCandidates(limit: 1).map(\.id) == ["a"])
    }

    @Test func movieLoadsWithGenresAndCastInBillingOrder() {
        #expect(catalog.movie(id: "a") == movies[0])
        #expect(catalog.movie(id: "missing") == nil)
    }

    @Test func recommendationFeaturesCoverEveryMovieWithGenresAndCastInBillingOrder() throws {
        let features = Dictionary(uniqueKeysWithValues: catalog.recommendationFeatures().map { ($0.id, $0) })

        #expect(Set(features.keys) == ["a", "b", "c", "d"])
        let a = try #require(features["a"])
        #expect(Set(a.genres) == ["Comedy", "Romance"])
        #expect(a.director == "Pia Núñez")
        #expect(a.cast == ["Bo Lin", "Cy Moss"])
        #expect(a.popularity == 9.0)
        #expect(features["c"]?.director == nil)
        #expect(features["c"]?.popularity == nil)
    }

    @Test func recommendationFeaturesOfAMovieWithoutGenresOrCastAreEmpty() throws {
        var bare = Self.movie("bare", "Bare")
        bare.cast = []
        let only = try #require(CatalogSnapshot(movies: [bare], trivia: []).recommendationFeatures().first)
        #expect(only.genres == [])
        #expect(only.cast == [])
    }

    // iOS additions: behaviour Room gave for free that the snapshot must reproduce.

    @Test func detailsListGenresAlphabeticallyLikeRoom() {
        // Room read genres back with ORDER BY genre; the snapshot keeps that display order.
        let m = Self.movie("x", "X", genres: ["Romance", "Comedy"])
        #expect(CatalogSnapshot(movies: [m], trivia: []).movie(id: "x")?.genres == ["Comedy", "Romance"])
    }

    @Test func swipeOrderIsPopularityThenIdWithMissingLast() {
        let extra = [Self.movie("e", "E", popularity: 5.0), Self.movie("0", "Zero")]
        let snapshot = CatalogSnapshot(movies: movies + extra, trivia: [])
        #expect(snapshot.swipeOrder() == ["a", "d", "b", "e", "0", "c"])
    }

    @Test func newestBreaksYearTiesOnReleaseDate() {
        let snapshot = CatalogSnapshot(movies: [
            Self.movie("p", "P", year: 2001, releaseDate: "2001-03-01"),
            Self.movie("q", "Q", year: 2001, releaseDate: "2001-11-01"),
            Self.movie("r", "R", year: 2001),
        ], trivia: [])
        #expect(snapshot.search(MovieQuery(sort: .newest)).map(\.id) == ["q", "p", "r"])
    }

    @Test func titleSortFoldsASCIICaseOnlyThenId() {
        // SQLite NOCASE folds A–Z only: "É" (U+00C9) sorts after every ASCII letter.
        let snapshot = CatalogSnapshot(movies: [
            Self.movie("2", "alpha"), Self.movie("1", "Alpha"), Self.movie("3", "Écume"), Self.movie("4", "zulu"),
        ], trivia: [])
        #expect(snapshot.search(MovieQuery(sort: .title)).map(\.id) == ["1", "2", "4", "3"])
    }

    @Test func summariesSkipUnknownIds() {
        #expect(Set(catalog.summaries(ids: ["a", "gone", "c", "a"]).map(\.id)) == ["a", "c"])
    }

    @Test func blankSearchTextIsNoFilter() {
        #expect(ids(MovieQuery(text: "   ")) == ["a", "d", "b", "c"])
        #expect(!MovieQuery(text: "   ").isFiltered)
        #expect(MovieQuery(genre: "Drama").isFiltered)
    }
}
