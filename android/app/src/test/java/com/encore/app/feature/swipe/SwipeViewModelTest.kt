package com.encore.app.feature.swipe

import com.encore.app.core.data.rating.RatingRepository
import com.encore.app.core.data.rating.RatingValue.DISLIKE
import com.encore.app.core.data.rating.RatingValue.LIKE
import com.encore.app.core.data.rating.RatingValue.UNSEEN
import com.encore.app.testing.FakeMovieCatalog
import com.encore.app.testing.FakeRatingStore
import com.encore.app.testing.MainDispatcherRule
import com.encore.app.testing.movie
import com.encore.app.testing.summary
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SwipeViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val ids = listOf("p1", "p2", "p3")
    private val catalog = FakeMovieCatalog(
        movies = ids.map { summary(it) },
        details = ids.associateWith { movie(it) },
    )
    private val store = FakeRatingStore()
    private val ratings = RatingRepository(store, clock = { 1L })

    private fun TestScope.newViewModel(): SwipeViewModel = SwipeViewModel(catalog, ratings).also { advanceUntilIdle() }

    private fun SwipeViewModel.content() = uiState.value as SwipeUiState.Content

    @Test
    fun `shows the most popular movie first with the next one behind it`() = runTest(main.dispatcher) {
        val vm = newViewModel()

        assertEquals("p1", vm.content().current.id)
        assertEquals("p2", vm.content().next?.id)
        assertEquals(3, vm.content().remaining)
    }

    @Test
    fun `movies rated anywhere, unseen included, never enter the queue`() = runTest(main.dispatcher) {
        ratings.setRating("p1", UNSEEN)
        ratings.setRating("p2", DISLIKE)

        val vm = newViewModel()

        assertEquals("p3", vm.content().current.id)
        assertEquals(null, vm.content().next)
        assertEquals(1, vm.content().remaining)
    }

    @Test
    fun `rating saves the value and moves to the next movie`() = runTest(main.dispatcher) {
        val vm = newViewModel()

        vm.rate("p1", LIKE)
        advanceUntilIdle()

        assertEquals(LIKE, ratings.rating("p1").first())
        assertEquals("p2", vm.content().current.id)
        assertEquals(2, vm.content().remaining)
        assertTrue(vm.canUndo.value)
    }

    @Test
    fun `the card leaves at once, before the write finishes`() = runTest(main.dispatcher) {
        val vm = newViewModel()

        vm.rate("p1", LIKE)

        assertEquals("p2", vm.content().current.id)
    }

    @Test
    fun `undo brings the movie back to the front and reports what it did`() = runTest(main.dispatcher) {
        val vm = newViewModel()
        val messages = mutableListOf<SwipeMessage>()
        backgroundScope.launch { vm.messages.collect { messages += it } }
        vm.rate("p1", DISLIKE)
        advanceUntilIdle()

        vm.undo()
        advanceUntilIdle()

        assertEquals("p1", vm.content().current.id)
        assertEquals(3, vm.content().remaining)
        assertFalse(vm.canUndo.value)
        assertEquals(listOf(SwipeMessage.Undone(title = "Film p1", restored = null)), messages)
    }

    @Test
    fun `undo of a change made in details reports the restored value`() = runTest(main.dispatcher) {
        ratings.setRating("p3", LIKE)
        ratings.setRating("p3", DISLIKE)
        val vm = newViewModel()
        val messages = mutableListOf<SwipeMessage>()
        backgroundScope.launch { vm.messages.collect { messages += it } }

        vm.undo()
        advanceUntilIdle()

        assertEquals(LIKE, ratings.rating("p3").first())
        assertEquals(listOf(SwipeMessage.Undone(title = "Film p3", restored = LIKE)), messages)
        assertEquals(2, vm.content().remaining)
    }

    @Test
    fun `when everything is rated the queue is done`() = runTest(main.dispatcher) {
        val vm = newViewModel()

        ids.forEach { vm.rate(it, LIKE) }
        advanceUntilIdle()

        assertEquals(SwipeUiState.AllRated, vm.uiState.value)
    }

    @Test
    fun `a failed save puts the card back and says so`() = runTest(main.dispatcher) {
        val vm = newViewModel()
        val messages = mutableListOf<SwipeMessage>()
        backgroundScope.launch { vm.messages.collect { messages += it } }
        store.failWrites = IOException("disk full")

        vm.rate("p1", LIKE)
        advanceUntilIdle()

        assertEquals("p1", vm.content().current.id)
        assertEquals(listOf(SwipeMessage.SaveFailed), messages)
    }

    @Test
    fun `a catalog read failure shows the error and retry recovers`() = runTest(main.dispatcher) {
        catalog.failWith = IOException("disk")
        val vm = newViewModel()
        assertEquals(SwipeUiState.Error, vm.uiState.value)

        catalog.failWith = null
        vm.retry()
        advanceUntilIdle()

        assertEquals("p1", vm.content().current.id)
    }
}
