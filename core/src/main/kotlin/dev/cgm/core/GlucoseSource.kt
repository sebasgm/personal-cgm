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
    /**
     * How long this sensor is designed to run.
     *
     * Per sensor rather than one constant, because Abbott ships both lengths at
     * once: Libre 2 and Libre 3 run 14 days, the Plus variants of both run 15.
     * A single constant made every reading of a 15-day sensor report "day 15/14",
     * which is not merely cosmetic — it marked the sensor expired a full day
     * early and muted the figure while the sensor was still perfectly good.
     *
     * The source cannot currently be trusted to say which it is, so this comes
     * from the user's own answer and is recorded per session, so a later change
     * of product does not rewrite the lifetime of sensors already finished.
     */
    val lifetimeDays: Int = DEFAULT_LIFETIME_DAYS,
    /**
     * Abbott's own product code, stored uninterpreted.
     *
     * LibreLinkUp sends this and we do not know the mapping: no public document
     * lists the codes, and guessing one would recreate the bug this field exists
     * to retire. Recorded so that a mapping can be *learned* from sensors this
     * phone has actually worn, and only then used to set [lifetimeDays] without
     * asking.
     */
    val productType: Int? = null,
) {
    /**
     * Day of the sensor session, 1-based, or null if unknown.
     *
     * Keeps counting past the end — day 16 of a 15-day sensor is a true
     * statement about a sensor that stopped yesterday. It is [isExpired] that
     * decides whether that is worth showing as a day number, and the UI shows
     * "expired" instead rather than printing a day above the lifetime.
     */
    fun dayOfSession(nowMillis: Long): Int? = startedAtMillis?.let {
        ((nowMillis - it) / DAY_MILLIS).toInt() + 1
    }

    /** When this sensor is designed to stop, epoch millis, or null if unknown. */
    fun endsAtMillis(): Long? = startedAtMillis?.plus(lifetimeDays * DAY_MILLIS)

    /** Time left before [endsAtMillis], negative once past it, null if unknown. */
    fun remainingMillis(nowMillis: Long): Long? = endsAtMillis()?.minus(nowMillis)

    fun isExpired(nowMillis: Long): Boolean = (remainingMillis(nowMillis) ?: 1) <= 0

    /**
     * Inside the last day of designed life.
     *
     * The point at which the hours left matter more than the day number, which is
     * the only time the detail view spells them out.
     */
    fun isFinalDay(nowMillis: Long): Boolean =
        remainingMillis(nowMillis)?.let { it > 0 && it <= DAY_MILLIS } == true

    /**
     * Whether [other] is the same physical sensor.
     *
     * Serial only. Activation time is not an identity: LibreLinkUp moves it when
     * the sensor is re-scanned, so comparing activations reports a new sensor for
     * a sensor that has not been touched. Two unknown serials are not evidence of
     * sameness, so they compare false.
     */
    fun isSameSensorAs(other: SensorInfo?): Boolean =
        serial != null && other?.serial != null && serial == other.serial

    companion object {
        /**
         * 14 days, the shorter of the two Abbott ships.
         *
         * The conservative default: it ends a 15-day sensor's count early, which
         * shows a sensor as finished while it still works. The other way round
         * would present a dead sensor's readings as current, and of the two
         * mistakes only one can be corrected by looking at the number.
         */
        const val DEFAULT_LIFETIME_DAYS = 14

        /** The lengths Abbott actually ships, for the picker to offer. */
        val LIFETIME_CHOICES = listOf(14, 15)

        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}

/**
 * What a stored sensor record and a freshly fetched one mean together.
 *
 * Separate from both the database and the API client because the rule is neither
 * one's business and is the part worth testing: which activation instant to
 * believe, and when a genuinely different sensor has been fitted.
 */
object SensorIdentity {

    data class Resolution(
        /** What to show and store. Null only when nothing is known at all. */
        val sensor: SensorInfo?,
        /**
         * A different physical sensor than the one on record.
         *
         * False on the first sighting of any sensor: learning about a sensor
         * already being worn is not the same event as one being fitted, and
         * announcing "new sensor" on first run after an install would be a lie
         * told to every new user.
         */
        val isNewSensor: Boolean,
    )

    /**
     * Merge [fetched] into [stored].
     *
     * The earlier of the two activations wins for a sensor we already know, which
     * is what makes a re-scan harmless: Abbott moves the activation stamp forward
     * to the scan, and taking it at face value restarted the day count from one
     * on a sensor halfway through its life.
     */
    fun reconcile(
        stored: SensorInfo?,
        fetched: SensorInfo?,
        lifetimeDays: Int,
    ): Resolution {
        if (fetched == null) {
            return Resolution(stored?.copy(lifetimeDays = lifetimeDays), isNewSensor = false)
        }
        val incoming = fetched.copy(lifetimeDays = lifetimeDays)
        if (stored == null) return Resolution(incoming, isNewSensor = false)

        if (!incoming.isSameSensorAs(stored)) {
            // An unknown serial on either side is not proof of a change, so the
            // record is updated but no change is announced.
            val changed = incoming.serial != null && stored.serial != null
            return Resolution(incoming, isNewSensor = changed)
        }

        val started = listOfNotNull(stored.startedAtMillis, incoming.startedAtMillis).minOrNull()
        return Resolution(
            incoming.copy(
                startedAtMillis = started,
                productType = incoming.productType ?: stored.productType,
            ),
            isNewSensor = false,
        )
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
