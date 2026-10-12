package dev.cgm.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings polling back after the process has been replaced from under it.
 *
 * Two events, for the same reason. A reboot is the obvious one. The other is the
 * app being reinstalled: `MY_PACKAGE_REPLACED` arrives after an update, and
 * without it a sideloaded build left the poller dead until someone noticed and
 * pressed Start — every `adb install` silently stopped the monitor, which during
 * a week of installing builds is most of the week. The service is killed and its
 * pending alarms, the watchdog's included, go with it, so nothing else was left
 * to recover: [ServiceWatchdog] can only fire an alarm that still exists.
 *
 * ## Why the decision is synchronous
 *
 * Both of these broadcasts carry a temporary exemption from the Android 12 rule
 * that a background process may not start a foreground service, and the exemption
 * lasts about as long as the broadcast does. This used to read the stored
 * credentials first — a DataStore read, on a coroutine, through `goAsync()` — and
 * so spent that window on disk before asking for the start. [WatchdogReceiver]
 * had the same bug and the same fix: decide from the one-key SharedPreferences
 * flag, which is synchronous and costs nothing.
 *
 * The flag also makes this agree with the watchdog about who decides. It records
 * whether the *user* wants polling, so a poller they deliberately stopped stays
 * stopped across a reboot, and one they started comes back — which the old
 * credentials check got backwards, resurrecting a service that had been switched
 * off on purpose.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED) return
        if (!PollingService.isEnabled(context)) return
        PollingService.start(context)
    }

    private companion object {
        val HANDLED = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
