package com.encore.app.feature.genie

import com.encore.app.core.data.rating.RatingRepository
import com.encore.app.core.data.rating.RatingValue.DISLIKE
import com.encore.app.core.data.rating.RatingValue.LIKE
import com.encore.app.core.data.rating.RatingValue.UNSEEN
import com.encore.app.core.recommendation.MovieFeatures
import com.encore.app.core.recommendation.Reason
import com.encore.app.core.recommendation.ReasonKind.DIRECTOR
import com.encore.app.core.recommendation.ReasonKind.GENRE
import com.encore.app.testing.FakeMovieCatalog
import com.encore.app.testing.FakeRatingStore
import com.encore.app.testing.MainDispatcherRule
import com.encore.app.testing.summary
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GenieViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private var now = 1L
    private val catalog = FakeMovieCatalog(
        movies = listOf("liked", "a", "b", "c", "horror").map { summary(it) },
        features = listOf(
            MovieFeatures("liked", listOf("Drama"), "Ada Vale", emptyList(), 5.0),
            MovieFeatures("a", listOf("Drama"), null, emptyList(), 9.0), // 1
            MovieFeatures("b", listOf("Drama"), "Ada Vale", emptyList(), 1.0), // 4
            MovieFeatures("c", listOf("Comedy"), null, emptyList(), 8.0), // 0
            MovieFeatures("horror", listOf("Horror"), null, emptyList(), 7.0),
        ),
    )
    private val ratings = RatingRepository(FakeRatingStore(), clock = { now++ })

    /** uiState only works while someone collects it (WhileSubscribed), as the screen does. */
    private fun TestScope.newViewModel(): GenieViewModel =
        GenieViewModel(catalog, ratings, computeDispatcher = main.dispatcher).also { vm ->
            backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()
        }

    private fun GenieViewModel.content() = uiState.value as GenieUiState.Content

    @Test
    fun `without likes or dislikes Genie explains how to start`() = runTest(main.dispatcher) {
        assertEquals(GenieUiState.NeedsRatings, newViewModel().uiState.value)
    }

    @Test
    fun `unseen ratings alone are not enough`() = runTest(main.dispatcher) {
        ratings.setRating("a", UNSEEN)

        assertEquals(GenieUiState.NeedsRatings, newViewModel().uiState.value)
    }

    @Test
    fun `a like shows scored recommendations with reasons and counts`() = runTest(main.dispatcher) {
        ratings.setRating("liked", LIKE)

        val content = newViewModel().content()

        assertEquals(listOf("b", "a"), content.items.map { it.movie.id })
        assertEquals("Film b", content.items.first().movie.title)
        assertEquals(
            listOf(ReasonLine(DIRECTOR, listOf("Ada Vale")), ReasonLine(GENRE, listOf("Drama"))),
            content.items.first().reasons,
        )
        assertEquals(1, content.likedCount)
        assertEquals(0, content.dislikedCount)
    }

    @Test
    fun `rating a recommendation anywhere removes it live`() = runTest(main.dispatcher) {
        ratings.setRating("liked", LIKE)
        val vm = newViewModel()

        ratings.setRating("b", UNSEEN)
        advanceUntilIdle()

        assertEquals(listOf("a"), vm.content().items.map { it.movie.id })
    }

    @Test
    fun `undo brings a recommendation back`() = runTest(main.dispatcher) {
        ratings.setRating("liked", LIKE)
        val vm = newViewModel()
        ratings.setRating("b", DISLIKE)
        advanceUntilIdle()

        ratings.undo()
        advanceUntilIdle()

        assertEquals(listOf("b", "a"), vm.content().items.map { it.movie.id })
    }

    @Test
    fun `preferences with no positive match show the no-matches state`() = runTest(main.dispatcher) {
        ratings.setRating("horror", DISLIKE)

        assertEquals(GenieUiState.NoMatches(likedCount = 0, dislikedCount = 1), newViewModel().uiState.value)
    }

    @Test
    fun `catalog features are loaded once, not on every rating change`() = runTest(main.dispatcher) {
        ratings.setRating("liked", LIKE)
        newViewModel()

        ratings.setRating("a", DISLIKE)
        advanceUntilIdle()
        ratings.setRating("c", LIKE)
        advanceUntilIdle()

        assertEquals(1, catalog.featureLoads)
    }

    @Test
    fun `a rating change during the first catalog read doesn't restart it`() = runTest(main.dispatcher) {
        ratings.setRating("liked", LIKE)
        val gate = CompletableDeferred<Unit>()
        catalog.featureGate = gate
        val vm = newViewModel()
        assertEquals(GenieUiState.Loading, vm.uiState.value)

        ratings.setRating("b", UNSEEN)
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("a"), vm.content().items.map { it.movie.id })
        assertEquals(1, catalog.featureLoads)
    }

    @Test
    fun `likes of movies no longer in the catalog are not counted`() = runTest(main.dispatcher) {
        ratings.setRating("gone", LIKE)
        ratings.setRating("liked", LIKE)

        assertEquals(1, newViewModel().content().likedCount)
    }

    @Test
    fun `a recommendation whose card is missing is skipped`() = runTest(main.dispatcher) {
        catalog.movies = catalog.movies.filterNot { it.id == "b" }
        ratings.setRating("liked", LIKE)

        assertEquals(listOf("a"), newViewModel().content().items.map { it.movie.id })
    }

    @Test
    fun `a read failure shows the error state and retry recovers`() = runTest(main.dispatcher) {
        ratings.setRating("liked", LIKE)
        catalog.failWith = IOException("disk")
        val vm = newViewModel()
        assertEquals(GenieUiState.Error, vm.uiState.value)

        catalog.failWith = null
        vm.retry()
        advanceUntilIdle()

        assertEquals(listOf("b", "a"), vm.content().items.map { it.movie.id })
    }

    @Test
    fun `reason lines merge names of the same kind in order`() {
        val lines = reasonLines(
            listOf(
                Reason(GENRE, "Drama", 2),
                Reason(GENRE, "Romance", 1),
            ),
        )
        assertEquals(listOf(ReasonLine(GENRE, listOf("Drama", "Romance"))), lines)
        assertTrue(reasonLines(emptyList()).isEmpty())
    }
}
