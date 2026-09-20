package dev.cgm.core

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class ContinuityTest {

    private val end = 1_800_000_000_000L
    private val start = end - 24.hours.inWholeMilliseconds

    private fun every(minutes: Int, count: Int, from: Long = start): List<GlucoseReading> =
        (0 until count).map {
            GlucoseReading(valueMgdl = 120.0, timestampMillis = from + it * minutes * 60_000L)
        }

    @Test
    fun `a dense window reports no gaps`() {
        val readings = every(1, 24 * 60 + 1)
        val report = ContinuityAnalyzer.analyse(readings, start, end)
        assertEquals(0, report.gapCount)
        assertTrue(report.isHealthy)
        assertTrue(report.completeness > 0.99)
    }

    @Test
    fun `finds the overnight stall`() {
        // Dense for six hours, then nothing for four - the shape a sleeping CPU
        // produces - then dense again.
        val firstStretch = every(1, 360, from = start)
        val resumeAt = start + 10.hours.inWholeMilliseconds
        val secondStretch = every(1, 840, from = resumeAt)

        val report = ContinuityAnalyzer.analyse(firstStretch + secondStretch, start, end)

        assertEquals(1, report.gapCount)
        // About four hours; the exact figure depends on where the last reading
        // before the stall landed, which is not what this is testing.
        val fourHours = 4.hours.inWholeMilliseconds
        assertTrue(
            report.largestGapMillis in (fourHours - 120_000)..(fourHours + 120_000),
            "largest gap was ${report.largestGapMillis}ms",
        )
        assertTrue(!report.isHealthy)
    }

    @Test
    fun `one dropped reading is not a gap`() {
        // 2-minute spacing is within normal; the threshold must not flag it.
        val report = ContinuityAnalyzer.analyse(every(2, 720), start, end)
        assertEquals(0, report.gapCount)
    }

    @Test
    fun `counts the window edges as missing data`() {
        // Readings only in the final hour of a 24-hour window.
        val readings = every(1, 60, from = end - 1.hours.inWholeMilliseconds)
        val report = ContinuityAnalyzer.analyse(readings, start, end)
        assertEquals(1, report.gapCount)
        assertTrue(report.largestGapMillis >= 22.hours.inWholeMilliseconds)
        assertTrue(report.completeness < 0.1)
    }

    @Test
    fun `an empty window is entirely missing rather than perfect`() {
        val report = ContinuityAnalyzer.analyse(emptyList(), start, end)
        assertEquals(0, report.readingCount)
        assertEquals(0.0, report.completeness)
        assertEquals(1, report.gapCount)
    }

    @Test
    fun `ignores readings outside the window`() {
        val outside = listOf(GlucoseReading(120.0, start - 10.minutes.inWholeMilliseconds))
        val report = ContinuityAnalyzer.analyse(every(1, 1441) + outside, start, end)
        assertEquals(1441, report.readingCount)
    }

    @Test
    fun `completeness never exceeds one or falls below zero`() {
        val report = ContinuityAnalyzer.analyse(every(1, 5000), start, end)
        assertTrue(report.completeness in 0.0..1.0)
    }
}
