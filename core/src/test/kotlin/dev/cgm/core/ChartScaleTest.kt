package dev.cgm.core

import kotlin.test.assertEquals
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
