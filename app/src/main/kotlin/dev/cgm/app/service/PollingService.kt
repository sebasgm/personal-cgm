package dev.cgm.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.cgm.app.CgmApplication
import dev.cgm.app.data.xdrip.XdripReceiver
import dev.cgm.core.SourceKind
import dev.cgm.app.Locales
import dev.cgm.app.R
import dev.cgm.app.alarm.AlarmNotifier
import dev.cgm.app.alarm.CarAlertNotifier
import dev.cgm.app.data.GlucoseRepository
import dev.cgm.app.data.TimeChangeReceiver
import dev.cgm.app.data.SecureSettings
import dev.cgm.core.AlarmEngine
import dev.cgm.app.ui.MainActivity
import dev.cgm.core.Freshness
import dev.cgm.core.PollOutcome
import dev.cgm.core.ReadingFont
import dev.cgm.core.PollScheduler
import dev.cgm.core.WatchPayload
import dev.cgm.core.StatusIconLabel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
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
    private lateinit var watch: WatchBridge
    private val relay = RelayPublisher()

    /**
     * The typeface the reader chose, kept to hand for the status bar icon.
     *
     * Followed rather than fetched per notification: this is rebuilt on every
     * poll, and a settings read there would be a disk hit a minute for a value
     * that changes about never.
     */
    @Volatile
    private var readingFont: ReadingFont = ReadingFont.SYSTEM

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
    private var clockReceiver: TimeChangeReceiver? = null
    private var xdripReceiver: XdripReceiver? = null
    private val alarms = AlarmEngine()
    private lateinit var carNotifier: CarAlertNotifier
    private var loop: Job? = null
    private lateinit var strings: Context

    override fun onCreate() {
        super.onCreate()
        val app = application as CgmApplication
        repository = app.repository
        settings = app.settings
        notifier = AlarmNotifier(this)
        carNotifier = CarAlertNotifier(this)
        // Notification text must follow the app's chosen language, not the device's.
        strings = Locales.wrap(this)
        watch = WatchBridge(this)
        _running.value = true

        acquireWakeLock()
        registerClockReceiver()
        publishToWatch()

        lifecycleScope.launch {
            settings.accessibility.collect { readingFont = it.font }
        }

        // Follows the setting rather than being registered once, so switching
        // source takes effect without restarting the service — and so a listener
        // for an app the user is no longer using does not stay registered.
        lifecycleScope.launch {
            settings.sourceKind.collect { kind ->
                if (kind == SourceKind.XDRIP) registerXdrip() else unregisterXdrip()
            }
        }
    }

    /**
     * Keeps the CPU running between polls.
     *
     * A foreground service guarantees the process survives; it guarantees nothing
     * about the CPU. `delay()` is an ordinary timer, and in deep sleep it does not
     * fire until something else wakes the device — so the loop overshoots by
     * minutes at a time, and every minute missed is a reading lost for good.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    /**
     * Starts listening for xDrip+ readings.
     *
     * Here rather than in the manifest because an implicit broadcast cannot reach
     * a manifest-declared receiver on API 26 and above, while a receiver
     * registered by a running process is exempt — and this service is the part of
     * the app guaranteed to be running. See [XdripReceiver].
     */
    private fun registerXdrip() {
        if (xdripReceiver != null) return
        val receiver = XdripReceiver(
            repository = repository,
            scope = lifecycleScope,
            onSourceSeen = { description -> lastXdripSource = description },
        )
        runCatching { XdripReceiver.register(this, receiver) }
            .onSuccess { xdripReceiver = receiver }
    }

    private fun unregisterXdrip() {
        xdripReceiver?.let { runCatching { unregisterReceiver(it) } }
        xdripReceiver = null
    }

    /**
     * Notices the clock or the zone moving.
     *
     * Freshness, staleness and signal loss are all measured against "now". When
     * the device's idea of now jumps they are all wrong until something
     * recomputes them, and waiting for the next poll means up to a minute of a
     * screen confidently stating something untrue.
     *
     * Registered through [ContextCompat] because a target of 34 or above must
     * declare whether a runtime receiver is exported, and getting that wrong is a
     * SecurityException thrown from service startup.
     */
    private fun registerClockReceiver() {
        if (clockReceiver != null) return
        val receiver = TimeChangeReceiver {
            lifecycleScope.launch {
                updateNotification()
                evaluateAlarms()
            }
        }
        ContextCompat.registerReceiver(
            this,
            receiver,
            TimeChangeReceiver.filter(),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        clockReceiver = receiver
    }

    /**
     * Sends every reading on to a paired watch.
     *
     * In its own coroutine with its own failure handling: a watch that is absent,
     * unpaired or unreachable is the normal case, and it must never be able to
     * take the poll loop down with it.
     */
    private fun publishToWatch() {
        lifecycleScope.launch {
            repository.results.collect { result ->
                runCatching {
                    val now = System.currentTimeMillis()
                    val doses = repository
                        .dosesBetween(now - WatchPayload.DOSE_WINDOW_MILLIS, now)
                        .first()
                    val payload = watch.publish(result, doses)

                    // Same payload onward to the relay, when one is configured.
                    // Separately guarded: a relay that is unreachable must not be
                    // able to stop the watch being fed.
                    payload?.let { runCatching { relay.publish(settings.relayConfigOnce(), it) } }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        startForegroundCompat(buildNotification(strings.getString(R.string.notif_starting), null, StatusIconLabel.NO_DATA))

        // A kick replaces a loop that is wedged. Checked against the loop's own
        // deadline rather than taken on trust, so a watchdog firing while the loop
        // is simply asleep cannot interrupt a wait it is entitled to.
        if (intent?.action == ACTION_KICK && isStalled()) {
            Log.w(TAG, "rebuilding a stalled poll loop")
            loop?.cancel()
            loop = null
        }

        if (loop?.isActive != true) {
            // Off the main thread. `lifecycleScope` is confined to it, so the poll
            // loop was sharing a thread with every frame the UI draws: anything
            // slow on either side delayed the other, and a main thread wedged for
            // any reason took the readings down with it. Nothing in the loop needs
            // to be there — notifications, the status-bar bitmap and the alarm
            // engine are all safe off it.
            loop = lifecycleScope.launch(Dispatchers.Default) { superviseLoop() }
        }
        // START_STICKY: if we are killed for memory, come back. The reading is the
        // whole point of the app being installed.
        return START_STICKY
    }

    /**
     * Keeps [pollLoop] alive for as long as the service is.
     *
     * The loop used to be launched directly, which made one unhandled exception
     * anywhere inside it — a notification the system refused, a DataStore read
     * that failed, an OEM throwing from Do Not Disturb — the permanent end of
     * polling. The service stayed in the foreground with a frozen number in the
     * status bar, which is the worst possible failure: it looks like it is working.
     *
     * Nothing is retried blindly. Each iteration already guards its own side
     * effects; this is the backstop for whatever those guards do not cover, and it
     * logs loudly because a loop that keeps restarting is a bug to find, not a
     * condition to live with.
     */
    private suspend fun superviseLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                pollLoop()
                // A clean return means the loop decided to stop. Nothing does that
                // any more, so treat it as a fault too rather than exiting quietly.
                Log.w(TAG, "poll loop returned; restarting")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "poll loop died; restarting", e)
            }
            loopAliveSinceMillis = 0L
            nextPollDueAtMillis = 0L
            delay(LOOP_RESTART_DELAY_MILLIS)
        }
    }

    private suspend fun pollLoop() {
        var consecutiveFailures = 0

        // Bring the hourly summaries in line with the readings table before the
        // first poll. A no-op once they agree; the backfill path after the
        // migration that created the table empty.
        runCatching { repository.ensureRollups() }
        // Housekeeping beside the rollups: photographs expire long before the
        // readings do, and the meals they belonged to are kept either way.
        runCatching { repository.pruneMealPhotos() }

        // Before the first fetch: the status bar shows the last known value rather
        // than "--", which after a restart could otherwise persist for as long as
        // polling keeps failing.
        repository.primeFromStorage()
        updateNotification()

        while (currentCoroutineContext().isActive) {
            val startedAt = System.currentTimeMillis()
            _lastPollAt.value = startedAt
            if (loopAliveSinceMillis == 0L) loopAliveSinceMillis = startedAt

            val outcome = repository.pollOnce()

            consecutiveFailures =
                if (outcome is PollOutcome.Success) 0 else consecutiveFailures + 1

            // Guarded individually. Neither is the point of the loop, and a
            // notification the system refuses or an alarm channel an OEM objects
            // to must not be able to stop readings being fetched and stored.
            runCatching { updateNotification() }
                .onFailure { Log.e(TAG, "could not update the notification", it) }
            runCatching { evaluateAlarms() }
                .onFailure { Log.e(TAG, "could not evaluate alarms", it) }

            val delayMillis = scheduler.nextDelayMillis(outcome, consecutiveFailures)
                // Fatal used to break out of the loop for good. It means "needs the
                // user" — a wrong password, revoked access — and staying in the
                // foreground to say so was right, but stopping forever was not: a
                // single transient 401 then ended the session until someone noticed
                // and pressed Start. Retrying slowly costs almost nothing and
                // recovers by itself, and the notification still says what is wrong.
                ?: FATAL_RETRY_MILLIS

            // Measured from when this poll *started*, not from when it finished,
            // so a slow request does not push every later poll further out. If a
            // poll overran its own interval the next one runs immediately.
            val elapsed = System.currentTimeMillis() - startedAt
            val wait = (delayMillis - elapsed).coerceAtLeast(0)
            nextPollDueAtMillis = System.currentTimeMillis() + wait
            delay(wait)
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

        // Read once per evaluation rather than held in a field: the toggle can
        // change while the service runs, and a car alert that keeps firing after
        // it was switched off is the kind of thing nobody thinks to look for.
        val carAlerts = settings.carAlertsEnabledOnce()

        decision.cleared.forEach {
            notifier.clear(it)
            // Cleared unconditionally, not only when the toggle is on. Switching
            // the setting off between firing and clearing would otherwise leave
            // the car's copy on screen with no way to take it down.
            carNotifier.clear(it)
        }
        decision.firing.forEach { kind ->
            notifier.notify(
                kind = kind,
                setting = configured[kind],
                snapshot = state.snapshot,
                makeSound = kind in decision.sound,
                font = readingFont,
            )
            if (carAlerts) {
                // Only when it would make a sound on the phone. The alarm engine
                // re-notifies silently to keep a condition visible, and the car
                // reads every message it is given aloud — so without this gate a
                // persistent low would be announced on every poll.
                if (kind in decision.sound) {
                    val wording = notifier.wording(kind, configured[kind], state.snapshot)
                    carNotifier.show(
                        kind = kind,
                        snapshot = state.snapshot,
                        title = wording.title,
                        body = wording.body,
                        font = readingFont,
                    )
                }
            }
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
            .setSmallIcon(StatusBarIcon.of(this, label, readingFont))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // Deliberately ungrouped. This used to be a group summary with alarms
            // joining it as children, to spend one status bar slot instead of two.
            // It did the opposite of what it claimed: a group holding a summary and
            // a single child is a *suppressed* group, so the platform hid this
            // notification's icon and showed the lone alarm standalone. The alarm's
            // icon is rendered once, when it fires, while this one is what every new
            // reading updates — so for the whole life of an alert the tray sat
            // frozen on the value from the moment it started, and a signal-loss
            // alert left a bare "?" there. Both read as the icon having died.
            //
            // The cost of ungrouping is a second icon while an alert is up. That is
            // the right way round: an extra icon is noise, whereas a stale number is
            // a lie, and this is the one number the app exists to tell the truth
            // about. Nothing here now depends on how the platform chooses to collapse
            // a group, which is the undocumented heuristic that caused the bug.
            // The watch posts its own, which is how tapping it can open something
            // on the watch. Bridging this as well would put two of the same value
            // on the wrist.
            .setLocalOnly(true)
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

    /**
     * Swiping the app out of recents.
     *
     * Several manufacturers treat that as "stop everything this app is doing",
     * foreground service included, and the user almost never means it that way —
     * they meant to tidy a screen. The watchdog is re-armed so the poller comes
     * back even if this process does not survive the next few seconds.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        ServiceWatchdog.schedule(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        loop?.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        clockReceiver?.let { runCatching { unregisterReceiver(it) } }
        clockReceiver = null
        unregisterXdrip()
        _running.value = false
        loopAliveSinceMillis = 0L
        nextPollDueAtMillis = 0L
        _lastPollAt.value = 0L
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TAG = "personal-cgm:polling"
        private const val TAG = "PollingService"

        /** Long enough not to spin on a persistent fault, short enough to recover. */
        private const val LOOP_RESTART_DELAY_MILLIS = 30_000L

        /**
         * How long to wait before retrying something that needs the user.
         *
         * Fifteen minutes of a wrong password costs nothing. The alternative — the
         * old behaviour — was stopping until someone noticed, which for a feed
         * whose whole job is to be there is a worse trade in every case.
         */
        private const val FATAL_RETRY_MILLIS = 15L * 60 * 1000

        /**
         * When the loop started its current run, or 0 while it is not running.
         *
         * Separate from [lastPollAtMillis] so a loop that is alive but wedged
         * inside a single poll is distinguishable from one that is not running at
         * all. Static, so it dies with the process — which is correct, because a
         * new process has no loop either.
         */
        @Volatile
        private var loopAliveSinceMillis = 0L

        /**
         * When the last poll began. The watchdog's evidence that work is happening.
         *
         * A flow as well as a value, because it is also the answer to "is it
         * actually running": a Settings screen showing a check from forty minutes
         * ago says more than any status word could, and says it without the user
         * having to attach a cable.
         */
        private val _lastPollAt = MutableStateFlow(0L)
        val lastPollAt: StateFlow<Long> = _lastPollAt.asStateFlow()

        val lastPollAtMillis: Long get() = _lastPollAt.value

        /**
         * When the loop intends to poll next, or 0 when it is not running.
         *
         * The loop publishes its own deadline rather than the watchdog guessing at
         * one. The guess was wrong: it compared the gap against a fixed twelve
         * minutes while the loop's longest legitimate sleep is fifteen, so every
         * wait for a condition needing the user was read as a stall and
         * "recovered" from. A deadline cannot drift out of step with the scheduler
         * that sets it.
         */
        @Volatile
        private var nextPollDueAtMillis = 0L

        /**
         * Whether the loop has missed its own deadline by a wide margin.
         *
         * Measured against the loop's intent, never against the reading's age: a
         * sensor out of range legitimately produces nothing for hours while the
         * loop keeps running. This asks whether *we* are working.
         */
        fun isStalled(nowMillis: Long = System.currentTimeMillis()): Boolean {
            if (!_running.value) return false
            // Running but never reached its first poll — stuck in start-up.
            val due = nextPollDueAtMillis
            if (due == 0L) return lastPollAtMillis == 0L &&
                loopAliveSinceMillis != 0L &&
                nowMillis - loopAliveSinceMillis > STALL_GRACE_MILLIS
            return nowMillis > due + STALL_GRACE_MILLIS
        }

        /**
         * How far past its own deadline the loop may be before we intervene.
         *
         * Generous on purpose. A poll can be slow, Doze can defer a timer, and the
         * cost of waiting another five minutes is one missed reading, while the
         * cost of intervening wrongly is the whole service — which is exactly the
         * mistake the fixed threshold made.
         */
        private const val STALL_GRACE_MILLIS = 5L * 60 * 1000

        /**
         * What xDrip+ last said it was reading, e.g. "G6 Native".
         *
         * Shown in Settings because it is the only way this app can know which
         * sensor is behind the numbers when they arrive over a broadcast. Static
         * and unpersisted: it describes the running session, and a remembered
         * value would outlive the truth of it.
         */
        @Volatile
        var lastXdripSource: String? = null
            private set

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
            setEnabled(context, true)
            context.startForegroundService(Intent(context, PollingService::class.java))
            // Laid down here rather than only in the service, so a start that is
            // refused — a background start the system rejects — still leaves
            // something scheduled to try again later.
            ServiceWatchdog.schedule(context)
        }

        fun stop(context: Context) {
            // Recorded first, and cancelled before the stop. Otherwise the
            // watchdog would start the service again a quarter of an hour after
            // the user deliberately stopped it, which is a worse bug than the one
            // it exists to fix.
            setEnabled(context, false)
            ServiceWatchdog.cancel(context)
            context.stopService(Intent(context, PollingService::class.java))
        }

        /**
         * Asks a running service to rebuild its loop.
         *
         * Emphatically **not** stop-then-start. That was the first attempt and it
         * made things worse: stopping the service drops it out of the foreground,
         * and the start that follows comes from a background process, where
         * Android 12 and later refuse it. The result was a service that had been
         * killed deliberately and could not come back — a cure strictly worse
         * than the disease.
         *
         * Delivering an action to a service that is already in the foreground is
         * subject to none of that. It keeps the wake lock, the notification and
         * the receivers, and replaces only the thing that was stuck.
         */
        fun kick(context: Context) {
            context.startForegroundService(
                Intent(context, PollingService::class.java).setAction(ACTION_KICK)
            )
        }

        /** Rebuild the poll loop in place. See [kick]. */
        const val ACTION_KICK = "dev.cgm.app.action.KICK_POLLER"

        /**
         * Whether the user wants the poller running, readable without suspending.
         *
         * The watchdog has to decide inside `onReceive`: the permission to start a
         * foreground service from an alarm lasts about as long as the broadcast
         * does, and the first version spent that window on a DataStore read before
         * calling start. A one-key SharedPreferences file is synchronous, so the
         * decision costs nothing and happens while it is still allowed.
         */
        fun isEnabled(context: Context): Boolean =
            flags(context).getBoolean(KEY_ENABLED, false)

        private fun setEnabled(context: Context, enabled: Boolean) {
            flags(context).edit().putBoolean(KEY_ENABLED, enabled).commit()
        }

        private fun flags(context: Context) =
            context.getSharedPreferences(FLAGS_FILE, Context.MODE_PRIVATE)

        private const val FLAGS_FILE = "poller-flags"
        private const val KEY_ENABLED = "enabled"
    }
}
