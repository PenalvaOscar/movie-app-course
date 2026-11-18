package com.encore.app.feature.history

import com.encore.app.core.data.rating.RatingRepository
import com.encore.app.core.data.rating.RatingValue.DISLIKE
import com.encore.app.core.data.rating.RatingValue.LIKE
import com.encore.app.core.data.rating.RatingValue.UNSEEN
import com.encore.app.testing.FakeMovieCatalog
import com.encore.app.testing.FakeRatingStore
import com.encore.app.testing.MainDispatcherRule
import com.encore.app.testing.summary
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private var now = 1L
    private val catalog = FakeMovieCatalog(movies = listOf(summary("a"), summary("b"), summary("c")))
    private val ratings = RatingRepository(FakeRatingStore(), clock = { now++ })

    private fun HistoryViewModel.items() = (uiState.value.items as HistoryItems.Content).items

    @Test
    fun `lists rated movies newest first with their values and counts`() = runTest(main.dispatcher) {
        ratings.setRating("a", LIKE)
        ratings.setRating("b", UNSEEN)
        ratings.setRating("c", LIKE)

        val vm = HistoryViewModel(catalog, ratings)
        advanceUntilIdle()

        assertEquals(listOf("c" to LIKE, "b" to UNSEEN, "a" to LIKE), vm.items().map { it.movie.id to it.value })
        assertEquals(mapOf(LIKE to 2, UNSEEN to 1), vm.uiState.value.counts)
    }

    @Test
    fun `a filter shows only that value`() = runTest(main.dispatcher) {
        ratings.setRating("a", LIKE)
        ratings.setRating("b", DISLIKE)
        val vm = HistoryViewModel(catalog, ratings)
        advanceUntilIdle()

        vm.onFilterSelected(DISLIKE)
        advanceUntilIdle()

        assertEquals(listOf("b"), vm.items().map { it.movie.id })
        assertEquals(DISLIKE, vm.uiState.value.filter)
    }

    @Test
    fun `a filter with no matches is empty, not loading`() = runTest(main.dispatcher) {
        ratings.setRating("a", LIKE)
        val vm = HistoryViewModel(catalog, ratings)
        advanceUntilIdle()

        vm.onFilterSelected(UNSEEN)
        advanceUntilIdle()

        assertEquals(HistoryItems.Empty, vm.uiState.value.items)
    }

    @Test
    fun `follows new ratings and undo live`() = runTest(main.dispatcher) {
        val vm = HistoryViewModel(catalog, ratings)
        advanceUntilIdle()
        assertEquals(HistoryItems.Empty, vm.uiState.value.items)

        ratings.setRating("a", LIKE)
        advanceUntilIdle()
        assertEquals(listOf("a"), vm.items().map { it.movie.id })

        ratings.undo()
        advanceUntilIdle()
        assertEquals(HistoryItems.Empty, vm.uiState.value.items)
    }

    @Test
    fun `ratings for movies no longer in the catalog are kept but hidden`() = runTest(main.dispatcher) {
        ratings.setRating("gone", LIKE)
        ratings.setRating("a", DISLIKE)

        val vm = HistoryViewModel(catalog, ratings)
        advanceUntilIdle()

        assertEquals(listOf("a"), vm.items().map { it.movie.id })
        assertEquals(mapOf(DISLIKE to 1), vm.uiState.value.counts)
    }

    @Test
    fun `a catalog read failure shows the error`() = runTest(main.dispatcher) {
        ratings.setRating("a", LIKE)
        catalog.failWith = IOException("disk")

        val vm = HistoryViewModel(catalog, ratings)
        advanceUntilIdle()

        assertEquals(HistoryItems.Error, vm.uiState.value.items)
    }
}
