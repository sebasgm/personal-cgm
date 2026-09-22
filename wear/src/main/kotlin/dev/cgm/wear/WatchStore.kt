package dev.cgm.wear

import android.content.Context
import dev.cgm.core.WatchPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * The last reading the phone sent, held for the whole process.
 *
 * Persisted to a single file rather than a database: there is exactly one value,
 * it is replaced wholesale, and the watch needs it back immediately on a cold
 * start. A watch face or complication that shows nothing until the phone happens
 * to send again would be blank every morning.
 *
 * The stored payload keeps its own timestamp, so a value restored from disk is
 * aged correctly rather than treated as new — which is what stops a restart from
 * making an hour-old reading look current.
 */
class WatchStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)

    private val _payload = MutableStateFlow(readFromDisk())
    val payload: StateFlow<WatchPayload?> = _payload.asStateFlow()

    /**
     * Accept a payload, unless it is older than what we already hold.
     *
     * The Data Layer makes no ordering guarantee, and a reconnection can deliver a
     * buffered item after a newer one. Without the sequence check that would move
     * the watch backwards in time.
     */
    fun accept(incoming: WatchPayload): Boolean {
        val current = _payload.value
        if (current != null && incoming.sequence <= current.sequence) return false

        _payload.value = incoming
        runCatching { file.writeBytes(incoming.encode()) }
        return true
    }

    private fun readFromDisk(): WatchPayload? =
        runCatching { WatchPayload.decode(file.readBytes()) }.getOrNull()

    private companion object {
        const val FILE_NAME = "watch-payload.json"
    }
}

/**
 * One instance per process.
 *
 * A complication provider, a tile and the app are separate entry points into the
 * same process, and each needs the same reading. Holding it here means they
 * cannot disagree about what the current value is.
 */
object WatchStoreHolder {
    @Volatile private var instance: WatchStore? = null

    fun get(context: Context): WatchStore =
        instance ?: synchronized(this) {
            instance ?: WatchStore(context.applicationContext).also { instance = it }
        }
}
