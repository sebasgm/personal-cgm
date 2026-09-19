package dev.cgm.core

import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * How much to trust the number on screen.
 *
 * This is derived from the clock, never from whether the last fetch succeeded.
 * A successful fetch that returned an old reading is still stale, and a failed
 * fetch on top of a 30-second-old reading is still fresh. Conflating the two is
 * how a display ends up confidently showing an hour-old number.
 */
enum class Freshness {
    /** Normal operation. */
    FRESH,

    /** Later than expected but plausible; show the age, keep the value. */
    AGING,

    /** Do not trust for anything. The UI must visibly degrade. */
    STALE;

    val isTrustworthy: Boolean get() = this == FRESH
}

/**
 * Thresholds. A Libre sensor produces a value a minute, and the LibreLinkUp cloud
 * path adds 1-5 minutes on top (measure yours with `spike/llu_probe.py stats`).
 * [aging] therefore has to sit above the worst-case normal latency or the UI
 * cries wolf constantly.
 */
@Serializable
data class FreshnessPolicy(
    val agingAfterMillis: Long = 6.minutes.inWholeMilliseconds,
    val staleAfterMillis: Long = 12.minutes.inWholeMilliseconds,
) {
    fun evaluate(ageMillis: Long): Freshness = when {
        ageMillis >= staleAfterMillis -> Freshness.STALE
        ageMillis >= agingAfterMillis -> Freshness.AGING
        else -> Freshness.FRESH
    }

    fun evaluate(reading: GlucoseReading, nowMillis: Long): Freshness =
        evaluate(reading.ageMillis(nowMillis))

    /**
     * When the verdict would next change, so a caller can schedule a redraw
     * instead of polling a clock.
     */
    fun nextTransition(reading: GlucoseReading, nowMillis: Long): Duration? {
        val age = reading.ageMillis(nowMillis)
        val target = when {
            age < agingAfterMillis -> agingAfterMillis
            age < staleAfterMillis -> staleAfterMillis
            else -> return null
        }
        return (target - age).milliseconds
    }

    companion object {
        val Default = FreshnessPolicy()
    }
}

/**
 * Everything the UI (phone or watch) needs to render one moment, with the
 * freshness already decided. Deliberately small: this is what crosses the Data
 * Layer to the watch in stage 3.
 */
@Serializable
data class GlucoseSnapshot(
    val reading: GlucoseReading,
    val range: GlucoseRange = GlucoseRange(),
    val unit: GlucoseUnit = GlucoseUnit.MGDL,
    /** Change since a recent earlier reading, with the interval it spans. */
    val delta: GlucoseDelta? = null,
) {
    fun freshness(nowMillis: Long, policy: FreshnessPolicy = FreshnessPolicy.Default): Freshness =
        policy.evaluate(reading, nowMillis)

    fun zone(): Zone = range.classify(reading.valueMgdl)

    fun formattedValue(): String = unit.format(reading.valueMgdl)

    /**
     * Signed change for display. A span meaningfully longer than the usual five
     * minutes is labelled, so "+40 / 15m" can never be misread as "+40 / 5m".
     */
    fun formattedDelta(): String? = delta?.let { d ->
        val v = unit.from(d.valueMgdl)
        val sign = if (v >= 0) "+" else ""
        val magnitude =
            if (unit.decimals == 0) "$sign${v.toInt()}" else String.format("%s%.1f", sign, v)
        if (d.isConventional) magnitude else "$magnitude / ${d.spanMillis / 60_000}m"
    }
}
