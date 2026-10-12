package dev.cgm.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the phone sends the watch over the Wear Data Layer in stage 3.
 *
 * Defined now, while the shape is cheap to change, so stage 3 is wiring rather
 * than design. Three constraints drove it:
 *
 *  1. Small. DataItems sync over Bluetooth and are capped; short `@SerialName`
 *     keys on [GlucoseReading] exist for this reason.
 *  2. Absolute timestamps only, never "minutes ago". The watch and phone clocks
 *     are both NTP-synced, and the watch needs the raw instant to hand to
 *     `TimeDifferenceComplicationText`.
 *  3. Self-contained. The watch must render correctly from one payload with no
 *     back-channel, because it will often be showing this while disconnected.
 */
@Serializable
data class WatchPayload(
    val snapshot: GlucoseSnapshot,
    /** Recent readings, oldest first, for the trend graph. Downsampled by the phone. */
    val history: List<GlucoseReading> = emptyList(),
    /** Phone clock when sent; lets the watch detect a wildly skewed clock. */
    val sentAtMillis: Long,
    /**
     * Bumped per payload, for diagnostics only.
     *
     * **Not an ordering key**, though it was one, and that was a bug worth
     * recording. The counter lives in the phone's polling service, so it restarts
     * at 1 whenever that process does. Every receiver compared it against the
     * value it had persisted, so a phone whose service had been restarted sent a
     * run of payloads that all looked older than what the receiver already held,
     * and every one of them was refused — permanently, because the counter
     * restarted again on the next restart. A frozen complication and a frozen
     * relay were the same bug seen twice.
     *
     * [supersedes] orders on the timestamps instead, which survive a restart
     * because they do not belong to the process that sends them.
     */
    val sequence: Long = 0,
    /**
     * Insulin logged in the last [DOSE_WINDOW_MILLIS], newest last.
     *
     * Carried so the watch can answer "what have I taken recently" without asking
     * the phone for it. That question is asked in the moment — usually while
     * looking at a number that is higher than expected — and a round trip to a
     * phone in another room is exactly when it fails.
     */
    val recentDoses: List<InsulinDose> = emptyList(),
) {
    fun encode(): ByteArray = Json.encodeToString(serializer(), this).toByteArray()

    /**
     * Whether this payload should replace [current] on a display.
     *
     * Every receiver of this format needs the same answer — the watch's store, the
     * complication behind it, and the web relay all hold one current payload and
     * must refuse anything that would move it backwards. So the rule lives with
     * the format rather than being written out once per receiver, which is how the
     * three of them came to share one bug.
     *
     * Ordered on the **reading's own timestamp**, which is the sensor's clock. That
     * is the only value in the payload that is monotonic across everything that
     * can go wrong between the two devices: the phone's polling service can
     * restart, which resets [sequence]; the phone's wall clock can be corrected or
     * change zone, which moves [sentAtMillis]. Neither disturbs the order readings
     * were taken in.
     *
     * A payload carrying the *same* reading still wins if it was sent later,
     * because the rest of it may have moved — a dose logged between polls, a delta
     * recomputed against a new neighbour. Only a strictly older reading is refused,
     * which is exactly the out-of-order delivery the Data Layer can produce when a
     * buffered item lands after a newer one.
     */
    fun supersedes(current: WatchPayload): Boolean {
        val mine = snapshot.reading.timestampMillis
        val theirs = current.snapshot.reading.timestampMillis
        if (mine != theirs) return mine > theirs
        return sentAtMillis > current.sentAtMillis
    }

    companion object {
        /** Data Layer path. Stage 3 listens on this prefix. */
        const val PATH = "/cgm/reading"

        /** Keep payloads small; roughly 3 hours at 5-minute spacing. */
        const val MAX_HISTORY_POINTS = 48

        /**
         * How far back doses are carried.
         *
         * Three hours covers the tail of rapid-acting insulin, which is the window
         * in which "did I already take something for this" is a live question.
         */
        const val DOSE_WINDOW_MILLIS = 3L * 60 * 60 * 1000

        fun decode(bytes: ByteArray): WatchPayload =
            Json.decodeFromString(serializer(), bytes.decodeToString())

        /**
         * Thin [history] to at most [MAX_HISTORY_POINTS] by dropping intermediate
         * points evenly. The newest point is always kept — it is the one the
         * graph's right edge and the big number both come from.
         */
        fun downsample(
            history: List<GlucoseReading>,
            max: Int = MAX_HISTORY_POINTS,
        ): List<GlucoseReading> {
            if (history.size <= max) return history
            val sorted = history.sortedBy { it.timestampMillis }
            val step = sorted.size.toDouble() / max
            val kept = (0 until max).map { sorted[(it * step).toInt()] }
            return if (kept.last() == sorted.last()) kept else kept.dropLast(1) + sorted.last()
        }
    }
}
