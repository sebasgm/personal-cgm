package dev.cgm.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dev.cgm.app.CgmApplication
import dev.cgm.core.SourceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Brings the poller back when it has stopped.
 *
 * A foreground service with a wake lock survives Doze and low memory, and it is
 * still not enough. `START_STICKY` is a request, not a guarantee, and several
 * manufacturers' battery managers kill a background process outright and ignore
 * it. When that happens nothing in the app is running to notice: no reading is
 * fetched, no alarm is evaluated, and the number in the status bar is whatever it
 * was at the moment the process died. It looks like a working app showing a value.
 *
 * The only component that can act when the process is gone is the system, so the
 * system holds the reminder. An alarm every quarter of an hour starts the service
 * if it is not running, restarts the loop if it is running but wedged, and lays
 * down the next alarm. Starting a service that is already polling is a no-op.
 *
 * **Exact, deliberately.** From Android 12 a background process may not start a
 * foreground service, and the exemptions are narrow — one of them is acting on an
 * exact alarm. The app already holds `SCHEDULE_EXACT_ALARM` for insulin
 * reminders, so this costs no new permission. Without exactness the watchdog
 * would be deferred to a maintenance window, which overnight is hours, and the
 * start would then be refused anyway.
 */
object ServiceWatchdog {

    /**
     * Quarter-hourly. Far enough apart to be invisible on the battery, close
     * enough that a death is caught before the sensor's own history is lost —
     * nothing backfills, so every minute the poller is down is a permanent hole.
     */
    private const val INTERVAL_MILLIS = 15L * 60 * 1000

    private const val REQUEST_CODE = 7301
    private const val TAG = "ServiceWatchdog"

    /** Lay down the next check. Called on start, on each firing, and on boot. */
    fun schedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val at = System.currentTimeMillis() + INTERVAL_MILLIS
        val intent = pendingIntent(context)

        runCatching {
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarms.canScheduleExactAlarms()
            if (exact) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            } else {
                // Still worth having. It will be batched, and a late check is
                // better than none — though without the grant a background start
                // of the service may also be refused, which is why Settings asks
                // for the exact-alarm permission rather than treating it as
                // cosmetic.
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            }
        }.onFailure { Log.e(TAG, "could not schedule the watchdog", it) }
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, WatchdogReceiver::class.java).setAction(WatchdogReceiver.ACTION_CHECK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/**
 * Decides, every quarter of an hour, whether the poller needs help.
 *
 * Three outcomes: it is fine and nothing happens; it is dead and gets started; it
 * is alive but has not polled in longer than it ever should, and gets stopped and
 * started so the loop is rebuilt. The last case is the one a plain restart would
 * miss, because the service is present and `startForegroundService` on a running
 * service does nothing.
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CHECK) return

        // Always first: if this throws or the app is not configured, the chain
        // must not end here, or one bad check disables the watchdog for good.
        ServiceWatchdog.schedule(context)

        val app = context.applicationContext as? CgmApplication ?: return
        val pending = goAsync()

        CoroutineScope(Dispatchers.Default).launch {
            try {
                // Nothing to watch over until there is a source to read.
                val configured = runCatching {
                    app.settings.sourceKindOnce() == SourceKind.XDRIP ||
                        app.settings.credentials() != null
                }.getOrDefault(false)
                if (!configured) return@launch

                when {
                    !PollingService.running.value -> {
                        Log.w(TAG, "poller is not running; starting it")
                        PollingService.start(context)
                    }

                    PollingService.isStalled() -> {
                        // Restarted rather than nudged. Whatever has the loop
                        // wedged is inside it, and a new service gets a new loop.
                        Log.w(TAG, "poller has stalled; restarting it")
                        PollingService.restart(context)
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "watchdog check failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_CHECK = "dev.cgm.app.action.WATCHDOG_CHECK"
        private const val TAG = "WatchdogReceiver"
    }
}
