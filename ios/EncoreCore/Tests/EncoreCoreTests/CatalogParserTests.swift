import Foundation
import Testing
@testable import EncoreCore

/// Port of Android `CatalogParserTest` (15 cases). Fixtures are fictional.
@Suite struct CatalogParserTests {
    private func parse(_ json: String) throws(InvalidCatalog) -> CatalogImport {
        try CatalogParser.parse(Data(json.utf8))
    }

    private func movieJSON(_ id: String) -> String {
        #"{"id":"\#(id)","title":"Film \#(id)","year":2001,"synopsis":"S","genres":["Drama"],"director":"Ada Vale","cast":["Bo Lin"],"runtime":100,"rating":7.5}"#
    }

    private func triviaJSON(
        _ id: String, _ movieId: String, options: String = #"["a","b","c","d"]"#, answer: Int = 1, question: String = "Q?"
    ) -> String {
        #"{"id":"\#(id)","movieId":"\#(movieId)","kind":"director","question":"\#(question)","options":\#(options),"answerIndex":\#(answer)}"#
    }

    private func catalogJSON(_ movies: [String], trivia: [String] = []) -> String {
        #"{"movies":[\#(movies.joined(separator: ","))],"trivia":[\#(trivia.joined(separator: ","))]}"#
    }

    @Test func parsesEveryMovieFieldAndTrivia() throws {
        let url = try #require(Bundle.module.url(forResource: "full-record", withExtension: "json", subdirectory: "Fixtures"))
        let result = try CatalogParser.parse(Data(contentsOf: url))

        let m = try #require(result.movies.first)
        #expect(result.movies.count == 1)
        #expect(m.id == "m1")
        #expect(m.title == "The Lantern Keeper")
        #expect(m.year == 1999)
        #expect(m.synopsis == "A keeper.")
        #expect(m.genres == ["Drama", "Mystery"])
        #expect(m.director == "Ada Vale")
        #expect(m.cast == ["Bo Lin", "Cy Moss"])
        #expect(m.runtimeMinutes == 112)
        #expect(m.rating == 7.8)
        #expect(m.posterUrl == "https://example.test/p.jpg")
        #expect(m.backdropUrl == "https://example.test/b.jpg")
        #expect(m.sourceUrl == "https://example.test/m1")
        #expect(m.releaseDate == "1999-05-01")
        #expect(m.voteCount == 321)
        #expect(m.popularity == 12.5)
        #expect(m.originalLanguage == "en")
        #expect(m.datasetSourceUrl == "https://example.test/ds")

        #expect(result.trivia == [Trivia(id: "t1", movieId: "m1", kind: "plot", question: "Which film?", options: ["A", "B", "C", "D"], answerIndex: 2, clue: "Light")])
        #expect(result.rejectedMovies == 0)
        #expect(result.rejectedTrivia == 0)
    }

    @Test func zeroAndBlankValuesAreTreatedAsMissing() throws {
        let json = #"""
        {"movies":[{"id":"m1","title":"Quiet","year":0,"synopsis":"","genres":["", "Drama"],
            "director":"  ","cast":["", "Bo Lin", " "],"runtime":0,"rating":0,"posterUrl":"",
            "backdropUrl":" ","sourceUrl":"","releaseDate":"","voteCount":0,"popularity":0,"originalLanguage":""}],"trivia":[]}
        """#
        let m = try #require(try parse(json).movies.first)

        #expect(m.year == nil)
        #expect(m.director == nil)
        #expect(m.runtimeMinutes == nil)
        #expect(m.rating == nil)
        #expect(m.posterUrl == nil)
        #expect(m.backdropUrl == nil)
        #expect(m.sourceUrl == nil)
        #expect(m.releaseDate == nil)
        #expect(m.voteCount == nil)
        #expect(m.popularity == nil)
        #expect(m.originalLanguage == nil)
        #expect(m.genres == ["Drama"])
        #expect(m.cast == ["Bo Lin"])
    }

    @Test func optionalFieldsMayBeAbsentExplicitNullOrUnknownKeys() throws {
        let json = #"{"movies":[{"id":"m1","title":"Sparse","director":null,"futureField":{"x":1}}],"trivia":[],"meta":"v2"}"#
        let m = try #require(try parse(json).movies.first)

        #expect(m.title == "Sparse")
        #expect(m.synopsis == "")
        #expect(m.genres == [])
        #expect(m.director == nil)
        #expect(m.year == nil)
    }

    @Test func missingMoviesArrayIsInvalid() {
        #expect(throws: InvalidCatalog.missingMovies) { try parse(#"{"trivia":[]}"#) }
    }

    @Test func emptyMoviesArrayIsInvalid() {
        #expect(throws: InvalidCatalog.emptyMovies) { try parse(#"{"movies":[],"trivia":[]}"#) }
    }

    @Test func missingTriviaArrayIsInvalid() {
        #expect(throws: InvalidCatalog.missingTrivia) { try parse(#"{"movies":[\#(movieJSON("m1"))]}"#) }
    }

    @Test func duplicateMovieIdsAreInvalid() {
        #expect(throws: InvalidCatalog.duplicateMovieIds) {
            try parse(catalogJSON([movieJSON("m1"), movieJSON("m2"), movieJSON("m1")]))
        }
    }

    @Test func malformedJSONIsInvalid() {
        #expect(throws: InvalidCatalog.malformed) { try parse(#"{"movies":[{"id":"m1","#) }
    }

    @Test func wrongFieldTypeIsInvalidRatherThanSilentlyDropped() {
        #expect(throws: InvalidCatalog.malformed) {
            try parse(#"{"movies":[{"id":"m1","title":"T","year":"soon"}],"trivia":[]}"#)
        }
    }

    @Test func aRootThatIsNotAnObjectIsInvalid() {
        #expect(throws: InvalidCatalog.malformed) { try parse("[1,2,3]") }
    }

    @Test func moviesWithoutIdOrTitleAreRejectedAndCounted() throws {
        let json = catalogJSON([
            movieJSON("m1"),
            #"{"id":"","title":"No id"}"#,
            #"{"id":"m3","title":"  "}"#,
            #"{"title":"Absent id"}"#,
            movieJSON("m5"),
        ])
        let result = try parse(json)

        #expect(result.movies.map(\.id) == ["m1", "m5"])
        #expect(result.rejectedMovies == 3)
    }

    @Test func onlyRejectedMoviesLeavesTheCatalogEmptyAndInvalid() {
        #expect(throws: InvalidCatalog.emptyMovies) { try parse(catalogJSON([#"{"id":"","title":""}"#])) }
    }

    @Test func invalidTriviaIsRejectedAndCounted() throws {
        let json = catalogJSON([movieJSON("m1")], trivia: [
            triviaJSON("t1", "m1"),
            triviaJSON("t2", "unknown-movie"),
            triviaJSON("t3", "m1", options: #"["a","b","c"]"#),
            triviaJSON("t4", "m1", answer: 4),
            triviaJSON("t5", "m1", answer: -1),
            triviaJSON("t6", "m1", question: " "),
            triviaJSON("t1", "m1"),
            triviaJSON("", "m1"),
            triviaJSON("t9", "m1", options: #"["a","","c","d"]"#),
        ])
        let result = try parse(json)

        #expect(result.trivia.map(\.id) == ["t1"])
        #expect(result.rejectedTrivia == 8)
    }

    @Test func idsAreComparedAfterTrimmingForMoviesAndTriviaAlike() throws {
        let result = try parse(catalogJSON([movieJSON(" m1 ")], trivia: [triviaJSON("t1", " m1")]))

        #expect(result.movies.first?.id == "m1")
        #expect(result.trivia.first?.movieId == "m1")
        #expect(result.rejectedTrivia == 0)
    }

    @Test func triviaPointingAtARejectedMovieIsRejected() throws {
        let result = try parse(catalogJSON([movieJSON("m1"), #"{"id":"m2","title":""}"#], trivia: [triviaJSON("t1", "m2")]))

        #expect(result.trivia.isEmpty)
        #expect(result.rejectedTrivia == 1)
    }

    // iOS additions

    @Test func trimmingMatchesKotlinWhitespace() throws {
        // Kotlin's trim() removes U+001C..U+001F and no-break space; it keeps U+0085 (NEL).
        let json = catalogJSON([#"{"id":"\u001Fm1 ","title":"\u0085Title"}"#])
        let m = try #require(try parse(json).movies.first)
        #expect(m.id == "m1")
        #expect(m.title == "\u{85}Title")
    }

    @Test func aGenreListWithANullElementIsMalformed() {
        #expect(throws: InvalidCatalog.malformed) {
            try parse(#"{"movies":[{"id":"m1","title":"T","genres":["Drama",null]}],"trivia":[]}"#)
        }
    }
}
