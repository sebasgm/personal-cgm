package dev.cgm.wear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import dev.cgm.core.WatchPayload

/**
 * Receives readings from the phone.
 *
 * A service rather than a listener registered by the app, because the watch app
 * is usually not open: the reading has to arrive so that a complication or tile
 * can show it, and the system starts this whether or not anything is on screen.
 */
class WatchDataService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        val store = WatchStoreHolder.get(this)
        var accepted = false

        events.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED) return@forEach
            if (event.dataItem.uri.path != WatchPayload.PATH) return@forEach

            val payload = runCatching {
                event.dataItem.data?.let(WatchPayload::decode)
            }.getOrNull() ?: return@forEach

            if (store.accept(payload)) accepted = true
        }

        events.release()

        if (accepted) onReadingAccepted(store)
    }

    /**
     * Everything that should happen when a reading lands.
     *
     * The complication request is here rather than on a timer because this is the
     * only moment there is anything new to say — which is also what keeps the
     * platform's update budget from being spent on repeats.
     */
    private fun onReadingAccepted(store: WatchStore) {
        store.payload.value?.let { WatchNotifier(this).show(it) }
        GlucoseComplicationService.requestUpdate(this)
    }
}
