package dev.cgm.core

import kotlinx.coroutines.flow.Flow

/**
 * Which source the app is reading.
 *
 * A stored choice rather than a guess from what is configured: both can be set up
 * at once, and silently preferring one would make the other look broken.
 */
enum class SourceKind(val id: String) {
    /** Abbott's follower cloud. Polled, needs an account. */
    LIBRELINKUP("librelinkup"),

    /**
     * Whatever xDrip+ is already reading, over a local broadcast.
     *
     * Pushed rather than polled, and there is nothing to authenticate: the
     * readings arrive from another app on the same phone. Every sensor xDrip+
     * supports arrives through this one path, which is the whole reason for it.
     */
    XDRIP("xdrip");

    val isPolled: Boolean get() = this == LIBRELINKUP

    companion object {
        val Default = LIBRELINKUP

        fun ofId(id: String?): SourceKind = entries.firstOrNull { it.id == id } ?: Default
    }
}

/**
 * Where readings come from.
 *
 * LibreLinkUp polls a cloud; xDrip+ pushes over a local broadcast. Nightscout and
 * a direct-BLE source are still planned (docs/09-sources.md), so nothing above
 * this interface may assume a cloud, an account, or a polling model — and since
 * one pushed source now exists, that is a live constraint rather than a promise.
 */
interface GlucoseSource {

    /** Stable id for logs and settings, e.g. "librelinkup". */
    val id: String

    /**
     * Fetch the most recent reading plus whatever recent history the source can
     * cheaply provide. History feeds the trend graph the watch draws in stage 5.
     */
    suspend fun fetch(): SourceResult

    /**
     * Sources that push (direct BLE, a local broadcast from xDrip) override this.
     * Polling sources leave it empty and the scheduler drives [fetch].
     */
    fun updates(): Flow<SourceResult> = kotlinx.coroutines.flow.emptyFlow()
}

data class SourceResult(
    val snapshot: GlucoseSnapshot,
    val history: List<GlucoseReading> = emptyList(),
    val sensor: SensorInfo? = null,
)

/** Sensor session metadata, where the source can tell us. */
@kotlinx.serialization.Serializable
data class SensorInfo(
    val serial: String? = null,
    /** Activation instant, epoch millis. */
    val startedAtMillis: Long? = null,
) {
    /**
     * Day of the sensor session, 1-based, or null if unknown.
     *
     * Libre sensors run 14 days, so day 15 means it has already expired and any
     * reading is suspect.
     */
    fun dayOfSession(nowMillis: Long): Int? = startedAtMillis?.let {
        ((nowMillis - it) / DAY_MILLIS).toInt() + 1
    }

    fun isExpired(nowMillis: Long): Boolean =
        (dayOfSession(nowMillis) ?: 0) > SESSION_DAYS

    companion object {
        const val SESSION_DAYS = 14
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}

/**
 * Failure modes the UI has to tell apart. "Something went wrong" is not good
 * enough: the user's response to an expired password differs completely from
 * their response to a sensor that is warming up.
 */
sealed class GlucoseSourceException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /** Credentials rejected. Needs the user. */
    class AuthFailed(message: String, cause: Throwable? = null) :
        GlucoseSourceException(message, cause)

    /** Account exists but needs action in the vendor app (terms, invitation). */
    class AccountActionRequired(message: String) : GlucoseSourceException(message)

    /** No sensor / no follower connection configured. */
    class NoData(message: String) : GlucoseSourceException(message)

    /** Transient: offline, DNS, timeout. Retry silently. */
    class Unreachable(message: String, cause: Throwable? = null) :
        GlucoseSourceException(message, cause)

    /** Back off for [retryAfterMillis] before trying again. */
    class RateLimited(val retryAfterMillis: Long) :
        GlucoseSourceException("rate limited for ${retryAfterMillis}ms")

    /** The API changed shape under us. Worth surfacing loudly; it means a fix. */
    class Unexpected(message: String, cause: Throwable? = null) :
        GlucoseSourceException(message, cause)

    /** Whether a scheduler should keep retrying on its own. */
    val isTransient: Boolean
        get() = this is Unreachable || this is RateLimited || this is NoData
}
