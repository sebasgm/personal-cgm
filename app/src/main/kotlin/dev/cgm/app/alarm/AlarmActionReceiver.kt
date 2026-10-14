package dev.cgm.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.cgm.app.CgmApplication
import dev.cgm.core.AlarmKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Handles the Snooze action on an alarm notification. */
class AlarmActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // The car's copy of an alert carries a reply and a mark-as-read action
        // only because Android Auto refuses to surface a notification without
        // them — see CarAlertNotifier. Neither does anything but take that copy
        // down: there is nothing to reply to, and the condition is not resolved
        // by acknowledging it, so the phone's own alarm is deliberately left up.
        if (intent.action == ACTION_CAR_REPLY || intent.action == ACTION_CAR_MARK_READ) {
            intent.getStringExtra(EXTRA_KIND)
                ?.let { runCatching { AlarmKind.valueOf(it) }.getOrNull() }
                ?.let { CarAlertNotifier(context).clear(it) }
            return
        }

        if (intent.action != ACTION_SNOOZE) return
        val kind = intent.getStringExtra(EXTRA_KIND)
            ?.let { runCatching { AlarmKind.valueOf(it) }.getOrNull() }
            ?: return

        val app = context.applicationContext as CgmApplication
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                app.settings.snoozeAlarm(kind, SNOOZE_MILLIS)
                // Leave the notification up: the condition has not gone away,
                // and silently removing it would imply it had.
                AlarmNotifier(context).notify(
                    kind = kind,
                    setting = app.settings.alarmSettingsOnce()[kind],
                    snapshot = app.repository.state.value.snapshot,
                    makeSound = false,
                )
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_SNOOZE = "dev.cgm.app.SNOOZE"
        const val ACTION_CAR_REPLY = "dev.cgm.app.CAR_REPLY"
        const val ACTION_CAR_MARK_READ = "dev.cgm.app.CAR_MARK_READ"
        const val EXTRA_KIND = "kind"
        const val SNOOZE_MILLIS = 30L * 60 * 1000
    }
}
