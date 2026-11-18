package com.encore.app.core.data.rating.db

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.encore.app.core.data.rating.Rating
import com.encore.app.core.data.rating.RatingRepository
import com.encore.app.core.data.rating.RatingValue.DISLIKE
import com.encore.app.core.data.rating.RatingValue.LIKE
import com.encore.app.core.data.rating.RatingValue.UNSEEN
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** user.db on a real file, so "survives a restart" means close and reopen. Fictional ids only. */
@RunWith(AndroidJUnit4::class)
class RoomRatingStoreTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "user-test.db"
    private var now = 1_000L
    private lateinit var db: UserDatabase

    private fun open(): UserDatabase = Room.databaseBuilder(context, UserDatabase::class.java, name).build()

    private fun repo(database: UserDatabase = db) = RatingRepository(RoomRatingStore(database), clock = { now })

    @Before
    fun setUp() {
        context.deleteDatabase(name)
        db = open()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(name)
    }

    @Test
    fun ratingsAndUndoHistorySurviveClosingTheDatabase() = runBlocking {
        repo().setRating("a", LIKE)
        now = 2_000L
        repo().setRating("a", DISLIKE)
        now = 3_000L
        repo().setRating("b", UNSEEN)
        db.close()

        db = open()
        val reopened = repo()
        assertEquals(listOf(Rating("b", UNSEEN, 3_000L), Rating("a", DISLIKE, 2_000L)), reopened.ratings.first())
        assertTrue(reopened.canUndo.first())

        assertEquals("b", reopened.undo()?.movieId)
        assertNull(reopened.rating("b").first())
        assertEquals("a", reopened.undo()?.movieId)
        assertEquals(Rating("a", LIKE, 1_000L), reopened.ratings.first().single())
        assertEquals("a", reopened.undo()?.movieId)
        assertTrue(reopened.ratings.first().isEmpty())
        assertFalse(reopened.canUndo.first())
    }

    @Test
    fun aFailureInsideTheTransactionRollsBackRatingAndHistory() = runBlocking {
        val store = RoomRatingStore(db)
        repo().setRating("a", LIKE)

        try {
            store.transaction {
                store.upsert(Rating("a", DISLIKE, 9L))
                throw IOException("simulated crash mid-write")
            }
            fail("expected the transaction to throw")
        } catch (_: IOException) {
        }

        assertEquals(LIKE, repo().rating("a").first())
        assertEquals("a", repo().undo()?.movieId)
        assertNull(repo().undo())
    }

    @Test
    fun observedRatingFollowsChangesAndUndo() = runBlocking {
        val repository = repo()
        assertNull(repository.rating("a").first())
        repository.setRating("a", UNSEEN)
        assertEquals(UNSEEN, repository.rating("a").first())
        repository.undo()
        assertNull(repository.rating("a").first())
    }
}
