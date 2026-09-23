package dev.cgm.app.service

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import dev.cgm.core.InsulinDose
import dev.cgm.core.SourceResult
import dev.cgm.core.WatchPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Sends each reading to a paired watch.
 *
 * Uses the Wear Data Layer rather than a Bluetooth connection of our own: Play
 * Services already runs that link, falls back to Wi-Fi when the watch is out of
 * range, and buffers while disconnected. Writing our own GATT service would
 * re-implement all of that, worse.
 *
 * A **DataItem** rather than a message, because the semantics match: there is one
 * current reading, the watch wants the latest one whenever it next looks, and a
 * delivery missed while disconnected should be superseded rather than queued.
 * Messages are for things that must arrive now, which is what alarms will use.
 */
class WatchBridge(context: Context) {

    private val dataClient = Wearable.getDataClient(context)
    private val sequence = AtomicLong(0)

    /**
     * Publish [result] for the watch.
     *
     * Failures are returned rather than thrown: a watch that is absent,
     * unsupported, or simply not paired is the normal case for most installs, and
     * it must not disturb polling.
     */
    suspend fun publish(
        result: SourceResult,
        recentDoses: List<InsulinDose> = emptyList(),
    ): Boolean = withContext(Dispatchers.IO) {
        val payload = WatchPayload(
            snapshot = result.snapshot,
            // Thinned on this side so the watch receives something small; it is
            // the phone that has the CPU and the battery to spare.
            history = WatchPayload.downsample(
                (result.history + result.snapshot.reading)
                    .distinctBy { it.timestampMillis }
                    .sortedBy { it.timestampMillis }
            ),
            sentAtMillis = System.currentTimeMillis(),
            sequence = sequence.incrementAndGet(),
            recentDoses = recentDoses,
        )

        val request = PutDataRequest.create(WatchPayload.PATH).apply {
            data = payload.encode()
            // Without this the sync is opportunistic and can wait minutes. A
            // glucose reading that arrives late is the thing this app exists to
            // avoid, so it is worth the battery.
            setUrgent()
        }

        runCatching { Tasks.await(dataClient.putDataItem(request)) }.isSuccess
    }
}
