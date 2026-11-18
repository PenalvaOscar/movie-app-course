import Testing
@testable import EncoreCore

/// Port of Android `RecommendationEngineTest` (16). Fictional fixtures only.
/// Weights per AGENTS.md: genre 1, director 3, actor 2, dislikes subtract equally.
@Suite struct RecommendationEngineTests {
    private func f(
        _ id: String,
        _ genres: [String] = [],
        _ director: String? = nil,
        cast: [String] = [],
        popularity: Double? = nil
    ) -> MovieFeatures {
        MovieFeatures(id: id, genres: genres, director: director, cast: cast, popularity: popularity)
    }

    private func recommend(_ catalog: [MovieFeatures], _ ratings: [String: RatingValue], limit: Int = 100) -> Recommendations {
        RecommendationEngine.recommend(catalog: catalog, ratings: ratings, limit: limit)
    }

    private func scores(_ result: Recommendations) -> [String] {
        result.items.map { "\($0.movieId)=\($0.score)" }
    }

    private func ids(_ result: Recommendations) -> [String] { result.items.map(\.movieId) }

    @Test func noRatingsMeansNoPreferencesAndNoRecommendations() {
        let result = recommend([f("a", ["Drama"])], [:])

        #expect(!result.hasPreferences)
        #expect(result.items.isEmpty)
    }

    @Test func unseenRatingsAreNotPreferencesAndAddNoAffinity() {
        let catalog = [f("seen", ["Horror"], "Ida Brook"), f("other", ["Horror"], "Ida Brook")]

        let result = recommend(catalog, ["seen": .unseen])

        #expect(!result.hasPreferences)
        #expect(result.likedCount == 0)
        #expect(result.items.isEmpty)
    }

    @Test func genreCounts1Director3Actor2() {
        let catalog = [
            f("liked", ["Drama"], "Ada Vale", cast: ["Bo Lin"]),
            f("genreOnly", ["Drama"]),
            f("directorOnly", [], "Ada Vale"),
            f("actorOnly", cast: ["Bo Lin"]),
            f("all", ["Drama"], "Ada Vale", cast: ["Bo Lin"]),
            f("none", ["Comedy"], "Cy Moss", cast: ["Di Park"]),
        ]

        let result = recommend(catalog, ["liked": .like])

        #expect(scores(result) == ["all=6", "directorOnly=3", "actorOnly=2", "genreOnly=1"])
        #expect(result.likedCount == 1)
        #expect(result.dislikedCount == 0)
    }

    @Test func affinityAddsUpAcrossLikedMovies() {
        let catalog = [f("l1", ["Comedy"]), f("l2", ["Comedy"]), f("c", ["Comedy"])]

        #expect(scores(recommend(catalog, ["l1": .like, "l2": .like])) == ["c=2"])
    }

    @Test func dislikesSubtractWithTheSameWeights() {
        let catalog = [
            f("liked", ["Drama"], "Ada Vale"),
            f("disliked", ["Drama"], "Cy Moss", cast: ["Di Park"]),
            f("dramaByAda", ["Drama"], "Ada Vale"), // genre 1 - 1 = 0, director +3
            f("dramaOnly", ["Drama"]), // 0: not shown
            f("byCy", [], "Cy Moss"), // -3: not shown
            f("adaWithDi", [], "Ada Vale", cast: ["Di Park"]), // +3 - 2 = 1
        ]

        let result = recommend(catalog, ["liked": .like, "disliked": .dislike])

        #expect(scores(result) == ["dramaByAda=3", "adaWithDi=1"])
        #expect(result.dislikedCount == 1)
    }

    @Test func onlyDislikesArePreferencesButRecommendNothing() {
        let catalog = [f("d", ["Drama"]), f("c", ["Drama"]), f("x", ["Comedy"])]

        let result = recommend(catalog, ["d": .dislike])

        #expect(result.hasPreferences)
        #expect(result.items.isEmpty)
    }

    @Test func everyRatedMovieIsExcludedUnseenIncluded() {
        let catalog = [
            f("liked1", ["Drama"]),
            f("liked2", ["Drama"]),
            f("disliked", ["Drama", "War"]),
            f("unseen", ["Drama"]),
            f("candidate", ["Drama"]),
        ]
        let ratings: [String: RatingValue] = ["liked1": .like, "liked2": .like, "disliked": .dislike, "unseen": .unseen]

        #expect(ids(recommend(catalog, ratings)) == ["candidate"])
    }

    @Test func blankDirectorsAndBlankCastNamesNeverMatch() {
        let catalog = [
            f("liked", [], "  ", cast: ["", " "]),
            f("blankToo", [], "", cast: [" "]),
        ]

        let result = recommend(catalog, ["liked": .like])

        #expect(result.items.isEmpty)
        #expect(result.hasPreferences) // still a like: Genie says "no matches", not "get started"
        #expect(result.likedCount == 1)
    }

    @Test func namesMatchAfterTrimmingAndARepeatedGenreCountsOnce() {
        let catalog = [
            f("liked", ["Drama", "Drama"], " Ada Vale"),
            f("c", ["Drama", "Drama"], "Ada Vale "),
        ]

        #expect(scores(recommend(catalog, ["liked": .like])) == ["c=4"])
    }

    @Test func tiesBreakOnPopularityMissingPopularityLastThenId() {
        let catalog = [
            f("liked", ["Drama"]),
            f("b", ["Drama"], popularity: nil),
            f("a", ["Drama"], popularity: nil),
            f("low", ["Drama"], popularity: 1.0),
            f("high", ["Drama"], popularity: 9.0),
        ]

        #expect(ids(recommend(catalog, ["liked": .like])) == ["high", "low", "a", "b"])
    }

    @Test func equalPopularityFallsBackToId() {
        let catalog = [f("liked", ["Drama"]), f("y", ["Drama"], popularity: 4.0), f("x", ["Drama"], popularity: 4.0)]

        #expect(ids(recommend(catalog, ["liked": .like])) == ["x", "y"])
    }

    @Test func reasonsAreTheTopTwoPositiveContributions() throws {
        let catalog = [
            f("l1", ["Drama", "Romance"], "Ada Vale"),
            f("l2", ["Drama"]),
            f("c", ["Drama", "Romance"], "Ada Vale"),
        ]

        let item = try #require(recommend(catalog, ["l1": .like, "l2": .like]).items.first)

        #expect(item.reasons == [Reason(kind: .director, name: "Ada Vale", points: 3), Reason(kind: .genre, name: "Drama", points: 2)])
    }

    @Test func equalContributionsListDirectorThenActorThenGenreThenName() throws {
        let catalog = [
            f("l1", ["Drama", "Comedy"], cast: ["Bo Lin"]),
            f("l2", ["Drama", "Comedy"]),
            f("c", ["Drama", "Comedy"], cast: ["Bo Lin"]),
        ]

        let item = try #require(recommend(catalog, ["l1": .like, "l2": .like]).items.first)

        #expect(item.reasons == [Reason(kind: .actor, name: "Bo Lin", points: 2), Reason(kind: .genre, name: "Comedy", points: 2)])
    }

    @Test func negativeContributionsAreNeverReasons() throws {
        let catalog = [
            f("liked", [], "Ada Vale"),
            f("disliked", ["Horror"]),
            f("c", ["Horror"], "Ada Vale"),
        ]

        let item = try #require(recommend(catalog, ["liked": .like, "disliked": .dislike]).items.first)

        #expect(item.reasons == [Reason(kind: .director, name: "Ada Vale", points: 3)])
    }

    @Test func limitKeepsTheBestScores() {
        let catalog = [
            f("liked", ["Drama"], "Ada Vale"),
            f("one", ["Drama"]),
            f("three", [], "Ada Vale"),
            f("four", ["Drama"], "Ada Vale"),
        ]

        #expect(ids(recommend(catalog, ["liked": .like], limit: 2)) == ["four", "three"])
        #expect(recommend(catalog, ["liked": .like], limit: -1).items.isEmpty) // clamped at 0
    }

    /// Not in the Android suite: Kotlin compares names by UTF-16 code units, so a precomposed and a
    /// decomposed spelling are different names. Swift `String` equality would merge them.
    @Test func namesThatDifferOnlyInUnicodeNormalizationStayApart() {
        let catalog = [f("liked", [], "Ren\u{E9}e Roy"), f("c", [], "Rene\u{301}e Roy")]

        #expect(recommend(catalog, ["liked": .like]).items.isEmpty)
    }

    /// Plan I4: U+FF5E is one UTF-16 unit (0xFF5E); U+1F600 is a surrogate pair starting 0xD83D.
    /// JVM order puts the emoji first; Swift's default `<` (scalar order) would not.
    @Test func idAndNameTiesFollowUTF16Order() throws {
        let tilde = "\u{FF5E}"
        let emoji = "\u{1F600}"
        let catalog = [
            f("liked", [tilde, emoji]),
            f(tilde, [tilde, emoji], popularity: 2),
            f(emoji, [tilde, emoji], popularity: 2),
        ]

        let result = recommend(catalog, ["liked": .like])

        #expect(ids(result) == [emoji, tilde])
        #expect(try #require(result.items.first).reasons.map(\.name) == [emoji, tilde])
    }

    @Test func ratingsForMoviesNoLongerInTheCatalogAreIgnored() {
        let result = recommend([f("c", ["Drama"])], ["gone": .like])

        #expect(!result.hasPreferences)
        #expect(result.items.isEmpty)
    }
}
