package com.encore.app.core.recommendation

import com.encore.app.core.data.rating.RatingValue

/** What scoring needs from a catalog movie. Blank names are ignored, so a missing director never matches. */
data class MovieFeatures(
    val id: String,
    val genres: List<String>,
    val director: String?,
    val cast: List<String>,
    val popularity: Double?,
)

/** Listed in tie-break order: with equal points a director explains a match better than an actor, an actor better than a genre. */
enum class ReasonKind { DIRECTOR, ACTOR, GENRE }

/** One positive contribution to a score: [points] = weight × net affinity for [name]. */
data class Reason(val kind: ReasonKind, val name: String, val points: Int)

data class Recommendation(val movieId: String, val score: Int, val reasons: List<Reason>)

/**
 * [likedCount] and [dislikedCount] count only ratings of movies still in the catalog: ratings
 * for removed movies are kept but play no part in scoring (O6).
 */
data class Recommendations(val items: List<Recommendation>, val likedCount: Int, val dislikedCount: Int) {
    val hasPreferences: Boolean get() = likedCount + dislikedCount > 0
}

/**
 * Content-based scoring (AGENTS.md, D6):
 * - affinity(feature) = liked movies with it − disliked movies with it
 * - score = 1 × Σ affinity(genre) + 3 × affinity(director) + 2 × Σ affinity(actor)
 *
 * Every rated movie is excluded, UNSEEN included, and UNSEEN adds no affinity. Only scores
 * above zero are recommended, ordered by score, then popularity (missing last), then id.
 */
object RecommendationEngine {
    const val GENRE_WEIGHT = 1
    const val DIRECTOR_WEIGHT = 3
    const val ACTOR_WEIGHT = 2
    const val MAX_REASONS = 2

    private val reasonOrder = compareByDescending<Reason> { it.points }.thenBy { it.kind }.thenBy { it.name }

    fun recommend(catalog: Collection<MovieFeatures>, ratings: Map<String, RatingValue>, limit: Int): Recommendations {
        val genreAffinity = HashMap<String, Int>()
        val directorAffinity = HashMap<String, Int>()
        val actorAffinity = HashMap<String, Int>()
        var liked = 0
        var disliked = 0

        for (movie in catalog) {
            val delta = when (ratings[movie.id]) {
                RatingValue.LIKE -> {
                    liked++
                    1
                }
                RatingValue.DISLIKE -> {
                    disliked++
                    -1
                }
                RatingValue.UNSEEN, null -> continue
            }
            movie.genreNames().forEach { genreAffinity.merge(it, delta, Int::plus) }
            movie.directorName()?.let { directorAffinity.merge(it, delta, Int::plus) }
            movie.actorNames().forEach { actorAffinity.merge(it, delta, Int::plus) }
        }

        if (liked == 0) return Recommendations(emptyList(), liked, disliked) // without a like nothing can score above zero

        val scored = ArrayList<Pair<Recommendation, Double?>>()
        for (movie in catalog) {
            if (movie.id in ratings) continue
            val genres = movie.genreNames()
            val director = movie.directorName()
            val actors = movie.actorNames()
            // Score first; Reason objects are built only for the few movies that make the list.
            val score = genres.sumOf { GENRE_WEIGHT * (genreAffinity[it] ?: 0) } +
                (director?.let { DIRECTOR_WEIGHT * (directorAffinity[it] ?: 0) } ?: 0) +
                actors.sumOf { ACTOR_WEIGHT * (actorAffinity[it] ?: 0) }
            if (score <= 0) continue
            val reasons = buildList {
                genres.forEach { add(Reason(ReasonKind.GENRE, it, GENRE_WEIGHT * (genreAffinity[it] ?: 0))) }
                director?.let { add(Reason(ReasonKind.DIRECTOR, it, DIRECTOR_WEIGHT * (directorAffinity[it] ?: 0))) }
                actors.forEach { add(Reason(ReasonKind.ACTOR, it, ACTOR_WEIGHT * (actorAffinity[it] ?: 0))) }
            }.filter { it.points > 0 }.sortedWith(reasonOrder).take(MAX_REASONS)
            scored.add(Recommendation(movie.id, score, reasons) to movie.popularity)
        }

        val order = compareByDescending<Pair<Recommendation, Double?>> { it.first.score }
            .thenComparator { a, b -> comparePopularityDesc(a.second, b.second) }
            .thenBy { it.first.movieId }
        return Recommendations(scored.sortedWith(order).take(limit.coerceAtLeast(0)).map { it.first }, liked, disliked)
    }

    /** Higher popularity first; a missing value sorts after every known one. */
    private fun comparePopularityDesc(a: Double?, b: Double?): Int = when {
        a == null && b == null -> 0
        a == null -> 1
        b == null -> -1
        else -> b.compareTo(a)
    }

    private fun MovieFeatures.genreNames(): Set<String> = genres.mapNotNullTo(LinkedHashSet()) { it.trim().ifEmpty { null } }

    private fun MovieFeatures.directorName(): String? = director?.trim()?.ifEmpty { null }

    private fun MovieFeatures.actorNames(): Set<String> = cast.mapNotNullTo(LinkedHashSet()) { it.trim().ifEmpty { null } }
}
