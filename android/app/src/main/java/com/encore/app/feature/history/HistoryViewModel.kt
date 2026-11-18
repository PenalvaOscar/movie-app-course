package com.encore.app.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.encore.app.EncoreApplication
import com.encore.app.core.data.catalog.MovieCatalog
import com.encore.app.core.data.catalog.MovieSummary
import com.encore.app.core.data.rating.RatingRepository
import com.encore.app.core.data.rating.RatingValue
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HistoryItem(val movie: MovieSummary, val value: RatingValue)

sealed interface HistoryItems {
    data object Loading : HistoryItems
    data class Content(val items: List<HistoryItem>) : HistoryItems
    /** Nothing rated yet, or nothing matching the filter ([HistoryUiState.filter] tells which). */
    data object Empty : HistoryItems
    data object Error : HistoryItems
}

data class HistoryUiState(
    val filter: RatingValue? = null,
    /** Visible ratings per value, ignoring the filter; values with none are absent. */
    val counts: Map<RatingValue, Int> = emptyMap(),
    val items: HistoryItems = HistoryItems.Loading,
)

/**
 * Preference history: every rated movie, most recently changed first, live (rating or undoing
 * anywhere updates it). Ratings for movies a catalog update removed stay in user.db but are
 * hidden here (O6). Movie cards are read from the catalog once and cached for the screen's life.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(
    private val catalog: MovieCatalog,
    ratings: RatingRepository,
) : ViewModel() {
    private val filter = MutableStateFlow<RatingValue?>(null)
    private val cache = HashMap<String, MovieSummary>()
    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(ratings.ratings, filter) { list, f -> list to f }
                .mapLatest { (list, f) ->
                    try {
                        val missing = list.map { it.movieId }.filter { it !in cache }
                        if (missing.isNotEmpty()) catalog.summaries(missing).forEach { cache[it.id] = it }
                        val visible = list.mapNotNull { r -> cache[r.movieId]?.let { HistoryItem(it, r.value) } }
                        val shown = if (f == null) visible else visible.filter { it.value == f }
                        HistoryUiState(
                            filter = f,
                            counts = visible.groupingBy { it.value }.eachCount(),
                            items = if (shown.isEmpty()) HistoryItems.Empty else HistoryItems.Content(shown),
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        HistoryUiState(filter = f, items = HistoryItems.Error)
                    }
                }
                .collect { _uiState.value = it }
        }
    }

    fun onFilterSelected(value: RatingValue?) {
        filter.value = value
        _uiState.update { it.copy(filter = value) }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as EncoreApplication).container
                HistoryViewModel(container.movieCatalog, container.ratingRepository)
            }
        }
    }
}
