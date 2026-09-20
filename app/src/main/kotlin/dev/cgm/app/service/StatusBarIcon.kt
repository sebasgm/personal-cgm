package dev.cgm.app.service

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import kotlin.math.min

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
 */
object StatusBarIcon {

    /**
     * Rendered well above the ~24dp the status bar will show, then downscaled by
     * the system. Cheaper than it looks — [of] caches — and the extra pixels are
     * what keep three digits from turning to mush on a high-density screen.
     *
     * Raising this does not make the number look bigger. The system scales whatever
     * it is given into a fixed slot, so this controls sharpness, not size.
     */
    private const val SIZE_PX = 96

    /**
     * How much of the square the glyphs may use.
     *
     * **This is the whole ceiling on apparent size, and it is width, not font size.**
     * Three digits laid across a ~24dp slot leaves each one about 7dp wide; the
     * height then follows from the aspect ratio, because Android scales the bitmap
     * into its slot without distorting it. "124" reaches this limit horizontally
     * while using only about half the height — that empty space above and below is
     * not wasted room for a bigger number, it is what a wide, short thing looks like
     * inside a square.
     *
     * Which also means **a digit cannot be made absolutely wider**: three of them
     * always fill the slot, so widening the proportion only makes them shorter.
     * What that buys is legibility of shape rather than size — a squarer glyph
     * reads better at arm's length than an elongated one of the same width — and
     * [SevenSegment.DIGIT_ASPECT] is the dial. The one change that escapes the
     * trade is narrowing the '1', which hands width back to its neighbours.
     * Beyond that the only way is a surface without the cap — a home-screen widget.
     *
     * Kept just under 1 so antialiasing at the edges is not clipped.
     */
    private const val USABLE = 0.97f

    /**
     * Tighter than the font intends, because horizontal space is the binding
     * constraint: every fraction of an em saved between digits is spent on making
     * all of them taller.
     */
    private const val TRACKING_EM = -0.03f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        // Condensed, so three digits fit across the slot at a larger size than the
        // default face allows. This is the single biggest win available here.
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        letterSpacing = TRACKING_EM
    }
    private val bounds = Rect()

    private var cachedLabel: String? = null
    private var cached: IconCompat? = null

    /**
     * An icon showing [label], reusing the last one when the value has not moved.
     *
     * The cache matters more than its size suggests: the service refreshes this
     * notification after every poll, so without it a bitmap would be allocated
     * every minute forever, and most of those polls return the same number.
     */
    @Synchronized
    fun of(label: String): IconCompat {
        cached?.let { if (cachedLabel == label) return it }
        return render(label).also {
            cachedLabel = label
            cached = it
        }
    }

    private fun render(label: String): IconCompat {
        val bitmap = createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val limit = SIZE_PX * USABLE

        if (SevenSegment.canRender(label)) {
            drawSegments(canvas, label, limit)
        } else {
            drawText(canvas, label, limit)
        }
        return IconCompat.createWithBitmap(bitmap)
    }

    /**
     * Digits, sized by solving for the height that makes the label exactly fill the
     * width. Width is always the binding constraint for two or more digits, so this
     * is the tallest the glyphs can be — and then capped at the height, for the
     * single-digit case where it is not.
     */
    private fun drawSegments(canvas: Canvas, label: String, limit: Float) {
        val widthAtUnitHeight = SevenSegment.widthFor(label, 1f)
        val height = minOf(limit / widthAtUnitHeight, limit)
        val width = SevenSegment.widthFor(label, height)

        SevenSegment.draw(
            canvas = canvas,
            label = label,
            left = (SIZE_PX - width) / 2f,
            top = (SIZE_PX - height) / 2f,
            height = height,
            paint = paint,
        )
    }

    /** Anything not made of digits — "HI", "LO", "?", "--" — still comes from the font. */
    private fun drawText(canvas: Canvas, label: String, limit: Float) {
        paint.textSize = SIZE_PX.toFloat()
        paint.getTextBounds(label, 0, label.length, bounds)
        val scale = min(limit / bounds.width(), limit / bounds.height())
        paint.textSize = SIZE_PX * scale

        paint.getTextBounds(label, 0, label.length, bounds)
        val centre = SIZE_PX / 2f
        val baseline = centre - (bounds.top + bounds.bottom) / 2f
        canvas.drawText(label, centre, baseline, paint)
    }
}
