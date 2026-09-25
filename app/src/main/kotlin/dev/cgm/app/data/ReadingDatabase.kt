package dev.cgm.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Delete
import androidx.room.Index
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.cgm.core.CarbEntry
import dev.cgm.core.GlucoseReading
import dev.cgm.core.InsulinDose
import dev.cgm.core.InsulinKind
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

/**
 * One row of aggregates over a window.
 *
 * Computed in SQLite rather than by loading readings into memory: a year at one
 * reading a minute is over half a million rows, and #6 asks for exactly that
 * window.
 */
data class ReadingAggregate(
    val total: Int,
    val mean: Double?,
    val urgentLow: Int,
    val low: Int,
    val inRange: Int,
    val high: Int,
    val veryHigh: Int,
    /** Distinct 5-minute buckets containing data, for coverage. */
    val buckets: Int,
)

@Dao
interface ReadingDao {

    @Query(
        """
        SELECT COUNT(*) AS total,
               AVG(valueMgdl) AS mean,
               SUM(CASE WHEN valueMgdl < :urgentLow THEN 1 ELSE 0 END) AS urgentLow,
               SUM(CASE WHEN valueMgdl >= :urgentLow AND valueMgdl < :low THEN 1 ELSE 0 END) AS low,
               SUM(CASE WHEN valueMgdl >= :low AND valueMgdl <= :high THEN 1 ELSE 0 END) AS inRange,
               SUM(CASE WHEN valueMgdl > :high AND valueMgdl <= :veryHigh THEN 1 ELSE 0 END) AS high,
               SUM(CASE WHEN valueMgdl > :veryHigh THEN 1 ELSE 0 END) AS veryHigh,
               COUNT(DISTINCT timestampMillis / 300000) AS buckets
        FROM readings
        WHERE timestampMillis BETWEEN :startMillis AND :endMillis
        """
    )
    suspend fun aggregate(
        startMillis: Long,
        endMillis: Long,
        urgentLow: Double,
        low: Double,
        high: Double,
        veryHigh: Double,
    ): ReadingAggregate

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(readings: List<ReadingEntity>)

    @Query("SELECT * FROM readings WHERE timestampMillis >= :sinceMillis ORDER BY timestampMillis ASC")
    fun observeSince(sinceMillis: Long): Flow<List<ReadingEntity>>

    /**
     * A bounded window, for browsing history rather than watching the live edge.
     *
     * `timestampMillis` is the primary key, so this is a rowid range scan however
     * far back the window sits.
     */
    @Query(
        "SELECT * FROM readings WHERE timestampMillis >= :startMillis " +
            "AND timestampMillis <= :endMillis ORDER BY timestampMillis ASC"
    )
    fun observeBetween(startMillis: Long, endMillis: Long): Flow<List<ReadingEntity>>

    /** Newest first, for the logbook (#10). Paged by limit rather than loaded whole. */
    @Query("SELECT * FROM readings ORDER BY timestampMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ReadingEntity>>

    @Query("SELECT * FROM readings ORDER BY timestampMillis DESC LIMIT 1")
    suspend fun latest(): ReadingEntity?

    @Query("SELECT * FROM readings WHERE timestampMillis >= :sinceMillis ORDER BY timestampMillis ASC")
    suspend fun since(sinceMillis: Long): List<ReadingEntity>

    @Query(
        "SELECT * FROM readings WHERE timestampMillis BETWEEN :startMillis AND :endMillis " +
            "ORDER BY timestampMillis ASC"
    )
    suspend fun betweenInclusive(startMillis: Long, endMillis: Long): List<ReadingEntity>

    @Query("SELECT COUNT(*) FROM readings")
    suspend fun count(): Int

    /** Housekeeping: a year of 5-minute readings is ~100k rows, so prune. */
    @Query("DELETE FROM readings WHERE timestampMillis < :beforeMillis")
    suspend fun deleteBefore(beforeMillis: Long)
}

/**
 * A dose the user recorded.
 *
 * Separate table from readings because it is a separate kind of fact: a reading is
 * observed and immutable, a dose is entered by a person and has to be correctable.
 * Hence a generated id rather than the timestamp as the key — two doses can share a
 * minute, and an edit must not depend on when it happened.
 */
@Entity(
    tableName = "doses",
    indices = [Index("givenAtMillis")],
)
data class DoseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val units: Double,
    val givenAtMillis: Long,
    val note: String?,
) {
    fun toDose() = InsulinDose(
        id = id,
        kind = runCatching { InsulinKind.valueOf(kind) }.getOrDefault(InsulinKind.BOLUS),
        units = units,
        givenAtMillis = givenAtMillis,
        note = note,
    )

    companion object {
        fun from(dose: InsulinDose) = DoseEntity(
            id = dose.id,
            kind = dose.kind.name,
            units = dose.units,
            givenAtMillis = dose.givenAtMillis,
            note = dose.note,
        )
    }
}

@Dao
interface DoseDao {

    @Upsert
    suspend fun upsert(dose: DoseEntity)

    @Delete
    suspend fun delete(dose: DoseEntity)

    @Query("SELECT * FROM doses ORDER BY givenAtMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DoseEntity>>

    @Query(
        "SELECT * FROM doses WHERE givenAtMillis >= :startMillis " +
            "AND givenAtMillis <= :endMillis ORDER BY givenAtMillis ASC"
    )
    fun observeBetween(startMillis: Long, endMillis: Long): Flow<List<DoseEntity>>
}

/**
 * Carbohydrates the user recorded.
 *
 * Its own table beside `doses` rather than a `kind` column on it. Units and grams
 * are different quantities, and a single table holding both would let a query add
 * them together — which is a bug that reads as a number rather than as an error.
 */
@Entity(
    tableName = "carbs",
    indices = [Index("eatenAtMillis")],
)
data class CarbEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val grams: Double,
    val eatenAtMillis: Long,
    val note: String?,
) {
    fun toEntry() = CarbEntry(
        id = id,
        grams = grams,
        eatenAtMillis = eatenAtMillis,
        note = note,
    )

    companion object {
        fun from(entry: CarbEntry) = CarbEntity(
            id = entry.id,
            grams = entry.grams,
            eatenAtMillis = entry.eatenAtMillis,
            note = entry.note,
        )
    }
}

@Dao
interface CarbDao {

    @Upsert
    suspend fun upsert(entry: CarbEntity)

    @Delete
    suspend fun delete(entry: CarbEntity)

    @Query("SELECT * FROM carbs ORDER BY eatenAtMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<CarbEntity>>

    @Query(
        "SELECT * FROM carbs WHERE eatenAtMillis >= :startMillis " +
            "AND eatenAtMillis <= :endMillis ORDER BY eatenAtMillis ASC"
    )
    fun observeBetween(startMillis: Long, endMillis: Long): Flow<List<CarbEntity>>
}

@Database(
    entities = [
        ReadingEntity::class,
        DoseEntity::class,
        CarbEntity::class,
        HourlyRollupEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class ReadingDatabase : RoomDatabase() {
    abstract fun readings(): ReadingDao
    abstract fun doses(): DoseDao
    abstract fun carbs(): CarbDao
    abstract fun rollups(): HourlyRollupDao

    companion object {
        /**
         * Adds the doses table.
         *
         * A real migration rather than destructive fallback, because the readings in
         * this database cannot be re-fetched — LibreLinkUp serves about twelve hours
         * of history and nothing older, so dropping the table would permanently lose
         * everything accumulated since install.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `doses` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`units` REAL NOT NULL, " +
                        "`givenAtMillis` INTEGER NOT NULL, " +
                        "`note` TEXT)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_doses_givenAtMillis` " +
                        "ON `doses` (`givenAtMillis`)"
                )
            }
        }

        /**
         * Adds the hourly rollup table (docs/05-trend-inference.md §3).
         *
         * Created empty. The rollups are derived data and are rebuilt from the
         * readings table on first run after the upgrade, which is also the repair
         * path if they ever drift.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `hourly_rollup` (" +
                        "`hourStartMillis` INTEGER PRIMARY KEY NOT NULL, " +
                        "`localDate` TEXT NOT NULL, " +
                        "`localHour` INTEGER NOT NULL, " +
                        "`count` INTEGER NOT NULL, " +
                        "`sum` REAL NOT NULL, " +
                        "`sumSq` REAL NOT NULL, " +
                        "`minMgdl` REAL NOT NULL, " +
                        "`maxMgdl` REAL NOT NULL, " +
                        "`buckets` INTEGER NOT NULL, " +
                        "`histogram` BLOB NOT NULL, " +
                        "`sensorSerial` TEXT, " +
                        "`sensorDay` INTEGER)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_hourly_rollup_localDate` " +
                        "ON `hourly_rollup` (`localDate`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_hourly_rollup_localHour` " +
                        "ON `hourly_rollup` (`localHour`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_hourly_rollup_sensorSerial_sensorDay` " +
                        "ON `hourly_rollup` (`sensorSerial`, `sensorDay`)"
                )
            }
        }

        /**
         * Records the zone each rollup's wall clock was resolved in.
         *
         * Existing rows get an empty zone rather than today's: they were written
         * in whatever zone was current then, and claiming to know which one would
         * be a guess dressed as a fact. An empty zone simply means "unknown", and
         * the daily pattern excludes it from its count rather than treating it as
         * a distinct place.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `hourly_rollup` ADD COLUMN `zoneId` TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /**
         * Adds the carbs table.
         *
         * A real migration for the same reason every other one here is: the readings
         * beside it cannot be re-fetched, so a destructive fallback would trade a new
         * feature for every month of history the phone has accumulated.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `carbs` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`grams` REAL NOT NULL, " +
                        "`eatenAtMillis` INTEGER NOT NULL, " +
                        "`note` TEXT)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_carbs_eatenAtMillis` " +
                        "ON `carbs` (`eatenAtMillis`)"
                )
            }
        }

        fun create(context: Context): ReadingDatabase =
            Room.databaseBuilder(context, ReadingDatabase::class.java, "readings.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
    }
}

fun Flow<List<ReadingEntity>>.asReadings(): Flow<List<GlucoseReading>> =
    map { rows -> rows.map { it.toReading() } }
