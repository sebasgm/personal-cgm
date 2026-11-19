package dev.cgm.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.staticCompositionLocalOf
import dev.cgm.core.ColorVision
import dev.cgm.core.TimeInRangeTarget
import dev.cgm.core.Zone

/**
 * Colour carries zone meaning and nothing else.
 *
 * Nothing in this app is coloured decoratively: if something has colour, the
 * colour is information. That is what lets the stale state simply remove it.
 */
/** The palette in force, so zone colours do not have to be threaded through every call. */
val LocalColorVision = staticCompositionLocalOf { ColorVision.DEFAULT }

/**
 * Whether to draw dark, or null to follow the device.
 *
 * Nullable rather than defaulting to false, so anything composed outside the
 * provider — a preview, a dialog hosted elsewhere — keeps asking the system
 * exactly as it did before this preference existed. A plain `false` default
 * would quietly light-mode anything that forgot to provide it.
 */
val LocalDarkTheme = staticCompositionLocalOf<Boolean?> { null }

/**
 * The light/dark decision in force: the stored preference if there is one, and
 * the device's setting otherwise.
 *
 * Every colour that varies by theme reads this rather than asking the system
 * directly. That is the whole mechanism — the chart palette and the Material
 * scheme have to agree, and a near-black trace on a dark band is what
 * disagreeing looks like.
 */
val isDarkTheme: Boolean
    @Composable get() = LocalDarkTheme.current ?: isSystemInDarkTheme()

object ZoneColors {

    /** LibreLink's convention: red low, green in range, amber high. */
    private val default = mapOf(
        Zone.URGENT_LOW to Color(0xFFC62828),
        Zone.LOW to Color(0xFFE53935),
        Zone.IN_RANGE to Color(0xFF2E7D32),
        Zone.HIGH to Color(0xFFF9A825),
        Zone.VERY_HIGH to Color(0xFFEF6C00),
    )

    /**
     * Okabe–Ito, mapped warm-low to cool-high.
     *
     * The default palette's problem is that its two most important states — a low
     * and an in-range — are red and green, which is precisely the pair that red-green
     * deficiency collapses. This separates the zones along blue–yellow instead, and
     * orders them by luminance so the chart still reads in greyscale.
     */
    private val colorBlindSafe = mapOf(
        Zone.URGENT_LOW to Color(0xFFD55E00),
        Zone.LOW to Color(0xFFE69F00),
        Zone.IN_RANGE to Color(0xFF009E73),
        Zone.HIGH to Color(0xFF56B4E9),
        Zone.VERY_HIGH to Color(0xFF0072B2),
    )

    /** The same hue logic in deeper tones, for low vision and for sunlight. */
    private val highContrast = mapOf(
        Zone.URGENT_LOW to Color(0xFF9A3412),
        Zone.LOW to Color(0xFFB45309),
        Zone.IN_RANGE to Color(0xFF00674E),
        Zone.HIGH to Color(0xFF1D6FA3),
        Zone.VERY_HIGH to Color(0xFF00335C),
    )

    fun palette(vision: ColorVision): Map<Zone, Color> = when (vision) {
        ColorVision.DEFAULT -> default
        ColorVision.COLOR_BLIND_SAFE -> colorBlindSafe
        ColorVision.HIGH_CONTRAST -> highContrast
    }

    /** Pure lookup, for draw code that has no composition to read from. */
    fun of(zone: Zone, vision: ColorVision): Color = palette(vision).getValue(zone)

    val inRange: Color
        @Composable get() = of(Zone.IN_RANGE, LocalColorVision.current)

    @Composable
    fun of(zone: Zone): Color = of(zone, LocalColorVision.current)

    /**
     * A time-in-range figure, coloured by how it compares with the clinical target.
     *
     * Reuses the zone colours rather than inventing a second scale, so green always
     * means "in range" whether it is describing one reading or a week of them.
     */
    @Composable
    fun ofTarget(target: TimeInRangeTarget, reliable: Boolean = true): Color {
        val vision = LocalColorVision.current
        val colour = when (target) {
            TimeInRangeTarget.AT_TARGET -> of(Zone.IN_RANGE, vision)
            TimeInRangeTarget.BELOW_TARGET -> of(Zone.HIGH, vision)
            TimeInRangeTarget.WELL_BELOW_TARGET -> of(Zone.LOW, vision)
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
    @Composable
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
        @Composable get() = if (isDarkTheme) bandDark else bandLight

    val trace: Color
        @Composable get() = if (isDarkTheme) traceDark else traceLight

    /**
     * The projection. Deliberately a different hue from the trace and from every
     * zone colour, so it reads as "not a measurement" before it reads as anything
     * else. Never red — red is reserved for "what you are looking at may not be
     * true", and a forecast is not a warning.
     */
    private val forecastLight = Color(0xFF5B6BB5)
    private val forecastDark = Color(0xFF9FAEE8)

    val forecast: Color
        @Composable get() = if (isDarkTheme) forecastDark else forecastLight

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
 * GMI and estimated A1C, as one hue in two tones.
 *
 * The hue is the forecast blue-violet, and it is the same hue for the same reason:
 * in this app that colour means **not a measurement**. Both of these figures are
 * estimates calculated from sensor readings, neither is a laboratory result, and the
 * one misreading the card has to prevent is someone taking either for a blood test.
 * Reusing the hue adds no new meaning to the palette — red is "may not be true",
 * amber and green and orange are zones, violet is insulin, teal is the interface.
 *
 * Two tones of one hue rather than two colours, exactly as [InsulinColors] is and for
 * the same argument: these are two estimates *of one quantity*. Two unrelated colours
 * would read as two unrelated measurements, where the point of showing them together
 * is that they are answers to one question that disagree.
 *
 * Separated by lightness rather than hue, which is why there is no colour-blind
 * variant here: a deep tone against a pale one survives any colour deficiency and
 * survives greyscale. Only the high-contrast palette gets its own pair, pushed
 * further apart. Both lines are directly labelled besides, so identity never rests
 * on colour at all.
 *
 * The deep tone is GMI, which is the figure derived for continuous sensor data and
 * the first of the two the card shows.
 */
object EstimateColors {

    /** The deep tone for GMI, the pale one for the A1C estimate. */
    data class Tones(val gmi: Color, val a1c: Color)

    private val light = Tones(gmi = Color(0xFF3E4D96), a1c = Color(0xFF7382CC))
    private val dark = Tones(gmi = Color(0xFF8090D8), a1c = Color(0xFFC3CDF2))

    private val lightHighContrast = Tones(gmi = Color(0xFF1F2C6E), a1c = Color(0xFF5C6BB8))
    private val darkHighContrast = Tones(gmi = Color(0xFF9FAEE8), a1c = Color(0xFFDDE3FA))

    fun of(vision: ColorVision, dark: Boolean): Tones = when {
        vision == ColorVision.HIGH_CONTRAST && dark -> darkHighContrast
        vision == ColorVision.HIGH_CONTRAST -> lightHighContrast
        dark -> this.dark
        else -> light
    }

    val tones: Tones
        @Composable get() = of(LocalColorVision.current, isDarkTheme)
}

/**
 * The two kinds of insulin, as one hue in two tones.
 *
 * One hue because they are two parts of one quantity: a day's insulin, split by
 * what the dose was for. Two unrelated colours would read as two unrelated
 * measurements, and the thing the chart is about is a total made of two pieces.
 *
 * Violet because every other meaning in this app is already spoken for. Red says
 * "what you are looking at may not be true", amber and green and orange are zones,
 * the blue-violet of a forecast is "not a measurement", and the teal accent is the
 * interface itself. A dose is none of those: it is the most certain number on the
 * phone, because a person typed it in.
 *
 * The two tones are separated by lightness rather than by hue, which is why there is
 * no colour-blind variant here as there is for the zones: a deep tone under a pale
 * one survives any colour deficiency, and survives greyscale. Only the high-contrast
 * palette gets its own pair, pushed further apart for low vision and for sunlight.
 *
 * The deep tone is basal and the pale one bolus, with basal at the bottom of the
 * stack. Basal is the floor a day is built on — the part that is the same every day
 * — so it reads as the base of the bar, and the part that moves with meals and
 * corrections sits on top where its variation is what changes the bar's height.
 */
object InsulinColors {

    /** One palette: the deep tone for basal, the pale one for bolus. */
    data class Tones(val basal: Color, val bolus: Color)

    private val light = Tones(basal = Color(0xFF4F3B93), bolus = Color(0xFFB6A4E8))
    private val dark = Tones(basal = Color(0xFF8E7BD6), bolus = Color(0xFFD5CBF4))

    private val lightHighContrast = Tones(basal = Color(0xFF2B1B63), bolus = Color(0xFF8E79CF))
    private val darkHighContrast = Tones(basal = Color(0xFFAB97F0), bolus = Color(0xFFE6DEFF))

    fun of(vision: ColorVision, dark: Boolean): Tones = when {
        vision == ColorVision.HIGH_CONTRAST && dark -> darkHighContrast
        vision == ColorVision.HIGH_CONTRAST -> lightHighContrast
        dark -> this.dark
        else -> light
    }

    val tones: Tones
        @Composable get() = of(LocalColorVision.current, isDarkTheme)
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
