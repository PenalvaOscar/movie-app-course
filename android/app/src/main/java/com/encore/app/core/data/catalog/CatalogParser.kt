package com.encore.app.core.data.catalog

import com.encore.app.core.model.Movie
import com.encore.app.core.model.Trivia
import java.io.InputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream

/** A validated catalog, ready to store. Rejected counts say how many records were dropped and why nothing is silent. */
data class CatalogImport(
    val movies: List<Movie>,
    val trivia: List<Trivia>,
    val rejectedMovies: Int,
    val rejectedTrivia: Int,
)

class InvalidCatalogException(val reason: Reason, cause: Throwable? = null) :
    Exception("Invalid catalog: $reason", cause) {
    enum class Reason { MALFORMED, MISSING_MOVIES, EMPTY_MOVIES, MISSING_TRIVIA, DUPLICATE_MOVIE_IDS }
}

/**
 * Turns the normalized catalog JSON (`{"movies":[...],"trivia":[...]}`, the shape served by
 * `api/` at /catalog) into validated domain objects.
 *
 * Whole-catalog rules mirror `api/setup.js`: both arrays present, at least one movie, unique
 * movie ids. Record rules: a movie needs an id and a title; a trivia item needs an id (unique),
 * a known movie, a question, exactly four non-blank options and an answer index inside them.
 * Records that break a record rule are counted and skipped. A value of the wrong JSON type
 * fails the whole catalog instead, because it means the source format changed.
 *
 * The stream is decoded directly (no 10 MB String), but the decoded catalog is held in memory
 * before it is stored.
 */
object CatalogParser {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true // explicit null -> field default
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun parse(input: InputStream): CatalogImport {
        val dto = try {
            json.decodeFromStream(CatalogDto.serializer(), input)
        } catch (e: IllegalArgumentException) { // SerializationException is a subclass
            throw InvalidCatalogException(InvalidCatalogException.Reason.MALFORMED, e)
        }
        val movieDtos = dto.movies ?: throw InvalidCatalogException(InvalidCatalogException.Reason.MISSING_MOVIES)
        val triviaDtos = dto.trivia ?: throw InvalidCatalogException(InvalidCatalogException.Reason.MISSING_TRIVIA)

        val movies = movieDtos.mapNotNull { it.toMovie() }
        if (movies.isEmpty()) throw InvalidCatalogException(InvalidCatalogException.Reason.EMPTY_MOVIES)
        val movieIds = movies.mapTo(HashSet(movies.size)) { it.id }
        if (movieIds.size != movies.size) throw InvalidCatalogException(InvalidCatalogException.Reason.DUPLICATE_MOVIE_IDS)

        val triviaIds = HashSet<String>()
        val trivia = triviaDtos.mapNotNull { dto -> dto.toTrivia(movieIds)?.takeIf { triviaIds.add(it.id) } }

        return CatalogImport(
            movies = movies,
            trivia = trivia,
            rejectedMovies = movieDtos.size - movies.size,
            rejectedTrivia = triviaDtos.size - trivia.size,
        )
    }

    private fun MovieDto.toMovie(): Movie? {
        val id = id.trim()
        val title = title.trim()
        if (id.isEmpty() || title.isEmpty()) return null
        return Movie(
            id = id,
            title = title,
            year = year.positiveOrNull(),
            synopsis = synopsis.trim(),
            genres = genres.cleaned(),
            director = director.blankToNull(),
            cast = cast.cleaned(),
            runtimeMinutes = runtime.positiveOrNull(),
            rating = rating.takeIf { it > 0.0 },
            posterUrl = posterUrl.blankToNull(),
            backdropUrl = backdropUrl.blankToNull(),
            sourceUrl = sourceUrl.blankToNull(),
            releaseDate = releaseDate.blankToNull(),
            voteCount = voteCount?.positiveOrNull(),
            popularity = popularity?.takeIf { it > 0.0 },
            originalLanguage = originalLanguage.blankToNull(),
            datasetSourceUrl = datasetSourceUrl.blankToNull(),
        )
    }

    private fun TriviaDto.toTrivia(movieIds: Set<String>): Trivia? {
        val id = id.trim()
        val movieId = movieId.trim() // movie ids are trimmed too, so compare like with like
        val valid = id.isNotEmpty() &&
            movieId in movieIds &&
            kind.isNotBlank() &&
            question.isNotBlank() &&
            options.size == 4 && options.none { it.isBlank() } &&
            answerIndex in options.indices
        if (!valid) return null
        return Trivia(id, movieId, kind.trim(), question.trim(), options.map { it.trim() }, answerIndex, clue.blankToNull())
    }

    private fun Int.positiveOrNull() = takeIf { it > 0 }
    private fun String?.blankToNull() = this?.trim()?.takeIf { it.isNotEmpty() }
    private fun List<String>.cleaned() = map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

@Serializable
private class CatalogDto(
    val movies: List<MovieDto>? = null,
    val trivia: List<TriviaDto>? = null,
)

@Serializable
private class MovieDto(
    val id: String = "",
    val title: String = "",
    val year: Int = 0,
    val synopsis: String = "",
    val genres: List<String> = emptyList(),
    val director: String = "",
    val cast: List<String> = emptyList(),
    val runtime: Int = 0,
    val rating: Double = 0.0,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val sourceUrl: String? = null,
    val releaseDate: String? = null,
    val voteCount: Int? = null,
    val popularity: Double? = null,
    val originalLanguage: String? = null,
    val datasetSourceUrl: String? = null,
)

@Serializable
private class TriviaDto(
    val id: String = "",
    val movieId: String = "",
    val kind: String = "",
    val question: String = "",
    val options: List<String> = emptyList(),
    val answerIndex: Int = -1,
    val clue: String? = null,
)
