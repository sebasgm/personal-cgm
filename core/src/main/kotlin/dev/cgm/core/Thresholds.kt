package dev.cgm.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Where a reading sits.
 *
 * Five bands rather than issue #9's four: a low alert (#1) needs somewhere to
 * escalate to, and "below 70" covers everything from a mild dip to an emergency.
 */
enum class Zone {
    URGENT_LOW,
    LOW,
    IN_RANGE,
    HIGH,
    VERY_HIGH;

    val isLow: Boolean get() = this == URGENT_LOW || this == LOW
    val isHigh: Boolean get() = this == HIGH || this == VERY_HIGH
    val needsAttention: Boolean get() = this != IN_RANGE
}

/**
 * The four boundaries between the five zones, in mg/dL.
 *
 * Defaults for [lowMgdl] and [highMgdl] are overwritten with the LibreLinkUp
 * account's own `targetLow`/`targetHigh` when we have them, so the app agrees
 * with what LibreLink shows rather than inventing its own idea of "in range".
 * [urgentLowMgdl] and [veryHighMgdl] have no equivalent in the API and come from
 * the user or these defaults.
 */
@Serializable
data class GlucoseThresholds(
    @SerialName("ul") val urgentLowMgdl: Double = 55.0,
    @SerialName("lo") val lowMgdl: Double = 70.0,
    @SerialName("hi") val highMgdl: Double = 180.0,
    @SerialName("vh") val veryHighMgdl: Double = 240.0,
) {

    fun classify(mgdl: Double): Zone = when {
        mgdl < urgentLowMgdl -> Zone.URGENT_LOW
        mgdl < lowMgdl -> Zone.LOW
        mgdl > veryHighMgdl -> Zone.VERY_HIGH
        mgdl > highMgdl -> Zone.HIGH
        else -> Zone.IN_RANGE
    }

    /**
     * Position within the in-range band, clamped to 0..1.
     *
     * Feeds RANGED_VALUE complications on the watch, which need a bare fraction.
     */
    fun fraction(mgdl: Double): Float =
        ((mgdl - lowMgdl) / (highMgdl - lowMgdl)).coerceIn(0.0, 1.0).toFloat()

    /** Inclusive bounds of a zone, for drawing bands and axis labels. */
    fun boundsOf(zone: Zone): ClosedFloatingPointRange<Double> = when (zone) {
        Zone.URGENT_LOW -> 0.0..urgentLowMgdl
        Zone.LOW -> urgentLowMgdl..lowMgdl
        Zone.IN_RANGE -> lowMgdl..highMgdl
        Zone.HIGH -> highMgdl..veryHighMgdl
        Zone.VERY_HIGH -> veryHighMgdl..MAX_PLAUSIBLE_MGDL
    }

    /**
     * Force the boundaries into ascending order.
     *
     * Settings will let the user type these in, and a low above a high would
     * make [classify] nonsense. Rejecting the edit mid-typing is hostile, so the
     * model repairs itself instead.
     */
    fun sanitised(): GlucoseThresholds {
        val ul = urgentLowMgdl.coerceIn(MIN_PLAUSIBLE_MGDL, MAX_PLAUSIBLE_MGDL)
        val lo = lowMgdl.coerceAtLeast(ul + 1)
        val hi = highMgdl.coerceAtLeast(lo + 1)
        val vh = veryHighMgdl.coerceAtLeast(hi + 1)
        return GlucoseThresholds(ul, lo, hi, vh)
    }

    /** Replace the in-range band with the account's targets, keeping the rest. */
    fun withAccountTargets(targetLow: Double?, targetHigh: Double?): GlucoseThresholds =
        copy(
            lowMgdl = targetLow ?: lowMgdl,
            highMgdl = targetHigh ?: highMgdl,
        ).sanitised()

    companion object {
        const val MIN_PLAUSIBLE_MGDL = 40.0
        const val MAX_PLAUSIBLE_MGDL = 400.0

        val Default = GlucoseThresholds()
    }
}

/** One of the four boundaries between the five zones, for addressing them generically. */
enum class ThresholdBoundary {
    URGENT_LOW,
    LOW,
    HIGH,
    VERY_HIGH;

    /** What the account can tell us. The other two have no LibreLinkUp equivalent. */
    val comesFromAccount: Boolean get() = this == LOW || this == HIGH
}

/** Read one boundary by name, so UI can loop over all four. */
operator fun GlucoseThresholds.get(boundary: ThresholdBoundary): Double = when (boundary) {
    ThresholdBoundary.URGENT_LOW -> urgentLowMgdl
    ThresholdBoundary.LOW -> lowMgdl
    ThresholdBoundary.HIGH -> highMgdl
    ThresholdBoundary.VERY_HIGH -> veryHighMgdl
}

/** Replace one boundary, leaving the rest alone. Not sanitised: the caller decides. */
fun GlucoseThresholds.with(boundary: ThresholdBoundary, mgdl: Double): GlucoseThresholds =
    when (boundary) {
        ThresholdBoundary.URGENT_LOW -> copy(urgentLowMgdl = mgdl)
        ThresholdBoundary.LOW -> copy(lowMgdl = mgdl)
        ThresholdBoundary.HIGH -> copy(highMgdl = mgdl)
        ThresholdBoundary.VERY_HIGH -> copy(veryHighMgdl = mgdl)
    }

/**
 * The boundaries the user has taken over, if any.
 *
 * Stored as four nullable values rather than a whole [GlucoseThresholds] so that
 * "I have opinions about my urgent low" does not silently freeze the in-range
 * band too. An unset boundary keeps following the LibreLinkUp account, so when
 * the account's target changes the app still agrees with LibreLink — which was
 * the whole reason for reading targets from it in the first place.
 */
@Serializable
data class ThresholdOverrides(
    @SerialName("ul") val urgentLowMgdl: Double? = null,
    @SerialName("lo") val lowMgdl: Double? = null,
    @SerialName("hi") val highMgdl: Double? = null,
    @SerialName("vh") val veryHighMgdl: Double? = null,
) {

    operator fun get(boundary: ThresholdBoundary): Double? = when (boundary) {
        ThresholdBoundary.URGENT_LOW -> urgentLowMgdl
        ThresholdBoundary.LOW -> lowMgdl
        ThresholdBoundary.HIGH -> highMgdl
        ThresholdBoundary.VERY_HIGH -> veryHighMgdl
    }

    /** A null [mgdl] hands the boundary back to the account. */
    fun with(boundary: ThresholdBoundary, mgdl: Double?): ThresholdOverrides =
        when (boundary) {
            ThresholdBoundary.URGENT_LOW -> copy(urgentLowMgdl = mgdl)
            ThresholdBoundary.LOW -> copy(lowMgdl = mgdl)
            ThresholdBoundary.HIGH -> copy(highMgdl = mgdl)
            ThresholdBoundary.VERY_HIGH -> copy(veryHighMgdl = mgdl)
        }

    fun overrides(boundary: ThresholdBoundary): Boolean = this[boundary] != null

    val isEmpty: Boolean
        get() = ThresholdBoundary.entries.none { overrides(it) }

    /**
     * Lay the overrides over [base] and repair the result.
     *
     * [GlucoseThresholds.sanitised] is the safety net, not the mechanism: the UI
     * bounds each boundary by its neighbours so disorder cannot be entered. It
     * still runs here, because settings persisted by an older build — or a hand-
     * edited DataStore — must not be able to make `classify` nonsense.
     */
    fun applyTo(base: GlucoseThresholds): GlucoseThresholds =
        base.copy(
            urgentLowMgdl = urgentLowMgdl ?: base.urgentLowMgdl,
            lowMgdl = lowMgdl ?: base.lowMgdl,
            highMgdl = highMgdl ?: base.highMgdl,
            veryHighMgdl = veryHighMgdl ?: base.veryHighMgdl,
        ).sanitised()

    companion object {
        val None = ThresholdOverrides()
    }
}
