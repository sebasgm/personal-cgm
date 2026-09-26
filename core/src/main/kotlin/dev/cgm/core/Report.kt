package dev.cgm.core

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** How a period is cut into ranges. */
enum class ReportSplit { DAY, WEEK, MONTH, EQUAL }

/**
 * What the report is built from, chosen once and kept.
 *
 * Stored rather than held on the screen because the report is now configured in
 * one place and read in another. A choice that survives leaving Settings but not
 * the process is not a setting, it is a mood.
 *
 * The period is days rather than a named window so this stays independent of
 * whatever presets the UI happens to offer, and an old value keeps meaning the
 * same length of time after that list changes.
 */
@Serializable
data class ReportPreferences(
    val periodDays: Int = 30,
    val split: ReportSplit = ReportSplit.WEEK,
    val rangeCount: Int = 4,
) {
    fun sanitised(): ReportPreferences = copy(
        periodDays = periodDays.coerceIn(1, MAX_PERIOD_DAYS),
        rangeCount = rangeCount.coerceIn(MIN_RANGES, MAX_EQUAL_RANGES),
    )

    val periodMillis: Long get() = periodDays * DAY_MILLIS

    companion object {
        val Default = ReportPreferences()

        /** Two ranges is the fewest that can be compared with each other. */
        const val MIN_RANGES = 2

        /** Past this the ranges are thinner than the gaps in the data. */
        const val MAX_EQUAL_RANGES = 24

        /** A couple of years, well beyond what any install will have recorded. */
        const val MAX_PERIOD_DAYS = 730

        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}

@Serializable
data class ReportSpec(
    val startMillis: Long,
    val endMillis: Long,
    val split: ReportSplit = ReportSplit.WEEK,
    /** Used only by [ReportSplit.EQUAL]. */
    val rangeCount: Int = 4,
    val thresholds: GlucoseThresholds = GlucoseThresholds.Default,
    val unit: GlucoseUnit = GlucoseUnit.MGDL,
) {
    val spanMillis: Long get() = (endMillis - startMillis).coerceAtLeast(0)
}

/**
 * One range of the report.
 *
 * Carries the distribution rather than only its summary, because the summary is
 * what a distribution looks like after you have thrown away the interesting part.
 * Two weeks with the same mean and very different spreads are the case this
 * exists to make visible.
 */
data class RangeReport(
    val label: String,
    val startMillis: Long,
    val endMillis: Long,
    val bins: IntArray,
    val moments: Moments,
    val coverage: Double,
    val zoneFractions: Map<Zone, Double>,
) {
    val readingCount: Int get() = moments.count
    val hasData: Boolean get() = readingCount > 0

    val mean: Double? get() = moments.mean
    val standardDeviation: Double? get() = moments.standardDeviation
    val coefficientOfVariation: Double? get() = moments.coefficientOfVariationPercent

    val median: Double? get() = GlucoseHistogram.percentile(bins, 0.50)
    val p10: Double? get() = GlucoseHistogram.percentile(bins, 0.10)
    val p25: Double? get() = GlucoseHistogram.percentile(bins, 0.25)
    val p75: Double? get() = GlucoseHistogram.percentile(bins, 0.75)
    val p90: Double? get() = GlucoseHistogram.percentile(bins, 0.90)

    val timeInRange: Double? get() = zoneFractions[Zone.IN_RANGE]

    val isReliable: Boolean get() = coverage >= GlucoseStatistics.RELIABLE_COVERAGE

    override fun equals(other: Any?): Boolean =
        other is RangeReport && other.label == label && other.startMillis == startMillis

    override fun hashCode(): Int = 31 * label.hashCode() + startMillis.hashCode()
}

data class Report(
    val generatedAtMillis: Long,
    val spec: ReportSpec,
    val ranges: List<RangeReport>,
    /** Every range together, so the whole period has a line of its own. */
    val overall: RangeReport,
) {
    val hasData: Boolean get() = overall.hasData

    /** True when any range is too thin to read, which the reader has to be told. */
    val hasUnreliableRanges: Boolean get() = ranges.any { it.hasData && !it.isReliable }
}

object ReportBuilder {

    /**
     * Cut [spec] into ranges and summarise each from hourly rows.
     *
     * Built from rollups rather than readings for the same reason every other
     * long window is: a year is a few thousand summarised rows against hundreds
     * of thousands of measurements, and the distribution needed here is already
     * stored.
     */
    fun build(
        spec: ReportSpec,
        hours: List<HourlyBin>,
        hourStartMillis: (HourlyBin) -> Long,
        generatedAtMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Report {
        val bounds = boundaries(spec, zone)

        val ranges = bounds.zipWithNext { from, to ->
            val inRange = hours.filter { hourStartMillis(it) in from until to }
            summarise(labelFor(from, to, spec.split, zone), from, to, inRange, spec)
        }

        return Report(
            generatedAtMillis = generatedAtMillis,
            spec = spec,
            ranges = ranges,
            overall = summarise("All", spec.startMillis, spec.endMillis, hours, spec),
        )
    }

    /**
     * Range edges, in wall-clock terms for the calendar splits.
     *
     * A week is not 604800000 milliseconds twice a year, and a month is never a
     * fixed number of them. Asking the calendar keeps "last four weeks" meaning
     * four weeks rather than 28 fixed-length blocks that drift off the days they
     * are named after.
     */
    internal fun boundaries(spec: ReportSpec, zone: ZoneId): List<Long> {
        if (spec.spanMillis <= 0) return listOf(spec.startMillis, spec.startMillis)

        if (spec.split == ReportSplit.EQUAL) {
            val count = spec.rangeCount.coerceIn(1, MAX_RANGES)
            val step = spec.spanMillis / count
            return (0..count).map { spec.startMillis + it * step }
                .toMutableList()
                .also { it[it.lastIndex] = spec.endMillis }
        }

        val unit = when (spec.split) {
            ReportSplit.DAY -> ChronoUnit.DAYS
            ReportSplit.WEEK -> ChronoUnit.WEEKS
            ReportSplit.MONTH -> ChronoUnit.MONTHS
            ReportSplit.EQUAL -> error("handled above")
        }

        val start = Instant.ofEpochMilli(spec.startMillis).atZone(zone)
        val end = Instant.ofEpochMilli(spec.endMillis).atZone(zone)

        val edges = mutableListOf(spec.startMillis)
        var cursor = start
        var guard = 0
        while (cursor.isBefore(end) && guard++ < MAX_RANGES) {
            cursor = cursor.plus(1, unit)
            edges += minOf(cursor.toInstant().toEpochMilli(), spec.endMillis)
        }
        if (edges.last() != spec.endMillis) edges += spec.endMillis
        return edges.distinct()
    }

    private fun summarise(
        label: String,
        from: Long,
        to: Long,
        hours: List<HourlyBin>,
        spec: ReportSpec,
    ): RangeReport {
        val merged = GlucoseHistogram.empty()
        var moments = Moments.Empty
        var buckets = 0

        hours.forEach { hour ->
            GlucoseHistogram.merge(merged, hour.bins)
            buckets += hour.coverageBuckets
            // Reconstructed from the distribution: a rollup carries the bins and
            // the count, and a mean assembled from bin midpoints is within half a
            // bin of the true one — closer than the sensor itself.
            moments += momentsOf(hour.bins)
        }

        val expected = (to - from) / HOUR_MILLIS * BUCKETS_PER_HOUR
        return RangeReport(
            label = label,
            startMillis = from,
            endMillis = to,
            bins = merged,
            moments = moments,
            coverage = if (expected <= 0) 0.0 else (buckets.toDouble() / expected).coerceIn(0.0, 1.0),
            zoneFractions = GlucoseHistogram.zoneFractions(merged, spec.thresholds),
        )
    }

    private fun momentsOf(bins: IntArray): Moments {
        var count = 0
        var sum = 0.0
        var sumSq = 0.0
        bins.forEachIndexed { index, n ->
            if (n == 0) return@forEachIndexed
            val midpoint = GlucoseHistogram.midpointOf(index)
            count += n
            sum += midpoint * n
            sumSq += midpoint * midpoint * n
        }
        return Moments(count, sum, sumSq)
    }

    private fun labelFor(from: Long, to: Long, split: ReportSplit, zone: ZoneId): String {
        val start = Instant.ofEpochMilli(from).atZone(zone)
        return when (split) {
            ReportSplit.DAY -> start.format(DAY)
            ReportSplit.MONTH -> start.format(MONTH)
            else -> {
                val finish = Instant.ofEpochMilli(to - 1).atZone(zone)
                "${start.format(DAY)} – ${finish.format(DAY)}"
            }
        }
    }

    /** Enough for a year of days, and a stop against a pathological request. */
    const val MAX_RANGES = 400

    private const val HOUR_MILLIS = 60L * 60 * 1000
    private const val BUCKETS_PER_HOUR = 12

    private val DAY = DateTimeFormatter.ofPattern("d MMM yyyy")
    private val MONTH = DateTimeFormatter.ofPattern("MMMM yyyy")
}
