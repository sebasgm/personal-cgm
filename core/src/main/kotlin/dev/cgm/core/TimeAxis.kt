package dev.cgm.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** One labelled moment on the chart's X axis. */
data class TimeTick(
    val atMillis: Long,
    val label: String,
    /**
     * True at local midnight.
     *
     * Worth distinguishing because it is the only tick that answers a different
     * question: the others say what time it was, this one says what day it was.
     */
    val startsDay: Boolean,
)

/**
 * Where the chart's X axis puts its labels.
 *
 * The chart could be read without one — tapping a point gives its exact value and
 * time — but only by tapping. Without an axis a trace browsed back three days is
 * indistinguishable from this morning's, which is the one thing a glucose chart
 * must never be ambiguous about.
 *
 * Ticks land on **wall-clock** boundaries rather than at even fractions of the
 * window, so they read as times a person recognises: 06:00, midnight, the 19th.
 * An axis labelled "‑4h 37m" is arithmetic, not a clock.
 *
 * The boundaries are resolved through a [ZoneId] rather than by epoch arithmetic
 * for the same reason the hourly rollups store a local hour: an offset can be a
 * half hour or a quarter, and dividing epoch millis by an hour puts the ticks in
 * the wrong place wherever that is true.
 */
object TimeAxis {

    /**
     * Candidate spacings under a day, in minutes, all of which divide a day evenly
     * — so a tick sequence started from midnight lands on midnight again, and the
     * day boundary is never straddled by two labels an hour apart.
     */
    private val STEP_MINUTES = listOf(5L, 10L, 15L, 30L, 60L, 120L, 180L, 360L, 720L)

    /** And above a day, in days. The main chart stops at a week; Trends does not. */
    private val STEP_DAYS = listOf(1L, 2L, 7L, 14L, 30L)

    /**
     * Roughly how many gaps between labels a phone's width holds.
     *
     * Deliberately few. The axis is a reference, and one dense enough to read like
     * a ruler competes with the trace it exists to date. Counted in intervals
     * rather than labels because that is what the spacing is chosen against, so the
     * axis can carry one more label than this.
     */
    const val MAX_TICKS = 5

    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
    private val DAY = DateTimeFormatter.ofPattern("d MMM")

    /** Safety rail: a caller asking for a decade should get a coarse axis, not a hang. */
    private const val TICK_CAP = 200

    fun ticks(
        startMillis: Long,
        endMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        maxTicks: Int = MAX_TICKS,
    ): List<TimeTick> {
        val span = endMillis - startMillis
        if (span <= 0 || maxTicks <= 0) return emptyList()

        val start = Instant.ofEpochMilli(startMillis).atZone(zone)
        // Midnight of the day the window opens on: every sequence is counted from
        // there, which is what keeps a tick on the day boundary itself.
        val midnight = start.truncatedTo(ChronoUnit.DAYS)

        val minutes = STEP_MINUTES.firstOrNull { span <= it * 60_000L * maxTicks }
        val cursor = if (minutes != null) {
            // Elapsed real minutes, floored to the step. Across a DST change this
            // keeps the ticks evenly spaced rather than keeping their wall clock,
            // which is the right trade for an axis read against a trace.
            val since = Duration.between(midnight, start).toMinutes()
            midnight.plusMinutes(since / minutes * minutes)
        } else {
            midnight
        }
        val days = if (minutes != null) 0L else {
            STEP_DAYS.firstOrNull { span <= it * DAY_MILLIS * maxTicks } ?: STEP_DAYS.last()
        }

        val out = mutableListOf<TimeTick>()
        var at = cursor
        while (out.size < TICK_CAP) {
            val millis = at.toInstant().toEpochMilli()
            if (millis > endMillis) break
            if (millis >= startMillis) out += at.toTick()
            at = if (minutes != null) at.plusMinutes(minutes) else at.plusDays(days)
        }
        return out
    }

    /**
     * Midnight is labelled with its date rather than "00:00".
     *
     * A row of clock times with one of them reading 00:00 says the window crossed
     * a day without saying which day it arrived at, and which day it is is the
     * whole reason this axis exists.
     */
    private fun ZonedDateTime.toTick(): TimeTick {
        val startsDay = hour == 0 && minute == 0
        return TimeTick(
            atMillis = toInstant().toEpochMilli(),
            label = format(if (startsDay) DAY else CLOCK),
            startsDay = startsDay,
        )
    }

    private const val DAY_MILLIS = 24L * 60 * 60 * 1000
}
