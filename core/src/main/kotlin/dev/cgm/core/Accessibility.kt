package dev.cgm.core

import kotlinx.serialization.Serializable

/**
 * Typeface used for everything the app draws.
 *
 * The evidence here is not what the marketing suggests, and the options are
 * labelled accordingly rather than ranked by reputation.
 */
enum class ReadingFont {
    /** Whatever the device uses. Familiar, and already tuned by the vendor. */
    SYSTEM,

    /**
     * Atkinson Hyperlegible, commissioned by the Braille Institute.
     *
     * Not a dyslexia font: a *legibility* font, drawn so that characters which
     * normally collapse into each other stay distinct — 1/l/I, 0/O, 6/8, 5/S.
     * That is why it is the default here rather than a dyslexia-specific face.
     * This app is mostly three digits read at a glance, often in poor light and
     * sometimes half-asleep, and confusing 5 for 6 matters more than reading
     * prose quickly.
     */
    HYPERLEGIBLE,

    /**
     * OpenDyslexic, with weighted letter bottoms.
     *
     * Offered because people who prefer it should be able to have it, and
     * preference is a real accessibility outcome. But the controlled evidence is
     * weak: a 2017 study found it *reduced* reading speed and accuracy against
     * Arial and Times, and eye-tracking work found no improvement in fixation.
     * A 2013 study did find fewer errors and a preference for it. It is a genuine
     * option, not a recommendation.
     */
    DYSLEXIC,
}

/**
 * Which zone palette to draw.
 *
 * Colour is never the only channel in this app — zones also carry position,
 * labels and glyphs — but the chart's whole point is read at a glance, and a
 * glance is exactly where colour does the work.
 */
enum class ColorVision {
    /** Red low, green in range, amber high: the convention LibreLink users know. */
    DEFAULT,

    /**
     * Okabe–Ito, designed in 2002 to stay distinguishable under deuteranopia,
     * protanopia and tritanopia, and the de facto standard for scientific figures
     * since Nature Methods picked it up in 2011.
     *
     * Mapped so that **warm means low and cool means high**, with green in the
     * middle. That separates the zones on the blue–yellow axis rather than the
     * red–green one, which is where roughly one man in twelve loses resolution.
     * It also orders them by luminance, so the chart survives being printed or
     * screenshotted in greyscale.
     */
    COLOR_BLIND_SAFE,

    /**
     * The same hue logic, pushed to deeper tones and stronger separation for low
     * vision and for screens read in sunlight.
     */
    HIGH_CONTRAST,
}

/**
 * Reading adjustments.
 *
 * The three numeric controls matter more than the font choice does. Size, letter
 * spacing and line spacing have consistent evidence behind them where "dyslexia
 * fonts" do not, and they help every reader rather than a category of them.
 */
@Serializable
data class AccessibilityPreferences(
    val font: ReadingFont = ReadingFont.HYPERLEGIBLE,
    /** Multiplier on every text size. */
    val textScale: Float = 1.0f,
    /** Added tracking, in em. */
    val letterSpacingEm: Float = 0.0f,
    /** Multiplier on line height. */
    val lineSpacing: Float = 1.0f,
    val colorVision: ColorVision = ColorVision.DEFAULT,
) {
    fun sanitised() = copy(
        textScale = textScale.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE),
        letterSpacingEm = letterSpacingEm.coerceIn(0f, MAX_LETTER_SPACING_EM),
        lineSpacing = lineSpacing.coerceIn(1f, MAX_LINE_SPACING),
    )

    companion object {
        const val MIN_TEXT_SCALE = 0.85f
        const val MAX_TEXT_SCALE = 1.6f

        /** Beyond this, words stop reading as words. */
        const val MAX_LETTER_SPACING_EM = 0.12f
        const val MAX_LINE_SPACING = 1.8f

        val Default = AccessibilityPreferences()
    }
}
