package com.encore.app.core.model

/**
 * A catalog movie. Values the source marks as missing (0 for year/runtime/rating/votes/
 * popularity, blank strings) are null here, so the UI never shows a fabricated "0".
 */
data class Movie(
    val id: String,
    val title: String,
    val year: Int?,
    val synopsis: String,
    val genres: List<String>,
    val director: String?,
    val cast: List<String>,
    val runtimeMinutes: Int?,
    val rating: Double?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val sourceUrl: String?,
    val releaseDate: String?,
    val voteCount: Int?,
    val popularity: Double?,
    val originalLanguage: String?,
    val datasetSourceUrl: String?,
)

/** A four-option trivia question about one catalog movie. Text is English-only in the source data. */
data class Trivia(
    val id: String,
    val movieId: String,
    val kind: String,
    val question: String,
    val options: List<String>,
    val answerIndex: Int,
    val clue: String?,
)
