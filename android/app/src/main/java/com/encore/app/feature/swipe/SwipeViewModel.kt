package com.encore.app.feature.swipe

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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface SwipeUiState {
    data object Loading : SwipeUiState
    /** [current] is the card on top, [next] the one peeking behind it; [remaining] counts both. */
    data class Content(val current: Movie, val next: Movie?, val remaining: Int) : SwipeUiState
    /** Every movie in the catalog has a rating. */
    data object AllRated : SwipeUiState
    data object Error : SwipeUiState
}

/** One-off feedback, shown as a snackbar (and so announced by TalkBack). */
sealed interface SwipeMessage {
    /** [restored] is the value the movie went back to, or null when its rating was removed. */
    data class Undone(val title: String?, val restored: RatingValue?) : SwipeMessage
    data object SaveFailed : SwipeMessage
}

/** Unrated movies in [order], keeping the catalog's popularity order. */
internal fun swipeQueue(order: List<String>, rated: Set<String>): List<String> = order.filter { it !in rated }

/**
 * The Swipe queue: every unrated catalog movie, most popular first (4b). A movie rated anywhere,
 * UNSEEN included, leaves the queue; undo brings it back.
 *
 * Activity-scoped, like Explore, because the shell's top bar (Undo) and the History screen share it.
 * A rated card leaves at once (an optimistic "pending" set), before the database write
 * confirms; a failed write puts it back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwipeViewModel(
    private val catalog: MovieCatalog,
    private val ratings: RatingRepository,
) : ViewModel() {
    private val order = MutableStateFlow<List<String>?>(null)
    private val pending = MutableStateFlow<Set<String>>(emptySet())
    private val loadFailed = MutableStateFlow(false)
    private val cards = HashMap<String, Movie>()

    private val _uiState = MutableStateFlow<SwipeUiState>(SwipeUiState.Loading)
    val uiState: StateFlow<SwipeUiState> = _uiState.asStateFlow()

    val canUndo: StateFlow<Boolean> = ratings.canUndo.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _messages = Channel<SwipeMessage>(Channel.BUFFERED)
    val messages: Flow<SwipeMessage> = _messages.receiveAsFlow()

    init {
        loadOrder()
        val ratedIds = ratings.ratings.map { list -> list.mapTo(HashSet()) { it.movieId } }
        viewModelScope.launch {
            // Once the database has a rating, the optimistic entry is no longer needed.
            ratedIds.collect { rated -> pending.update { it - rated } }
        }
        viewModelScope.launch {
            loadFailed
                .flatMapLatest { failed -> if (failed) flowOf(SwipeUiState.Error) else queueState(ratedIds) }
                .collect { _uiState.value = it }
        }
    }

    private fun queueState(ratedIds: Flow<Set<String>>): Flow<SwipeUiState> =
        combine(order.filterNotNull(), ratedIds, pending) { ids, rated, optimistic ->
            swipeQueue(ids, rated + optimistic)
        }.mapLatest { queue -> load(queue) }

    private suspend fun load(queue: List<String>): SwipeUiState {
        if (queue.isEmpty()) return SwipeUiState.AllRated
        return try {
            // Movies are read one by one as they reach the top two; the rest stay ids.
            val top = queue.take(2).mapNotNull { id -> cards[id] ?: catalog.movie(id)?.also { cards[id] = it } }
            cards.keys.retainAll(top.map { it.id }.toSet())
            if (top.isEmpty()) SwipeUiState.Error else SwipeUiState.Content(top[0], top.getOrNull(1), queue.size)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            SwipeUiState.Error
        }
    }

    private fun loadOrder() {
        viewModelScope.launch {
            try {
                order.value = catalog.swipeOrder()
                loadFailed.value = false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                loadFailed.value = true
            }
        }
    }

    fun retry() {
        _uiState.value = SwipeUiState.Loading
        loadFailed.value = false
        if (order.value == null) loadOrder()
    }

    fun rate(movieId: String, value: RatingValue) {
        pending.update { it + movieId }
        // Promote the card behind right away (same frame); the pipeline then fills in the next one.
        val shown = _uiState.value
        if (shown is SwipeUiState.Content && shown.current.id == movieId) {
            _uiState.value = shown.next?.let { SwipeUiState.Content(it, null, shown.remaining - 1) } ?: SwipeUiState.Loading
        }
        viewModelScope.launch {
            try {
                ratings.setRating(movieId, value)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                pending.update { it - movieId }
                _messages.send(SwipeMessage.SaveFailed)
            }
        }
    }

    fun undo() {
        viewModelScope.launch {
            try {
                val event = ratings.undo() ?: return@launch
                pending.update { it - event.movieId }
                val title = try {
                    catalog.summaries(listOf(event.movieId)).firstOrNull()?.title
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                _messages.send(SwipeMessage.Undone(title, event.previous))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _messages.send(SwipeMessage.SaveFailed)
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as EncoreApplication).container
                SwipeViewModel(container.movieCatalog, container.ratingRepository)
            }
        }
    }
}
