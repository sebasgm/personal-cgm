package dev.cgm.app.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.cgm.app.CgmApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fires one reminder, then books the next.
 *
 * Rescheduling from here rather than from a repeating alarm is what keeps a
 * reminder on wall-clock time: each firing works out when the next one is in the
 * current zone, so a daylight-saving change or a flight moves it correctly
 * instead of drifting it by an hour.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as CgmApplication
        val pending = goAsync()

        CoroutineScope(Dispatchers.Default).launch {
            try {
                val reminders = app.settings.remindersOnce()

                when (intent.action) {
                    ACTION_FIRE -> {
                        val id = intent.getStringExtra(EXTRA_ID)
                        val reminder = reminders.reminders.firstOrNull { it.id == id }
                        if (reminder != null && reminder.enabled) {
                            ReminderNotifier(context).show(reminder)
                            // Book the next one before anything else can fail.
                            ReminderScheduler(context).schedule(reminder)
                        }
                    }

                    // A reboot clears every scheduled alarm, so they are all laid
                    // down again. Without this a reminder silently stops existing
                    // the first time the phone restarts.
                    else -> ReminderScheduler(context).rescheduleAll(reminders)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "dev.cgm.app.REMINDER_FIRE"
        const val EXTRA_ID = "reminder_id"
    }
}
