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
        const val EXTRA_KIND = "kind"
        const val SNOOZE_MILLIS = 30L * 60 * 1000
    }
}
