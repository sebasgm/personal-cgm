package dev.cgm.app.service

import dev.cgm.core.WatchPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Where to push, and the key that proves it is us. Both empty means "off". */
data class RelayConfig(val baseUrl: String, val secret: String) {

    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && secret.length >= MIN_SECRET_LENGTH

    /** True unless the URL is plain HTTP to somewhere other than this machine. */
    val isSecure: Boolean
        get() = baseUrl.startsWith("https://", ignoreCase = true) ||
            baseUrl.contains("://127.0.0.1") ||
            baseUrl.contains("://localhost")

    fun pushUrl(): String = baseUrl.trimEnd('/') + "/api/push"

    companion object {
        const val MIN_SECRET_LENGTH = 24
        val Empty = RelayConfig("", "")
    }
}

/**
 * Sends each reading to the relay.
 *
 * The same payload the watch receives, so there is one wire format rather than a
 * second one to keep in step.
 *
 * Failures are swallowed on purpose. A relay that is down, moved, or misconfigured
 * is a display problem somewhere else; the phone is the source of truth, and
 * nothing about pushing may be able to interrupt polling or alarms.
 */
class RelayPublisher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build(),
) {

    suspend fun publish(config: RelayConfig, payload: WatchPayload): Boolean {
        if (!config.isConfigured) return false

        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(config.pushUrl())
                    .header("Authorization", "Bearer ${config.secret}")
                    .post(payload.encode().toRequestBody(JSON))
                    .build()

                client.newCall(request).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
