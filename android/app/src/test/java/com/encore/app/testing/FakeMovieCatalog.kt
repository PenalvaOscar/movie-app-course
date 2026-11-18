package com.encore.app.testing

import com.encore.app.core.data.catalog.MovieCatalog
import com.encore.app.core.data.catalog.MovieQuery
import com.encore.app.core.data.catalog.MovieSummary
import com.encore.app.core.model.Movie

/** In-memory catalog with fictional data; records every query it receives. */
class FakeMovieCatalog(
    var movies: List<MovieSummary> = emptyList(),
    var genreList: List<String> = emptyList(),
    var featuredCandidates: List<MovieSummary> = emptyList(),
    var details: Map<String, Movie> = emptyMap(),
) : MovieCatalog {
    val queries = mutableListOf<MovieQuery>()
    var failWith: Exception? = null

    override suspend fun genres(): List<String> {
        failWith?.let { throw it }
        return genreList
    }

    override suspend fun search(query: MovieQuery): List<MovieSummary> {
        queries += query
        failWith?.let { throw it }
        return movies.filter { query.text.isBlank() || it.title.contains(query.text, ignoreCase = true) }
    }

    override suspend fun featuredCandidates(limit: Int): List<MovieSummary> {
        failWith?.let { throw it }
        return featuredCandidates.take(limit)
    }

    override suspend fun movie(id: String): Movie? {
        failWith?.let { throw it }
        return details[id]
    }

    /** [movies] in list order stand for "most popular first". */
    override suspend fun swipeOrder(): List<String> {
        failWith?.let { throw it }
        return movies.map { it.id }
    }

    override suspend fun summaries(ids: Collection<String>): List<MovieSummary> {
        failWith?.let { throw it }
        val wanted = ids.toSet()
        return movies.filter { it.id in wanted }
    }
}

fun summary(id: String, title: String = "Film $id") =
    MovieSummary(id = id, title = title, year = 2001, director = "Director $id", rating = null, posterUrl = null)

fun movie(id: String, title: String = "Film $id") = Movie(
    id = id, title = title, year = 2001, synopsis = "Synopsis $id", genres = listOf("Drama"),
    director = "Director $id", cast = listOf("Actor A", "Actor B"), runtimeMinutes = 100, rating = 7.5,
    posterUrl = null, backdropUrl = null, sourceUrl = "https://en.wikipedia.org/wiki/Film_$id", releaseDate = null,
    voteCount = 10, popularity = 1.0, originalLanguage = "en", datasetSourceUrl = null,
)
