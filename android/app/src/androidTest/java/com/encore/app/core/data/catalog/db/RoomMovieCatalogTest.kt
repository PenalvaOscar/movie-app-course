package com.encore.app.core.data.catalog.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.encore.app.core.data.catalog.CatalogImport
import com.encore.app.core.data.catalog.CatalogOrigin
import com.encore.app.core.data.catalog.MovieQuery
import com.encore.app.core.data.catalog.MovieSort
import com.encore.app.core.model.Movie
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Explore and Details queries against a real (in-memory) Room database. Fictional fixtures only. */
@RunWith(AndroidJUnit4::class)
class RoomMovieCatalogTest {
    private lateinit var db: CatalogDatabase
    private lateinit var catalog: RoomMovieCatalog

    private fun movie(
        id: String,
        title: String,
        director: String? = null,
        genres: List<String> = emptyList(),
        year: Int? = null,
        rating: Double? = null,
        votes: Int? = null,
        popularity: Double? = null,
        poster: String? = null,
        synopsis: String = "S",
    ) = Movie(
        id = id, title = title, year = year, synopsis = synopsis, genres = genres, director = director,
        cast = listOf("Bo Lin", "Cy Moss"), runtimeMinutes = 90, rating = rating, posterUrl = poster, backdropUrl = null,
        sourceUrl = "https://example.test/$id", releaseDate = null, voteCount = votes, popularity = popularity,
        originalLanguage = "en", datasetSourceUrl = null,
    )

    private val movies = listOf(
        movie("a", "Amélie's Garden", director = "Pia Núñez", genres = listOf("Comedy", "Romance"), year = 2001, rating = 7.9, votes = 50, popularity = 9.0, poster = "https://p.test/a.jpg"),
        movie("b", "zebra crossing", director = "Ola Berg", genres = listOf("Drama"), year = 1990, rating = 7.9, votes = 900, popularity = 5.0, poster = "https://p.test/b.jpg"),
        movie("c", "Bright 100% Days", director = null, genres = listOf("Drama", "Comedy"), year = null, rating = null, popularity = null),
        movie("d", "Under_score", director = "Ana Núñez", genres = listOf("Drama"), year = 2010, rating = 6.0, popularity = 7.0, poster = "https://p.test/d.jpg", synopsis = ""),
    )

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, CatalogDatabase::class.java).build()
        RoomCatalogStore(db).replaceCatalog(CatalogImport(movies, emptyList(), 0, 0), CatalogOrigin.ASSET) {}
        catalog = RoomMovieCatalog(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun ids(query: MovieQuery) = runBlocking { catalog.search(query).map { it.id } }

    @Test
    fun defaultQueryReturnsEverythingByPopularityWithMissingLast() {
        assertEquals(listOf("a", "d", "b", "c"), ids(MovieQuery()))
    }

    @Test
    fun ratingSortBreaksTiesOnVotesAndPutsMissingRatingsLast() {
        assertEquals(listOf("b", "a", "d", "c"), ids(MovieQuery(sort = MovieSort.RATING)))
    }

    @Test
    fun newestAndTitleSorts() {
        assertEquals(listOf("d", "a", "b", "c"), ids(MovieQuery(sort = MovieSort.NEWEST)))
        assertEquals(listOf("a", "c", "d", "b"), ids(MovieQuery(sort = MovieSort.TITLE))) // case-insensitive
    }

    @Test
    fun searchMatchesTitleOrDirectorIgnoringCaseAndAccents() {
        assertEquals(listOf("a"), ids(MovieQuery(text = "AMELIE")))
        assertEquals(listOf("a", "d"), ids(MovieQuery(text = "nunez")))
        assertEquals(listOf("b"), ids(MovieQuery(text = "berg")))
    }

    @Test
    fun likeWildcardsInTheSearchAreLiteral() {
        assertEquals(listOf("c"), ids(MovieQuery(text = "100%")))
        // "_" would match any single character if it weren't escaped; only "Under_score" contains it.
        assertEquals(listOf("d"), ids(MovieQuery(text = "_")))
    }

    @Test
    fun genreFilterCombinesWithSearchAndSort() {
        assertEquals(listOf("a", "c"), ids(MovieQuery(genre = "Comedy")))
        assertEquals(listOf("c"), ids(MovieQuery(genre = "Comedy", text = "bright")))
        assertEquals(listOf("b", "d", "c"), ids(MovieQuery(genre = "Drama", sort = MovieSort.RATING)))
        assertEquals(emptyList<String>(), ids(MovieQuery(genre = "Western")))
    }

    @Test
    fun genresAreListedMostCommonFirst() = runBlocking {
        assertEquals(listOf("Drama", "Comedy", "Romance"), catalog.genres())
    }

    @Test
    fun featuredCandidatesNeedAPosterAndASynopsis() = runBlocking {
        assertEquals(listOf("a", "b"), catalog.featuredCandidates(10).map { it.id })
        assertEquals(listOf("a"), catalog.featuredCandidates(1).map { it.id })
    }

    @Test
    fun movieLoadsWithGenresAndCastInBillingOrder() = runBlocking {
        val loaded = catalog.movie("a")!!
        assertEquals(movies[0].copy(genres = listOf("Comedy", "Romance")), loaded)
        assertNull(catalog.movie("missing"))
    }
}
