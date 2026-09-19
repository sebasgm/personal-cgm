package dev.cgm.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.cgm.core.Zone

/**
 * Colour carries zone meaning and nothing else.
 *
 * Nothing in this app is coloured decoratively: if something has colour, the
 * colour is information. That is what lets the stale state simply remove it.
 */
object ZoneColors {
    val urgentLow = Color(0xFFC62828)
    val low = Color(0xFFE53935)
    val inRange = Color(0xFF2E7D32)
    val high = Color(0xFFF9A825)
    val veryHigh = Color(0xFFEF6C00)

    fun of(zone: Zone): Color = when (zone) {
        Zone.URGENT_LOW -> urgentLow
        Zone.LOW -> low
        Zone.IN_RANGE -> inRange
        Zone.HIGH -> high
        Zone.VERY_HIGH -> veryHigh
    }

    /** The colour a stale reading gets: none. */
    val stale: Color
        @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * The chart's own palette, following LibreLink's conventions so the app reads
 * familiarly to someone arriving from the official one.
 *
 * The in-range band is a pale green stripe and the trace is near-black, chosen
 * for contrast in the worst case rather than the average one: the line has to
 * stay legible crossing the green band, crossing plain background, and inverted
 * in dark mode. Near-black on pale green is the strongest of those; a saturated
 * accent colour tested well on white and disappeared over the green.
 */
object ChartColors {
    private val bandLight = Color(0xFFD7EED8)
    private val bandDark = Color(0xFF16301C)
    private val traceLight = Color(0xFF121212)
    private val traceDark = Color(0xFFF2F2F2)

    val band: Color
        @Composable get() = if (isSystemInDarkTheme()) bandDark else bandLight

    val trace: Color
        @Composable get() = if (isSystemInDarkTheme()) traceDark else traceLight

    /**
     * Signal loss, and the one colour here that does not mean a zone.
     *
     * Red is spent on exactly one thing: the claim that what is on screen may not
     * be true. Nothing else in the app may use it, or it stops meaning that.
     */
    val signalLoss = Color(0xFFD32F2F)
}
