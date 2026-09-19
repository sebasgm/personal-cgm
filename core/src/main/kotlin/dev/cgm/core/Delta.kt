package dev.cgm.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.time.Duration.Companion.minutes

/**
 * A change in glucose, carrying the interval it was measured over.
 *
 * The span is not decoration. LibreLinkUp's `graphData` is aggregated to roughly
 * 15-minute spacing (measured, not assumed), while the convention every other CGM
 * display follows is a ~5-minute delta. Rendering a 15-minute change as if it
 * were a 5-minute one triples the apparent rate of change, which is exactly the
 * kind of quiet misreading this app must not produce.
 */
@Serializable
data class GlucoseDelta(
    @SerialName("d") val valueMgdl: Double,
    @SerialName("s") val spanMillis: Long,
) {
    /** True when the span is close enough to 5 minutes to display bare. */
    val isConventional: Boolean
        get() = spanMillis <= DeltaCalculator.MAX_CONVENTIONAL_SPAN_MILLIS

    /** Change per 5 minutes, for comparing spans on equal terms. */
    fun normalisedPerFiveMinutes(): Double =
        valueMgdl * (DeltaCalculator.TARGET_SPAN_MILLIS.toDouble() / spanMillis)
}

object DeltaCalculator {

    val TARGET_SPAN_MILLIS = 5.minutes.inWholeMilliseconds

    /** Above this, a delta is labelled with its span rather than shown bare. */
    val MAX_CONVENTIONAL_SPAN_MILLIS = 8.minutes.inWholeMilliseconds

    /** Above this, any delta is more misleading than useful, so report none. */
    val MAX_USABLE_SPAN_MILLIS = 20.minutes.inWholeMilliseconds

    /**
     * Pick the reading closest to [TARGET_SPAN_MILLIS] before [current] and
     * measure against it.
     *
     * @return null when [history] has nothing recent enough to be meaningful.
     */
    fun compute(current: GlucoseReading, history: List<GlucoseReading>): GlucoseDelta? {
        val candidate = history
            .filter { it.timestampMillis < current.timestampMillis }
            .minByOrNull { abs((current.timestampMillis - it.timestampMillis) - TARGET_SPAN_MILLIS) }
            ?: return null

        val span = current.timestampMillis - candidate.timestampMillis
        if (span > MAX_USABLE_SPAN_MILLIS) return null

        return GlucoseDelta(
            valueMgdl = current.valueMgdl - candidate.valueMgdl,
            spanMillis = span,
        )
    }
}
