package dev.cgm.app.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import dev.cgm.app.R
import dev.cgm.core.AccessibilityPreferences
import dev.cgm.core.ReadingFont

/**
 * Atkinson Hyperlegible, from the Braille Institute.
 *
 * Every character is drawn to be unmistakable for another: the 1, l and I have
 * different shapes, 0 carries no slash but a distinct aperture from O, and 6, 8
 * and 5 keep their counters open. On a screen that exists to show three digits at
 * a glance, that matters more than anything a dyslexia-specific face offers.
 */
private val hyperlegible = FontFamily(
    Font(R.font.atkinson_hyperlegible_regular, FontWeight.Normal),
    Font(R.font.atkinson_hyperlegible_bold, FontWeight.Bold),
)

/** OpenDyslexic, with weighted letter bottoms. Offered on preference, not evidence. */
private val dyslexic = FontFamily(
    Font(R.font.open_dyslexic_regular, FontWeight.Normal),
    Font(R.font.open_dyslexic_bold, FontWeight.Bold),
)

fun familyFor(font: ReadingFont): FontFamily = when (font) {
    ReadingFont.SYSTEM -> FontFamily.Default
    ReadingFont.HYPERLEGIBLE -> hyperlegible
    ReadingFont.DYSLEXIC -> dyslexic
}

/**
 * The app's type scale, adjusted for reading.
 *
 * The three numeric adjustments do more work than the font choice. Size, tracking
 * and line spacing have consistent evidence behind them where "dyslexia fonts"
 * have contested evidence, and they help every reader rather than one group — so
 * they are offered as continuous dials rather than buried behind a font name.
 *
 * Applied to the whole scale at once, so nothing is left at a size the rest has
 * grown away from.
 */
fun cgmTypography(preferences: AccessibilityPreferences): Typography {
    val prefs = preferences.sanitised()
    val family = familyFor(prefs.font)
    val base = Typography()

    fun adjust(style: TextStyle): TextStyle = style.copy(
        fontFamily = family,
        fontSize = style.fontSize.scaled(prefs.textScale),
        lineHeight = style.lineHeight.scaled(prefs.textScale * prefs.lineSpacing),
        letterSpacing = style.letterSpacing.plusEm(prefs.letterSpacingEm),
    )

    return Typography(
        displayLarge = adjust(base.displayLarge),
        displayMedium = adjust(base.displayMedium),
        displaySmall = adjust(base.displaySmall),
        headlineLarge = adjust(base.headlineLarge),
        headlineMedium = adjust(base.headlineMedium),
        headlineSmall = adjust(base.headlineSmall),
        titleLarge = adjust(base.titleLarge),
        titleMedium = adjust(base.titleMedium),
        titleSmall = adjust(base.titleSmall),
        bodyLarge = adjust(base.bodyLarge),
        bodyMedium = adjust(base.bodyMedium),
        bodySmall = adjust(base.bodySmall),
        labelLarge = adjust(base.labelLarge),
        labelMedium = adjust(base.labelMedium),
        labelSmall = adjust(base.labelSmall),
    )
}

/** Unspecified sizes stay unspecified; scaling one would invent a value. */
private fun TextUnit.scaled(factor: Float): TextUnit =
    if (isSpecified) (value * factor).sp else this

private fun TextUnit.plusEm(extra: Float): TextUnit =
    if (extra == 0f) this else if (isSpecified) (value + extra * EM_TO_SP).sp else extra.em

/**
 * Tracking is declared in sp across the Material scale rather than in em, so the
 * extra em has to be converted against a representative size. Exact enough: this
 * is a legibility nudge, not a metric.
 */
private const val EM_TO_SP = 14f
