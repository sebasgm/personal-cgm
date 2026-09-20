package dev.cgm.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import dev.cgm.core.GlucoseHistogram
import dev.cgm.core.Moments

/**
 * One hour of readings, summarised.
 *
 * Every long-range question reads from here rather than from `readings`: at a
 * reading a minute, two years is about a million rows, and #6 asks for a window
 * that wide.
 *
 * Two fields carry the design (see docs/05-trend-inference.md §3):
 *
 *  - [sum] and [sumSq] rather than a mean and an SD, because sums combine across
 *    any set of hours. A 90-day figure is 2160 of these rows added together, and
 *    no window size needs a table of its own.
 *  - [histogram] rather than per-zone counts, because zones depend on thresholds
 *    the user can edit. A distribution answers "how long below 70" and "how long
 *    below 80" equally well, so changing the target band re-reads history instead
 *    of invalidating it.
 *
 * [localDate] and [localHour] are resolved when the row is written, in the zone
 * the reading was taken. Deriving them later from epoch arithmetic gives UTC and
 * breaks across DST and travel, and "my 3am" is a wall-clock idea.
 */
@Entity(
    tableName = "hourly_rollup",
    indices = [
        Index("localDate"),
        Index("localHour"),
        Index("sensorSerial", "sensorDay"),
    ],
)
class HourlyRollupEntity(
    @PrimaryKey val hourStartMillis: Long,
    val localDate: String,
    val localHour: Int,
    val count: Int,
    val sum: Double,
    val sumSq: Double,
    val minMgdl: Double,
    val maxMgdl: Double,
    /** Distinct 5-minute buckets containing data, for coverage. Max 12. */
    val buckets: Int,
    /** 72 little-endian uint16 bins; see [GlucoseHistogram]. */
    val histogram: ByteArray,
    val sensorSerial: String?,
    val sensorDay: Int?,
) {
    fun moments() = Moments(count = count, sum = sum, sumSq = sumSq)

    fun bins(): IntArray = GlucoseHistogram.fromBytes(histogram)

    // ByteArray needs these spelled out; the default identity comparison would
    // make two equal rows compare unequal.
    override fun equals(other: Any?): Boolean =
        other is HourlyRollupEntity && other.hourStartMillis == hourStartMillis

    override fun hashCode(): Int = hourStartMillis.hashCode()
}

@Dao
interface HourlyRollupDao {

    @Upsert
    suspend fun upsert(rows: List<HourlyRollupEntity>)

    @Query("SELECT * FROM hourly_rollup WHERE hourStartMillis BETWEEN :startMillis AND :endMillis ORDER BY hourStartMillis")
    suspend fun between(startMillis: Long, endMillis: Long): List<HourlyRollupEntity>

    /**
     * Rows for one wall-clock hour band across many days — the shape every
     * time-of-day analysis wants, and the reason [HourlyRollupEntity.localHour]
     * is stored rather than computed.
     */
    @Query(
        """
        SELECT * FROM hourly_rollup
        WHERE hourStartMillis BETWEEN :startMillis AND :endMillis
          AND localHour BETWEEN :fromHour AND :toHour
        ORDER BY hourStartMillis
        """
    )
    suspend fun betweenForHours(
        startMillis: Long,
        endMillis: Long,
        fromHour: Int,
        toHour: Int,
    ): List<HourlyRollupEntity>

    @Query("SELECT * FROM hourly_rollup WHERE sensorSerial IS NOT NULL ORDER BY hourStartMillis")
    suspend fun withSensor(): List<HourlyRollupEntity>

    @Query("SELECT MAX(hourStartMillis) FROM hourly_rollup")
    suspend fun newestHour(): Long?

    @Query("SELECT COUNT(*) FROM hourly_rollup")
    suspend fun rowCount(): Int

    @Query("DELETE FROM hourly_rollup")
    suspend fun deleteAll()

    @Query("DELETE FROM hourly_rollup WHERE hourStartMillis < :beforeMillis")
    suspend fun deleteBefore(beforeMillis: Long)
}
