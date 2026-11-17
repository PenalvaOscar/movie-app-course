package com.encore.app.feature.details

import com.encore.app.testing.FakeMovieCatalog
import com.encore.app.testing.MainDispatcherRule
import com.encore.app.testing.movie
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MovieDetailsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test
    fun `loads the movie by id`() = runTest(main.dispatcher) {
        val vm = MovieDetailsViewModel("7", FakeMovieCatalog(details = mapOf("7" to movie("7"))))
        assertEquals(DetailsUiState.Loading, vm.uiState.value)
        advanceUntilIdle()
        assertEquals(DetailsUiState.Content(movie("7")), vm.uiState.value)
    }

    @Test
    fun `a movie that is no longer in the catalog shows not found`() = runTest(main.dispatcher) {
        val vm = MovieDetailsViewModel("gone", FakeMovieCatalog())
        advanceUntilIdle()
        assertEquals(DetailsUiState.NotFound, vm.uiState.value)
    }

    @Test
    fun `a read failure shows the error and retry recovers`() = runTest(main.dispatcher) {
        val catalog = FakeMovieCatalog(details = mapOf("7" to movie("7"))).apply { failWith = IOException("disk") }
        val vm = MovieDetailsViewModel("7", catalog)
        advanceUntilIdle()
        assertEquals(DetailsUiState.Error, vm.uiState.value)

        catalog.failWith = null
        vm.retry()
        advanceUntilIdle()
        assertEquals(DetailsUiState.Content(movie("7")), vm.uiState.value)
    }
}
