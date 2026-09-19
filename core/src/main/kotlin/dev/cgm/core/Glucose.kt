package dev.cgm.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Display unit. Readings are always stored in mg/dL and converted at the edge. */
enum class GlucoseUnit(val suffix: String) {
    MGDL("mg/dL"),
    MMOLL("mmol/L");

    fun from(mgdl: Double): Double = when (this) {
        MGDL -> mgdl
        MMOLL -> mgdl / MMOL_PER_MGDL
    }

    /** Decimal places appropriate for this unit. */
    val decimals: Int get() = if (this == MMOLL) 1 else 0

    fun format(mgdl: Double): String {
        val v = from(mgdl)
        return if (decimals == 0) v.toInt().toString() else String.format("%.1f", v)
    }

    companion object {
        const val MMOL_PER_MGDL = 18.0182
    }
}

/**
 * LibreLinkUp reports trend as an int 1..5. Kept as an enum so other sources
 * (Nightscout uses strings like "FortyFiveUp") can map onto the same type.
 */
@Serializable
enum class TrendArrow(val glyph: String) {
    @SerialName("fd") FALLING_QUICKLY("↓"),
    @SerialName("f") FALLING("↘"),
    @SerialName("s") STEADY("→"),
    @SerialName("r") RISING("↗"),
    @SerialName("ru") RISING_QUICKLY("↑"),
    @SerialName("u") UNKNOWN("?");

    companion object {
        fun fromLibreLinkUp(value: Int?): TrendArrow = when (value) {
            1 -> FALLING_QUICKLY
            2 -> FALLING
            3 -> STEADY
            4 -> RISING
            5 -> RISING_QUICKLY
            else -> UNKNOWN
        }
    }
}

/**
 * A single measurement.
 *
 * [timestampMillis] is the instant the *sensor* took the reading (epoch UTC), not
 * the instant we fetched it. This distinction is the whole ballgame: the watch
 * renders reading age from this value via the platform's time-difference
 * bindings, so it stays truthful even when our updates are being throttled.
 */
@Serializable
data class GlucoseReading(
    @SerialName("v") val valueMgdl: Double,
    @SerialName("t") val timestampMillis: Long,
    @SerialName("a") val trend: TrendArrow = TrendArrow.UNKNOWN,
    @SerialName("h") val isHigh: Boolean = false,
    @SerialName("l") val isLow: Boolean = false,
) : Comparable<GlucoseReading> {

    fun ageMillis(nowMillis: Long): Long = nowMillis - timestampMillis

    override fun compareTo(other: GlucoseReading): Int =
        timestampMillis.compareTo(other.timestampMillis)
}
