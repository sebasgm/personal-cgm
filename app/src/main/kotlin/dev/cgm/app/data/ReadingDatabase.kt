package dev.cgm.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.cgm.core.GlucoseReading
import dev.cgm.core.TrendArrow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Local history.
 *
 * Exists for the watch, not the phone: stage 5 draws a trend graph on the wrist
 * and the LibreLinkUp graph endpoint only reaches back a few hours. Keeping our
 * own copy also means the watch still gets a graph when the API is down.
 *
 * `timestampMillis` is the primary key, so re-fetching overlapping windows —
 * which polling does constantly — is naturally idempotent.
 */
@Entity(tableName = "readings")
data class ReadingEntity(
    @PrimaryKey val timestampMillis: Long,
    val valueMgdl: Double,
    val trend: String,
    val isHigh: Boolean,
    val isLow: Boolean,
) {
    fun toReading() = GlucoseReading(
        valueMgdl = valueMgdl,
        timestampMillis = timestampMillis,
        trend = runCatching { TrendArrow.valueOf(trend) }.getOrDefault(TrendArrow.UNKNOWN),
        isHigh = isHigh,
        isLow = isLow,
    )

    companion object {
        fun from(reading: GlucoseReading) = ReadingEntity(
            timestampMillis = reading.timestampMillis,
            valueMgdl = reading.valueMgdl,
            trend = reading.trend.name,
            isHigh = reading.isHigh,
            isLow = reading.isLow,
        )
    }
}

@Dao
interface ReadingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(readings: List<ReadingEntity>)

    @Query("SELECT * FROM readings WHERE timestampMillis >= :sinceMillis ORDER BY timestampMillis ASC")
    fun observeSince(sinceMillis: Long): Flow<List<ReadingEntity>>

    @Query("SELECT * FROM readings ORDER BY timestampMillis DESC LIMIT 1")
    suspend fun latest(): ReadingEntity?

    @Query("SELECT COUNT(*) FROM readings")
    suspend fun count(): Int

    /** Housekeeping: a year of 5-minute readings is ~100k rows, so prune. */
    @Query("DELETE FROM readings WHERE timestampMillis < :beforeMillis")
    suspend fun deleteBefore(beforeMillis: Long)
}

@Database(entities = [ReadingEntity::class], version = 1, exportSchema = false)
abstract class ReadingDatabase : RoomDatabase() {
    abstract fun readings(): ReadingDao

    companion object {
        fun create(context: Context): ReadingDatabase =
            Room.databaseBuilder(context, ReadingDatabase::class.java, "readings.db").build()
    }
}

fun Flow<List<ReadingEntity>>.asReadings(): Flow<List<GlucoseReading>> =
    map { rows -> rows.map { it.toReading() } }
