package dev.cgm.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.cgm.app.CgmApplication
import dev.cgm.core.SourceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Resumes polling after a reboot, if there is a source to read. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val app = context.applicationContext as CgmApplication
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // A pushed source has no credentials to check — readings arrive
                // from another app — so asking only about those would leave an
                // xDrip+ user with no poller after every reboot.
                val configured = app.settings.sourceKindOnce() == SourceKind.XDRIP ||
                    app.settings.credentials() != null
                if (configured) PollingService.start(context)
            } finally {
                pending.finish()
            }
        }
    }
}
