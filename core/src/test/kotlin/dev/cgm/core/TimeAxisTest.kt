package dev.cgm.core

import java.time.ZoneId
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class TimeAxisTest {

    private val madrid = ZoneId.of("Europe/Madrid")

    /** Kathmandu is UTC+05:45, which is what catches epoch-arithmetic ticks. */
    private val kathmandu = ZoneId.of("Asia/Kathmandu")

    private fun at(zone: ZoneId, iso: String): Long =
        LocalDateTime.parse(iso).atZone(zone).toInstant().toEpochMilli()

    private fun labels(start: Long, end: Long, zone: ZoneId) =
        TimeAxis.ticks(start, end, zone).map { it.label }

    private val hour = 60 * 60 * 1000L
    private val day = 24 * hour

    @Test
    fun `ticks land on round wall-clock times, not on window fractions`() {
        val start = at(madrid, "2026-09-19T08:37")
        val ticks = TimeAxis.ticks(start, start + 6 * hour, madrid)

        assertTrue(ticks.isNotEmpty())
        ticks.forEach { tick ->
            val local = java.time.Instant.ofEpochMilli(tick.atMillis).atZone(madrid)
            assertEquals(0, local.minute % 5, "tick at ${tick.label} is not on a round minute")
            assertEquals(0, local.second)
        }
    }

    @Test
    fun `no more labels than the axis can hold`() {
        listOf(20L * 60 * 1000, 3 * hour, day, 3 * day, 7 * day, 90 * day).forEach { span ->
            val start = at(madrid, "2026-09-19T08:37")
            val count = TimeAxis.ticks(start, start + span, madrid).size
            assertTrue(count <= TimeAxis.MAX_TICKS + 1, "span $span produced $count labels")
        }
    }

    @Test
    fun `a window inside one day is labelled by the clock`() {
        val start = at(madrid, "2026-09-19T08:00")
        assertEquals(listOf("08:00", "10:00", "12:00", "14:00"), labels(start, start + 6 * hour, madrid))
    }

    /**
     * The reason the axis exists. A trace browsed back three days must not read
     * like this morning's, and a row of clock times alone cannot say which day it
     * is — so midnight carries its date instead of "00:00".
     */
    @Test
    fun `crossing midnight shows the date rather than another clock time`() {
        val start = at(madrid, "2026-09-19T18:00")
        val ticks = TimeAxis.ticks(start, start + 12 * hour, madrid)

        // The month's name is whatever the JVM's locale calls it; what matters is
        // that the label names the day it arrived at rather than a clock time.
        val boundary = ticks.single { it.startsDay }
        assertTrue(boundary.label.startsWith("20 "), "expected a date, got ${boundary.label}")
        assertTrue(ticks.none { it.label == "00:00" })
    }

    @Test
    fun `a multi-day window is labelled by date alone`() {
        val start = at(madrid, "2026-09-19T08:37")
        val ticks = TimeAxis.ticks(start, start + 7 * day, madrid)

        assertTrue(ticks.all { it.startsDay })
        assertTrue(ticks.size >= 3)
    }

    /**
     * A quarter-hour offset is exactly what dividing epoch millis by an hour gets
     * wrong: the ticks would come out at :15 past every local hour.
     */
    @Test
    fun `offsets that are not whole hours still get round local times`() {
        val start = at(kathmandu, "2026-09-19T08:05")
        val ticks = TimeAxis.ticks(start, start + 6 * hour, kathmandu)

        ticks.forEach { tick ->
            val local = java.time.Instant.ofEpochMilli(tick.atMillis).atZone(kathmandu)
            assertEquals(0, local.minute, "tick at ${tick.label} is off the hour")
        }
    }

    @Test
    fun `every tick is inside the window`() {
        val start = at(madrid, "2026-09-19T08:37")
        val end = start + 3 * hour
        TimeAxis.ticks(start, end, madrid).forEach {
            assertTrue(it.atMillis in start..end, "${it.label} falls outside the window")
        }
    }

    @Test
    fun `an empty or backwards window has no axis`() {
        val start = at(madrid, "2026-09-19T08:37")
        assertEquals(emptyList(), TimeAxis.ticks(start, start, madrid))
        assertEquals(emptyList(), TimeAxis.ticks(start, start - hour, madrid))
    }

    /**
     * Spring forward loses an hour of wall clock. The ticks stay evenly spaced in
     * real time, which is what the trace is drawn against; what must not happen is
     * a duplicate or a gap in the labels.
     */
    @Test
    fun `a daylight-saving change does not repeat or drop a label`() {
        val start = at(madrid, "2026-03-29T00:00")
        val ticks = TimeAxis.ticks(start, start + 12 * hour, madrid)

        assertEquals(ticks.map { it.atMillis }.distinct(), ticks.map { it.atMillis })
        assertTrue(ticks.zipWithNext().all { (a, b) -> b.atMillis > a.atMillis })
    }
}
