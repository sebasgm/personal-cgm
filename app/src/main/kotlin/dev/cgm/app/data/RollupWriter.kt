package dev.cgm.app.data

import dev.cgm.core.GlucoseHistogram
import dev.cgm.core.GlucoseReading
import dev.cgm.core.SensorInfo
import dev.cgm.core.StatisticsCalculator
import java.time.Instant
import java.time.ZoneId

/**
 * Keeps [HourlyRollupEntity] in step with the readings table.
 *
 * **Recomputes whole hours rather than adding increments.** Readings arrive from
 * overlapping graph windows and are upserted by timestamp, so the same reading is
 * written many times over its life. Adding deltas would count it once per write;
 * recomputing the affected hour from raw is idempotent, and an hour is at most
 * sixty rows, so it costs nothing.
 */
class RollupWriter(
    private val readings: ReadingDao,
    private val rollups: HourlyRollupDao,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {

    /**
     * Rebuild everything if the rollup table is empty but readings exist.
     *
     * This is the path taken the first time the app runs after the migration that
     * added the table, and the repair path if rollups are ever cleared.
     */
    suspend fun rebuildIfEmpty(sensor: SensorInfo?): Int {
        if (rollups.rowCount() > 0) return 0
        return rebuildAll(sensor)
    }

    /** Refresh the hours touched by [changed]. */
    suspend fun refreshFor(changed: List<GlucoseReading>, sensor: SensorInfo?) {
        if (changed.isEmpty()) return
        val hours = changed.map { hourStartOf(it.timestampMillis) }.toSortedSet()
        rebuildHours(hours.toList(), sensor)
    }

    /**
     * Rebuild every rollup from raw.
     *
     * Needed after a bulk import, and as the escape hatch for any drift: a derived
     * table that cannot be regenerated from its source is a liability.
     */
    suspend fun rebuildAll(sensor: SensorInfo? = null): Int {
        rollups.deleteAll()
        val all = readings.since(0)
        if (all.isEmpty()) return 0
        val hours = all.map { hourStartOf(it.timestampMillis) }.toSortedSet().toList()
        hours.chunked(REBUILD_CHUNK_HOURS).forEach { rebuildHours(it, sensor) }
        return hours.size
    }

    private suspend fun rebuildHours(hourStarts: List<Long>, sensor: SensorInfo?) {
        val rows = hourStarts.mapNotNull { hourStart ->
            val inHour = readings
                .betweenInclusive(hourStart, hourStart + HOUR_MILLIS - 1)
                .map { it.toReading() }
            if (inHour.isEmpty()) null else summarise(hourStart, inHour, sensor)
        }
        if (rows.isNotEmpty()) rollups.upsert(rows)
    }

    private fun summarise(
        hourStartMillis: Long,
        readings: List<GlucoseReading>,
        sensor: SensorInfo?,
    ): HourlyRollupEntity {
        val values = readings.map { it.valueMgdl }
        val local = Instant.ofEpochMilli(hourStartMillis).atZone(zone())

        var sum = 0.0
        var sumSq = 0.0
        val bins = GlucoseHistogram.empty()
        values.forEach {
            sum += it
            sumSq += it * it
            GlucoseHistogram.add(bins, it)
        }

        val buckets = readings
            .map { (it.timestampMillis - hourStartMillis) / StatisticsCalculator.COVERAGE_BUCKET_MILLIS }
            .toHashSet()
            .size

        return HourlyRollupEntity(
            hourStartMillis = hourStartMillis,
            localDate = local.toLocalDate().toString(),
            localHour = local.hour,
            count = readings.size,
            sum = sum,
            sumSq = sumSq,
            minMgdl = values.min(),
            maxMgdl = values.max(),
            buckets = buckets,
            histogram = GlucoseHistogram.toBytes(bins),
            sensorSerial = sensor?.serial,
            // Which day of the sensor's session this hour fell in, so a bias
            // curve across sessions can be measured later.
            sensorDay = sensor?.dayOfSession(hourStartMillis),
        )
    }

    private fun hourStartOf(millis: Long): Long = millis - (millis % HOUR_MILLIS)

    private companion object {
        const val HOUR_MILLIS = 60L * 60 * 1000

        /** Bounded so a full rebuild cannot hold a huge transaction open. */
        const val REBUILD_CHUNK_HOURS = 24
    }
}
