package com.encore.app.core.data.catalog

import com.encore.app.core.model.Movie
import java.text.Normalizer
import java.util.Locale

/** Sort orders offered in Explore. Missing values (null rating, year, popularity) always sort last. */
enum class MovieSort { POPULARITY, RATING, NEWEST, TITLE }

/** What Explore asks the catalog for. [text] matches title or director, accent- and case-insensitive. */
data class MovieQuery(
    val text: String = "",
    val genre: String? = null,
    val sort: MovieSort = MovieSort.POPULARITY,
) {
    val isFiltered: Boolean get() = text.isNotBlank() || genre != null
}

/** The fields a poster card needs; the full [Movie] is loaded only for details. */
data class MovieSummary(
    val id: String,
    val title: String,
    val year: Int?,
    val director: String?,
    val rating: Double?,
    val posterUrl: String?,
)

/** Read access to the local catalog (catalog.db). Every call works offline. */
interface MovieCatalog {
    /** Genre names as they appear in the source data, most common first. */
    suspend fun genres(): List<String>

    suspend fun search(query: MovieQuery): List<MovieSummary>

    /** The most popular movies that have a poster and a synopsis: the pool [pickFeatured] draws from. */
    suspend fun featuredCandidates(limit: Int): List<MovieSummary>

    suspend fun movie(id: String): Movie?

    /** Every movie id, most popular first (then id): the Swipe queue before rated movies are removed. */
    suspend fun swipeOrder(): List<String>

    /** Cards for [ids], in no particular order. Ids no longer in the catalog are simply absent (O6). */
    suspend fun summaries(ids: Collection<String>): List<MovieSummary>
}

/**
 * Search normalization shared by the importer (stored search columns) and queries, so
 * "almodovar" finds "Almodóvar". SQLite's LIKE folds ASCII case only, hence the explicit folding.
 */
object SearchText {
    private val marks = Regex("\\p{Mn}+")

    fun normalize(text: String): String =
        marks.replace(Normalizer.normalize(text.trim(), Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

    /** A contains-pattern for `LIKE … ESCAPE '\'`, or null when there is nothing to match. */
    fun likePattern(text: String): String? {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return null
        val escaped = normalized.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return "%$escaped%"
    }
}

/** One featured movie per day, stable for the whole day and rotating through [candidates]. */
fun pickFeatured(candidates: List<MovieSummary>, epochDay: Long): MovieSummary? =
    if (candidates.isEmpty()) null else candidates[Math.floorMod(epochDay, candidates.size.toLong()).toInt()]
