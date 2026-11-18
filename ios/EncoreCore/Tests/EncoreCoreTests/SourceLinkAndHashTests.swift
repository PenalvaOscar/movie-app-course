import Testing
@testable import EncoreCore

/// Port of Android `SourceLinkTest` (5).
@Suite struct SourceLinkTests {
    @Test func wikipediaLinksAreLabelledAsWikipedia() throws {
        let link = try #require(SourceLink.from("https://en.wikipedia.org/wiki/Some_Film"))
        #expect(link.kind == .wikipedia)
        #expect(link.url == "https://en.wikipedia.org/wiki/Some_Film")
    }

    @Test func kaggleLinksAreLabelledAsTheDataset() {
        #expect(SourceLink.from("https://www.kaggle.com/datasets/x/y")?.kind == .kaggle)
    }

    @Test func otherHttpsHostsAreKeptAsAGenericSource() {
        #expect(SourceLink.from("https://example.org/film")?.kind == .other)
    }

    @Test func missingOrUnsafeLinksAreDropped() {
        #expect(SourceLink.from(nil) == nil)
        #expect(SourceLink.from("") == nil)
        #expect(SourceLink.from("javascript:alert(1)") == nil)
        #expect(SourceLink.from("file:///sdcard/x") == nil)
        #expect(SourceLink.from("intent://scan/#Intent;end") == nil)
        #expect(SourceLink.from("not a url") == nil)
    }

    @Test func sourcesForAMovieSkipDuplicatesAndKeepTheRecordOrder() {
        let wiki = "https://en.wikipedia.org/wiki/A"
        let kaggle = "https://www.kaggle.com/datasets/x/y"
        #expect(SourceLink.forMovie(sourceUrl: wiki, datasetSourceUrl: kaggle).map(\.url) == [wiki, kaggle])
        #expect(SourceLink.forMovie(sourceUrl: kaggle, datasetSourceUrl: kaggle).map(\.url) == [kaggle])
        #expect(SourceLink.forMovie(sourceUrl: nil, datasetSourceUrl: kaggle).map(\.url) == [kaggle])
    }

    // iOS addition: the host check is case-insensitive and the scheme may be plain http.
    @Test func hostAndSchemeAreCaseInsensitive() {
        #expect(SourceLink.from("HTTP://EN.Wikipedia.ORG/wiki/A")?.kind == .wikipedia)
        #expect(SourceLink.from("https://notwikipedia.org/x")?.kind == .other)
    }
}

/// `String.hashCode()` parity; expected values printed by the JVM in Android Studio's JBR on 2026-11-18.
@Suite struct JavaStringHashTests {
    @Test(arguments: [
        ("", Int32(0), 0),
        ("hello", 99_162_322, 322),
        ("Aa", 2112, 312),
        ("BB", 2112, 312),
        ("polygenelubricants", Int32.min, 232),
        ("Amélie", 1_968_607_051, 331),
        ("tt0111161", -2_103_658_325, 235),
        ("São Paulo 🎬", 1_229_614_122, 282),
        ("m1", 3428, 188),
    ])
    func matchesTheJVM(input: String, hash: Int32, hue: Int) {
        #expect(JavaStringHash.hash(input) == hash)
        #expect(JavaStringHash.floorMod(hash, 360) == hue)
    }
}
