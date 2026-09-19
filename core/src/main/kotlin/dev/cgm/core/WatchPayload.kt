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
    /** Bumped per payload so the watch can drop out-of-order Data Layer delivery. */
    val sequence: Long = 0,
) {
    fun encode(): ByteArray = Json.encodeToString(serializer(), this).toByteArray()

    companion object {
        /** Data Layer path. Stage 3 listens on this prefix. */
        const val PATH = "/cgm/reading"

        /** Keep payloads small; roughly 3 hours at 5-minute spacing. */
        const val MAX_HISTORY_POINTS = 48

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
