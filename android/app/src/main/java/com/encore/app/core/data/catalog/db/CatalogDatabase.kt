package com.encore.app.core.data.catalog.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.withTransaction
import com.encore.app.core.data.catalog.CatalogImport
import com.encore.app.core.data.catalog.CatalogOrigin
import com.encore.app.core.data.catalog.CatalogStore
import com.encore.app.core.data.catalog.MovieCatalog
import com.encore.app.core.data.catalog.MovieQuery
import com.encore.app.core.data.catalog.MovieSort
import com.encore.app.core.data.catalog.MovieSummary
import com.encore.app.core.data.catalog.SearchText
import com.encore.app.core.model.Movie
import com.encore.app.core.model.Trivia
import kotlinx.serialization.json.Json

/*
 * catalog.db holds only rebuildable data (O4): any schema change drops and re-imports it.
 * Ratings live in a separate user.db keyed on movieId, so a re-import never touches them.
 */

@Entity(
    tableName = "movies",
    indices = [Index("title"), Index("year"), Index("rating"), Index("popularity")],
)
data class MovieEntity(
    @PrimaryKey val id: String,
    val title: String,
    val year: Int?,
    val synopsis: String,
    val director: String?,
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
    /** [SearchText.normalize]d title and director: what Explore search matches against. */
    val searchTitle: String,
    val searchDirector: String?,
)

/** One row per (movie, genre): genre filters and recommendation scoring become indexed joins. */
@Entity(
    tableName = "movie_genres",
    primaryKeys = ["movieId", "genre"],
    foreignKeys = [ForeignKey(MovieEntity::class, ["id"], ["movieId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("genre")],
)
data class MovieGenreEntity(val movieId: String, val genre: String)

/** Billing order is kept in [position]. */
@Entity(
    tableName = "movie_cast",
    primaryKeys = ["movieId", "position"],
    foreignKeys = [ForeignKey(MovieEntity::class, ["id"], ["movieId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("actor")],
)
data class MovieCastEntity(val movieId: String, val position: Int, val actor: String)

@Entity(
    tableName = "trivia",
    foreignKeys = [ForeignKey(MovieEntity::class, ["id"], ["movieId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("movieId")],
)
data class TriviaEntity(
    @PrimaryKey val id: String,
    val movieId: String,
    val kind: String,
    val question: String,
    val options: List<String>,
    val answerIndex: Int,
    val clue: String?,
)

/** Single row (id 0), written last inside the import transaction: its presence means "import complete". */
@Entity(tableName = "catalog_meta")
data class CatalogMetaEntity(
    @PrimaryKey val id: Int = 0,
    val origin: String,
    val importedAt: Long,
    val movieCount: Int,
    val triviaCount: Int,
    val rejectedMovies: Int,
    val rejectedTrivia: Int,
)

class CatalogConverters {
    @TypeConverter
    fun fromList(value: List<String>): String = Json.encodeToString(value)

    @TypeConverter
    fun toList(value: String): List<String> = Json.decodeFromString(value)
}

@Dao
interface CatalogDao {
    @Query("SELECT movieCount FROM catalog_meta WHERE id = 0")
    suspend fun importedMovieCount(): Int?

    @Query("SELECT * FROM catalog_meta WHERE id = 0")
    suspend fun meta(): CatalogMetaEntity?

    @Query("SELECT COUNT(*) FROM movies")
    suspend fun movieCount(): Int

    @Query("SELECT COUNT(*) FROM trivia")
    suspend fun triviaCount(): Int

    @Query("SELECT genre FROM movie_genres WHERE movieId = :movieId ORDER BY genre")
    suspend fun genresOf(movieId: String): List<String>

    @Query("SELECT actor FROM movie_cast WHERE movieId = :movieId ORDER BY position")
    suspend fun castOf(movieId: String): List<String>

    @Query("SELECT * FROM movies WHERE id = :id")
    suspend fun movie(id: String): MovieEntity?

    @Query("SELECT * FROM trivia WHERE id = :id")
    suspend fun trivia(id: String): TriviaEntity?

    // Explore. One query per sort order (Room can't bind ORDER BY); the WHERE clause is identical in all four.
    // DESC puts NULL last in SQLite, so missing ratings, years and popularity sink to the end.

    @Query("SELECT id, title, year, director, rating, posterUrl FROM movies $EXPLORE_WHERE ORDER BY popularity DESC, id")
    suspend fun searchByPopularity(pattern: String?, genre: String?): List<MovieSummary>

    @Query("SELECT id, title, year, director, rating, posterUrl FROM movies $EXPLORE_WHERE ORDER BY rating DESC, voteCount DESC, id")
    suspend fun searchByRating(pattern: String?, genre: String?): List<MovieSummary>

    @Query("SELECT id, title, year, director, rating, posterUrl FROM movies $EXPLORE_WHERE ORDER BY year DESC, releaseDate DESC, id")
    suspend fun searchByNewest(pattern: String?, genre: String?): List<MovieSummary>

    @Query("SELECT id, title, year, director, rating, posterUrl FROM movies $EXPLORE_WHERE ORDER BY title COLLATE NOCASE, id")
    suspend fun searchByTitle(pattern: String?, genre: String?): List<MovieSummary>

    @Query("SELECT genre FROM movie_genres GROUP BY genre ORDER BY COUNT(*) DESC, genre")
    suspend fun genres(): List<String>

    @Query(
        "SELECT id, title, year, director, rating, posterUrl FROM movies " +
            "WHERE posterUrl IS NOT NULL AND synopsis != '' ORDER BY popularity DESC, id LIMIT :limit",
    )
    suspend fun featuredCandidates(limit: Int): List<MovieSummary>

    @Query("SELECT id FROM movies ORDER BY popularity DESC, id")
    suspend fun swipeOrder(): List<String>

    @Query("SELECT id, title, year, director, rating, posterUrl FROM movies WHERE id IN (:ids)")
    suspend fun summaries(ids: List<String>): List<MovieSummary>

    @Insert suspend fun insertMovies(rows: List<MovieEntity>)
    @Insert suspend fun insertGenres(rows: List<MovieGenreEntity>)
    @Insert suspend fun insertCast(rows: List<MovieCastEntity>)
    @Insert suspend fun insertTrivia(rows: List<TriviaEntity>)
    @Insert suspend fun insertMeta(row: CatalogMetaEntity)

    @Query("DELETE FROM catalog_meta") suspend fun clearMeta()
    @Query("DELETE FROM trivia") suspend fun clearTrivia()
    @Query("DELETE FROM movie_cast") suspend fun clearCast()
    @Query("DELETE FROM movie_genres") suspend fun clearGenres()
    @Query("DELETE FROM movies") suspend fun clearMovies()
}

private const val EXPLORE_WHERE =
    "WHERE (:genre IS NULL OR id IN (SELECT movieId FROM movie_genres WHERE genre = :genre)) " +
        "AND (:pattern IS NULL OR searchTitle LIKE :pattern ESCAPE '\\' OR searchDirector LIKE :pattern ESCAPE '\\')"

@Database(
    entities = [MovieEntity::class, MovieGenreEntity::class, MovieCastEntity::class, TriviaEntity::class, CatalogMetaEntity::class],
    version = 2, // 2: search columns (Stage 3). Destructive: the next launch re-imports.
    exportSchema = false, // rebuildable; user.db (Stage 4) is the one with exported schemas
)
@TypeConverters(CatalogConverters::class)
abstract class CatalogDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao

    companion object {
        fun build(context: Context): CatalogDatabase =
            Room.databaseBuilder(context, CatalogDatabase::class.java, "catalog.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}

class RoomCatalogStore(
    private val db: CatalogDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : CatalogStore {
    private val dao = db.catalogDao()

    override suspend fun importedMovieCount(): Int? = dao.importedMovieCount()

    override suspend fun replaceCatalog(catalog: CatalogImport, origin: CatalogOrigin, onProgress: (Float) -> Unit) {
        db.withTransaction {
            dao.clearMeta()
            dao.clearTrivia()
            dao.clearCast()
            dao.clearGenres()
            dao.clearMovies()

            val chunks = catalog.movies.chunked(CHUNK)
            val steps = chunks.size + 1
            chunks.forEachIndexed { index, chunk ->
                dao.insertMovies(chunk.map { it.toEntity() })
                dao.insertGenres(chunk.flatMap { m -> m.genres.map { MovieGenreEntity(m.id, it) } })
                dao.insertCast(chunk.flatMap { m -> m.cast.mapIndexed { i, actor -> MovieCastEntity(m.id, i, actor) } })
                onProgress((index + 1f) / steps)
            }
            dao.insertTrivia(catalog.trivia.map { it.toEntity() })
            dao.insertMeta(
                CatalogMetaEntity(
                    origin = origin.name,
                    importedAt = clock(),
                    movieCount = catalog.movies.size,
                    triviaCount = catalog.trivia.size,
                    rejectedMovies = catalog.rejectedMovies,
                    rejectedTrivia = catalog.rejectedTrivia,
                ),
            )
            onProgress(1f)
        }
    }

    private companion object {
        const val CHUNK = 500
    }
}

private const val SQL_VARIABLE_CHUNK = 500

/** Explore, Details, Swipe and History read the catalog through here; all queries run on Room's own executor. */
class RoomMovieCatalog(db: CatalogDatabase) : MovieCatalog {
    private val dao = db.catalogDao()

    override suspend fun genres(): List<String> = dao.genres()

    override suspend fun search(query: MovieQuery): List<MovieSummary> {
        val pattern = SearchText.likePattern(query.text)
        return when (query.sort) {
            MovieSort.POPULARITY -> dao.searchByPopularity(pattern, query.genre)
            MovieSort.RATING -> dao.searchByRating(pattern, query.genre)
            MovieSort.NEWEST -> dao.searchByNewest(pattern, query.genre)
            MovieSort.TITLE -> dao.searchByTitle(pattern, query.genre)
        }
    }

    override suspend fun featuredCandidates(limit: Int): List<MovieSummary> = dao.featuredCandidates(limit)

    override suspend fun swipeOrder(): List<String> = dao.swipeOrder()

    // Chunked: API 26's SQLite allows at most 999 bound variables per statement.
    override suspend fun summaries(ids: Collection<String>): List<MovieSummary> =
        ids.distinct().chunked(SQL_VARIABLE_CHUNK).flatMap { dao.summaries(it) }

    override suspend fun movie(id: String): Movie? {
        val row = dao.movie(id) ?: return null
        return Movie(
            id = row.id, title = row.title, year = row.year, synopsis = row.synopsis,
            genres = dao.genresOf(id), director = row.director, cast = dao.castOf(id),
            runtimeMinutes = row.runtimeMinutes, rating = row.rating, posterUrl = row.posterUrl,
            backdropUrl = row.backdropUrl, sourceUrl = row.sourceUrl, releaseDate = row.releaseDate,
            voteCount = row.voteCount, popularity = row.popularity, originalLanguage = row.originalLanguage,
            datasetSourceUrl = row.datasetSourceUrl,
        )
    }
}

private fun Movie.toEntity() = MovieEntity(
    id = id, title = title, year = year, synopsis = synopsis, director = director,
    runtimeMinutes = runtimeMinutes, rating = rating, posterUrl = posterUrl, backdropUrl = backdropUrl,
    sourceUrl = sourceUrl, releaseDate = releaseDate, voteCount = voteCount, popularity = popularity,
    originalLanguage = originalLanguage, datasetSourceUrl = datasetSourceUrl,
    searchTitle = SearchText.normalize(title), searchDirector = director?.let(SearchText::normalize),
)

private fun Trivia.toEntity() = TriviaEntity(id, movieId, kind, question, options, answerIndex, clue)
