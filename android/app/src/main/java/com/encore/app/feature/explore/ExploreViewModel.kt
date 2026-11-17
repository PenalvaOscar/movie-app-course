package com.encore.app.feature.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.encore.app.EncoreApplication
import com.encore.app.core.data.catalog.MovieCatalog
import com.encore.app.core.data.catalog.MovieQuery
import com.encore.app.core.data.catalog.MovieSort
import com.encore.app.core.data.catalog.MovieSummary
import com.encore.app.core.data.catalog.pickFeatured
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ExploreUiState(
    val query: MovieQuery = MovieQuery(),
    val genres: List<String> = emptyList(),
    val results: ExploreResults = ExploreResults.Loading,
)

sealed interface ExploreResults {
    data object Loading : ExploreResults
    /** [featured] is only set on the unfiltered catalog. */
    data class Content(val movies: List<MovieSummary>, val featured: MovieSummary?) : ExploreResults
    data object Empty : ExploreResults
    data object Error : ExploreResults
}

/**
 * Explore's state holder. Scoped to the activity, so the search, filters and results survive
 * switching tabs and rotation. Text changes are debounced; genre and sort changes search at once,
 * and a newer query cancels a search still running (mapLatest).
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class ExploreViewModel(
    private val catalog: MovieCatalog,
    private val epochDay: () -> Long = { LocalDate.now().toEpochDay() },
) : ViewModel() {
    private val query = MutableStateFlow(MovieQuery())
    private val retries = MutableStateFlow(0)
    private val _uiState = MutableStateFlow(ExploreUiState())
    val uiState: StateFlow<ExploreUiState> = _uiState.asStateFlow()

    private var genresLoaded = false
    private var featuredPool: List<MovieSummary>? = null
    private var lastSearchedText = ""

    init {
        viewModelScope.launch {
            combine(query, retries) { q, _ -> q }
                .debounce { q -> if (q.text != lastSearchedText) SEARCH_DEBOUNCE_MS else 0L }
                .mapLatest { q ->
                    lastSearchedText = q.text
                    load(q)
                }
                .collect { results -> _uiState.update { it.copy(results = results) } }
        }
    }

    fun onQueryChange(text: String) = setQuery { it.copy(text = text) }
    fun onGenreSelected(genre: String?) = setQuery { it.copy(genre = genre) }
    fun onSortSelected(sort: MovieSort) = setQuery { it.copy(sort = sort) }
    fun clearFilters() = setQuery { MovieQuery(sort = it.sort) }

    fun retry() {
        _uiState.update { it.copy(results = ExploreResults.Loading) }
        retries.update { it + 1 }
    }

    private fun setQuery(change: (MovieQuery) -> MovieQuery) {
        val next = change(query.value)
        query.value = next
        _uiState.update { it.copy(query = next) }
    }

    private suspend fun load(q: MovieQuery): ExploreResults = try {
        if (!genresLoaded) {
            val genres = catalog.genres()
            _uiState.update { it.copy(genres = genres) }
            genresLoaded = true
        }
        val movies = catalog.search(q)
        when {
            movies.isEmpty() -> ExploreResults.Empty
            q.isFiltered -> ExploreResults.Content(movies, featured = null)
            else -> {
                val pool = featuredPool ?: catalog.featuredCandidates(FEATURED_POOL).also { featuredPool = it }
                ExploreResults.Content(movies, pickFeatured(pool, epochDay()))
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ExploreResults.Error
    }

    companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
        private const val FEATURED_POOL = 30

        val Factory = viewModelFactory {
            initializer {
                ExploreViewModel((this[APPLICATION_KEY] as EncoreApplication).container.movieCatalog)
            }
        }
    }
}
