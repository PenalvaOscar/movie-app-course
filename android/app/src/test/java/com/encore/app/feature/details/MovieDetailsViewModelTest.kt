package com.encore.app.feature.details

import com.encore.app.core.data.rating.RatingRepository
import com.encore.app.core.data.rating.RatingValue
import com.encore.app.testing.FakeMovieCatalog
import com.encore.app.testing.FakeRatingStore
import com.encore.app.testing.MainDispatcherRule
import com.encore.app.testing.movie
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MovieDetailsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val ratings = RatingRepository(FakeRatingStore(), clock = { 1L })

    @Test
    fun `loads the movie by id`() = runTest(main.dispatcher) {
        val vm = MovieDetailsViewModel("7", FakeMovieCatalog(details = mapOf("7" to movie("7"))), ratings)
        assertEquals(DetailsUiState.Loading, vm.uiState.value)
        advanceUntilIdle()
        assertEquals(DetailsUiState.Content(movie("7")), vm.uiState.value)
    }

    @Test
    fun `a movie that is no longer in the catalog shows not found`() = runTest(main.dispatcher) {
        val vm = MovieDetailsViewModel("gone", FakeMovieCatalog(), ratings)
        advanceUntilIdle()
        assertEquals(DetailsUiState.NotFound, vm.uiState.value)
    }

    @Test
    fun `a read failure shows the error and retry recovers`() = runTest(main.dispatcher) {
        val catalog = FakeMovieCatalog(details = mapOf("7" to movie("7"))).apply { failWith = IOException("disk") }
        val vm = MovieDetailsViewModel("7", catalog, ratings)
        advanceUntilIdle()
        assertEquals(DetailsUiState.Error, vm.uiState.value)

        catalog.failWith = null
        vm.retry()
        advanceUntilIdle()
        assertEquals(DetailsUiState.Content(movie("7")), vm.uiState.value)
    }

    @Test
    fun `shows the user's current rating and changes it`() = runTest(main.dispatcher) {
        ratings.setRating("7", RatingValue.UNSEEN)
        val vm = MovieDetailsViewModel("7", FakeMovieCatalog(details = mapOf("7" to movie("7"))), ratings)
        advanceUntilIdle()
        assertEquals(RatingValue.UNSEEN, vm.userRating.value)

        vm.setRating(RatingValue.LIKE)
        advanceUntilIdle()

        assertEquals(RatingValue.LIKE, vm.userRating.value)
        assertEquals(RatingValue.LIKE, ratings.rating("7").first())
    }

    @Test
    fun `an unrated movie has no rating selected`() = runTest(main.dispatcher) {
        val vm = MovieDetailsViewModel("7", FakeMovieCatalog(details = mapOf("7" to movie("7"))), ratings)
        advanceUntilIdle()
        assertEquals(null, vm.userRating.value)
    }
}
