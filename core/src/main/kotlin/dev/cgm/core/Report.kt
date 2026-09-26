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
    /** Figures per day within each range; 0 leaves them out. See [ReportBands]. */
    val bandsPerDay: Int = 0,
) {
    fun sanitised(): ReportPreferences = copy(
        periodDays = periodDays.coerceIn(1, MAX_PERIOD_DAYS),
        rangeCount = rangeCount.coerceIn(MIN_RANGES, MAX_EQUAL_RANGES),
        bandsPerDay = ReportBands.nearest(bandsPerDay),
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
    /**
     * How many times a day each range reports a figure, or 0 for none.
     *
     * Six gives a value every four hours: the shape of a day at the resolution
     * someone actually acts on, rather than one number standing in for a whole
     * week. The bands are equal divisions of the local day, so this has to divide
     * 24 — see [ReportBands.OPTIONS].
     */
    val bandsPerDay: Int = 0,
    val thresholds: GlucoseThresholds = GlucoseThresholds.Default,
    val unit: GlucoseUnit = GlucoseUnit.MGDL,
) {
    val spanMillis: Long get() = (endMillis - startMillis).coerceAtLeast(0)
}

/** The divisions of the day a report may ask for. */
object ReportBands {
    /**
     * Only divisors of 24, so every band is the same width.
     *
     * Five bands a day would be 4.8 hours each, which puts a boundary at 04:48
     * and makes two adjacent figures incomparable. A band you cannot name is a
     * band nobody will read off a printed page.
     */
    val OPTIONS = listOf(0, 1, 2, 3, 4, 6, 8, 12, 24)

    fun nearest(bandsPerDay: Int): Int =
        if (bandsPerDay in OPTIONS) bandsPerDay else OPTIONS.minBy { kotlin.math.abs(it - bandsPerDay) }
}

/**
 * One part of the local day, within one range.
 *
 * Built from the hourly rollups' local hour, so the bands stay put across a time
 * zone change: 08:00 means the reader's eight in the morning, which is the only
 * reading of it that makes a figure comparable with the one above it.
 */
data class BandReport(
    val startHour: Int,
    val endHour: Int,
    val bins: IntArray,
    val moments: Moments,
    val coverage: Double,
) {
    val readingCount: Int get() = moments.count
    val hasData: Boolean get() = readingCount > 0
    val mean: Double? get() = moments.mean
    val median: Double? get() = GlucoseHistogram.percentile(bins, 0.50)
    val p10: Double? get() = GlucoseHistogram.percentile(bins, 0.10)
    val p90: Double? get() = GlucoseHistogram.percentile(bins, 0.90)

    /** "08–12", in whole local hours. */
    val label: String get() = "%02d–%02d".format(startHour, endHour % 24)

    override fun equals(other: Any?): Boolean =
        other is BandReport && other.startHour == startHour && other.moments == moments

    override fun hashCode(): Int = 31 * startHour + moments.hashCode()
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
    /** Empty unless [ReportSpec.bandsPerDay] asked for them. */
    val bands: List<BandReport> = emptyList(),
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
            bands = bands(hours, from, to, spec.bandsPerDay),
        )
    }

    /**
     * The same hours, regrouped by where they fall in the local day.
     *
     * Grouped on the rollup's own local hour rather than on the instant, so a
     * band keeps its wall-clock meaning across a time zone change or a DST shift.
     * A "08–12" figure that silently became 07–11 halfway down the page would be
     * worse than no figure.
     *
     * Bands with nothing in them are kept. A gap in a printed grid is a fact —
     * dropping the row would leave the ones after it lined up under the wrong
     * heading.
     */
    private fun bands(
        hours: List<HourlyBin>,
        from: Long,
        to: Long,
        bandsPerDay: Int,
    ): List<BandReport> {
        if (bandsPerDay <= 0) return emptyList()

        val width = (HOURS_PER_DAY / bandsPerDay).coerceAtLeast(1)
        val byBand = hours.groupBy { (it.localHour / width).coerceIn(0, bandsPerDay - 1) }

        // What one band could hold if every day in the range were fully recorded.
        val days = (to - from).toDouble() / DAY_MILLIS
        val expected = days * width * BUCKETS_PER_HOUR

        return (0 until bandsPerDay).map { index ->
            val inBand = byBand[index].orEmpty()
            val merged = GlucoseHistogram.empty()
            var moments = Moments.Empty
            var buckets = 0
            inBand.forEach { hour ->
                GlucoseHistogram.merge(merged, hour.bins)
                buckets += hour.coverageBuckets
                moments += momentsOf(hour.bins)
            }
            BandReport(
                startHour = index * width,
                endHour = (index + 1) * width,
                bins = merged,
                moments = moments,
                coverage = if (expected <= 0) 0.0 else (buckets / expected).coerceIn(0.0, 1.0),
            )
        }
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
    private const val DAY_MILLIS = 24L * HOUR_MILLIS
    private const val HOURS_PER_DAY = 24
    private const val BUCKETS_PER_HOUR = 12

    private val DAY = DateTimeFormatter.ofPattern("d MMM yyyy")
    private val MONTH = DateTimeFormatter.ofPattern("MMMM yyyy")
}
