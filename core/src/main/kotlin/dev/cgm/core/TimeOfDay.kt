package dev.cgm.core

/**
 * One hour of readings, as the rollup stores it.
 *
 * The input to a time-of-day profile. [localHour] and [localDate] were resolved
 * when the row was written, in the zone the readings were taken — deriving them
 * later from epoch arithmetic gives UTC, and "my 3am" is a wall-clock idea.
 */
data class HourlyBin(
    val localDate: String,
    val localHour: Int,
    /** Distribution over [GlucoseHistogram]'s bins. */
    val bins: IntArray,
    val readingCount: Int,
    /** Distinct 5-minute buckets with data, out of twelve. */
    val coverageBuckets: Int,
) {
    // Arrays need these spelled out, or two equal bins compare unequal.
    override fun equals(other: Any?): Boolean =
        other is HourlyBin && other.localDate == localDate && other.localHour == localHour

    override fun hashCode(): Int = 31 * localDate.hashCode() + localHour
}

/**
 * One slice of the day, across every day in the window.
 *
 * Carries a spread rather than an average, which is the whole point: a slice
 * averaging 140 with a 60–260 interquartile range and one averaging 140 with a
 * 125–155 range are completely different situations, and a bar chart of averages
 * draws them identically.
 */
data class TimeOfDayBucket(
    val startHour: Int,
    val endHour: Int,
    val readingCount: Int,
    /** Days that contributed anything to this slice. */
    val dayCount: Int,
    val coverage: Double,
    val median: Double?,
    val p10: Double?,
    val p25: Double?,
    val p75: Double?,
    val p90: Double?,
) {
    val hasData: Boolean get() = readingCount > 0

    /** Label like "00–03", in the 24-hour clock the chart's axis uses. */
    fun label(): String = "%02d–%02d".format(startHour, endHour % 24)
}

/**
 * The shape of a typical day: the Ambulatory Glucose Profile, in three-hour slices.
 *
 * This is the view LibreView shows and issue #8 asked for, with one deliberate
 * difference — the bars are bands. Each slice reports the middle 50% of readings
 * and the 10th-to-90th spread around a median, so what you read off it is how
 * *variable* that part of the day is, not only where it sits.
 *
 * Built from the hourly rollups' histograms, which is exactly what they were
 * stored for: percentiles cannot be recovered from a mean and a standard
 * deviation, and recomputing them from raw readings would mean sweeping months of
 * rows every time the window changes.
 */
data class TimeOfDayProfile(
    val buckets: List<TimeOfDayBucket>,
    /** Fraction of the window that has data behind it. */
    val coverage: Double,
    /** Distinct days anywhere in the window. */
    val dayCount: Int,
) {
    val hasData: Boolean get() = buckets.any { it.hasData }

    val isReliable: Boolean
        get() = coverage >= GlucoseStatistics.RELIABLE_COVERAGE &&
            dayCount >= MIN_DAYS

    /** Lowest and highest percentile drawn, for scaling an axis to fit the bands. */
    fun valueRange(): ClosedFloatingPointRange<Double>? {
        val lows = buckets.mapNotNull { it.p10 }
        val highs = buckets.mapNotNull { it.p90 }
        if (lows.isEmpty() || highs.isEmpty()) return null
        return lows.min()..highs.max()
    }

    /** The slice with the widest middle 50%: where the day is least predictable. */
    fun mostVariable(): TimeOfDayBucket? = buckets
        .filter { it.p25 != null && it.p75 != null }
        .maxByOrNull { it.p75!! - it.p25!! }

    companion object {
        /**
         * Below about a week, a time-of-day profile is describing a few days
         * rather than a pattern, and one unusual night dominates a slice.
         */
        const val MIN_DAYS = 7

        val Empty = TimeOfDayProfile(emptyList(), 0.0, 0)
    }
}

object TimeOfDayProfiler {

    /** Three hours, so the day divides into eight slices. */
    const val BUCKET_HOURS = 3

    const val BUCKET_COUNT = 24 / BUCKET_HOURS

    /** Coverage buckets a fully recorded hour contains. */
    private const val BUCKETS_PER_HOUR = 12

    fun of(hours: List<HourlyBin>): TimeOfDayProfile {
        if (hours.isEmpty()) return TimeOfDayProfile.Empty

        val days = hours.map { it.localDate }.toHashSet().size
        val grouped = hours.groupBy { (it.localHour / BUCKET_HOURS).coerceIn(0, BUCKET_COUNT - 1) }

        val buckets = (0 until BUCKET_COUNT).map { index ->
            val startHour = index * BUCKET_HOURS
            val inBucket = grouped[index].orEmpty()

            if (inBucket.isEmpty()) {
                return@map TimeOfDayBucket(
                    startHour = startHour,
                    endHour = startHour + BUCKET_HOURS,
                    readingCount = 0,
                    dayCount = 0,
                    coverage = 0.0,
                    median = null, p10 = null, p25 = null, p75 = null, p90 = null,
                )
            }

            // Merging distributions is what makes any window answerable from
            // hourly rows without touching a reading.
            val merged = GlucoseHistogram.empty()
            inBucket.forEach { GlucoseHistogram.merge(merged, it.bins) }

            val bucketDays = inBucket.map { it.localDate }.toHashSet().size
            // An hour is fully covered at twelve five-minute buckets, and a slice
            // spans three hours on each of the days that contributed.
            val expected = bucketDays * BUCKET_HOURS * BUCKETS_PER_HOUR

            TimeOfDayBucket(
                startHour = startHour,
                endHour = startHour + BUCKET_HOURS,
                readingCount = inBucket.sumOf { it.readingCount },
                dayCount = bucketDays,
                coverage = if (expected == 0) 0.0
                else (inBucket.sumOf { it.coverageBuckets }.toDouble() / expected).coerceIn(0.0, 1.0),
                median = GlucoseHistogram.percentile(merged, 0.50),
                p10 = GlucoseHistogram.percentile(merged, 0.10),
                p25 = GlucoseHistogram.percentile(merged, 0.25),
                p75 = GlucoseHistogram.percentile(merged, 0.75),
                p90 = GlucoseHistogram.percentile(merged, 0.90),
            )
        }

        val expectedOverall = days * 24 * BUCKETS_PER_HOUR
        return TimeOfDayProfile(
            buckets = buckets,
            coverage = if (expectedOverall == 0) 0.0
            else (hours.sumOf { it.coverageBuckets }.toDouble() / expectedOverall).coerceIn(0.0, 1.0),
            dayCount = days,
        )
    }
}
