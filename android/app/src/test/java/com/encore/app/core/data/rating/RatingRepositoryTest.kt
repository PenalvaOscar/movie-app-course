package com.encore.app.core.data.rating

import com.encore.app.core.data.rating.RatingValue.DISLIKE
import com.encore.app.core.data.rating.RatingValue.LIKE
import com.encore.app.core.data.rating.RatingValue.UNSEEN
import com.encore.app.testing.FakeRatingStore
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RatingRepositoryTest {
    private var now = 1_000L
    private val store = FakeRatingStore()
    private val repo = RatingRepository(store, clock = { now })

    @Test
    fun `a first rating is saved and logged with no previous value`() = runTest {
        assertTrue(repo.setRating("a", LIKE))

        assertEquals(listOf(Rating("a", LIKE, 1_000L)), repo.ratings.first())
        assertEquals(LIKE, repo.rating("a").first())
        val event = store.eventLog.single()
        assertEquals("a", event.movieId)
        assertNull(event.previous)
        assertEquals(LIKE, event.value)
    }

    @Test
    fun `changing a rating logs the previous value and time`() = runTest {
        repo.setRating("a", LIKE)
        now = 2_000L
        repo.setRating("a", DISLIKE)

        assertEquals(Rating("a", DISLIKE, 2_000L), repo.ratings.first().single())
        val last = store.eventLog.last()
        assertEquals(LIKE, last.previous)
        assertEquals(1_000L, last.previousAt)
        assertEquals(DISLIKE, last.value)
    }

    @Test
    fun `rating with the same value again changes nothing and adds no undo step`() = runTest {
        repo.setRating("a", UNSEEN)
        now = 2_000L
        assertFalse(repo.setRating("a", UNSEEN))

        assertEquals(1, store.eventLog.size)
        assertEquals(1_000L, repo.ratings.first().single().updatedAt)
    }

    @Test
    fun `undo of a first rating removes it`() = runTest {
        repo.setRating("a", LIKE)

        val undone = repo.undo()

        assertEquals("a", undone?.movieId)
        assertTrue(repo.ratings.first().isEmpty())
        assertNull(repo.rating("a").first())
        assertFalse(repo.canUndo.first())
    }

    @Test
    fun `undo of a change restores the previous value with its original time`() = runTest {
        repo.setRating("a", LIKE)
        now = 5_000L
        repo.setRating("a", UNSEEN)

        repo.undo()

        assertEquals(listOf(Rating("a", LIKE, 1_000L)), repo.ratings.first())
    }

    @Test
    fun `undo steps back one change at a time, newest first, across movies`() = runTest {
        repo.setRating("a", LIKE)
        now = 2_000L
        repo.setRating("b", DISLIKE)
        now = 3_000L
        repo.setRating("a", DISLIKE)

        assertEquals("a", repo.undo()?.movieId)
        assertEquals(setOf(Rating("a", LIKE, 1_000L), Rating("b", DISLIKE, 2_000L)), repo.ratings.first().toSet())
        assertEquals("b", repo.undo()?.movieId)
        assertEquals(listOf(Rating("a", LIKE, 1_000L)), repo.ratings.first())
        assertEquals("a", repo.undo()?.movieId)
        assertTrue(repo.ratings.first().isEmpty())
        assertNull(repo.undo())
    }

    @Test
    fun `undo with no history does nothing`() = runTest {
        assertFalse(repo.canUndo.first())
        assertNull(repo.undo())
    }

    @Test
    fun `ratings are listed newest first`() = runTest {
        repo.setRating("old", LIKE)
        now = 2_000L
        repo.setRating("new", DISLIKE)

        assertEquals(listOf("new", "old"), repo.ratings.first().map { it.movieId })
    }

    @Test
    fun `history survives a new repository on the same store`() = runTest {
        repo.setRating("a", LIKE)
        repo.setRating("a", DISLIKE)

        val reopened = RatingRepository(store, clock = { now })

        assertTrue(reopened.canUndo.first())
        reopened.undo()
        assertEquals(LIKE, reopened.rating("a").first())
    }

    @Test
    fun `a failed write leaves rating and history unchanged`() = runTest {
        repo.setRating("a", LIKE)
        store.failWrites = IOException("disk full")

        try {
            repo.setRating("a", DISLIKE)
            fail("expected the write to fail")
        } catch (_: IOException) {
        }

        store.failWrites = null
        assertEquals(LIKE, repo.rating("a").first())
        assertEquals(1, store.eventLog.size)
    }
}
