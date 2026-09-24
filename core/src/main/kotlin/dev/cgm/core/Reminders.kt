package dev.cgm.core

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A standing reminder to take insulin.
 *
 * Distinct from an alarm, and deliberately so: an alarm fires because of a
 * reading, a reminder fires because of a clock. They share notification
 * machinery and nothing else — a reminder has no thresholds, no trend, and no
 * opinion about whether it is needed.
 *
 * [units] is a suggestion carried into the logging sheet, not a prescription. The
 * app records what someone did; it does not tell them what to do.
 */
@Serializable
data class InsulinReminder(
    val id: String,
    val kind: InsulinKind,
    /** Local time of day, as minutes since midnight. */
    val minuteOfDay: Int,
    /**
     * Days it fires on. Empty means every day.
     *
     * Stored as ISO day numbers (Monday = 1) rather than an enum set, because
     * this crosses a serialisation boundary and enum names are a worse contract
     * than numbers that will never change.
     */
    val daysOfWeek: Set<Int> = emptySet(),
    val units: Double? = null,
    val label: String? = null,
    val enabled: Boolean = true,
) {
    val localTime: LocalTime get() = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)

    val isDaily: Boolean get() = daysOfWeek.isEmpty() || daysOfWeek.size == 7

    fun firesOn(day: DayOfWeek): Boolean =
        daysOfWeek.isEmpty() || day.value in daysOfWeek

    companion object {
        fun minuteOfDay(hour: Int, minute: Int): Int = hour * 60 + minute
    }
}

@Serializable
data class InsulinReminders(val reminders: List<InsulinReminder> = emptyList()) {

    fun enabled(): List<InsulinReminder> = reminders.filter { it.enabled }

    fun with(reminder: InsulinReminder): InsulinReminders {
        val without = reminders.filterNot { it.id == reminder.id }
        return copy(reminders = (without + reminder).sortedBy { it.minuteOfDay })
    }

    fun without(id: String): InsulinReminders =
        copy(reminders = reminders.filterNot { it.id == id })

    companion object {
        val Empty = InsulinReminders()
    }
}

object ReminderSchedule {

    /**
     * When a reminder next fires, strictly after [afterMillis].
     *
     * Computed in wall-clock terms rather than by adding twenty-four hours,
     * because those are different things twice a year. A reminder set for 08:00
     * means eight o'clock as the clock reads it, so across a daylight-saving
     * boundary the gap is twenty-three hours or twenty-five, and adding a fixed
     * day would drift it by one.
     *
     * @return epoch millis, or null if the reminder is disabled.
     */
    fun nextOccurrence(
        reminder: InsulinReminder,
        afterMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        if (!reminder.enabled) return null

        val from = Instant.ofEpochMilli(afterMillis).atZone(zone)

        // Eight days rather than seven: a reminder that fires only on the current
        // weekday still needs a candidate when today's time has already passed.
        for (dayOffset in 0..7) {
            val candidate = candidateOn(from, dayOffset, reminder, zone)
            if (candidate != null && candidate.toInstant().toEpochMilli() > afterMillis) {
                return candidate.toInstant().toEpochMilli()
            }
        }
        return null
    }

    private fun candidateOn(
        from: ZonedDateTime,
        dayOffset: Int,
        reminder: InsulinReminder,
        zone: ZoneId,
    ): ZonedDateTime? {
        val date = from.toLocalDate().plusDays(dayOffset.toLong())
        if (!reminder.firesOn(date.dayOfWeek)) return null

        // Resolved through the zone rather than assembled from parts, so a time
        // that does not exist on a spring-forward morning lands on a real instant
        // instead of throwing or silently shifting a day.
        return date.atTime(reminder.localTime).atZone(zone)
    }

    /** Every enabled reminder's next firing, soonest first. */
    fun upcoming(
        reminders: InsulinReminders,
        afterMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Pair<InsulinReminder, Long>> =
        reminders.enabled()
            .mapNotNull { r -> nextOccurrence(r, afterMillis, zone)?.let { r to it } }
            .sortedBy { it.second }
}
