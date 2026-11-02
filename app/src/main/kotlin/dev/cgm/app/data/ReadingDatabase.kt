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
import dev.cgm.core.SensorInfo
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
    /**
     * When this phone first saw the reading, against [timestampMillis] which is
     * when the sensor measured it. Null for rows written before the column existed.
     *
     * The difference between the two is the only way to tell, after the fact, that
     * a value arrived late — and therefore the only way to tell a poller that was
     * asleep from one that was awake and being handed nothing new. Without it both
     * look identical in this table, because a poll that re-reads an unchanged
     * current value writes no row at all.
     */
    val storedAtMillis: Long? = null,
) {
    fun toReading() = GlucoseReading(
        valueMgdl = valueMgdl,
        timestampMillis = timestampMillis,
        trend = runCatching { TrendArrow.valueOf(trend) }.getOrDefault(TrendArrow.UNKNOWN),
        isHigh = isHigh,
        isLow = isLow,
        storedAtMillis = storedAtMillis,
    )

    companion object {
        fun from(reading: GlucoseReading, storedAtMillis: Long? = null) = ReadingEntity(
            timestampMillis = reading.timestampMillis,
            valueMgdl = reading.valueMgdl,
            trend = reading.trend.name,
            isHigh = reading.isHigh,
            isLow = reading.isLow,
            storedAtMillis = storedAtMillis ?: reading.storedAtMillis,
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

    /**
     * First write wins, deliberately.
     *
     * Was REPLACE, for idempotence over the overlapping windows polling fetches
     * constantly. IGNORE is just as idempotent and does not destroy two things the
     * first write knew: the trend arrow, and when the reading was first seen.
     *
     * The arrow mattered more than it looked. A measurement polled live carries
     * one; the same measurement re-served later inside the 15-minute history
     * carries none, because graph points have no arrow. Under REPLACE the later,
     * poorer copy overwrote the better one — so a reading captured live was being
     * downgraded to UNKNOWN by its own backfill.
     *
     * A reading is immutable anyway: the value the sensor recorded for a given
     * instant does not change, so there is nothing a second write should update.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
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
    /**
     * Glucose at the time of the dose in mg/dL, or null when none was known.
     *
     * Nullable rather than defaulted, and for the usual reason: doses recorded
     * before this column existed have an unknown glucose, and a zero there would
     * claim a reading of 0 mg/dL — a figure that is not merely wrong but alarming.
     */
    val glucoseMgdl: Double? = null,
) {
    fun toDose() = InsulinDose(
        id = id,
        kind = runCatching { InsulinKind.valueOf(kind) }.getOrDefault(InsulinKind.BOLUS),
        units = units,
        givenAtMillis = givenAtMillis,
        note = note,
        glucoseMgdl = glucoseMgdl,
    )

    companion object {
        fun from(dose: InsulinDose) = DoseEntity(
            id = dose.id,
            kind = dose.kind.name,
            units = dose.units,
            givenAtMillis = dose.givenAtMillis,
            note = dose.note,
            glucoseMgdl = dose.glucoseMgdl,
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

    /** Every dose, for the raw export. */
    @Query("SELECT * FROM doses ORDER BY givenAtMillis ASC")
    suspend fun all(): List<DoseEntity>
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
    /** Null for a meal logged without a carbohydrate count. */
    val grams: Double?,
    val eatenAtMillis: Long,
    val note: String?,
    /** File name of the meal's photo in internal storage, or null. */
    val photoName: String? = null,
) {
    fun toEntry() = CarbEntry(
        id = id,
        grams = grams,
        eatenAtMillis = eatenAtMillis,
        note = note,
        photoName = photoName,
    )

    companion object {
        fun from(entry: CarbEntry) = CarbEntity(
            id = entry.id,
            grams = entry.grams,
            eatenAtMillis = entry.eatenAtMillis,
            note = entry.note,
            photoName = entry.photoName,
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

    /** Every meal, for the raw export. */
    @Query("SELECT * FROM carbs ORDER BY eatenAtMillis ASC")
    suspend fun all(): List<CarbEntity>

    /** Photo names on meals older than [beforeMillis], so the files can be removed. */
    @Query(
        "SELECT photoName FROM carbs " +
            "WHERE eatenAtMillis < :beforeMillis AND photoName IS NOT NULL"
    )
    suspend fun photoNamesBefore(beforeMillis: Long): List<String>

    /** Every photo name still referenced, so orphaned files can be told apart. */
    @Query("SELECT photoName FROM carbs WHERE photoName IS NOT NULL")
    suspend fun allPhotoNames(): List<String>

    /**
     * Forgets the photos on meals older than [beforeMillis].
     *
     * The row stays; only its picture goes. A meal from last year is still a meal,
     * and the alternative — deleting the entry with its image — would quietly
     * rewrite the food log to keep disk space down.
     */
    @Query(
        "UPDATE carbs SET photoName = NULL " +
            "WHERE eatenAtMillis < :beforeMillis AND photoName IS NOT NULL"
    )
    suspend fun clearPhotosBefore(beforeMillis: Long)
}

/**
 * One row per physical sensor this phone has seen.
 *
 * Exists so that sensor identity survives a restart, and so a change of sensor is
 * something observed rather than inferred. Without it the app had only the
 * activation instant the API reports, which LibreLinkUp moves when the sensor is
 * re-scanned — and a moved activation looks exactly like a new sensor, which is
 * how a sensor halfway through its life came to be announced as freshly fitted.
 *
 * The serial is the key because it is the only thing Abbott sends that identifies
 * the hardware. Rows are kept after the sensor ends: they are the record of which
 * sensor produced which readings, which is the part that cannot be reconstructed.
 */
@Entity(tableName = "sensors")
data class SensorEntity(
    @PrimaryKey val serial: String,
    /**
     * Earliest activation ever seen for this serial.
     *
     * Only ever moves earlier. A later activation for a serial already on record
     * is a re-scan, not a restart, and taking it would reset the day count.
     */
    val startedAtMillis: Long,
    /** When this phone first saw the sensor, which is not when it was fitted. */
    val firstSeenMillis: Long,
    /** The lifetime in force for this session, so later changes do not rewrite it. */
    val lifetimeDays: Int,
    /** Abbott's product code, uninterpreted. Null when the source did not say. */
    val productType: Int?,
) {
    fun toInfo() = SensorInfo(
        serial = serial,
        startedAtMillis = startedAtMillis,
        lifetimeDays = lifetimeDays,
        productType = productType,
    )
}

@Dao
interface SensorDao {

    @Upsert
    suspend fun upsert(sensor: SensorEntity)

    @Query("SELECT * FROM sensors WHERE serial = :serial")
    suspend fun bySerial(serial: String): SensorEntity?

    /** The most recently activated sensor, which is the one being worn. */
    @Query("SELECT * FROM sensors ORDER BY startedAtMillis DESC LIMIT 1")
    suspend fun latest(): SensorEntity?

    @Query("SELECT * FROM sensors ORDER BY startedAtMillis DESC")
    fun observeAll(): Flow<List<SensorEntity>>
}

@Database(
    entities = [
        ReadingEntity::class,
        DoseEntity::class,
        CarbEntity::class,
        HourlyRollupEntity::class,
        SensorEntity::class,
    ],
    version = 10,
    exportSchema = false,
)
abstract class ReadingDatabase : RoomDatabase() {
    abstract fun readings(): ReadingDao
    abstract fun doses(): DoseDao
    abstract fun carbs(): CarbDao
    abstract fun rollups(): HourlyRollupDao
    abstract fun sensors(): SensorDao

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

        /**
         * Adds the sensors table.
         *
         * Created empty, and the first poll after the upgrade fills in the sensor
         * currently being worn. That row's `firstSeenMillis` is therefore the
         * upgrade, not the activation — which is why it is a separate column from
         * `startedAtMillis` and why nothing counts days from it.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    // Spelled the way Room generates it, primary key as a table
                    // constraint and all: this statement is validated against the
                    // entity when the database opens, and a cosmetic difference
                    // there surfaces as a crash on launch rather than a warning.
                    "CREATE TABLE IF NOT EXISTS `sensors` (" +
                        "`serial` TEXT NOT NULL, " +
                        "`startedAtMillis` INTEGER NOT NULL, " +
                        "`firstSeenMillis` INTEGER NOT NULL, " +
                        "`lifetimeDays` INTEGER NOT NULL, " +
                        "`productType` INTEGER, " +
                        "PRIMARY KEY(`serial`))"
                )
            }
        }

        /**
         * Makes a meal's carbohydrate count optional.
         *
         * SQLite cannot drop a NOT NULL, so the table is rebuilt and copied. Every
         * existing row keeps its figure: a meal that was counted stays counted, and
         * only meals logged from here on can be null.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `carbs_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`grams` REAL, " +
                        "`eatenAtMillis` INTEGER NOT NULL, " +
                        "`note` TEXT)"
                )
                db.execSQL(
                    "INSERT INTO `carbs_new` (`id`, `grams`, `eatenAtMillis`, `note`) " +
                        "SELECT `id`, `grams`, `eatenAtMillis`, `note` FROM `carbs`"
                )
                db.execSQL("DROP TABLE `carbs`")
                db.execSQL("ALTER TABLE `carbs_new` RENAME TO `carbs`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_carbs_eatenAtMillis` " +
                        "ON `carbs` (`eatenAtMillis`)"
                )
            }
        }

        /**
         * Adds the meal photo column.
         *
         * A plain nullable column, so no table rebuild: every existing meal keeps
         * its row and simply has no picture.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `carbs` ADD COLUMN `photoName` TEXT")
            }
        }

        /**
         * Records when a reading was first seen, beside when it was measured.
         *
         * Nullable rather than defaulted to zero: rows already stored have an
         * unknown first-seen time, and 0 would claim they arrived in 1970. Code
         * reading it has to treat null as "not known", which is the truth.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `readings` ADD COLUMN `storedAtMillis` INTEGER")
            }
        }

        /**
         * Records the glucose a dose was given at, beside the dose.
         *
         * Left null on every existing row rather than filled in from the readings
         * table. The reading is probably still there for the recent ones, but
         * backfilling would write a value the user never saw at a moment they were
         * not asked about — and for older doses, whose readings have been pruned,
         * it would write nothing anyway. A column that is null for everything
         * before the upgrade says exactly what happened.
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `doses` ADD COLUMN `glucoseMgdl` REAL")
            }
        }

        fun create(context: Context): ReadingDatabase =
            Room.databaseBuilder(context, ReadingDatabase::class.java, "readings.db")
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                )
                .build()
    }
}

fun Flow<List<ReadingEntity>>.asReadings(): Flow<List<GlucoseReading>> =
    map { rows -> rows.map { it.toReading() } }
