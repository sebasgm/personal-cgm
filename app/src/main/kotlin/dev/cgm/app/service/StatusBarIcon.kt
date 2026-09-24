package dev.cgm.app.service

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.content.Context
import androidx.core.content.res.ResourcesCompat
import dev.cgm.app.R
import dev.cgm.core.ReadingFont
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import kotlin.math.ceil

/**
 * The glucose value, drawn as the notification's small icon (issue #11).
 *
 * Android gives a notification one small icon and no way to put text in the status
 * bar, so the number has to *become* the icon — the same trick xDrip uses. A
 * vector drawable cannot carry a value that changes every minute, so this renders
 * a bitmap per distinct label.
 *
 * The glyphs are drawn white on transparent because the system tints small icons
 * to contrast with the status bar it is drawing them on; supplying our own colour
 * would either be overridden or, worse, survive onto a background it cannot be
 * read against. Alpha is what carries the shape.
 *
 * ## What actually controls the size
 *
 * The system scales the icon to fit a fixed square, preserving aspect. So the
 * height it ends up drawn at is
 *
 *     slot size × (glyph height / the larger of the bitmap's two dimensions)
 *
 * which gives exactly two levers, and the first one was being thrown away:
 *
 *  1. **Do not pad the bitmap.** It used to be a 96×96 square holding glyphs that
 *     were only about 52 rows tall, which asked the system to draw them at 52/96
 *     of the slot. Sizing the bitmap to the label recovers all of that.
 *  2. **Make the glyphs narrower.** For three digits it is width that hits the
 *     limit, so a narrower digit is a *larger* one — see [SevenSegment.DIGIT_ASPECT].
 *
 * Together those are worth roughly a third more height than the previous square,
 * squarer-digit version, and a narrow label like "111" now comes out taller than
 * it is wide, so it is drawn at the slot's full size.
 */
object StatusBarIcon {

    /**
     * Render height. Well above what the status bar shows, then downscaled by the
     * system — the extra pixels are what keep three digits from turning to mush on
     * a high-density screen. This controls sharpness, not size.
     */
    private const val HEIGHT_PX = 96

    /** A pixel each side, so antialiasing at the outer edge is not clipped. */
    private const val PAD_PX = 1

    /** Used for "HI", "LO" and "?" when no typeface was chosen. */
    private val condensed: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = condensed
        textAlign = Paint.Align.CENTER
    }
    private val bounds = Rect()

    private var cachedLabel: String? = null
    private var cached: IconCompat? = null
    private var cachedFont: ReadingFont? = null

    /**
     * An icon showing [label], reusing the last one when the value has not moved.
     *
     * The cache matters more than its size suggests: the service refreshes this
     * notification after every poll, so without it a bitmap would be allocated
     * every minute forever, and most of those polls return the same number.
     */
    /**
     * An icon showing [label], in whichever typeface the reader chose.
     *
     * The font setting wins over size here, and that is a real trade. Digits are
     * drawn by hand precisely because narrow ones are drawn *larger* — width is
     * what hits the icon slot's limit first — so a real typeface, whose glyphs
     * are wider, comes out smaller.
     *
     * Someone who has gone to Settings and picked a legibility or dyslexia face
     * has said something specific about how they read characters, and that should
     * hold in the one place they glance at most. Someone on the system default has
     * asked for nothing, so they keep the largest number available.
     */
    @Synchronized
    fun of(context: Context, label: String, font: ReadingFont): IconCompat {
        cached?.let { if (cachedLabel == label && cachedFont == font) return it }
        return render(context, label, font).also {
            cachedLabel = label
            cachedFont = font
            cached = it
        }
    }

    private fun render(context: Context, label: String, font: ReadingFont): IconCompat {
        val typeface = typefaceFor(context, font)
        val bitmap = when {
            // Only the hand-drawn digits reach the full size, and only the system
            // default leaves the choice to us.
            typeface == null && SevenSegment.canRender(label) -> renderDigits(label)
            else -> renderText(label, typeface)
        }
        return IconCompat.createWithBitmap(bitmap)
    }

    /** Null means no preference expressed, so the size-optimised path is used. */
    private fun typefaceFor(context: Context, font: ReadingFont): Typeface? = when (font) {
        ReadingFont.SYSTEM -> null
        ReadingFont.HYPERLEGIBLE ->
            ResourcesCompat.getFont(context, R.font.atkinson_hyperlegible_bold)
        ReadingFont.DYSLEXIC ->
            ResourcesCompat.getFont(context, R.font.open_dyslexic_bold)
    }

    /** Digits drawn at full render height, in a bitmap only as wide as they need. */
    private fun renderDigits(label: String): Bitmap {
        val height = HEIGHT_PX.toFloat()
        val width = SevenSegment.widthFor(label, height)
        val bitmap = createBitmap(
            ceil(width).toInt() + PAD_PX * 2,
            HEIGHT_PX + PAD_PX * 2,
            Bitmap.Config.ARGB_8888,
        )
        SevenSegment.draw(
            canvas = Canvas(bitmap),
            label = label,
            left = PAD_PX.toFloat(),
            top = PAD_PX.toFloat(),
            height = height,
            paint = paint,
        )
        return bitmap
    }

    /** Anything not made of digits — "HI", "LO", "?", "--" — comes from the font. */
    private fun renderText(label: String, typeface: Typeface? = null): Bitmap {
        paint.typeface = typeface ?: condensed
        // Measure once at an arbitrary size, then scale so the glyphs are exactly
        // HEIGHT_PX tall, and size the bitmap to whatever width that needs.
        paint.textSize = HEIGHT_PX.toFloat()
        paint.getTextBounds(label, 0, label.length, bounds)
        if (bounds.height() > 0) {
            paint.textSize = HEIGHT_PX * (HEIGHT_PX.toFloat() / bounds.height())
            paint.getTextBounds(label, 0, label.length, bounds)
        }

        val bitmap = createBitmap(
            (bounds.width() + PAD_PX * 2).coerceAtLeast(1),
            (bounds.height() + PAD_PX * 2).coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        Canvas(bitmap).drawText(
            label,
            bitmap.width / 2f,
            PAD_PX - bounds.top.toFloat(),
            paint,
        )
        return bitmap
    }
}
