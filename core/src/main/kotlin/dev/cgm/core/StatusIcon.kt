package dev.cgm.core

import kotlin.math.roundToInt

/**
 * What the status bar should read (issue #11).
 *
 * The status bar gets one small square, so this is deliberately a *label*, not a
 * formatted reading: at most three glyphs, no unit suffix, no trend arrow. The
 * ongoing notification's title still carries the full value, unit, arrow and
 * delta for anyone who pulls the shade down.
 *
 * Kept in :core and free of Android types so the decision is unit-testable, and
 * so the watch's complication can reuse it in stage 3 rather than reinventing
 * which value is fit to show in a tiny space.
 */
object StatusIconLabel {

    /** Shown before the first reading arrives. */
    const val NO_DATA = "--"

    /**
     * Shown instead of a number once the reading is stale.
     *
     * Deliberately not the last known value. The status bar is glanced at, not
     * read — there is no room for "17 min ago" beside it, so a number there is
     * taken as current. The same reasoning drives the alarm engine's refusal to
     * fire glucose alarms on stale data: when the data is old, the honest answer
     * is that we do not know.
     */
    const val UNKNOWN = "?"

    /**
     * Above this the sensor is out of its calibrated range and Libre itself
     * stops reporting a number, so a digit here would be invented precision.
     */
    const val ABOVE_RANGE = "HI"

    /** Below the sensor's floor, for the same reason. */
    const val BELOW_RANGE = "LO"

    fun of(snapshot: GlucoseSnapshot?, freshness: Freshness): String {
        if (snapshot == null) return NO_DATA
        // AGING still shows its number: it is late, not wrong, and the shade says
        // "later than expected" for anyone who looks. Only STALE withholds it.
        if (freshness == Freshness.STALE) return UNKNOWN
        return format(snapshot.reading.valueMgdl, snapshot.unit)
    }

    /**
     * Three characters at most: "55", "124", "7.2", "14".
     *
     * Single-digit mmol/L keeps its decimal, because a reader shown "7" cannot
     * tell 7.0 from 7.4 and at that scale the decimal is the interesting digit.
     * From 10 mmol/L up the decimal is dropped instead of overflowing to four
     * glyphs: "10.0" would not fit, and by then the integer part has already said
     * what there is to say.
     */
    fun format(valueMgdl: Double, unit: GlucoseUnit): String {
        if (valueMgdl > SENSOR_CEILING_MGDL) return ABOVE_RANGE
        if (valueMgdl < SENSOR_FLOOR_MGDL) return BELOW_RANGE

        // Units that have no decimal are already as short as they get, and are
        // formatted by the same code as the rest of the app so the status bar can
        // never disagree with the big value on Home.
        val formatted = unit.format(valueMgdl)
        if (unit.decimals == 0 || formatted.length <= MAX_GLYPHS) return formatted

        // Measure rather than predict. A threshold of "under 10 keeps its decimal"
        // is almost right, but 9.96 mmol/L formats as "10.0" and would overflow
        // the icon. Rounded, not truncated: 9.99 belongs at "10", and "9" would
        // understate a value the shade is about to show as 10.0.
        return unit.from(valueMgdl).roundToInt().toString()
    }

    /** What one status bar icon can hold legibly. */
    const val MAX_GLYPHS = 3

    /**
     * Abbott's Libre sensors report 40-500 mg/dL and clamp outside that. Using
     * the sensor's own limits rather than [GlucoseThresholds.MAX_PLAUSIBLE_MGDL]
     * keeps this about what the hardware can measure, not about what the user
     * considers alarming.
     */
    const val SENSOR_FLOOR_MGDL = 40.0
    const val SENSOR_CEILING_MGDL = 500.0
}
