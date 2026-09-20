package dev.cgm.app.service

import android.graphics.Canvas
import android.graphics.Paint

/**
 * Digits drawn by hand, because no font is narrow enough.
 *
 * The status bar gives one small square and scales whatever it is handed to fit,
 * preserving aspect ratio. So for a three-digit number the *width* decides how tall
 * the glyphs can be, and the only way to make them taller is to make each digit
 * narrower. A condensed bold face spends about 0.48 of its height on each digit's
 * width; these spend [DIGIT_ASPECT], which is what buys the extra height.
 *
 * The cost is honest: seven-segment digits look like a digital display rather than
 * text. On a glucose readout that is a reasonable place to land, and it is the last
 * gain available without leaving the status bar for a home-screen widget.
 */
object SevenSegment {

    /** Digit width as a fraction of digit height. Fonts sit nearer 0.5. */
    const val DIGIT_ASPECT = 0.46f

    /** Gap between digits, as a fraction of digit width. */
    private const val GAP = 0.13f

    /** A decimal point's width, as a fraction of digit width. */
    private const val DOT_ASPECT = 0.30f

    /** Stroke thickness as a fraction of digit height. */
    private const val STROKE = 0.17f

    /** True when every character can be drawn here. */
    fun canRender(label: String): Boolean = label.all { it.isDigit() || it == '.' }

    /**
     * Total width this label needs at the given digit [height], in the same units.
     *
     * The caller uses this to pick the height that fits its box, which is why the
     * geometry is a pure function of the label rather than something measured after
     * the fact.
     */
    fun widthFor(label: String, height: Float): Float {
        val digit = height * DIGIT_ASPECT
        var width = 0f
        label.forEachIndexed { index, char ->
            if (index > 0) width += digit * GAP
            width += if (char == '.') digit * DOT_ASPECT else digit
        }
        return width
    }

    /**
     * Draws [label] with its digits [height] tall, starting at [left]/[top].
     *
     * The paint's colour and antialiasing are the caller's; stroke width and caps are
     * set here, because they are part of the glyph shape rather than of its styling.
     */
    fun draw(canvas: Canvas, label: String, left: Float, top: Float, height: Float, paint: Paint) {
        val digit = height * DIGIT_ASPECT
        val thickness = height * STROKE
        paint.strokeWidth = thickness
        paint.strokeCap = Paint.Cap.ROUND

        var x = left
        label.forEachIndexed { index, char ->
            if (index > 0) x += digit * GAP
            if (char == '.') {
                val radius = thickness / 2f
                canvas.drawCircle(x + digit * DOT_ASPECT / 2f, top + height - radius, radius, paint)
                x += digit * DOT_ASPECT
            } else {
                drawDigit(canvas, char, x, top, digit, height, thickness, paint)
                x += digit
            }
        }
    }

    private fun drawDigit(
        canvas: Canvas,
        char: Char,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        thickness: Float,
        paint: Paint,
    ) {
        // Inset by half the stroke so the glyph's outer edge lands on the box, not
        // half a stroke outside it.
        val inset = thickness / 2f
        val l = x + inset
        val r = x + width - inset
        val t = y + inset
        val b = y + height - inset
        val mid = (t + b) / 2f

        val segments = SEGMENTS[char] ?: return
        if (Seg.A in segments) canvas.drawLine(l, t, r, t, paint)
        if (Seg.G in segments) canvas.drawLine(l, mid, r, mid, paint)
        if (Seg.D in segments) canvas.drawLine(l, b, r, b, paint)
        if (Seg.F in segments) canvas.drawLine(l, t, l, mid, paint)
        if (Seg.B in segments) canvas.drawLine(r, t, r, mid, paint)
        if (Seg.E in segments) canvas.drawLine(l, mid, l, b, paint)
        if (Seg.C in segments) canvas.drawLine(r, mid, r, b, paint)
    }

    private enum class Seg { A, B, C, D, E, F, G }

    private val SEGMENTS: Map<Char, Set<Seg>> = mapOf(
        '0' to setOf(Seg.A, Seg.B, Seg.C, Seg.D, Seg.E, Seg.F),
        '1' to setOf(Seg.B, Seg.C),
        '2' to setOf(Seg.A, Seg.B, Seg.G, Seg.E, Seg.D),
        '3' to setOf(Seg.A, Seg.B, Seg.G, Seg.C, Seg.D),
        '4' to setOf(Seg.F, Seg.G, Seg.B, Seg.C),
        '5' to setOf(Seg.A, Seg.F, Seg.G, Seg.C, Seg.D),
        '6' to setOf(Seg.A, Seg.F, Seg.G, Seg.E, Seg.C, Seg.D),
        '7' to setOf(Seg.A, Seg.B, Seg.C),
        '8' to setOf(Seg.A, Seg.B, Seg.C, Seg.D, Seg.E, Seg.F, Seg.G),
        '9' to setOf(Seg.A, Seg.B, Seg.C, Seg.D, Seg.F, Seg.G),
    )
}
