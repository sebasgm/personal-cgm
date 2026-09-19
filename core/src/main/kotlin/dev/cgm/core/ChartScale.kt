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

/**
 * How much time the chart shows, and what a pinch does to it.
 *
 * The window used to be one of four fixed chips. Zoom makes it continuous, so the
 * span becomes a number with limits rather than an enum — the chips stay on as
 * coarse presets, because reaching for "24h" should not require a gesture.
 */
object ChartZoom {

    /**
     * Closest you can get. Below a quarter of an hour there are perhaps fifteen
     * readings on screen and the trace stops being a curve and becomes a zigzag
     * between individual samples.
     */
    val MIN_SPAN_MILLIS = 15L * 60 * 1000

    /**
     * Furthest out. Beyond a week the trace is denser than the pixels available,
     * and the question "what were my numbers last month" is a Trends question,
     * answered by aggregates rather than by a squashed line.
     */
    val MAX_SPAN_MILLIS = 7L * 24 * 60 * 60 * 1000

    fun clamp(spanMillis: Long): Long = spanMillis.coerceIn(MIN_SPAN_MILLIS, MAX_SPAN_MILLIS)

    /**
     * A pinch, as a new span.
     *
     * [factor] is the gesture's scale: above 1 the fingers moved apart, which
     * means zoom *in*, which means a **shorter** span — hence the division. A
     * non-positive factor cannot come from a real gesture and is ignored rather
     * than allowed to produce a zero or negative window.
     */
    fun zoomed(spanMillis: Long, factor: Float): Long {
        if (factor <= 0f || !factor.isFinite()) return clamp(spanMillis)
        return clamp((spanMillis / factor).toLong())
    }

    /**
     * How the span reads once it is no longer one of the presets.
     *
     * Rounded to whole units deliberately: "2h" is what someone wants to know,
     * where "1h 58m" is noise generated by their own fingers.
     */
    fun label(spanMillis: Long): String {
        val minutes = spanMillis / 60_000
        return when {
            minutes < 60 -> "${minutes}m"
            minutes < 48 * 60 -> "${(minutes + 30) / 60}h"
            else -> "${(minutes + 12 * 60) / (24 * 60)}d"
        }
    }
}

/**
 * Moving the chart back through history.
 *
 * The viewport is "a span ending at some instant", and normally that instant is
 * now. Browsing is what happens when it is not: the end detaches from the clock
 * and the chart shows a window that has already passed.
 *
 * Live is a *range* rather than an exact equality, because the end is compared
 * against a clock that keeps moving. Requiring `end == now` would drop out of live
 * mode a millisecond after entering it.
 */
object ChartHistory {

    /**
     * How far back browsing may go, matched to the database's retention. Beyond it
     * there is nothing to find, and a chart that scrolls for ever into emptiness
     * is a worse answer than one that stops where the data does.
     */
    val MAX_LOOKBACK_MILLIS = 730L * 24 * 60 * 60 * 1000

    /** Within this of now, the chart is still following the clock. */
    val LIVE_TOLERANCE_MILLIS = 60L * 1000

    fun isLive(endMillis: Long, nowMillis: Long): Boolean =
        nowMillis - endMillis <= LIVE_TOLERANCE_MILLIS

    /**
     * The future holds no readings, so the end never passes now — which is also
     * what makes dragging forward settle back into live mode rather than into a
     * blank window an hour ahead.
     */
    fun clampEnd(endMillis: Long, nowMillis: Long): Long =
        endMillis.coerceIn(nowMillis - MAX_LOOKBACK_MILLIS, nowMillis)

    /**
     * A horizontal drag, as a new end.
     *
     * [fractionOfSpan] is how far the finger moved as a proportion of the chart's
     * width. Dragging right pulls older data into view, so the end moves *back*:
     * the content follows the finger, which is the only direction that feels like
     * dragging a piece of paper rather than a scrollbar.
     */
    fun panned(
        endMillis: Long,
        spanMillis: Long,
        fractionOfSpan: Float,
        nowMillis: Long,
    ): Long {
        if (!fractionOfSpan.isFinite()) return clampEnd(endMillis, nowMillis)
        return clampEnd(endMillis - (spanMillis * fractionOfSpan).toLong(), nowMillis)
    }

    /** One tap of the arrows. Negative goes back. */
    fun steppedDays(endMillis: Long, days: Int, nowMillis: Long): Long =
        clampEnd(endMillis + days * DAY_MILLIS, nowMillis)

    private const val DAY_MILLIS = 24L * 60 * 60 * 1000
}
