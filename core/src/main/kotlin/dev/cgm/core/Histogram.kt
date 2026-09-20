package dev.cgm.core

import kotlin.math.roundToInt

/**
 * A fixed-bin distribution of glucose values.
 *
 * This is what makes rolled-up history survive a change of thresholds. Storing
 * per-zone counts would bake the user's target band into every historical row, so
 * editing the band later would either silently invalidate months of figures or
 * force a rescan of the raw table. A distribution answers "how much time below
 * 70" and "how much time below 80" equally well, after the fact.
 *
 * 72 bins of 5 mg/dL spanning 40..400. At 2 bytes a bin that is 144 bytes an
 * hour, about 1.2 MB a year — small enough that the flexibility is free.
 */
object GlucoseHistogram {

    const val MIN_MGDL = 40
    const val BIN_WIDTH = 5
    const val BIN_COUNT = 72
    const val MAX_MGDL = MIN_MGDL + BIN_COUNT * BIN_WIDTH // 400

    fun empty(): IntArray = IntArray(BIN_COUNT)

    /** Values outside the sensor's range clamp into the end bins rather than vanish. */
    fun binOf(mgdl: Double): Int =
        (((mgdl - MIN_MGDL) / BIN_WIDTH).toInt()).coerceIn(0, BIN_COUNT - 1)

    fun add(bins: IntArray, mgdl: Double, weight: Int = 1) {
        bins[binOf(mgdl)] += weight
    }

    fun of(values: Iterable<Double>): IntArray =
        empty().also { bins -> values.forEach { add(bins, it) } }

    /** Combining histograms is what lets any window be answered from hourly rows. */
    fun merge(into: IntArray, other: IntArray): IntArray {
        for (i in into.indices) into[i] += other[i]
        return into
    }

    fun total(bins: IntArray): Int = bins.sum()

    /** Midpoint of a bin, which is the best estimate of a value inside it. */
    fun midpointOf(bin: Int): Double = MIN_MGDL + bin * BIN_WIDTH + BIN_WIDTH / 2.0

    /** Count of samples strictly below [mgdl], interpolating within the straddled bin. */
    fun countBelow(bins: IntArray, mgdl: Double): Double {
        val edge = (mgdl - MIN_MGDL) / BIN_WIDTH
        if (edge <= 0) return 0.0
        if (edge >= BIN_COUNT) return total(bins).toDouble()

        val whole = edge.toInt()
        var count = 0.0
        for (i in 0 until whole) count += bins[i]
        // Assume a uniform spread inside the bin the boundary falls in.
        count += bins[whole] * (edge - whole)
        return count
    }

    fun countBetween(bins: IntArray, lowInclusive: Double, highInclusive: Double): Double =
        (countBelow(bins, highInclusive) - countBelow(bins, lowInclusive)).coerceAtLeast(0.0)

    /**
     * Zone proportions for an arbitrary threshold set — the whole point of storing
     * a distribution rather than counts.
     */
    fun zoneFractions(bins: IntArray, thresholds: GlucoseThresholds): Map<Zone, Double> {
        val total = total(bins).toDouble()
        if (total == 0.0) return emptyMap()
        return mapOf(
            Zone.URGENT_LOW to countBelow(bins, thresholds.urgentLowMgdl) / total,
            Zone.LOW to countBetween(bins, thresholds.urgentLowMgdl, thresholds.lowMgdl) / total,
            Zone.IN_RANGE to countBetween(bins, thresholds.lowMgdl, thresholds.highMgdl) / total,
            Zone.HIGH to countBetween(bins, thresholds.highMgdl, thresholds.veryHighMgdl) / total,
            Zone.VERY_HIGH to (total - countBelow(bins, thresholds.veryHighMgdl)) / total,
        )
    }

    /**
     * The [fraction]-quantile, interpolated within its bin.
     *
     * Percentiles are what an ambulatory glucose profile is made of — median with
     * 25/75 and 10/90 bands — and they cannot be recovered from a mean and an SD.
     */
    fun percentile(bins: IntArray, fraction: Double): Double? {
        val total = total(bins)
        if (total == 0) return null

        val target = fraction.coerceIn(0.0, 1.0) * total
        var cumulative = 0.0
        for (bin in 0 until BIN_COUNT) {
            val here = bins[bin]
            if (here == 0) continue
            if (cumulative + here >= target) {
                val within = ((target - cumulative) / here).coerceIn(0.0, 1.0)
                return MIN_MGDL + (bin + within) * BIN_WIDTH
            }
            cumulative += here
        }
        return midpointOf(BIN_COUNT - 1)
    }

    fun median(bins: IntArray): Double? = percentile(bins, 0.5)

    // -- serialisation ----------------------------------------------------

    /**
     * Packed little-endian uint16 per bin.
     *
     * uint16 caps a bin at 65535 samples; an hour holds at most ~60, so the
     * headroom is enormous even for merged rows written back to storage.
     */
    fun toBytes(bins: IntArray): ByteArray {
        val out = ByteArray(BIN_COUNT * 2)
        for (i in 0 until BIN_COUNT) {
            val v = bins[i].coerceIn(0, 0xFFFF)
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    fun fromBytes(bytes: ByteArray?): IntArray {
        val bins = empty()
        if (bytes == null || bytes.size < BIN_COUNT * 2) return bins
        for (i in 0 until BIN_COUNT) {
            bins[i] = (bytes[i * 2].toInt() and 0xFF) or
                ((bytes[i * 2 + 1].toInt() and 0xFF) shl 8)
        }
        return bins
    }
}

/**
 * Count, sum and sum of squares — enough to reconstruct mean and SD, and
 * combinable across any set of windows.
 *
 * Storing these instead of a precomputed mean and SD is what lets a 90-day figure
 * be assembled from 2160 hourly rows without touching a reading, and without any
 * window size needing a table of its own.
 */
data class Moments(
    val count: Int = 0,
    val sum: Double = 0.0,
    val sumSq: Double = 0.0,
) {
    val mean: Double? get() = if (count > 0) sum / count else null

    /** Population variance; the sample correction is noise at these counts. */
    val variance: Double?
        get() = mean?.let { m -> (sumSq / count - m * m).coerceAtLeast(0.0) }

    val standardDeviation: Double? get() = variance?.let { kotlin.math.sqrt(it) }

    /** Coefficient of variation as a percentage. Commonly cited stability bar: <=36%. */
    val coefficientOfVariationPercent: Double?
        get() {
            val m = mean ?: return null
            val sd = standardDeviation ?: return null
            return if (m > 0) sd / m * 100 else null
        }

    operator fun plus(other: Moments) = Moments(
        count = count + other.count,
        sum = sum + other.sum,
        sumSq = sumSq + other.sumSq,
    )

    companion object {
        val Empty = Moments()

        fun of(values: Iterable<Double>): Moments {
            var count = 0
            var sum = 0.0
            var sumSq = 0.0
            values.forEach {
                count++
                sum += it
                sumSq += it * it
            }
            return Moments(count, sum, sumSq)
        }

        fun sum(parts: Iterable<Moments>): Moments =
            parts.fold(Empty) { acc, m -> acc + m }
    }
}

/** Rounded mean, for display where a fraction of a mg/dL is meaningless. */
fun Moments.roundedMean(): Int? = mean?.roundToInt()
