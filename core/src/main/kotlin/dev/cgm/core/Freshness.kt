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
 * Thresholds, tuned against 56 live samples recorded 2026-09-19:
 *
 *   cloud latency   35-55s      (median 45s)
 *   sensor cadence  60s         (max 122s, one dropped reading)
 *   newest reading age, worst observed: 177s (2.9 min)
 *   gaps over 5 minutes: 0 of 55
 *
 * Caveat on that sample: the recording ran for about one hour (103 readings,
 * 0 errors), not the overnight stretch originally intended, so it says nothing
 * about how this path behaves while the phone sleeps. Doze may well delay polls
 * far beyond anything above; re-measure over a night before trusting these
 * numbers for sleeping hours.
 *
 * So anything past ~3 minutes is already abnormal on this path. [agingAfterMillis]
 * sits at 5 minutes — roughly two missed readings, far enough above the worst
 * observed case not to cry wolf — and [staleAfterMillis] at 10 minutes, by which
 * point eight readings are missing and the number on screen is not current.
 *
 * These are deliberately tighter than the alarm defaults: the display should stop
 * claiming a value is current long before it is worth waking someone over.
 *
 * Re-measure with `spike/llu_probe.py stats` if the region or sensor changes.
 */
@Serializable
data class FreshnessPolicy(
    val agingAfterMillis: Long = 5.minutes.inWholeMilliseconds,
    val staleAfterMillis: Long = 10.minutes.inWholeMilliseconds,
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
    val thresholds: GlucoseThresholds = GlucoseThresholds.Default,
    val unit: GlucoseUnit = GlucoseUnit.MGDL,
    /** Change since a recent earlier reading, with the interval it spans. */
    val delta: GlucoseDelta? = null,
) {
    fun freshness(nowMillis: Long, policy: FreshnessPolicy = FreshnessPolicy.Default): Freshness =
        policy.evaluate(reading, nowMillis)

    fun zone(): Zone = thresholds.classify(reading.valueMgdl)

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
