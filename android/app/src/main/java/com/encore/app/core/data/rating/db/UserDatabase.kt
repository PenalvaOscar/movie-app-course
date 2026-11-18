package com.encore.app.core.data.rating.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.withTransaction
import com.encore.app.core.data.rating.Rating
import com.encore.app.core.data.rating.RatingEvent
import com.encore.app.core.data.rating.RatingStore
import com.encore.app.core.data.rating.RatingValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/*
 * user.db holds what the user created (O4): ratings and their undo history. Unlike catalog.db it is
 * never dropped: schema changes need real migrations, and each version's schema is exported to
 * android/app/schemas/. Rows key on movieId only, with no foreign key into catalog.db, so a catalog
 * update never touches them (O6). Enum columns store the constant's name.
 */

@Entity(tableName = "ratings", indices = [Index("updatedAt")])
data class RatingEntity(
    @PrimaryKey val movieId: String,
    val value: RatingValue,
    val updatedAt: Long,
)

@Entity(tableName = "rating_events")
data class RatingEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val movieId: String,
    val previousValue: RatingValue?,
    val previousAt: Long?,
    val value: RatingValue,
    val createdAt: Long,
)

@Dao
interface RatingDao {
    @Query("SELECT * FROM ratings ORDER BY updatedAt DESC, movieId")
    fun observeAll(): Flow<List<RatingEntity>>

    @Query("SELECT * FROM ratings WHERE movieId = :movieId")
    fun observe(movieId: String): Flow<RatingEntity?>

    @Query("SELECT EXISTS(SELECT 1 FROM rating_events)")
    fun observeHasEvents(): Flow<Boolean>

    @Query("SELECT * FROM ratings WHERE movieId = :movieId")
    suspend fun rating(movieId: String): RatingEntity?

    @Upsert
    suspend fun upsert(row: RatingEntity)

    @Query("DELETE FROM ratings WHERE movieId = :movieId")
    suspend fun delete(movieId: String)

    @Insert
    suspend fun insertEvent(row: RatingEventEntity): Long

    @Query("SELECT * FROM rating_events ORDER BY id DESC LIMIT 1")
    suspend fun latestEvent(): RatingEventEntity?

    @Query("DELETE FROM rating_events WHERE id = :id")
    suspend fun deleteEvent(id: Long)
}

@Database(entities = [RatingEntity::class, RatingEventEntity::class], version = 1, exportSchema = true)
abstract class UserDatabase : RoomDatabase() {
    abstract fun ratingDao(): RatingDao

    companion object {
        const val NAME = "user.db"

        // No destructive fallback: a missing migration must fail loudly rather than wipe ratings.
        fun build(context: Context): UserDatabase =
            Room.databaseBuilder(context, UserDatabase::class.java, NAME).build()
    }
}

class RoomRatingStore(private val db: UserDatabase) : RatingStore {
    private val dao = db.ratingDao()

    override fun observeRatings(): Flow<List<Rating>> = dao.observeAll().map { rows -> rows.map { it.toModel() } }

    override fun observeRating(movieId: String): Flow<Rating?> = dao.observe(movieId).map { it?.toModel() }

    override fun observeHasHistory(): Flow<Boolean> = dao.observeHasEvents()

    override suspend fun rating(movieId: String): Rating? = dao.rating(movieId)?.toModel()

    override suspend fun upsert(rating: Rating) = dao.upsert(RatingEntity(rating.movieId, rating.value, rating.updatedAt))

    override suspend fun delete(movieId: String) = dao.delete(movieId)

    override suspend fun appendEvent(event: RatingEvent) {
        dao.insertEvent(
            RatingEventEntity(
                movieId = event.movieId,
                previousValue = event.previous,
                previousAt = event.previousAt,
                value = event.value,
                createdAt = event.createdAt,
            ),
        )
    }

    override suspend fun latestEvent(): RatingEvent? = dao.latestEvent()?.let {
        RatingEvent(it.id, it.movieId, it.previousValue, it.previousAt, it.value, it.createdAt)
    }

    override suspend fun deleteEvent(id: Long) = dao.deleteEvent(id)

    override suspend fun <R> transaction(block: suspend () -> R): R = db.withTransaction { block() }
}

private fun RatingEntity.toModel() = Rating(movieId, value, updatedAt)
