package dev.cgm.app.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.ZoneId

/**
 * The device's current time zone, as something the UI can observe.
 *
 * `ZoneId.systemDefault()` read once and held is wrong the moment someone flies,
 * and screens that group readings by day are exactly the ones that hold it. A day
 * boundary drawn in the zone you left is not a day.
 *
 * Only *display* follows this. Stored readings are epoch instants and do not move,
 * and a rollup keeps the zone its wall clock was resolved in — a reading taken at
 * 03:00 somewhere was taken at 03:00 there, whatever the clock says now.
 */
object ZoneWatcher {

    private val _zone = MutableStateFlow(ZoneId.systemDefault())
    val zone: StateFlow<ZoneId> = _zone.asStateFlow()

    fun refresh() {
        val current = ZoneId.systemDefault()
        if (current != _zone.value) _zone.value = current
    }
}

/**
 * Listens for the clock or the zone moving underneath us.
 *
 * Registered at runtime by the polling service rather than declared in the
 * manifest: the service is already long-lived, and a runtime registration is not
 * subject to the background restrictions that make manifest-declared implicit
 * broadcasts unreliable.
 *
 * [onClockChanged] re-evaluates anything derived from "now". A clock correction
 * can turn a fresh reading stale or a stale one fresh, and neither should wait
 * for the next poll to be noticed — least of all the signal-loss alarm, whose
 * entire job is to notice that nothing has arrived.
 */
class TimeChangeReceiver(private val onClockChanged: () -> Unit) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        ZoneWatcher.refresh()
        onClockChanged()
    }

    companion object {
        fun filter(): IntentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            // The user or the network moved the clock. Epoch millis jump, so every
            // age on screen is wrong until something recomputes it.
            addAction(Intent.ACTION_TIME_CHANGED)
            // Midnight, and the day boundaries the logbook groups by have moved.
            addAction(Intent.ACTION_DATE_CHANGED)
        }
    }
}
