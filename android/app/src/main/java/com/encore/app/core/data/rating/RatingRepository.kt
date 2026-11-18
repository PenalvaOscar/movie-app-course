package com.encore.app.core.data.rating

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** LIKE and DISLIKE feed recommendations; UNSEEN is saved but only keeps the movie out of Swipe and Genie. */
enum class RatingValue { LIKE, DISLIKE, UNSEEN }

data class Rating(val movieId: String, val value: RatingValue, val updatedAt: Long)

/**
 * One rating change, kept so it can be undone. [previous] is null for a first rating; undoing it
 * removes the rating. [previousAt] lets undo restore the old rating exactly, time included.
 */
data class RatingEvent(
    val id: Long,
    val movieId: String,
    val previous: RatingValue?,
    val previousAt: Long?,
    val value: RatingValue,
    val createdAt: Long,
)

/** Storage primitives for [RatingRepository]; user.db in the app, an in-memory fake in tests. */
interface RatingStore {
    /** Every rating, most recently changed first. */
    fun observeRatings(): Flow<List<Rating>>
    fun observeRating(movieId: String): Flow<Rating?>
    /** True while at least one change can be undone. */
    fun observeHasHistory(): Flow<Boolean>

    suspend fun rating(movieId: String): Rating?
    suspend fun upsert(rating: Rating)
    suspend fun delete(movieId: String)
    /** Stores [event] under a new id (the event's own id is ignored). */
    suspend fun appendEvent(event: RatingEvent)
    suspend fun latestEvent(): RatingEvent?
    suspend fun deleteEvent(id: Long)

    /** Runs [block] atomically and serialized with other transactions; any exception rolls it all back. */
    suspend fun <R> transaction(block: suspend () -> R): R
}

/**
 * The only writer of ratings (Swipe, Details and History all go through here). Every change
 * is written together with its undo step in one transaction, so the undo history always
 * matches the ratings, including after the app restarts.
 */
class RatingRepository(
    private val store: RatingStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val ratings: Flow<List<Rating>> = store.observeRatings()

    val canUndo: Flow<Boolean> = store.observeHasHistory().distinctUntilChanged()

    fun rating(movieId: String): Flow<RatingValue?> = store.observeRating(movieId).map { it?.value }.distinctUntilChanged()

    /** Returns false, writing nothing, when the movie already has [value]. */
    suspend fun setRating(movieId: String, value: RatingValue): Boolean = store.transaction {
        val current = store.rating(movieId)
        if (current?.value == value) return@transaction false
        val now = clock()
        store.upsert(Rating(movieId, value, now))
        store.appendEvent(RatingEvent(0, movieId, current?.value, current?.updatedAt, value, now))
        true
    }

    /**
     * Reverts the most recent change, whichever screen made it: restores the previous value,
     * or removes the rating if it was the movie's first. Returns the reverted change, or null
     * when there is nothing to undo.
     */
    suspend fun undo(): RatingEvent? = store.transaction {
        val event = store.latestEvent() ?: return@transaction null
        val previous = event.previous
        if (previous == null) {
            store.delete(event.movieId)
        } else {
            store.upsert(Rating(event.movieId, previous, event.previousAt ?: clock()))
        }
        store.deleteEvent(event.id)
        event
    }
}
