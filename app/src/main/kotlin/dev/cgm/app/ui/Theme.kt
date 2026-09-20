package dev.cgm.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.cgm.core.TimeInRangeTarget
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

    /**
     * A time-in-range figure, coloured by how it compares with the clinical target.
     *
     * Reuses the zone colours rather than inventing a second scale, so green always
     * means "in range" whether it is describing one reading or a week of them.
     */
    fun ofTarget(target: TimeInRangeTarget, reliable: Boolean = true): Color {
        val colour = when (target) {
            TimeInRangeTarget.AT_TARGET -> inRange
            TimeInRangeTarget.BELOW_TARGET -> high
            TimeInRangeTarget.WELL_BELOW_TARGET -> low
        }
        return if (reliable) colour else colour.copy(alpha = UNRELIABLE_ALPHA)
    }

    /**
     * How strongly a zone colour is drawn when the figure behind it is thin.
     *
     * Low coverage used to replace the colour with grey, which flattened a five-zone
     * chart into one indistinguishable block — it destroyed the very information the
     * colours carry in order to say something the coverage label already says in
     * words. Alpha is the right axis for "less certain": hue keeps identifying the
     * zone, weight says how much to trust it.
     */
    const val UNRELIABLE_ALPHA = 0.45f

    /** A zone colour, dimmed when the statistic behind it is thin. */
    fun of(zone: Zone, reliable: Boolean): Color =
        if (reliable) of(zone) else of(zone).copy(alpha = UNRELIABLE_ALPHA)

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

/**
 * The app's palette, seeded from #E1CA96.
 *
 * That seed is a light, desaturated sand, which decides how it can be used: as a
 * light scheme's `primary` it needs *dark* text on top, not white, so `onPrimary`
 * is a deep brown rather than the usual white. Getting that backwards is how a warm
 * theme ends up with unreadable buttons.
 *
 * Surfaces are warmed to match rather than left neutral grey, because a warm accent
 * on cold grey reads as a mistake. The zone colours in [ZoneColors] and the signal
 * loss red in [ChartColors] are deliberately *not* derived from this: they carry
 * meaning, and meaning must not shift when someone changes the theme.
 */
object CgmPalette {

    val seed = Color(0xFFE1CA96)

    val light = lightColorScheme(
        primary = seed,
        onPrimary = Color(0xFF3A2E14),
        primaryContainer = Color(0xFFF3E7C9),
        onPrimaryContainer = Color(0xFF241B06),
        secondary = Color(0xFF6D5D3F),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFF1E4C3),
        onSecondaryContainer = Color(0xFF241A04),
        tertiary = Color(0xFF52643F),
        onTertiary = Color(0xFFFFFFFF),
        background = Color(0xFFFFFBF2),
        onBackground = Color(0xFF1E1B13),
        surface = Color(0xFFFFFBF2),
        onSurface = Color(0xFF1E1B13),
        surfaceVariant = Color(0xFFEAE1CC),
        onSurfaceVariant = Color(0xFF4B4639),
        outline = Color(0xFF7C7767),
        outlineVariant = Color(0xFFCDC5B0),
    )

    val dark = darkColorScheme(
        primary = seed,
        onPrimary = Color(0xFF382E12),
        primaryContainer = Color(0xFF534526),
        onPrimaryContainer = Color(0xFFFEE6BC),
        secondary = Color(0xFFD5C5A1),
        onSecondary = Color(0xFF3A2F15),
        secondaryContainer = Color(0xFF52452A),
        onSecondaryContainer = Color(0xFFF1E4C3),
        tertiary = Color(0xFFB9CBA0),
        onTertiary = Color(0xFF253515),
        background = Color(0xFF15130B),
        onBackground = Color(0xFFE8E2D4),
        surface = Color(0xFF15130B),
        onSurface = Color(0xFFE8E2D4),
        surfaceVariant = Color(0xFF4B4639),
        onSurfaceVariant = Color(0xFFCEC6B4),
        outline = Color(0xFF979080),
        outlineVariant = Color(0xFF4B4639),
    )
}
