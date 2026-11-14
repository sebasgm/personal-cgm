package dev.cgm.app.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp

/**
 * The parts every chart in this app shares.
 *
 * Two of them, and both are here for the same reason: the charts are read one after
 * another down a single screen, so anything they do differently reads as a
 * difference in what they are saying. A gutter of a different width makes two value
 * axes that do not line up; a callout drawn by another convention makes someone who
 * has learned to tap one chart discover a second idiom on the next.
 */

/**
 * The strip down the left where value labels go.
 *
 * One figure for every chart, so their axes line up when they are stacked. Wide
 * enough for three digits of mg/dL, which is also wide enough for any day's units.
 */
internal val AXIS_GUTTER = 34.dp

/**
 * The figures behind whatever was tapped, in a box beside a line marking it.
 *
 * A full-height line through the thing selected and a rounded panel of text on
 * whichever side has room. The panel flips to the other side of the line rather than
 * being clipped, because the point worth inspecting is so often the one at the edge,
 * and it sits vertically centred rather than tied to the value it describes — at
 * four or five lines tall it would otherwise cover its own subject.
 *
 * The first line is the heading and the rest the body, which is the only thing the
 * caller decides besides the words and the accent colour.
 */
internal fun DrawScope.drawCallout(
    lines: List<String>,
    anchorX: Float,
    plotHeight: Float,
    accent: Color,
    surface: Color,
    titleStyle: TextStyle,
    bodyStyle: TextStyle,
    measurer: TextMeasurer,
) {
    if (lines.isEmpty()) return

    drawLine(
        color = accent.copy(alpha = 0.55f),
        start = Offset(anchorX, 0f),
        end = Offset(anchorX, plotHeight),
        strokeWidth = 1.dp.toPx(),
    )

    val measured = lines.mapIndexed { index, line ->
        measurer.measure(line, if (index == 0) titleStyle else bodyStyle)
    }
    val padding = 8.dp.toPx()
    val boxWidth = measured.maxOf { it.size.width } + padding * 2
    val boxHeight = measured.sumOf { it.size.height } + padding * 2

    val gap = CALLOUT_GAP.toPx()
    val left = if (anchorX + gap + boxWidth <= size.width) anchorX + gap
    else anchorX - gap - boxWidth
    val clampedLeft = left.coerceIn(0f, (size.width - boxWidth).coerceAtLeast(0f))
    val top = ((plotHeight - boxHeight) / 2f).coerceAtLeast(0f)

    drawRoundRect(
        color = surface,
        topLeft = Offset(clampedLeft, top),
        size = Size(boxWidth, boxHeight),
        cornerRadius = CornerRadius(8.dp.toPx()),
    )

    var lineTop = top + padding
    measured.forEach { text ->
        drawText(text, topLeft = Offset(clampedLeft + padding, lineTop))
        lineTop += text.size.height
    }
}

/** How far the panel sits from the line it belongs to. */
private val CALLOUT_GAP = 10.dp
