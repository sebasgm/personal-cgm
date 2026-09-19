package dev.cgm.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.cgm.app.CgmApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Resumes polling after a reboot, but only if the user ever signed in. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val app = context.applicationContext as CgmApplication
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (app.settings.credentials() != null) {
                    PollingService.start(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
