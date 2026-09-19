package dev.cgm.core

import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.minutes

/**
 * Summary of a window of readings.
 *
 * [coverage] is not decoration. LibreLinkUp cannot backfill history (research
 * §0), so every window longer than about twelve hours is partly empty until the
 * app has been running long enough to fill it. A time-in-range figure computed
 * from six of ninety days is not wrong so much as meaningless, and the UI needs
 * to be able to say so.
 */
@Serializable
data class GlucoseStatistics(
    val readingCount: Int,
    val meanMgdl: Double?,
    /** Fraction of covered time in each zone; sums to 1 when any data exists. */
    val zoneFractions: Map<Zone, Double>,
    /** Fraction of the requested window we actually have data for, 0..1. */
    val coverage: Double,
) {
    val timeInRange: Double? get() = zoneFractions[Zone.IN_RANGE]

    /**
     * Glucose Management Indicator, the standardised CGM-derived estimate of
     * A1C: `GMI(%) = 3.31 + 0.02392 x mean mg/dL`.
     *
     * An estimate from sensor data, not a laboratory value, and unreliable below
     * [RELIABLE_COVERAGE] or over less than about 14 days. The UI says so.
     */
    val gmiPercent: Double? get() = meanMgdl?.let { 3.31 + 0.02392 * it }

    /**
     * Estimated A1C from mean glucose, inverting the ADAG eAG relationship:
     * `A1C(%) = (mean mg/dL + 46.7) / 28.7`.
     *
     * Differs from [gmiPercent] by design — they are two different published
     * estimates of the same thing, and issue #7 asks for both.
     */
    val estimatedA1cPercent: Double? get() = meanMgdl?.let { (it + 46.7) / 28.7 }

    /**
     * Below this, clinical guidance treats CGM summary metrics as unreliable.
     * The UI still shows the number, but muted and labelled.
     */
    val isReliable: Boolean get() = coverage >= RELIABLE_COVERAGE

    companion object {
        const val RELIABLE_COVERAGE = 0.70

        val Empty = GlucoseStatistics(0, null, emptyMap(), 0.0)

        /** Below roughly two weeks, GMI and A1C estimates are not meaningful. */
        const val MIN_DAYS_FOR_A1C = 14
    }
}

object StatisticsCalculator {

    /**
     * Bucket width for coverage. Counting buckets that contain at least one
     * reading is robust to cadence changes, where counting readings against an
     * expected rate is not — our own polling produces roughly one a minute, but
     * the twelve hours of graph data we start from is fifteen-minute spaced.
     */
    val COVERAGE_BUCKET_MILLIS = 5.minutes.inWholeMilliseconds

    fun compute(
        readings: List<GlucoseReading>,
        thresholds: GlucoseThresholds,
        windowStartMillis: Long,
        windowEndMillis: Long,
    ): GlucoseStatistics {
        val window = readings.filter {
            it.timestampMillis in windowStartMillis..windowEndMillis
        }
        if (window.isEmpty()) return GlucoseStatistics.Empty

        val byZone = window.groupingBy { thresholds.classify(it.valueMgdl) }.eachCount()
        val total = window.size.toDouble()

        return GlucoseStatistics(
            readingCount = window.size,
            meanMgdl = window.sumOf { it.valueMgdl } / total,
            zoneFractions = byZone.mapValues { (_, count) -> count / total },
            coverage = coverage(window, windowStartMillis, windowEndMillis),
        )
    }

    private fun coverage(
        readings: List<GlucoseReading>,
        startMillis: Long,
        endMillis: Long,
    ): Double {
        val span = endMillis - startMillis
        if (span <= 0) return 0.0

        val totalBuckets = (span / COVERAGE_BUCKET_MILLIS).coerceAtLeast(1)
        val filled = readings
            .map { (it.timestampMillis - startMillis) / COVERAGE_BUCKET_MILLIS }
            .toHashSet()
            .size

        return (filled.toDouble() / totalBuckets).coerceIn(0.0, 1.0)
    }
}
