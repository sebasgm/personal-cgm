package dev.cgm.core

import kotlin.math.abs

/**
 * Which measurements the number on screen actually came from.
 *
 * Kept beside the figure rather than inferred from it, because a fused value is the
 * one number in this app that no instrument ever produced. Every screen that shows
 * one has to be able to say so, and a screen cannot say what it was not told.
 */
enum class FusionBasis {
    /** No strip near this reading. The sensor's own value, unchanged. */
    SENSOR,

    /** A flat trace and a strip beside it: both instruments, weighted. */
    BLEND,

    /**
     * A strip beside a moving trace: the strip alone.
     *
     * Not a failure to blend. The two instruments are honestly describing different
     * moments while glucose is moving, and the strip is the one that measured blood.
     */
    STRIP,
}

/**
 * What the trace was doing when a measurement was taken.
 *
 * Three states rather than a rate, because the question these answer is categorical:
 * were these two instruments describing the same moment, or was one of them behind the
 * other. The boundary is [GlucoseFusion.FLAT_RATE_MGDL_PER_MINUTE] — the same line the
 * blend is gated on, deliberately — so that "this pair is in the flat bucket" and "the
 * app would have blended this pair" are one statement rather than two that drift.
 *
 * Direction is kept, not just magnitude, and that is the whole value of the type. A
 * sensor reads interstitial fluid and lags blood, so on a falling trace an accurate
 * sensor still reads *above* the strip, and on a rising one it reads below. Pooling
 * both into "moving" would cancel the two halves of that effect against each other and
 * report a sensor with no lag at all.
 */
enum class TrendBucket {
    FALLING,
    FLAT,
    RISING;

    companion object {
        /**
         * Which bucket a rate falls in, or null when the rate is unknown.
         *
         * Null rather than [FLAT]: no usable delta is not evidence of a flat trace, and
         * a pair filed under "flat" on the strength of missing data would be the one
         * thing that could make this breakdown lie in the direction it is meant to test.
         */
        fun of(ratePerMinute: Double?): TrendBucket? = when {
            ratePerMinute == null -> null
            ratePerMinute > GlucoseFusion.FLAT_RATE_MGDL_PER_MINUTE -> RISING
            ratePerMinute < -GlucoseFusion.FLAT_RATE_MGDL_PER_MINUTE -> FALLING
            else -> FLAT
        }
    }
}

/**
 * One moment, as the sensor and the meter together describe it.
 *
 * Derived, never stored. The reading table keeps what the sensor said and the blood
 * test table keeps what the strip said; this is computed from both at read time, which
 * is the same doctrine that governs the diary — "a corrected reading would be a
 * falsified record" — and has a practical edge beyond principle: when the weighting
 * stops being a guess and becomes a measurement, every past moment re-derives, because
 * nothing was ever baked in.
 *
 * [mgdl] and [alarmMgdl] are deliberately two fields and are not interchangeable. The
 * blend is for display and for comparison; the alarm engine never sees it. See
 * [GlucoseFusion.alarmValue].
 *
 * Freshness is not this type's business and is not adjusted here. A strip paired with
 * a twenty-minute-old reading produces a fused value that is itself twenty minutes old,
 * and the screen that shows the age is what says so — the same age, from the same
 * reading, as it showed before fusion existed.
 */
data class FusedGlucose(
    val reading: GlucoseReading,
    /** The strip this was fused with, or null when there was none near enough. */
    val strip: BloodTest?,
    /** How far apart the two measurements were, or null when there is no pair. */
    val gapMillis: Long?,
    /**
     * How fast glucose was moving, in mg/dL per minute, or null when that is unknown.
     *
     * Null is not zero and must never be read as flat: it means there was no usable
     * delta, which is precisely the case where it cannot be shown that the two
     * instruments describe one moment.
     */
    val ratePerMinute: Double?,
    val basis: FusionBasis,
    /** The figure to show, in mg/dL. */
    val mgdl: Double,
    /** The figure the alarm engine must judge, in mg/dL. Never a blend. */
    val alarmMgdl: Double,
) {

    /** True when the figure on screen came from more than the sensor. */
    val isFused: Boolean get() = basis != FusionBasis.SENSOR

    /** The sensor's own value, kept reachable so a screen can show both halves. */
    val sensorMgdl: Double get() = reading.valueMgdl

    /** The strip's value, or null when there is no pair. */
    val stripMgdl: Double? get() = strip?.mgdl

    /**
     * The difference the fusion covered over, signed sensor minus strip.
     *
     * Worth showing beside a fused figure: a blend of 98 and 102 is a formality, and a
     * blend of 70 and 110 is the reader's cue that one of these instruments is wrong
     * today.
     */
    val disagreementMgdl: Double? get() = strip?.let { reading.valueMgdl - it.mgdl }
}

/**
 * Weighting a strip against the sensor reading beside it.
 *
 * The owner's decision is the strip at 60% and the sensor at 40%, and that is the
 * right direction — the strip is the reference and the sensor is the estimate. The
 * rules around it are what keep a blended number honest:
 *
 * - **"The same instant" is one sensor cadence**, not the diary's fifteen minutes.
 *   Glucose can move sixty mg/dL in fifteen, and two values that far apart are not two
 *   measurements of one state.
 * - **Blend when flat, prefer the strip when not.** A strip reads capillary blood and a
 *   sensor reads interstitial fluid, which lags blood by five to fifteen minutes and
 *   lags further the faster glucose moves. When the trace is flat the two agree about
 *   what "now" means; at 3 mg/dL/min they do not, and averaging them produces a number
 *   that is wrong in a direction that was predictable.
 * - **The alarm engine never sees the blend.** See [alarmValue].
 * - **Statistics stay sensor-only.** Time in range, GMI, the daily pattern and the
 *   coverage figure all claim to describe a continuous sensor record. Nothing here is
 *   offered to them, and the absence is the feature.
 *
 * This is fusion, not calibration. The scope is one instant: the matched pair. What is
 * deliberately not built here is a correction carried forward — an offset that decays
 * over the following hours, which is what a calibrating CGM does. That needs the bias
 * to be measured rather than asserted, which is what [SensorAgreement] is collecting.
 *
 * See `docs/12-finger-prick-meter.md`.
 */
object GlucoseFusion {

    /**
     * The strip's share of a blend.
     *
     * A prior, not a measurement: nobody has yet checked how far this sensor sits from
     * this meter. After a few dozen paired points [SensorAgreement] can report the real
     * bias and spread, and the weights can be derived from the instrument that has been
     * more consistent rather than from a decision made in advance. Until then 60/40 is
     * a reasonable, stated guess, and saying so here is what stops it from quietly
     * becoming a fact.
     */
    const val STRIP_WEIGHT = 0.6

    /**
     * The sensor's share.
     *
     * Named rather than written as `1 - STRIP_WEIGHT` at the call site, so that a reader
     * of the arithmetic sees both halves of the decision.
     */
    const val SENSOR_WEIGHT = 1.0 - STRIP_WEIGHT

    /**
     * How far apart a strip and a reading may be and still describe one moment.
     *
     * Deliberately [SensorAgreement.MATCH_WINDOW_MILLIS] rather than a second constant
     * with the same value: the question is identical to the one the accuracy card asks,
     * and two constants that happen to agree today are two constants that will disagree
     * after the first edit.
     */
    const val MATCH_WINDOW_MILLIS: Long = SensorAgreement.MATCH_WINDOW_MILLIS

    /**
     * Above this rate of change, the trace is moving and the strip stands alone.
     *
     * One mg/dL per minute is the convention every CGM display already draws as a flat
     * arrow, which makes it the threshold a reader has already been trained on rather
     * than a number invented here. At that rate the interstitial lag is worth a handful
     * of mg/dL; at three times it, blending is averaging two different moments.
     */
    const val FLAT_RATE_MGDL_PER_MINUTE = 1.0

    /**
     * The sensor and the strip beside it, as one value.
     *
     * [tests] may be any recent set — the nearest plausible one inside the window is
     * taken, in either direction. Nearest rather than the last one before the reading:
     * both are minutes away, and preferring the earlier only because it is earlier
     * would compare against a moment that had already passed.
     */
    fun of(snapshot: GlucoseSnapshot, tests: List<BloodTest>): FusedGlucose {
        val reading = snapshot.reading
        val rate = ratePerMinute(snapshot.delta)

        val strip = tests
            .filter { it.isPlausible }
            .filter { abs(it.takenAtMillis - reading.timestampMillis) <= MATCH_WINDOW_MILLIS }
            .minByOrNull { abs(it.takenAtMillis - reading.timestampMillis) }

        if (strip == null) {
            return FusedGlucose(
                reading = reading,
                strip = null,
                gapMillis = null,
                ratePerMinute = rate,
                basis = FusionBasis.SENSOR,
                mgdl = reading.valueMgdl,
                alarmMgdl = reading.valueMgdl,
            )
        }

        // Unknown is not flat. Without a usable delta there is no evidence that these
        // two instruments are describing one moment, and the one that measured blood
        // is the one to believe in the absence of that evidence.
        val flat = rate != null && abs(rate) <= FLAT_RATE_MGDL_PER_MINUTE
        val basis = if (flat) FusionBasis.BLEND else FusionBasis.STRIP

        return FusedGlucose(
            reading = reading,
            strip = strip,
            gapMillis = abs(strip.takenAtMillis - reading.timestampMillis),
            ratePerMinute = rate,
            basis = basis,
            mgdl = when (basis) {
                FusionBasis.BLEND -> blend(stripMgdl = strip.mgdl, sensorMgdl = reading.valueMgdl)
                else -> strip.mgdl
            },
            alarmMgdl = alarmValue(
                stripMgdl = strip.mgdl,
                sensorMgdl = reading.valueMgdl,
                thresholds = snapshot.thresholds,
            ),
        )
    }

    /** The weighted mean, and the only place the two weights are ever applied. */
    fun blend(stripMgdl: Double, sensorMgdl: Double): Double =
        stripMgdl * STRIP_WEIGHT + sensorMgdl * SENSOR_WEIGHT

    /**
     * What the alarm engine judges: the more severe of the two real measurements.
     *
     * Never the blend, in either direction. Take a strip of 55 against a sensor of 75 —
     * the blend is 63, which is *higher than the reference measurement*, in the one
     * direction where being wrong is dangerous, and it is higher precisely because the
     * less trusted instrument pulled it there. A weighting that softens a hypo is not a
     * weighting, it is a bug with a rationale.
     *
     * The symmetric case matters just as much: a comfortable strip must not silence a
     * sensor that is reading 50. So this is not "the strip wins" but "the worse of the
     * two wins", which happens to be the strip in every case the owner was worried
     * about.
     *
     * When the two sit in the same band the more extreme one is taken; when they
     * disagree about which direction is wrong — a strip that is low against a sensor
     * that is high, which means one of the instruments has failed outright — the strip
     * is taken, because it is the reference and because one of the two alarms has to be
     * dropped.
     */
    fun alarmValue(stripMgdl: Double, sensorMgdl: Double, thresholds: GlucoseThresholds): Double {
        val stripZone = thresholds.classify(stripMgdl)
        val sensorZone = thresholds.classify(sensorMgdl)

        val stripRank = severity(stripZone)
        val sensorRank = severity(sensorZone)
        if (stripRank != sensorRank) return if (stripRank > sensorRank) stripMgdl else sensorMgdl

        return when {
            stripZone.isLow && sensorZone.isLow -> minOf(stripMgdl, sensorMgdl)
            stripZone.isHigh && sensorZone.isHigh -> maxOf(stripMgdl, sensorMgdl)
            else -> stripMgdl
        }
    }

    /**
     * How fast glucose is moving, in mg/dL per minute, or null when unknown.
     *
     * Normalised through [GlucoseDelta.normalisedPerFiveMinutes] rather than divided by
     * its own span, so that a fifteen-minute delta is not read as a five-minute one —
     * which would report a third of the real rate and call a climbing trace flat.
     */
    fun ratePerMinute(delta: GlucoseDelta?): Double? {
        if (delta == null || delta.spanMillis <= 0) return null
        return delta.normalisedPerFiveMinutes() / 5.0
    }

    /**
     * How bad a zone is, as a number, so two can be compared.
     *
     * Low and high rank together on purpose: this decides which of two measurements the
     * alarm engine judges, not which alarm fires. The engine still applies its own
     * thresholds, which are independent of these bands.
     */
    private fun severity(zone: Zone): Int = when (zone) {
        Zone.IN_RANGE -> 0
        Zone.LOW, Zone.HIGH -> 1
        Zone.URGENT_LOW, Zone.VERY_HIGH -> 2
    }
}
