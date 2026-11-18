package com.encore.app.testing

import com.encore.app.core.data.rating.Rating
import com.encore.app.core.data.rating.RatingEvent
import com.encore.app.core.data.rating.RatingStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory [RatingStore] with the same contract as Room's: [transaction] is serialized and
 * rolls back every change when the block throws. Set [failWrites] to make the next write throw.
 */
class FakeRatingStore : RatingStore {
    private val ratings = MutableStateFlow<Map<String, Rating>>(emptyMap())
    private val events = MutableStateFlow<List<RatingEvent>>(emptyList())
    private var nextEventId = 1L
    private val mutex = Mutex()
    var failWrites: Exception? = null

    val eventLog: List<RatingEvent> get() = events.value

    override fun observeRatings(): Flow<List<Rating>> =
        ratings.map { all -> all.values.sortedWith(compareByDescending<Rating> { it.updatedAt }.thenBy { it.movieId }) }

    override fun observeRating(movieId: String): Flow<Rating?> = ratings.map { it[movieId] }

    override fun observeHasHistory(): Flow<Boolean> = events.map { it.isNotEmpty() }

    override suspend fun rating(movieId: String): Rating? = ratings.value[movieId]

    override suspend fun upsert(rating: Rating) {
        failWrites?.let { throw it }
        ratings.value = ratings.value + (rating.movieId to rating)
    }

    override suspend fun delete(movieId: String) {
        failWrites?.let { throw it }
        ratings.value = ratings.value - movieId
    }

    override suspend fun appendEvent(event: RatingEvent) {
        failWrites?.let { throw it }
        events.value = events.value + event.copy(id = nextEventId++)
    }

    override suspend fun latestEvent(): RatingEvent? = events.value.maxByOrNull { it.id }

    override suspend fun deleteEvent(id: Long) {
        events.value = events.value.filterNot { it.id == id }
    }

    override suspend fun <R> transaction(block: suspend () -> R): R = mutex.withLock {
        val savedRatings = ratings.value
        val savedEvents = events.value
        try {
            block()
        } catch (e: Throwable) {
            ratings.value = savedRatings
            events.value = savedEvents
            throw e
        }
    }
}
