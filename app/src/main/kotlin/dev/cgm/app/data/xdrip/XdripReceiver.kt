package dev.cgm.app.data.xdrip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import dev.cgm.app.data.GlucoseRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Listens for xDrip+ readings while the service runs.
 *
 * Registered at runtime rather than declared in the manifest, and that is not a
 * style choice: since Android 8 a manifest-declared receiver cannot be given an
 * implicit broadcast, and xDrip+'s local broadcast is implicit unless the user has
 * named a target package in its settings. A receiver registered by a running
 * process is exempt, and this app already keeps a foreground service alive for the
 * whole session — so the one place guaranteed to be running is exactly where this
 * belongs.
 *
 * Exported, because the sender is another app. There is nothing to protect: the
 * only thing this accepts is a glucose number, which it validates, and the worst a
 * hostile broadcast achieves is one wrong reading in the log — the same damage a
 * mis-set xDrip+ would do.
 */
class XdripReceiver(
    private val repository: GlucoseRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Told the sensor xDrip+ says it is reading, when it says. */
    private val onSourceSeen: (String) -> Unit = {},
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == XdripBroadcast.ACTION_NO_DATA) {
            // Said out loud by xDrip+ rather than inferred from silence. Nothing to
            // record: the freshness clock already ages the last reading, and writing
            // a gap marker would be inventing data about an absence.
            Log.i(TAG, "xDrip+ reports no data")
            return
        }

        val reading = XdripBroadcast.readingFrom(intent, clock()) ?: run {
            Log.w(TAG, "ignoring unusable broadcast: ${intent.action}")
            return
        }
        XdripBroadcast.sourceDescription(intent)?.let(onSourceSeen)

        // onReceive runs on the main thread and must return quickly; the write,
        // the rollup refresh and the downstream emit all happen off it.
        scope.launch {
            runCatching { repository.ingest(XdripBroadcast.resultFor(reading)) }
                .onFailure { Log.e(TAG, "failed to store a pushed reading", it) }
        }
    }

    companion object {
        private const val TAG = "XdripReceiver"

        fun filter(): IntentFilter = IntentFilter().apply {
            addAction(XdripBroadcast.ACTION)
            addAction(XdripBroadcast.ACTION_NO_DATA)
        }

        /** Registers [receiver] for xDrip+'s broadcasts. */
        fun register(context: Context, receiver: XdripReceiver) {
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter(),
                ContextCompat.RECEIVER_EXPORTED,
            )
        }
    }
}
