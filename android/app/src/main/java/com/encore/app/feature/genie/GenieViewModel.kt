package com.encore.app.feature.genie

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import android.util.Log
import com.encore.app.EncoreApplication
import com.encore.app.core.data.catalog.MovieCatalog
import com.encore.app.core.data.catalog.MovieSummary
import com.encore.app.core.data.rating.RatingRepository
import com.encore.app.core.recommendation.MovieFeatures
import com.encore.app.core.recommendation.Reason
import com.encore.app.core.recommendation.ReasonKind
import com.encore.app.core.recommendation.RecommendationEngine
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** One line of "why": every name of one kind, e.g. GENRE [Drama, Romance] → "Because you liked Drama and Romance films". */
data class ReasonLine(val kind: ReasonKind, val names: List<String>)

data class GenieItem(val movie: MovieSummary, val reasons: List<ReasonLine>)

sealed interface GenieUiState {
    data object Loading : GenieUiState
    /** No LIKE or DISLIKE yet (UNSEEN alone doesn't count): explain how to get started. */
    data object NeedsRatings : GenieUiState
    /** Preferences exist, but no unrated movie scores above zero (for example, only dislikes). */
    data class NoMatches(val likedCount: Int, val dislikedCount: Int) : GenieUiState
    /** [items] are best first; the counts say what the picks are based on. */
    data class Content(val items: List<GenieItem>, val likedCount: Int, val dislikedCount: Int) : GenieUiState
    data object Error : GenieUiState
}

/** Groups reasons into lines by kind, keeping the order of each kind's first appearance. */
internal fun reasonLines(reasons: List<Reason>): List<ReasonLine> =
    reasons.groupBy { it.kind }.map { (kind, list) -> ReasonLine(kind, list.map { it.name }) }

/**
 * Genie: personal recommendations from likes and dislikes, live. Rating or undoing anywhere
 * recomputes them, so a movie rated from Details (UNSEEN included) leaves the list at once.
 *
 * Activity-scoped like Explore, so the list survives tab switches; it only computes while the
 * screen collects [uiState]. Catalog features are read once and kept: scoring 10k movies in
 * memory takes milliseconds, the database read is the slow part.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GenieViewModel(
    private val catalog: MovieCatalog,
    ratings: RatingRepository,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val logError: (String, Throwable) -> Unit = { _, _ -> },
) : ViewModel() {
    private val retryRequests = MutableStateFlow(0)
    // Both caches are read and written only on Main, inside mapLatest, which runs one block at a time.
    // [features] never goes stale today because the catalog is not replaced after first import;
    // a future "Update catalog" (Stage 6) must clear it.
    private var features: List<MovieFeatures>? = null
    private val cards = HashMap<String, MovieSummary>()

    val uiState: StateFlow<GenieUiState> = combine(ratings.ratings, retryRequests) { list, _ -> list }
        .mapLatest { list ->
            try {
                val catalogFeatures = loadFeatures()
                val byMovie = list.associate { it.movieId to it.value }
                val result = withContext(computeDispatcher) { RecommendationEngine.recommend(catalogFeatures, byMovie, LIMIT) }
                when {
                    !result.hasPreferences -> GenieUiState.NeedsRatings
                    result.items.isEmpty() -> GenieUiState.NoMatches(result.likedCount, result.dislikedCount)
                    else -> {
                        val missing = result.items.map { it.movieId }.filter { it !in cards }
                        if (missing.isNotEmpty()) catalog.summaries(missing).forEach { cards[it.id] = it }
                        val items = result.items.mapNotNull { r -> cards[r.movieId]?.let { GenieItem(it, reasonLines(r.reasons)) } }
                        if (items.isEmpty()) {
                            GenieUiState.NoMatches(result.likedCount, result.dislikedCount)
                        } else {
                            GenieUiState.Content(items, result.likedCount, result.dislikedCount)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError("Couldn't compute recommendations", e)
                GenieUiState.Error
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), GenieUiState.Loading)

    /**
     * The slow first read finishes even if a rating change cancels the computation that started it,
     * so the next one finds it cached instead of starting over.
     */
    private suspend fun loadFeatures(): List<MovieFeatures> =
        features ?: withContext(NonCancellable) { features ?: catalog.recommendationFeatures().also { features = it } }

    fun retry() {
        retryRequests.value++
    }

    companion object {
        /** Enough to browse; the list refills as the user rates. */
        const val LIMIT = 50
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val TAG = "EncoreGenie"

        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as EncoreApplication).container
                GenieViewModel(container.movieCatalog, container.ratingRepository, logError = { msg, e -> Log.w(TAG, msg, e) })
            }
        }
    }
}
