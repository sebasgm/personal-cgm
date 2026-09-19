package dev.cgm.app.ui

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
