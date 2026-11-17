package com.encore.app.core.data.catalog.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.encore.app.core.data.catalog.CatalogImport
import com.encore.app.core.data.catalog.CatalogOrigin
import com.encore.app.core.model.Movie
import com.encore.app.core.model.Trivia
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Fictional fixtures only. */
@RunWith(AndroidJUnit4::class)
class RoomCatalogStoreTest {
    private lateinit var db: CatalogDatabase
    private lateinit var store: RoomCatalogStore

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, CatalogDatabase::class.java).build()
        store = RoomCatalogStore(db, clock = { 1_000L })
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun movie(id: String, genres: List<String> = listOf("Drama"), cast: List<String> = listOf("Bo Lin", "Cy Moss")) = Movie(
        id = id, title = "Film $id", year = 2001, synopsis = "S", genres = genres, director = "Ada Vale", cast = cast,
        runtimeMinutes = 100, rating = null, posterUrl = null, backdropUrl = null, sourceUrl = "https://example.test/$id",
        releaseDate = null, voteCount = null, popularity = 3.5, originalLanguage = "en", datasetSourceUrl = null,
    )

    private fun import(vararg movies: Movie, trivia: List<Trivia> = emptyList()) = CatalogImport(movies.toList(), trivia, rejectedMovies = 2, rejectedTrivia = 1)

    @Test
    fun emptyDatabaseHasNoImport() = runBlocking {
        assertNull(store.importedMovieCount())
    }

    @Test
    fun storesMoviesGenresCastTriviaAndMeta() = runBlocking {
        val trivia = Trivia("t1", "m1", "plot", "Which?", listOf("A", "B", "C", "D"), 3, null)
        val progress = mutableListOf<Float>()

        store.replaceCatalog(import(movie("m1", genres = listOf("Drama", "Mystery")), movie("m2"), trivia = listOf(trivia)), CatalogOrigin.ASSET) { progress += it }

        val dao = db.catalogDao()
        assertEquals(2, store.importedMovieCount())
        assertEquals(2, dao.movieCount())
        assertEquals(listOf("Drama", "Mystery"), dao.genresOf("m1"))
        assertEquals(listOf("Bo Lin", "Cy Moss"), dao.castOf("m1"))
        assertNull(dao.movie("m1")!!.rating)
        assertEquals(trivia.options, dao.trivia("t1")!!.options)
        val meta = dao.meta()!!
        assertEquals("ASSET", meta.origin)
        assertEquals(1_000L, meta.importedAt)
        assertEquals(1, meta.triviaCount)
        assertEquals(2, meta.rejectedMovies)
        assertEquals(1, meta.rejectedTrivia)
        assertEquals(1f, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun replaceSwapsTheWholeCatalog() = runBlocking {
        store.replaceCatalog(import(movie("m1"), movie("m2")), CatalogOrigin.ASSET) {}
        store.replaceCatalog(import(movie("m3")), CatalogOrigin.DOWNLOAD) {}

        val dao = db.catalogDao()
        assertEquals(1, store.importedMovieCount())
        assertNull(dao.movie("m1"))
        assertEquals(emptyList<String>(), dao.castOf("m1"))
        assertEquals("DOWNLOAD", dao.meta()!!.origin)
    }

    @Test
    fun failedReplaceKeepsThePreviousCatalog() = runBlocking {
        store.replaceCatalog(import(movie("m1")), CatalogOrigin.ASSET) {}

        // Trivia pointing at a movie that isn't in the new catalog violates the foreign key mid-transaction.
        val broken = import(movie("m2"), trivia = listOf(Trivia("t1", "ghost", "plot", "Q", listOf("A", "B", "C", "D"), 0, null)))
        val failed = runCatching { store.replaceCatalog(broken, CatalogOrigin.DOWNLOAD) {} }

        assertTrue(failed.isFailure)
        assertEquals(1, store.importedMovieCount())
        assertEquals("Film m1", db.catalogDao().movie("m1")!!.title)
        assertEquals("ASSET", db.catalogDao().meta()!!.origin)
    }

    @Test
    fun handlesMoreThanOneChunk() = runBlocking {
        val many = Array(1_234) { movie("m$it") }
        store.replaceCatalog(import(*many), CatalogOrigin.ASSET) {}
        assertEquals(1_234, db.catalogDao().movieCount())
    }
}
