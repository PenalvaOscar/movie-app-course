package com.encore.app.feature.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.encore.app.EncoreApplication
import com.encore.app.core.data.catalog.MovieCatalog
import com.encore.app.core.data.rating.RatingRepository
import com.encore.app.core.data.rating.RatingValue
import com.encore.app.core.model.Movie
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface DetailsUiState {
    data object Loading : DetailsUiState
    data class Content(val movie: Movie) : DetailsUiState
    /** The id is no longer in the catalog (for example after a catalog update, O6). */
    data object NotFound : DetailsUiState
    data object Error : DetailsUiState
}

/** Scoped to the details back-stack entry: popping the entry clears it. */
class MovieDetailsViewModel(
    private val movieId: String,
    private val catalog: MovieCatalog,
    private val ratings: RatingRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow<DetailsUiState>(DetailsUiState.Loading)
    val uiState: StateFlow<DetailsUiState> = _uiState.asStateFlow()

    /** The user's own rating, live: a change from Swipe, History or Undo shows here at once. */
    val userRating: StateFlow<RatingValue?> =
        ratings.rating(movieId).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        load()
    }

    fun retry() = load()

    fun setRating(value: RatingValue) {
        viewModelScope.launch {
            // A failed write leaves the previous selection showing, which is the truth on disk.
            try {
                ratings.setRating(movieId, value)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private fun load() {
        _uiState.value = DetailsUiState.Loading
        viewModelScope.launch {
            _uiState.value = try {
                catalog.movie(movieId)?.let { DetailsUiState.Content(it) } ?: DetailsUiState.NotFound
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DetailsUiState.Error
            }
        }
    }

    companion object {
        fun factory(movieId: String) = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as EncoreApplication).container
                MovieDetailsViewModel(movieId, container.movieCatalog, container.ratingRepository)
            }
        }
    }
}
