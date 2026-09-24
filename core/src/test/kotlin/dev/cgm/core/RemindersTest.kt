package dev.cgm.core

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ReminderScheduleTest {

    private val madrid = ZoneId.of("Europe/Madrid")

    private fun at(text: String, zone: ZoneId = madrid): Long =
        LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private fun readable(millis: Long, zone: ZoneId = madrid): String =
        java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime().toString()

    private fun reminder(
        hour: Int,
        minute: Int = 0,
        days: Set<Int> = emptySet(),
        enabled: Boolean = true,
    ) = InsulinReminder(
        id = "r",
        kind = InsulinKind.BASAL,
        minuteOfDay = InsulinReminder.minuteOfDay(hour, minute),
        daysOfWeek = days,
        enabled = enabled,
    )

    @Test
    fun `fires later the same day when the time has not passed`() {
        val next = ReminderSchedule.nextOccurrence(reminder(22), at("2026-09-24T08:00"), madrid)!!
        assertEquals("2026-09-24T22:00", readable(next))
    }

    @Test
    fun `rolls to tomorrow when today's time has gone`() {
        val next = ReminderSchedule.nextOccurrence(reminder(8), at("2026-09-24T09:00"), madrid)!!
        assertEquals("2026-09-25T08:00", readable(next))
    }

    @Test
    fun `the exact minute counts as gone, not as now`() {
        // Otherwise a reminder that has just fired reschedules onto itself and
        // fires again immediately.
        val next = ReminderSchedule.nextOccurrence(reminder(8), at("2026-09-24T08:00"), madrid)!!
        assertEquals("2026-09-25T08:00", readable(next))
    }

    @Test
    fun `honours selected days of the week`() {
        // Thursday 2026-09-24; reminder only on Monday.
        val monday = setOf(DayOfWeek.MONDAY.value)
        val next = ReminderSchedule.nextOccurrence(
            reminder(9, days = monday), at("2026-09-24T12:00"), madrid,
        )!!
        assertEquals("2026-09-28T09:00", readable(next))
    }

    @Test
    fun `a weekday-only reminder still finds today when the time is ahead`() {
        val thursday = setOf(DayOfWeek.THURSDAY.value)
        val next = ReminderSchedule.nextOccurrence(
            reminder(23, days = thursday), at("2026-09-24T12:00"), madrid,
        )!!
        assertEquals("2026-09-24T23:00", readable(next))
    }

    @Test
    fun `a single-day reminder wraps a full week`() {
        val thursday = setOf(DayOfWeek.THURSDAY.value)
        // Already past Thursday's time, so the next one is a week out.
        val next = ReminderSchedule.nextOccurrence(
            reminder(9, days = thursday), at("2026-09-24T12:00"), madrid,
        )!!
        assertEquals("2026-10-01T09:00", readable(next))
    }

    @Test
    fun `keeps wall-clock time across a daylight saving change`() {
        // Europe/Madrid turns the clocks back on 2026-10-25. A reminder set for
        // 08:00 means eight as the clock reads it, on both sides of that.
        val before = at("2026-10-24T09:00")
        val next = ReminderSchedule.nextOccurrence(reminder(8), before, madrid)!!
        assertEquals("2026-10-25T08:00", readable(next))

        // And the gap is not a flat 24 hours, which is the whole point.
        val gap = next - at("2026-10-24T08:00")
        assertTrue(gap != 24 * 60 * 60 * 1000L, "gap was exactly 24h across a DST boundary")
    }

    @Test
    fun `a disabled reminder never fires`() {
        assertNull(
            ReminderSchedule.nextOccurrence(reminder(8, enabled = false), at("2026-09-24T07:00"))
        )
    }

    @Test
    fun `an empty day set means every day`() {
        assertTrue(reminder(8).isDaily)
        DayOfWeek.entries.forEach { assertTrue(reminder(8).firesOn(it)) }
    }

    @Test
    fun `upcoming orders by when they fire, not by when they were added`() {
        val evening = reminder(22).copy(id = "evening")
        val morning = reminder(8).copy(id = "morning")
        val reminders = InsulinReminders(listOf(evening, morning))

        val order = ReminderSchedule
            .upcoming(reminders, at("2026-09-24T06:00"), madrid)
            .map { it.first.id }
        assertEquals(listOf("morning", "evening"), order)
    }

    @Test
    fun `upcoming leaves out the disabled ones`() {
        val reminders = InsulinReminders(
            listOf(reminder(8).copy(id = "on"), reminder(9, enabled = false).copy(id = "off"))
        )
        val upcoming = ReminderSchedule.upcoming(reminders, at("2026-09-24T06:00"), madrid)
        assertEquals(listOf("on"), upcoming.map { it.first.id })
    }
}

class InsulinRemindersTest {

    private fun reminder(id: String, minute: Int) = InsulinReminder(
        id = id,
        kind = InsulinKind.BOLUS,
        minuteOfDay = minute,
    )

    @Test
    fun `adding replaces an existing reminder rather than duplicating it`() {
        val first = reminder("a", 480)
        val edited = first.copy(minuteOfDay = 540)
        val result = InsulinReminders.Empty.with(first).with(edited)

        assertEquals(1, result.reminders.size)
        assertEquals(540, result.reminders.single().minuteOfDay)
    }

    @Test
    fun `reminders are kept in time order`() {
        val result = InsulinReminders.Empty
            .with(reminder("late", 1320))
            .with(reminder("early", 420))
        assertEquals(listOf("early", "late"), result.reminders.map { it.id })
    }

    @Test
    fun `removing one leaves the rest`() {
        val result = InsulinReminders.Empty
            .with(reminder("a", 420))
            .with(reminder("b", 480))
            .without("a")
        assertEquals(listOf("b"), result.reminders.map { it.id })
    }
}
