package dev.cgm.app.meter

import android.content.Context
import dev.cgm.app.data.GlucoseRepository
import dev.cgm.app.data.SecureSettings
import dev.cgm.app.data.ZoneWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Takes a new result off the meter without being asked.
 *
 * The meter is the one source in this app that cannot be polled: it is asleep almost
 * always, it wakes for a few seconds after a strip, and nothing tells the phone that it
 * has. What makes this possible without scanning — and therefore without the location
 * permission a scan would need — is that Android's Bluetooth controller will hold a
 * *pending* connection to a bonded device and complete it whenever that device next
 * advertises. The wait costs nothing while it waits.
 *
 * So the loop is: arm a connection, and when the meter appears, take whatever is newer
 * than what is already stored and let go of it again. A result pricked at breakfast is
 * in the app by the time the phone is picked up, which is the difference between a
 * feature that gets used and one that needs remembering.
 *
 * It lets go rather than holding the connection open, because the meter is also talking
 * to whatever else the owner has paired — the manufacturer's own app, most likely — and
 * a meter generally entertains one connection at a time. Holding ours open would make
 * this app a thing that stops the other one working.
 */
class MeterWatcher(
    private val context: Context,
    private val repository: GlucoseRepository,
    private val settings: SecureSettings,
) {

    /**
     * Follows the chosen meter for as long as [scope] lives.
     *
     * `collectLatest` rather than `collect`: choosing a different meter must abandon the
     * connection pending on the old one rather than leave two armed, and clearing the
     * choice must stop the loop rather than leave it running against a device the owner
     * has deliberately forgotten.
     */
    fun follow(scope: CoroutineScope) {
        scope.launch {
            settings.meterAddress.collectLatest { address ->
                if (address.isNullOrBlank()) {
                    Status.clear()
                    return@collectLatest
                }

                while (isActive) {
                    val result = MeterSync(context, repository).sync(
                        address = address,
                        zone = ZoneWatcher.zone.value,
                        scope = MeterScope.NEW,
                        // The whole point: wait for the meter rather than demand it.
                        waitForDevice = true,
                    )
                    Status.record(result)

                    // A pause before arming again, for two different reasons depending
                    // on what happened. After a success the meter is awake and about to
                    // sleep, and reconnecting immediately would fight its own shutdown.
                    // After a failure something is wrong that another attempt in one
                    // second will not fix.
                    delay(
                        when (result) {
                            is MeterSyncResult.Synced -> SETTLE_MILLIS
                            else -> BACKOFF_MILLIS
                        }
                    )
                }
            }
        }
    }

    /**
     * What the last automatic read did, for the screen to report.
     *
     * An object rather than a flow on the class, for the same reason [ZoneWatcher] is
     * one: the watcher lives in the service and the screen that reports it does not know
     * the service exists. Nothing here is persisted — it describes this run of the app,
     * and a stale "last read" from three days ago would be read as current.
     */
    object Status {
        private val _last = MutableStateFlow<MeterSyncResult?>(null)

        /** Null until an automatic read has happened at all. */
        val last: StateFlow<MeterSyncResult?> = _last.asStateFlow()

        internal fun record(result: MeterSyncResult) {
            _last.value = result
        }

        internal fun clear() {
            _last.value = null
        }
    }

    private companion object {
        /** Long enough for the meter to finish going to sleep. */
        const val SETTLE_MILLIS = 20_000L

        /** Bluetooth off, meter forgotten, permission revoked: none of it fixes itself fast. */
        const val BACKOFF_MILLIS = 5 * 60 * 1000L
    }
}
