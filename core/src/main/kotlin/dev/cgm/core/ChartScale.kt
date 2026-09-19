package dev.cgm.core

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Where the chart's Y axis starts and stops.
 *
 * Two things fight here. Scaling tightly to the data makes variation readable,
 * which is the point; but an axis recomputed from live data moves every time a
 * reading arrives, and a chart whose gridlines crawl once a minute is worse than
 * one that wastes a little space. So the bounds are snapped to a coarse grid:
 * tight enough to gain resolution, stable enough to sit still between polls.
 *
 * The in-range band is always inside the axis. It is the reference the whole
 * picture is read against — a trace with no visible band is just a squiggle.
 */
data class ValueAxis(val minMgdl: Double, val maxMgdl: Double) {

    val spanMgdl: Double get() = maxMgdl - minMgdl

    /** 0 at the bottom of the axis, 1 at the top. Clamped, so outliers cannot escape. */
    fun fraction(valueMgdl: Double): Float =
        ((valueMgdl - minMgdl) / spanMgdl).coerceIn(0.0, 1.0).toFloat()

    /** Round values to label, from the bottom up, on the snap grid. */
    fun gridLines(): List<Double> {
        val step = gridStep()
        val first = ceil(minMgdl / step) * step
        return buildList {
            var v = first
            while (v <= maxMgdl) {
                add(v)
                v += step
            }
        }
    }

    /**
     * Enough lines to read against, never so many they become texture. Four to
     * eight across the height is the target whatever the span turns out to be.
     */
    private fun gridStep(): Double =
        GRID_STEPS.firstOrNull { spanMgdl / it <= MAX_GRID_LINES } ?: GRID_STEPS.last()

    companion object {
        /**
         * Bounds move only in steps this large, which is what stops the axis
         * crawling as readings arrive.
         */
        const val SNAP_MGDL = 10.0

        /**
         * Below this the chart would magnify ordinary noise into alarming
         * mountains. A flat hour should look flat.
         */
        const val MIN_SPAN_MGDL = 60.0

        /** Breathing room above and below, so the trace never rides the edge. */
        const val PADDING_FRACTION = 0.06

        private const val MAX_GRID_LINES = 7
        private val GRID_STEPS = listOf(10.0, 20.0, 25.0, 50.0, 100.0)

        fun of(valuesMgdl: List<Double>, thresholds: GlucoseThresholds): ValueAxis {
            // The band is in view even with no data at all, so an empty chart is
            // still recognisably *this* chart rather than a blank box.
            var low = thresholds.lowMgdl
            var high = thresholds.highMgdl
            if (valuesMgdl.isNotEmpty()) {
                low = minOf(low, valuesMgdl.min())
                high = maxOf(high, valuesMgdl.max())
            }

            val padding = (high - low) * PADDING_FRACTION
            low -= padding
            high += padding

            val shortfall = MIN_SPAN_MGDL - (high - low)
            if (shortfall > 0) {
                low -= shortfall / 2
                high += shortfall / 2
            }

            return ValueAxis(
                minMgdl = (floor(low / SNAP_MGDL) * SNAP_MGDL).coerceAtLeast(0.0),
                maxMgdl = ceil(high / SNAP_MGDL) * SNAP_MGDL,
            )
        }
    }
}

/**
 * Splits readings wherever the sensor stopped reporting.
 *
 * Drawing one unbroken line through a forty-minute hole invents data that was
 * never measured, and invented data on a glucose chart is the kind of thing
 * someone doses on. A gap in the trace is the honest rendering: it says we do
 * not know what happened between these two points.
 *
 * The default threshold is the same boundary the display uses to stop calling a
 * reading current, so "too old to trust" and "too far apart to join" are one
 * decision rather than two that can drift apart.
 */
object ChartSeries {

    fun segments(
        readings: List<GlucoseReading>,
        maxGapMillis: Long = FreshnessPolicy.Default.agingAfterMillis,
    ): List<List<GlucoseReading>> {
        if (readings.isEmpty()) return emptyList()

        val ordered = readings.sortedBy { it.timestampMillis }
        val out = mutableListOf<MutableList<GlucoseReading>>()
        var current = mutableListOf(ordered.first())

        ordered.drop(1).forEach { reading ->
            val gap = reading.timestampMillis - current.last().timestampMillis
            if (gap > maxGapMillis) {
                out += current
                current = mutableListOf(reading)
            } else {
                current += reading
            }
        }
        out += current
        return out
    }
}
