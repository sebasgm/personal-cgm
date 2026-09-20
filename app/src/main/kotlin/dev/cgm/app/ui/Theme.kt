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
     * The projection. Deliberately a different hue from the trace and from every
     * zone colour, so it reads as "not a measurement" before it reads as anything
     * else. Never red — red is reserved for "what you are looking at may not be
     * true", and a forecast is not a warning.
     */
    private val forecastLight = Color(0xFF5B6BB5)
    private val forecastDark = Color(0xFF9FAEE8)

    val forecast: Color
        @Composable get() = if (isSystemInDarkTheme()) forecastDark else forecastLight

    /**
     * Signal loss, and the one colour here that does not mean a zone.
     *
     * Red is spent on exactly one thing: the claim that what is on screen may not
     * be true. Nothing else in the app may use it, or it stops meaning that.
     */
    val signalLoss = Color(0xFFD32F2F)

    /**
     * The levels you asked to be warned at, drawn dashed across the chart.
     *
     * Red, and softer than [signalLoss] so the two can share a screen without
     * competing: a threshold line is a boundary, not an event. Red was previously
     * reserved for "what you are looking at may not be true"; it now also marks
     * "where you asked to be told", which is close enough in kind that the reader
     * does not have to learn two rules.
     */
    val alarmLine = Color(0xFFE06C6C)
}

/**
 * The app's palette, seeded from #42B28E.
 *
 * A mid-tone teal-green, which decides how it can be used. It is light enough that
 * white text on it is hard to read and dark enough that black is harsh, so
 * `onPrimary` is a deep green rather than either — getting that wrong is how an
 * accent colour ends up with illegible buttons.
 *
 * Surfaces are cooled very slightly toward the accent rather than left neutral
 * grey, because a green accent on cold grey reads as a mistake.
 *
 * The zone colours in [ZoneColors], the alarm red and the signal-loss red in
 * [ChartColors] are deliberately *not* derived from this: they carry meaning, and
 * meaning must not move when someone changes the theme. The in-range band stays
 * the pale LibreLink green for the same reason, even though the theme is now green
 * too — it is a convention borrowed from the official app, not decoration.
 */
object CgmPalette {

    val seed = Color(0xFF42B28E)

    val light = lightColorScheme(
        primary = seed,
        onPrimary = Color(0xFF06291F),
        primaryContainer = Color(0xFFC5EBDC),
        onPrimaryContainer = Color(0xFF032018),
        secondary = Color(0xFF4A6358),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFCDE9DB),
        onSecondaryContainer = Color(0xFF072019),
        tertiary = Color(0xFF3F6374),
        onTertiary = Color(0xFFFFFFFF),
        background = Color(0xFFF6FBF8),
        onBackground = Color(0xFF171D1A),
        surface = Color(0xFFF6FBF8),
        onSurface = Color(0xFF171D1A),
        surfaceVariant = Color(0xFFDBE5DF),
        onSurfaceVariant = Color(0xFF3F4945),
        outline = Color(0xFF6F7975),
        outlineVariant = Color(0xFFBFC9C4),
    )

    val dark = darkColorScheme(
        primary = seed,
        onPrimary = Color(0xFF00382A),
        primaryContainer = Color(0xFF005140),
        onPrimaryContainer = Color(0xFF5FCFA9),
        secondary = Color(0xFFB1CCC0),
        onSecondary = Color(0xFF1C352C),
        secondaryContainer = Color(0xFF334B42),
        onSecondaryContainer = Color(0xFFCDE9DB),
        tertiary = Color(0xFFA7CBDD),
        onTertiary = Color(0xFF0A3445),
        background = Color(0xFF0F1512),
        onBackground = Color(0xFFDEE4E0),
        surface = Color(0xFF0F1512),
        onSurface = Color(0xFFDEE4E0),
        surfaceVariant = Color(0xFF3F4945),
        onSurfaceVariant = Color(0xFFBFC9C4),
        outline = Color(0xFF899390),
        outlineVariant = Color(0xFF3F4945),
    )
}
