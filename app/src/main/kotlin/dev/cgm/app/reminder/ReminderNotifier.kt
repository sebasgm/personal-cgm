package dev.cgm.app.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.cgm.app.R
import dev.cgm.app.ui.MainActivity
import dev.cgm.core.InsulinKind
import dev.cgm.core.InsulinReminder

/**
 * Shows a reminder, on the phone and on a paired watch.
 *
 * Deliberately *not* `setLocalOnly`, unlike the ongoing reading. The reading is
 * posted on the watch by the watch itself, so bridging it would duplicate it;
 * a reminder has no watch-side source, and bridging is how it gets to a wrist
 * without a second delivery path to keep working.
 *
 * Its own channel, separate from alarms. A reminder is a routine prompt and
 * should be silenceable without touching anything that fires on a low.
 */
class ReminderNotifier(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)

    fun ensureChannel() {
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.reminder_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.reminder_channel_desc)
                setShowBadge(true)
            }
        )
    }

    fun show(reminder: InsulinReminder) {
        ensureChannel()

        val open = PendingIntent.getActivity(
            context,
            reminder.id.hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        manager.notify(
            reminder.id.hashCode(),
            NotificationCompat.Builder(context, CHANNEL)
                .setContentTitle(title(reminder))
                .setContentText(body(reminder))
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(open)
                // Dismissible: a reminder that cannot be cleared becomes furniture,
                // and furniture is not read.
                .setAutoCancel(true)
                .build()
        )
    }

    private fun title(reminder: InsulinReminder): String =
        reminder.label?.takeIf { it.isNotBlank() }
            ?: context.getString(
                when (reminder.kind) {
                    InsulinKind.BASAL -> R.string.reminder_title_basal
                    InsulinKind.BOLUS -> R.string.reminder_title_bolus
                }
            )

    /**
     * The suggested dose, when one was set.
     *
     * Phrased as what was configured rather than as an instruction. The app
     * records what someone did; it does not tell them what to take.
     */
    private fun body(reminder: InsulinReminder): String {
        val units = reminder.units ?: return context.getString(R.string.reminder_body_plain)
        val formatted = if (units % 1.0 == 0.0) units.toInt().toString() else "%.1f".format(units)
        return context.getString(R.string.reminder_body_units, formatted)
    }

    companion object {
        const val CHANNEL = "reminders"
    }
}
