package com.encore.app.feature.explore

import com.encore.app.core.data.catalog.MovieQuery
import com.encore.app.core.data.catalog.MovieSort
import com.encore.app.testing.FakeMovieCatalog
import com.encore.app.testing.MainDispatcherRule
import com.encore.app.testing.summary
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val catalog = FakeMovieCatalog(
        movies = listOf(summary("1", "Northern Lights"), summary("2", "Southern Tide"), summary("3", "Northern Rail")),
        genreList = listOf("Drama", "Comedy"),
        featuredCandidates = listOf(summary("2", "Southern Tide")),
    )

    private fun viewModel() = ExploreViewModel(catalog, epochDay = { 20_000L })

    private fun ExploreViewModel.content() = uiState.value.results as ExploreResults.Content

    @Test
    fun `first load shows every movie, the genres and a featured movie`() = runTest(main.dispatcher) {
        val vm = viewModel()
        assertEquals(ExploreResults.Loading, vm.uiState.value.results)
        advanceUntilIdle()

        assertEquals(listOf("1", "2", "3"), vm.content().movies.map { it.id })
        assertEquals("2", vm.content().featured?.id)
        assertEquals(listOf("Drama", "Comedy"), vm.uiState.value.genres)
        assertEquals(MovieQuery(), catalog.queries.single())
    }

    @Test
    fun `typing is debounced into a single search`() = runTest(main.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        catalog.queries.clear()

        vm.onQueryChange("N")
        vm.onQueryChange("No")
        vm.onQueryChange("North")
        assertEquals("North", vm.uiState.value.query.text) // the field updates at once
        advanceTimeBy(ExploreViewModel.SEARCH_DEBOUNCE_MS - 1)
        runCurrent()
        assertTrue(catalog.queries.isEmpty())

        advanceUntilIdle()
        assertEquals(listOf("North"), catalog.queries.map { it.text })
        assertEquals(listOf("1", "3"), vm.content().movies.map { it.id })
    }

    @Test
    fun `featured movie is hidden while searching or filtering`() = runTest(main.dispatcher) {
        val vm = viewModel()
        vm.onQueryChange("North")
        advanceUntilIdle()
        assertNull(vm.content().featured)

        vm.onQueryChange("")
        vm.onGenreSelected("Drama")
        advanceUntilIdle()
        assertNull(vm.content().featured)

        vm.onGenreSelected(null)
        advanceUntilIdle()
        assertEquals("2", vm.content().featured?.id)
    }

    @Test
    fun `genre and sort changes search without waiting for the debounce`() = runTest(main.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        catalog.queries.clear()

        vm.onGenreSelected("Comedy")
        runCurrent()
        vm.onSortSelected(MovieSort.RATING)
        runCurrent()

        assertEquals(
            listOf(MovieQuery(genre = "Comedy"), MovieQuery(genre = "Comedy", sort = MovieSort.RATING)),
            catalog.queries,
        )
    }

    @Test
    fun `no match shows the empty state and clearing filters restores the catalog`() = runTest(main.dispatcher) {
        val vm = viewModel()
        vm.onQueryChange("zzz")
        vm.onGenreSelected("Comedy")
        advanceUntilIdle()
        assertEquals(ExploreResults.Empty, vm.uiState.value.results)

        vm.clearFilters()
        advanceUntilIdle()
        assertEquals(MovieQuery(), vm.uiState.value.query)
        assertEquals(3, vm.content().movies.size)
    }

    @Test
    fun `clearing filters keeps the chosen sort order`() = runTest(main.dispatcher) {
        val vm = viewModel()
        vm.onSortSelected(MovieSort.TITLE)
        vm.onGenreSelected("Drama")
        vm.clearFilters()
        advanceUntilIdle()
        assertEquals(MovieQuery(sort = MovieSort.TITLE), vm.uiState.value.query)
    }

    @Test
    fun `a failing catalog shows the error state and retry recovers`() = runTest(main.dispatcher) {
        catalog.failWith = IOException("disk")
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(ExploreResults.Error, vm.uiState.value.results)

        catalog.failWith = null
        vm.retry()
        advanceUntilIdle()
        assertEquals(3, vm.content().movies.size)
        assertEquals(listOf("Drama", "Comedy"), vm.uiState.value.genres)
    }
}
