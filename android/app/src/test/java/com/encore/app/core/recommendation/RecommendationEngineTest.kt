package com.encore.app.core.recommendation

import com.encore.app.core.data.rating.RatingValue
import com.encore.app.core.data.rating.RatingValue.DISLIKE
import com.encore.app.core.data.rating.RatingValue.LIKE
import com.encore.app.core.data.rating.RatingValue.UNSEEN
import com.encore.app.core.recommendation.ReasonKind.ACTOR
import com.encore.app.core.recommendation.ReasonKind.DIRECTOR
import com.encore.app.core.recommendation.ReasonKind.GENRE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fictional fixtures only. Weights per AGENTS.md: genre 1, director 3, actor 2, dislikes subtract equally. */
class RecommendationEngineTest {
    private fun f(
        id: String,
        genres: List<String> = emptyList(),
        director: String? = null,
        cast: List<String> = emptyList(),
        popularity: Double? = null,
    ) = MovieFeatures(id, genres, director, cast, popularity)

    private fun recommend(catalog: List<MovieFeatures>, ratings: Map<String, RatingValue>, limit: Int = 100) =
        RecommendationEngine.recommend(catalog, ratings, limit)

    private fun Recommendations.scores() = items.map { it.movieId to it.score }

    @Test
    fun `no ratings means no preferences and no recommendations`() {
        val result = recommend(listOf(f("a", listOf("Drama"))), emptyMap())

        assertFalse(result.hasPreferences)
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `UNSEEN ratings are not preferences and add no affinity`() {
        val catalog = listOf(f("seen", listOf("Horror"), "Ida Brook"), f("other", listOf("Horror"), "Ida Brook"))

        val result = recommend(catalog, mapOf("seen" to UNSEEN))

        assertFalse(result.hasPreferences)
        assertEquals(0, result.likedCount)
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `genre counts 1, director 3, actor 2`() {
        val catalog = listOf(
            f("liked", listOf("Drama"), "Ada Vale", listOf("Bo Lin")),
            f("genreOnly", listOf("Drama")),
            f("directorOnly", director = "Ada Vale"),
            f("actorOnly", cast = listOf("Bo Lin")),
            f("all", listOf("Drama"), "Ada Vale", listOf("Bo Lin")),
            f("none", listOf("Comedy"), "Cy Moss", listOf("Di Park")),
        )

        val result = recommend(catalog, mapOf("liked" to LIKE))

        assertEquals(listOf("all" to 6, "directorOnly" to 3, "actorOnly" to 2, "genreOnly" to 1), result.scores())
        assertEquals(1, result.likedCount)
        assertEquals(0, result.dislikedCount)
    }

    @Test
    fun `affinity adds up across liked movies`() {
        val catalog = listOf(f("l1", listOf("Comedy")), f("l2", listOf("Comedy")), f("c", listOf("Comedy")))

        assertEquals(listOf("c" to 2), recommend(catalog, mapOf("l1" to LIKE, "l2" to LIKE)).scores())
    }

    @Test
    fun `dislikes subtract with the same weights`() {
        val catalog = listOf(
            f("liked", listOf("Drama"), "Ada Vale"),
            f("disliked", listOf("Drama"), "Cy Moss", listOf("Di Park")),
            f("dramaByAda", listOf("Drama"), "Ada Vale"), // genre 1 - 1 = 0, director +3
            f("dramaOnly", listOf("Drama")), // 0: not shown
            f("byCy", director = "Cy Moss"), // -3: not shown
            f("adaWithDi", director = "Ada Vale", cast = listOf("Di Park")), // +3 - 2 = 1
        )

        val result = recommend(catalog, mapOf("liked" to LIKE, "disliked" to DISLIKE))

        assertEquals(listOf("dramaByAda" to 3, "adaWithDi" to 1), result.scores())
        assertEquals(1, result.dislikedCount)
    }

    @Test
    fun `only dislikes are preferences but recommend nothing`() {
        val catalog = listOf(f("d", listOf("Drama")), f("c", listOf("Drama")), f("x", listOf("Comedy")))

        val result = recommend(catalog, mapOf("d" to DISLIKE))

        assertTrue(result.hasPreferences)
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `every rated movie is excluded, UNSEEN included`() {
        val catalog = listOf(
            f("liked1", listOf("Drama")),
            f("liked2", listOf("Drama")),
            f("disliked", listOf("Drama", "War")),
            f("unseen", listOf("Drama")),
            f("candidate", listOf("Drama")),
        )
        val ratings = mapOf("liked1" to LIKE, "liked2" to LIKE, "disliked" to DISLIKE, "unseen" to UNSEEN)

        assertEquals(listOf("candidate"), recommend(catalog, ratings).items.map { it.movieId })
    }

    @Test
    fun `blank directors and blank cast names never match`() {
        val catalog = listOf(
            f("liked", director = "  ", cast = listOf("", " ")),
            f("blankToo", director = "", cast = listOf(" ")),
        )

        val result = recommend(catalog, mapOf("liked" to LIKE))
        assertTrue(result.items.isEmpty())
        assertTrue(result.hasPreferences) // still a like: Genie says "no matches", not "get started"
        assertEquals(1, result.likedCount)
    }

    @Test
    fun `names match after trimming and a repeated genre counts once`() {
        val catalog = listOf(
            f("liked", listOf("Drama", "Drama"), " Ada Vale"),
            f("c", listOf("Drama", "Drama"), "Ada Vale "),
        )

        assertEquals(listOf("c" to 4), recommend(catalog, mapOf("liked" to LIKE)).scores())
    }

    @Test
    fun `ties break on popularity, missing popularity last, then id`() {
        val catalog = listOf(
            f("liked", listOf("Drama")),
            f("b", listOf("Drama"), popularity = null),
            f("a", listOf("Drama"), popularity = null),
            f("low", listOf("Drama"), popularity = 1.0),
            f("high", listOf("Drama"), popularity = 9.0),
        )

        assertEquals(listOf("high", "low", "a", "b"), recommend(catalog, mapOf("liked" to LIKE)).items.map { it.movieId })
    }

    @Test
    fun `equal popularity falls back to id`() {
        val catalog = listOf(f("liked", listOf("Drama")), f("y", listOf("Drama"), popularity = 4.0), f("x", listOf("Drama"), popularity = 4.0))

        assertEquals(listOf("x", "y"), recommend(catalog, mapOf("liked" to LIKE)).items.map { it.movieId })
    }

    @Test
    fun `reasons are the top two positive contributions`() {
        val catalog = listOf(
            f("l1", listOf("Drama", "Romance"), "Ada Vale"),
            f("l2", listOf("Drama")),
            f("c", listOf("Drama", "Romance"), "Ada Vale"),
        )

        val reasons = recommend(catalog, mapOf("l1" to LIKE, "l2" to LIKE)).items.single().reasons

        assertEquals(listOf(Reason(DIRECTOR, "Ada Vale", 3), Reason(GENRE, "Drama", 2)), reasons)
    }

    @Test
    fun `equal contributions list director, then actor, then genre, then name`() {
        val catalog = listOf(
            f("l1", listOf("Drama", "Comedy"), cast = listOf("Bo Lin")),
            f("l2", listOf("Drama", "Comedy")),
            f("c", listOf("Drama", "Comedy"), cast = listOf("Bo Lin")),
        )

        val reasons = recommend(catalog, mapOf("l1" to LIKE, "l2" to LIKE)).items.single().reasons

        assertEquals(listOf(Reason(ACTOR, "Bo Lin", 2), Reason(GENRE, "Comedy", 2)), reasons)
    }

    @Test
    fun `negative contributions are never reasons`() {
        val catalog = listOf(
            f("liked", director = "Ada Vale"),
            f("disliked", listOf("Horror")),
            f("c", listOf("Horror"), "Ada Vale"),
        )

        val reasons = recommend(catalog, mapOf("liked" to LIKE, "disliked" to DISLIKE)).items.single().reasons

        assertEquals(listOf(Reason(DIRECTOR, "Ada Vale", 3)), reasons)
    }

    @Test
    fun `limit keeps the best scores`() {
        val catalog = listOf(
            f("liked", listOf("Drama"), "Ada Vale"),
            f("one", listOf("Drama")),
            f("three", director = "Ada Vale"),
            f("four", listOf("Drama"), "Ada Vale"),
        )

        assertEquals(listOf("four", "three"), recommend(catalog, mapOf("liked" to LIKE), limit = 2).items.map { it.movieId })
    }

    @Test
    fun `ratings for movies no longer in the catalog are ignored`() {
        val catalog = listOf(f("c", listOf("Drama")))

        val result = recommend(catalog, mapOf("gone" to LIKE))

        assertFalse(result.hasPreferences)
        assertTrue(result.items.isEmpty())
    }
}
