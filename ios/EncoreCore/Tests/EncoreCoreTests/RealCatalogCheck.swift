import Foundation
import Testing
@testable import EncoreCore

/// Local-only check against the real, ignored `data/movies.json`. Skipped unless
/// `ENCORE_REAL_CATALOG` points at the file, so CI and fresh clones never need the data.
@Suite struct RealCatalogCheck {
    static let path = ProcessInfo.processInfo.environment["ENCORE_REAL_CATALOG"]

    @Test(.enabled(if: path != nil, "set ENCORE_REAL_CATALOG to data/movies.json"))
    func parsesTheRealCatalog() throws {
        let data = try Data(contentsOf: URL(fileURLWithPath: try #require(Self.path)))

        let clock = ContinuousClock()
        var parsed: CatalogImport?
        var snapshot: CatalogSnapshot?
        let parseTime = try clock.measure { parsed = try CatalogParser.parse(data) }
        let indexTime = clock.measure { snapshot = CatalogSnapshot(parsed!) }
        let result = try #require(parsed)
        let catalog = try #require(snapshot)

        print("Real catalog: \(data.count) bytes, \(result.movies.count) movies, \(result.trivia.count) trivia, rejected \(result.rejectedMovies)/\(result.rejectedTrivia); parse \(parseTime), index \(indexTime)")
        #expect(result.movies.count == 9_919)
        #expect(result.trivia.count == 1_109)
        #expect(result.rejectedMovies == 0)
        #expect(result.rejectedTrivia == 0)
        #expect(catalog.movieCount == 9_919)

        let searchTime = clock.measure { _ = catalog.search(MovieQuery(text: "menendez")) }
        let hits = catalog.search(MovieQuery(text: "menendez"))
        print("Search 'menendez': \(hits.map(\.title)) in \(searchTime)")
        #expect(hits.contains { $0.director == "Ramón Menéndez" })
        #expect(catalog.featuredCandidates(limit: 30).count == 30)
        print("Genres (\(catalog.genres().count)): \(catalog.genres().prefix(5))")
    }
}
