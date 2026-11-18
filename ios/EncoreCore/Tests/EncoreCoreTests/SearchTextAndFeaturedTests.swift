import Foundation
import Testing
@testable import EncoreCore

/// Port of Android `SearchTextTest` (4). SQL LIKE patterns don't exist on iOS, so the two
/// pattern cases become "needle" cases; wildcard escaping is covered in `CatalogSnapshotTests`.
@Suite struct SearchTextTests {
    @Test func normalizeLowercasesAndStripsAccents() {
        #expect(SearchText.normalize("Amélie") == "amelie")
        #expect(SearchText.normalize("  Pedro ALMODÓVAR ") == "pedro almodovar")
        #expect(SearchText.normalize("São Paulo") == "sao paulo")
        #expect(SearchText.normalize("München") == "munchen")
    }

    @Test func blankTextMeansNoFilter() {
        #expect(SearchText.needle("") == nil)
        #expect(SearchText.needle("   ") == nil)
    }

    @Test func needleIsTheNormalizedText() {
        #expect(SearchText.needle(" AMÉLIE ") == "amelie")
    }

    @Test func wildcardCharactersAreLiteral() {
        #expect(SearchText.matches("bright 100% days", needle: "100%"))
        #expect(!SearchText.matches("bright 1000 days", needle: "100%"))
        #expect(SearchText.matches("under_score", needle: "_"))
        #expect(!SearchText.matches("underscore", needle: "_"))
        #expect(SearchText.matches(#"c\d"#, needle: #"c\d"#))
    }

    // iOS addition: Java's root-locale lowercasing, checked against the JVM (Android Studio JBR).
    @Test func matchesJavaRootLowercasingIncludingFinalSigma() {
        #expect(SearchText.normalize("ΟΔΥΣΣΕΑΣ İstanbul") == "οδυσσεας istanbul")
    }
}

/// Port of Android `FeaturedPickTest` (4).
@Suite struct FeaturedPickTests {
    private let candidates = [summary("a"), summary("b"), summary("c")]

    @Test func sameDayGivesTheSameMovie() {
        #expect(Featured.pick(candidates, epochDay: 20_000) == Featured.pick(candidates, epochDay: 20_000))
    }

    @Test func thePickRotatesDayByDayThroughTheCandidates() {
        let picks = Set((20_000..<20_003).compactMap { Featured.pick(candidates, epochDay: Int64($0))?.id })
        #expect(picks == ["a", "b", "c"])
    }

    @Test func noCandidatesMeansNoFeaturedMovie() {
        #expect(Featured.pick([MovieSummary](), epochDay: 20_000) == nil)
    }

    @Test func daysBeforeTheEpochStillPickAValidIndex() {
        let pick = Featured.pick(candidates, epochDay: -1)
        #expect(pick.map(candidates.contains) == true)
        #expect(pick?.id == "c") // floorMod(-1, 3) == 2
    }

    // iOS addition: the epoch day comes from the local calendar date, not UTC.
    @Test func epochDayUsesTheLocalCalendarDate() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try #require(TimeZone(identifier: "Europe/Vienna"))
        // 1970-01-01T23:30:00Z is already Jan 2 in Vienna (UTC+1).
        let date = Date(timeIntervalSince1970: 23.5 * 3600)
        #expect(EpochDay.of(date, calendar: calendar) == 1)
        calendar.timeZone = try #require(TimeZone(identifier: "America/Los_Angeles"))
        #expect(EpochDay.of(date, calendar: calendar) == 0)
        #expect(EpochDay.of(Date(timeIntervalSince1970: -3600), calendar: calendar) == -1)
    }
}
