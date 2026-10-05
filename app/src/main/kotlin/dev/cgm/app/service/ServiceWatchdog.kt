package dev.cgm.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

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
 * Everything happens inside `onReceive`, synchronously, and that is the point.
 * The first version read the stored settings on a background coroutine first,
 * which spent the only window in which an alarm is permitted to start a
 * foreground service — and then the start was refused. The decision now costs one
 * synchronous preference read and one static flag.
 *
 * Three outcomes: nothing is wrong and nothing happens; the process is gone and
 * the service is started; the service is alive but has missed its own polling
 * deadline by five minutes, and is asked to rebuild its loop in place.
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CHECK) return

        // Always first: if anything below throws, the chain must not end here, or
        // one bad check disables the watchdog for good.
        ServiceWatchdog.schedule(context)

        // Nothing to watch over until the user has asked for a poller. Read
        // synchronously so the rest of this runs while a foreground start is
        // still allowed.
        if (!PollingService.isEnabled(context)) return

        runCatching {
            when {
                !PollingService.running.value -> {
                    Log.w(TAG, "poller is not running; starting it")
                    PollingService.start(context)
                }

                PollingService.isStalled() -> {
                    // Asked to rebuild its loop, never stopped. Stopping would
                    // drop it out of the foreground, and the start that followed
                    // would come from the background, where it is refused.
                    Log.w(TAG, "poller has missed its deadline; rebuilding the loop")
                    PollingService.kick(context)
                }
            }
        }.onFailure { Log.e(TAG, "watchdog check failed", it) }
    }

    companion object {
        const val ACTION_CHECK = "dev.cgm.app.action.WATCHDOG_CHECK"
        private const val TAG = "WatchdogReceiver"
    }
}
