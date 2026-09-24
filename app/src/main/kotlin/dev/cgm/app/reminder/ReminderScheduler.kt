package dev.cgm.app.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import dev.cgm.core.InsulinReminder
import dev.cgm.core.InsulinReminders
import dev.cgm.core.ReminderSchedule

/**
 * Puts reminders on the system clock.
 *
 * `AlarmManager` rather than a coroutine `delay`, because a reminder has to fire
 * at a wall-clock time hours away, on a device that will be asleep, possibly
 * after the process has been killed. A timer inside the app answers none of that.
 *
 * One alarm is scheduled per reminder — its *next* firing only. Recurring alarms
 * exist, but they repeat on a fixed interval, and a reminder set for 08:00 is not
 * an interval: across a daylight-saving boundary the gap is 23 hours or 25, so a
 * fixed period drifts it by one. Each firing schedules the next instead.
 */
class ReminderScheduler(private val context: Context) {

    private val alarms = context.getSystemService(AlarmManager::class.java)

    /**
     * Whether the system will honour an exact time.
     *
     * From Android 12 this is a grant the user makes, and without it alarms are
     * batched — which for a reminder can mean tens of minutes late. The UI says so
     * rather than letting it be discovered.
     */
    fun canScheduleExactly(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    fun exactAlarmSettingsIntent(): Intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .setData(android.net.Uri.parse("package:${context.packageName}"))

    /** Cancel everything and lay down the next firing of each enabled reminder. */
    fun rescheduleAll(reminders: InsulinReminders, nowMillis: Long = System.currentTimeMillis()) {
        reminders.reminders.forEach { cancel(it) }
        reminders.enabled().forEach { schedule(it, nowMillis) }
    }

    fun schedule(reminder: InsulinReminder, nowMillis: Long = System.currentTimeMillis()) {
        val at = ReminderSchedule.nextOccurrence(reminder, nowMillis) ?: return
        val intent = pendingIntent(reminder, mutable = false)

        if (canScheduleExactly()) {
            // AllowWhileIdle so Doze does not defer it to the next maintenance
            // window, which overnight can be hours.
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        } else {
            // Late is better than never, and the UI has already said it may be late.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        }
    }

    fun cancel(reminder: InsulinReminder) {
        alarms.cancel(pendingIntent(reminder, mutable = false))
    }

    private fun pendingIntent(reminder: InsulinReminder, mutable: Boolean): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_FIRE)
            // In the data, not an extra: PendingIntents are matched ignoring
            // extras, so two reminders sharing an action would collapse into one.
            .setData(android.net.Uri.parse("cgm://reminder/${reminder.id}"))
            .putExtra(ReminderReceiver.EXTRA_ID, reminder.id)

        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
