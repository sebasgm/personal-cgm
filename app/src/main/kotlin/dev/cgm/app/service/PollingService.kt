package dev.cgm.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.cgm.app.CgmApplication
import dev.cgm.app.Locales
import dev.cgm.app.R
import dev.cgm.app.alarm.AlarmNotifier
import dev.cgm.app.data.GlucoseRepository
import dev.cgm.app.data.SecureSettings
import dev.cgm.core.AlarmEngine
import dev.cgm.app.ui.MainActivity
import dev.cgm.core.Freshness
import dev.cgm.core.PollOutcome
import dev.cgm.core.PollScheduler
import dev.cgm.core.StatusIconLabel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the current reading current.
 *
 * A foreground service rather than WorkManager because the requirement is a value
 * that is at most a minute or two old; WorkManager's minimum periodic interval is
 * fifteen minutes and it is free to defer that.
 */
class PollingService : LifecycleService() {

    private lateinit var repository: GlucoseRepository
    private lateinit var settings: SecureSettings
    private lateinit var notifier: AlarmNotifier
    private val scheduler = PollScheduler()

    /**
     * Keeps the CPU running between polls.
     *
     * A foreground service guarantees the process survives; it guarantees nothing
     * about the CPU. `delay()` is backed by an ordinary timer, and when the device
     * enters deep sleep - screen off, idle, which is most of a night - that timer
     * does not fire until something else wakes the device. The loop then overshoots
     * by minutes at a time and every minute missed is a reading lost for good: the
     * graph endpoint only backfills at about fifteen-minute spacing, so a gap can
     * never be refilled at the resolution we poll at.
     *
     * The cost is honest - this is why the app has to ask to be left out of battery
     * optimisation, and why it uses more power than an app that may sleep.
     */
    private var wakeLock: PowerManager.WakeLock? = null
    private val alarms = AlarmEngine()
    private var loop: Job? = null
    private lateinit var strings: Context

    override fun onCreate() {
        super.onCreate()
        val app = application as CgmApplication
        repository = app.repository
        settings = app.settings
        notifier = AlarmNotifier(this)
        // Notification text must follow the app's chosen language, not the device's.
        strings = Locales.wrap(this)
        _running.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        startForegroundCompat(buildNotification(strings.getString(R.string.notif_starting), null, StatusIconLabel.NO_DATA))
        if (loop?.isActive != true) {
            loop = lifecycleScope.launch { pollLoop() }
        }
        // START_STICKY: if we are killed for memory, come back. The reading is the
        // whole point of the app being installed.
        return START_STICKY
    }

    private suspend fun pollLoop() {
        var consecutiveFailures = 0

        // Bring the hourly summaries in line with the readings table before the
        // first poll. A no-op once they agree; the backfill path after the
        // migration that created the table empty.
        runCatching { repository.ensureRollups() }

        // Before the first fetch: the status bar shows the last known value rather
        // than "--", which after a restart could otherwise persist for as long as
        // polling keeps failing.
        repository.primeFromStorage()
        updateNotification()

        while (lifecycleScope.isActive) {
            val startedAt = System.currentTimeMillis()
            val outcome = repository.pollOnce()

            consecutiveFailures =
                if (outcome is PollOutcome.Success) 0 else consecutiveFailures + 1

            updateNotification()
            evaluateAlarms()

            val delayMillis = scheduler.nextDelayMillis(outcome, consecutiveFailures)
            if (delayMillis == null) {
                // Fatal: credentials are wrong or access was revoked. Stay in the
                // foreground showing why, rather than disappearing silently.
                updateNotification()
                break
            }

            // Measured from when this poll *started*, not from when it finished,
            // so a slow request does not push every later poll further out. If a
            // poll overran its own interval the next one runs immediately.
            val elapsed = System.currentTimeMillis() - startedAt
            delay((delayMillis - elapsed).coerceAtLeast(0))
        }
    }

    /**
     * Runs after every poll, including failed ones — signal loss is precisely the
     * case where polling is not succeeding, so skipping this on failure would
     * disable the one alarm that matters most then.
     */
    private suspend fun evaluateAlarms() {
        val configured = settings.alarmSettingsOnce()
        notifier.ensureChannels(configured)

        val state = repository.state.value
        val now = System.currentTimeMillis()

        val decision = alarms.evaluate(
            snapshot = state.snapshot,
            freshness = state.freshness(now),
            settings = configured,
            previous = settings.alarmState(),
            nowMillis = now,
        )

        decision.cleared.forEach(notifier::clear)
        decision.firing.forEach { kind ->
            notifier.notify(
                kind = kind,
                setting = configured[kind],
                snapshot = state.snapshot,
                makeSound = kind in decision.sound,
            )
        }
        settings.saveAlarmState(decision.state)
    }

    private fun updateNotification() {
        val state = repository.state.value
        val snapshot = state.snapshot
        val now = System.currentTimeMillis()

        val title = when {
            snapshot == null -> strings.getString(R.string.notif_no_reading_yet)
            else -> buildString {
                append(snapshot.formattedValue())
                append(' ')
                append(snapshot.unit.suffix)
                append("  ")
                append(snapshot.reading.trend.glyph)
                snapshot.formattedDelta()?.let { append("  ").append(it) }
            }
        }

        val detail = when {
            state.error?.needsUser == true -> strings.getString(state.error.messageRes)
            snapshot == null -> state.error?.let { strings.getString(it.messageRes) }
                ?: strings.getString(R.string.notif_waiting_first)
            else -> {
                val minutes = snapshot.reading.ageMillis(now) / 60_000
                val age = if (minutes < 1) {
                    strings.getString(R.string.notif_just_now)
                } else {
                    strings.getString(R.string.notif_minutes_ago, minutes)
                }
                when (state.freshness(now)) {
                    Freshness.FRESH -> age
                    Freshness.AGING -> strings.getString(R.string.notif_later_than_expected, age)
                    Freshness.STALE -> strings.getString(R.string.notif_stale, age)
                }
            }
        }

        notificationManager().notify(
            NOTIFICATION_ID,
            buildNotification(
                title = title,
                detail = detail,
                // The status bar gets the bare number; the title above keeps the
                // unit, arrow and delta for anyone who opens the shade.
                label = StatusIconLabel.of(snapshot, state.freshness(now)),
            ),
        )
    }

    private fun notificationManager() =
        getSystemService(android.app.NotificationManager::class.java)

    private fun buildNotification(title: String, detail: String?, label: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CgmApplication.CHANNEL_STATUS)
            .setContentTitle(title)
            .setContentText(detail)
            .setSmallIcon(StatusBarIcon.of(label))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
    }

    override fun onDestroy() {
        loop?.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        _running.value = false
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TAG = "personal-cgm:polling"

        private val _running = MutableStateFlow(false)

        /**
         * Whether the poller is alive.
         *
         * Settings shows this because its absence is otherwise invisible: no service
         * means no ongoing notification, which means no value in the status bar and
         * nothing at all to explain why. Static state, so it dies with the process,
         * which is exactly right — a killed process has no poller either.
         */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        fun start(context: Context) {
            context.startForegroundService(Intent(context, PollingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PollingService::class.java))
        }
    }
}
