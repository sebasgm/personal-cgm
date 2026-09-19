package dev.cgm.core

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

class ValueAxisTest {

    private val thresholds = GlucoseThresholds(
        urgentLowMgdl = 55.0,
        lowMgdl = 70.0,
        highMgdl = 180.0,
        veryHighMgdl = 240.0,
    )

    @Test
    fun `keeps the in-range band in view even with no data`() {
        val axis = ValueAxis.of(emptyList(), thresholds)

        assertTrue(axis.minMgdl <= thresholds.lowMgdl)
        assertTrue(axis.maxMgdl >= thresholds.highMgdl)
    }

    /**
     * The band is the reference the trace is read against. An axis that cropped it
     * to fit the data tighter would gain resolution and lose the meaning.
     */
    @Test
    fun `keeps the band in view when every reading sits inside it`() {
        val axis = ValueAxis.of(listOf(110.0, 115.0, 120.0), thresholds)

        assertTrue(axis.minMgdl <= thresholds.lowMgdl)
        assertTrue(axis.maxMgdl >= thresholds.highMgdl)
    }

    @Test
    fun `grows to include readings outside the band`() {
        val axis = ValueAxis.of(listOf(45.0, 310.0), thresholds)

        assertTrue(axis.minMgdl <= 45.0, "low outlier clipped: ${axis.minMgdl}")
        assertTrue(axis.maxMgdl >= 310.0, "high outlier clipped: ${axis.maxMgdl}")
    }

    /**
     * The reason bounds snap to a grid: a chart recomputed every poll must not
     * have its gridlines crawl. One reading moving by 3 mg/dL should not move the
     * axis at all.
     */
    @Test
    fun `small changes in data do not move the axis`() {
        val first = ValueAxis.of(listOf(96.0, 143.0), thresholds)
        val second = ValueAxis.of(listOf(97.0, 141.0), thresholds)

        assertEquals(first, second)
    }

    @Test
    fun `bounds land on the snap grid`() {
        val axis = ValueAxis.of(listOf(63.0, 207.0), thresholds)

        assertEquals(0.0, axis.minMgdl % ValueAxis.SNAP_MGDL)
        assertEquals(0.0, axis.maxMgdl % ValueAxis.SNAP_MGDL)
    }

    @Test
    fun `never magnifies a flat stretch into mountains`() {
        val axis = ValueAxis.of(listOf(119.0, 120.0, 121.0), thresholds)

        assertTrue(
            axis.spanMgdl >= ValueAxis.MIN_SPAN_MGDL,
            "span ${axis.spanMgdl} would exaggerate noise",
        )
    }

    @Test
    fun `never goes below zero`() {
        val axis = ValueAxis.of(listOf(41.0), thresholds)

        assertTrue(axis.minMgdl >= 0.0)
    }

    @Test
    fun `fraction puts the bottom at zero and the top at one`() {
        val axis = ValueAxis(50.0, 250.0)

        assertEquals(0f, axis.fraction(50.0))
        assertEquals(1f, axis.fraction(250.0))
        assertEquals(0.5f, axis.fraction(150.0))
    }

    @Test
    fun `fraction clamps rather than escaping the plot`() {
        val axis = ValueAxis(50.0, 250.0)

        assertEquals(0f, axis.fraction(10.0))
        assertEquals(1f, axis.fraction(600.0))
    }

    @Test
    fun `offers a readable number of gridlines at any span`() {
        val spans = listOf(
            ValueAxis.of(listOf(120.0), thresholds),
            ValueAxis.of(listOf(45.0, 310.0), thresholds),
            ValueAxis.of(listOf(40.0, 500.0), thresholds),
        )

        spans.forEach { axis ->
            val lines = axis.gridLines()
            assertTrue(lines.size in 2..8, "${lines.size} lines for span ${axis.spanMgdl}")
            lines.forEach {
                assertTrue(it >= axis.minMgdl && it <= axis.maxMgdl, "$it outside axis")
            }
        }
    }
}

class ChartSeriesTest {

    private val start = 1_800_000_000_000L

    private fun readings(vararg offsetsMinutes: Int): List<GlucoseReading> =
        offsetsMinutes.map { minutes ->
            GlucoseReading(
                valueMgdl = 120.0,
                timestampMillis = start + minutes.minutes.inWholeMilliseconds,
                trend = TrendArrow.STEADY,
            )
        }

    @Test
    fun `an unbroken run stays one segment`() {
        val segments = ChartSeries.segments(readings(0, 1, 2, 3, 4))

        assertEquals(1, segments.size)
        assertEquals(5, segments.single().size)
    }

    /**
     * The whole point: a line drawn straight through a gap invents readings that
     * were never measured, on a chart someone may dose from.
     */
    @Test
    fun `splits where the sensor stopped reporting`() {
        val segments = ChartSeries.segments(readings(0, 1, 2, 40, 41))

        assertEquals(2, segments.size)
        assertEquals(3, segments.first().size)
        assertEquals(2, segments.last().size)
    }

    @Test
    fun `the split threshold is the display's own staleness boundary`() {
        val justUnder = ChartSeries.segments(readings(0, 4))
        val justOver = ChartSeries.segments(readings(0, 6))

        assertEquals(1, justUnder.size)
        assertEquals(2, justOver.size)
    }

    @Test
    fun `orders readings before splitting, so arrival order cannot invent a gap`() {
        val shuffled = readings(4, 0, 2, 1, 3)

        val segments = ChartSeries.segments(shuffled)

        assertEquals(1, segments.size)
        assertEquals(
            segments.single().map { it.timestampMillis },
            segments.single().map { it.timestampMillis }.sorted(),
        )
    }

    @Test
    fun `no readings means nothing to draw`() {
        assertTrue(ChartSeries.segments(emptyList()).isEmpty())
    }

    @Test
    fun `a single reading is still a segment, so its dot is drawn`() {
        val segments = ChartSeries.segments(readings(0))

        assertEquals(1, segments.size)
        assertEquals(1, segments.single().size)
    }

    @Test
    fun `every reading survives the split`() {
        val input = readings(0, 1, 2, 30, 31, 90)

        val kept = ChartSeries.segments(input).flatten()

        assertEquals(input.size, kept.size)
        assertEquals(input.map { it.timestampMillis }.sorted(), kept.map { it.timestampMillis })
    }
}

class ChartZoomTest {

    private val threeHours = 3L * 60 * 60 * 1000

    @Test
    fun `fingers apart zooms in, which shortens the span`() {
        assertEquals(threeHours / 2, ChartZoom.zoomed(threeHours, 2f))
    }

    @Test
    fun `fingers together zooms out, which lengthens the span`() {
        assertEquals(threeHours * 2, ChartZoom.zoomed(threeHours, 0.5f))
    }

    @Test
    fun `cannot zoom past the limits`() {
        assertEquals(ChartZoom.MIN_SPAN_MILLIS, ChartZoom.zoomed(threeHours, 1000f))
        assertEquals(ChartZoom.MAX_SPAN_MILLIS, ChartZoom.zoomed(threeHours, 0.0001f))
    }

    /**
     * A gesture cannot really produce these, but a zero or negative span would
     * divide by zero somewhere downstream, so they are refused here rather than
     * trusted.
     */
    @Test
    fun `nonsense gesture factors leave the span alone`() {
        assertEquals(threeHours, ChartZoom.zoomed(threeHours, 0f))
        assertEquals(threeHours, ChartZoom.zoomed(threeHours, -2f))
        assertEquals(threeHours, ChartZoom.zoomed(threeHours, Float.NaN))
        assertEquals(threeHours, ChartZoom.zoomed(threeHours, Float.POSITIVE_INFINITY))
    }

    @Test
    fun `a span is never zero or negative however it is reached`() {
        listOf(0L, -5L, 1L, Long.MAX_VALUE).forEach { span ->
            assertTrue(ChartZoom.clamp(span) >= ChartZoom.MIN_SPAN_MILLIS, "clamp($span)")
        }
    }

    /**
     * The chips stay on as presets, so a span that came from tapping one has to
     * read back as that chip's own label rather than as something approximate.
     */
    @Test
    fun `preset spans read back as their chip labels`() {
        assertEquals("3h", ChartZoom.label(threeHours))
        assertEquals("6h", ChartZoom.label(6L * 60 * 60 * 1000))
        assertEquals("12h", ChartZoom.label(12L * 60 * 60 * 1000))
        assertEquals("24h", ChartZoom.label(24L * 60 * 60 * 1000))
    }

    @Test
    fun `labels stay in whole units at every scale`() {
        assertEquals("15m", ChartZoom.label(ChartZoom.MIN_SPAN_MILLIS))
        assertEquals("45m", ChartZoom.label(45L * 60 * 1000))
        assertEquals("2h", ChartZoom.label(118L * 60 * 1000))
        assertEquals("7d", ChartZoom.label(ChartZoom.MAX_SPAN_MILLIS))
    }
}

class ChartHistoryTest {

    private val now = 1_800_000_000_000L
    private val threeHours = 3L * 60 * 60 * 1000
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `an end at now is live`() {
        assertTrue(ChartHistory.isLive(now, now))
    }

    /**
     * Live has to be a range: the end is compared against a moving clock, so exact
     * equality would fall out of live mode a millisecond after entering it.
     */
    @Test
    fun `a few seconds behind is still live`() {
        assertTrue(ChartHistory.isLive(now - 30_000, now))
    }

    @Test
    fun `an hour behind is browsing, not live`() {
        assertFalse(ChartHistory.isLive(now - 60 * 60 * 1000, now))
    }

    @Test
    fun `the end never moves into the future`() {
        assertEquals(now, ChartHistory.clampEnd(now + day, now))
        assertEquals(now, ChartHistory.panned(now, threeHours, -5f, now))
    }

    @Test
    fun `cannot browse past where the data is kept`() {
        val farBack = ChartHistory.clampEnd(now - 10 * 365 * day, now)

        assertEquals(now - ChartHistory.MAX_LOOKBACK_MILLIS, farBack)
    }

    /**
     * Dragging right pulls older data in, so the end moves back. Getting this
     * backwards makes the chart feel like a scrollbar rather than paper.
     */
    @Test
    fun `dragging right moves the window backwards`() {
        val panned = ChartHistory.panned(now, threeHours, 0.5f, now)

        assertEquals(now - threeHours / 2, panned)
    }

    @Test
    fun `dragging forward from the past returns towards live`() {
        val browsing = now - 2 * day

        val panned = ChartHistory.panned(browsing, threeHours, -1f, now)

        assertEquals(browsing + threeHours, panned)
    }

    @Test
    fun `a pan is proportional to the span, so zoom changes its reach`() {
        val wide = ChartHistory.panned(now, day, 1f, now)
        val narrow = ChartHistory.panned(now, threeHours, 1f, now)

        assertEquals(now - day, wide)
        assertEquals(now - threeHours, narrow)
    }

    @Test
    fun `nonsense drag fractions leave the end alone`() {
        assertEquals(now, ChartHistory.panned(now, threeHours, Float.NaN, now))
    }

    @Test
    fun `the arrows step whole days`() {
        assertEquals(now - day, ChartHistory.steppedDays(now, -1, now))
        assertEquals(now - day, ChartHistory.steppedDays(now - 2 * day, 1, now))
    }

    @Test
    fun `stepping forward past now stops at now`() {
        assertEquals(now, ChartHistory.steppedDays(now - 3_600_000, 1, now))
    }
}
