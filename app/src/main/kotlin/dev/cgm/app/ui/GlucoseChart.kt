package dev.cgm.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cgm.core.ChartSeries
import dev.cgm.core.GlucoseReading
import dev.cgm.core.GlucoseThresholds
import dev.cgm.core.GlucoseUnit
import dev.cgm.core.ValueAxis

/**
 * The trace.
 *
 * Lives in its own file because it is the screen's centre of gravity, not a
 * detail of it: five of the open issues are about this drawing.
 *
 * Everything here is drawn at dp-derived sizes. The version this replaced used
 * raw pixel strokes, which is why the line looked hairline-thin on a dense
 * display — 4px is about 1dp on a 4x screen.
 *
 * Also the dry run for the bitmap the Wear app renders in stage 5, so the
 * scaling, banding and gap logic is worth getting right on a screen that is easy
 * to iterate on.
 */
@Composable
fun GlucoseChart(
    readings: List<GlucoseReading>,
    thresholds: GlucoseThresholds,
    unit: GlucoseUnit,
    stale: Boolean,
    /**
     * Whether the window ends at the live edge. While browsing history the newest
     * reading on screen is simply the last one of a window that has passed, not the
     * current value, so it gets no emphasis — marking it would claim a reading from
     * last Tuesday is what your glucose is now.
     */
    isLive: Boolean = true,
    modifier: Modifier = Modifier,
    onZoom: (Float) -> Unit = {},
    onPan: (Float) -> Unit = {},
) {
    val band = ChartColors.band
    val staleColor = ZoneColors.stale
    val trace = if (stale) staleColor else ChartColors.trace
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val emptyColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()

    Box(
        modifier.pointerInput(Unit) {
            // Reported incrementally through the gesture, and 1f means the fingers
            // rotated or panned without scaling — nothing to do with zoom.
            detectTransformGestures { _, pan, zoom, _ ->
                if (zoom != 1f) onZoom(zoom)
                // Reported as a fraction of the chart's width, so the caller can
                // turn it into time without knowing anything about pixels.
                if (pan.x != 0f && size.width > 0) onPan(pan.x / size.width)
            }
        }
    ) {
        if (readings.isEmpty()) {
            Text(
                "no readings in this window yet",
                style = MaterialTheme.typography.bodySmall,
                color = emptyColor,
                modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }

        val axis = ValueAxis.of(readings.map { it.valueMgdl }, thresholds)
        val labelStyle = TextStyle(fontSize = 11.sp, color = labelColor)

        Canvas(Modifier.fillMaxSize()) {
            // Left gutter for the axis labels, so the trace never runs under them.
            val gutter = AXIS_GUTTER.toPx()
            val plotWidth = (size.width - gutter).coerceAtLeast(1f)

            fun y(valueMgdl: Double) = (1f - axis.fraction(valueMgdl)) * size.height

            val firstTime = readings.first().timestampMillis
            val lastTime = readings.last().timestampMillis
            val timeSpan = (lastTime - firstTime).coerceAtLeast(1L)
            fun x(t: Long) = gutter + ((t - firstTime).toFloat() / timeSpan) * plotWidth

            drawInRangeBand(band, gutter, plotWidth, ::y, thresholds)
            drawGrid(axis, grid, labelColor, gutter, plotWidth, ::y, measurer, labelStyle, unit)
            drawZoneEdges(thresholds, axis, grid, gutter, plotWidth, ::y)
            drawTrace(readings, trace, ::x, ::y, plotWidth)
            if (isLive) {
                drawCurrentPoint(
                    reading = readings.last(),
                    thresholds = thresholds,
                    staleColor = if (stale) staleColor else null,
                    x = ::x,
                    y = ::y,
                )
            }
        }
    }
}

/**
 * The pale green stripe LibreLink users read the whole picture against.
 *
 * Drawn first, under everything: it is background, not data.
 */
private fun DrawScope.drawInRangeBand(
    color: Color,
    gutter: Float,
    plotWidth: Float,
    y: (Double) -> Float,
    thresholds: GlucoseThresholds,
) {
    val top = y(thresholds.highMgdl)
    drawRect(
        color = color,
        topLeft = Offset(gutter, top),
        size = Size(plotWidth, (y(thresholds.lowMgdl) - top).coerceAtLeast(0f)),
    )
}

private fun DrawScope.drawGrid(
    axis: ValueAxis,
    gridColor: Color,
    labelColor: Color,
    gutter: Float,
    plotWidth: Float,
    y: (Double) -> Float,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
    unit: GlucoseUnit,
) {
    axis.gridLines().forEach { level ->
        val lineY = y(level)
        drawLine(
            color = gridColor.copy(alpha = 0.4f),
            start = Offset(gutter, lineY),
            end = Offset(gutter + plotWidth, lineY),
            strokeWidth = GRID_STROKE.toPx(),
        )

        // Labels in the user's own unit: an axis in mg/dL beside a big mmol/L
        // number would be two different charts sharing a screen.
        val text = unit.format(level)
        val measured = measurer.measure(text, labelStyle)
        drawText(
            textMeasurer = measurer,
            text = text,
            topLeft = Offset(
                x = (gutter - LABEL_GAP.toPx() - measured.size.width).coerceAtLeast(0f),
                y = lineY - measured.size.height / 2f,
            ),
            style = labelStyle.copy(color = labelColor),
        )
    }
}

/**
 * Urgent-low and very-high, dashed.
 *
 * These are the boundaries worth seeing but not worth shading: solid bands for
 * all five zones would turn the chart into a flag.
 */
private fun DrawScope.drawZoneEdges(
    thresholds: GlucoseThresholds,
    axis: ValueAxis,
    color: Color,
    gutter: Float,
    plotWidth: Float,
    y: (Double) -> Float,
) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(DASH_ON.toPx(), DASH_OFF.toPx()))
    listOf(thresholds.urgentLowMgdl, thresholds.veryHighMgdl)
        .filter { it > axis.minMgdl && it < axis.maxMgdl }
        .forEach { level ->
            drawLine(
                color = color,
                start = Offset(gutter, y(level)),
                end = Offset(gutter + plotWidth, y(level)),
                strokeWidth = GRID_STROKE.toPx(),
                pathEffect = dash,
            )
        }
}

/**
 * One path per continuous run, plus a dot on every real measurement.
 *
 * The breaks are the point. A single line through a gap would draw readings that
 * were never taken, and the dots are what let you tell a measured point from the
 * line between two of them.
 *
 * Dots are dropped when they would collide: at 24 hours there are over a thousand
 * readings across a phone's width, and a dot per pixel is not a dot, it is a
 * thicker line that happens to cost more to draw.
 */
private fun DrawScope.drawTrace(
    readings: List<GlucoseReading>,
    color: Color,
    x: (Long) -> Float,
    y: (Double) -> Float,
    plotWidth: Float,
) {
    val segments = ChartSeries.segments(readings)
    val spacing = plotWidth / readings.size.coerceAtLeast(1)
    val showDots = spacing >= MIN_DOT_SPACING.toPx()
    val dotRadius = DOT_RADIUS.toPx()

    segments.forEach { segment ->
        if (segment.size >= 2) {
            val path = Path().apply {
                moveTo(x(segment.first().timestampMillis), y(segment.first().valueMgdl))
                segment.drop(1).forEach { lineTo(x(it.timestampMillis), y(it.valueMgdl)) }
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = TRACE_STROKE.toPx(), cap = StrokeCap.Round),
            )
        }

        // A lone reading between two gaps has no line to belong to, so it is drawn
        // whatever the spacing says — otherwise it would vanish entirely.
        if (showDots || segment.size == 1) {
            segment.forEach {
                drawCircle(
                    color = color,
                    radius = dotRadius,
                    center = Offset(x(it.timestampMillis), y(it.valueMgdl)),
                )
            }
        }
    }
}

/**
 * The newest reading, in its zone colour: the one point that is also a status.
 *
 * When the data is stale the zone colour is dropped, exactly as the big number
 * above drops it. A green dot that happens to be forty minutes old reads as
 * "fine", which is the dangerous lie this screen exists to avoid.
 */
private fun DrawScope.drawCurrentPoint(
    reading: GlucoseReading,
    thresholds: GlucoseThresholds,
    staleColor: Color?,
    x: (Long) -> Float,
    y: (Double) -> Float,
) {
    drawCircle(
        color = staleColor ?: ZoneColors.of(thresholds.classify(reading.valueMgdl)),
        radius = CURRENT_RADIUS.toPx(),
        center = Offset(x(reading.timestampMillis), y(reading.valueMgdl)),
    )
}

private val AXIS_GUTTER = 34.dp
private val LABEL_GAP = 6.dp
/**
 * Thin on purpose. The line is context; the dots are the measurements, and every
 * bit taken off the stroke makes them stand out more without growing them further.
 */
private val TRACE_STROKE = 1.9.dp
private val GRID_STROKE = 1.dp
/**
 * Measured points have to out-read the line joining them, so the dot is wider than
 * the stroke rather than merely wider than half of it. At a 2dp radius the dot was
 * 4dp across against a 3dp line — technically larger, visually a slight bulge. At
 * 3.5dp against a 2.25dp stroke it is over three times the width of the line.
 */
private val DOT_RADIUS = 3.5.dp

/** The current reading stays unmistakably the largest thing on the trace. */
private val CURRENT_RADIUS = 6.dp
private val DASH_ON = 4.dp
private val DASH_OFF = 4.dp

/**
 * Below this, dots merge into the line and stop carrying information. Raised with
 * the dot size — wider dots run into each other sooner, and a solid row of them is
 * just a fatter line that costs more to draw.
 */
private val MIN_DOT_SPACING = 9.dp
