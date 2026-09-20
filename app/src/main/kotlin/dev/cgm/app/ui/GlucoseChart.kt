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
import dev.cgm.core.Forecast
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
    /**
     * Where the trace is projected to go next, or null when the projection is off.
     *
     * Drawn only while [isLive]: a forecast made from the end of last Tuesday is
     * not a forecast, it is a hypothetical about a question already answered.
     */
    forecast: Forecast? = null,
    /**
     * Glucose levels with an alarm switched on, in mg/dL.
     *
     * The chart draws what you asked to be warned about rather than the zone
     * boundaries it used to: the zone edges are a display convention, while these
     * are the numbers that will actually wake you.
     */
    alarmLevels: List<Double> = emptyList(),
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
    val forecastColor = ChartColors.forecast
    val alarmColor = ChartColors.alarmLine
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

        val shown = forecast?.takeIf { isLive }

        // The axis has to contain the band, or the projection gets clipped by the
        // top of the chart exactly when it is saying something worth seeing.
        val axis = ValueAxis.of(
            readings.map { it.valueMgdl } +
                (shown?.points?.flatMap { listOf(it.lowMgdl, it.highMgdl) } ?: emptyList()),
            thresholds,
        )
        val labelStyle = TextStyle(fontSize = 11.sp, color = labelColor)

        Canvas(Modifier.fillMaxSize()) {
            // Left gutter for the axis labels, so the trace never runs under them.
            val gutter = AXIS_GUTTER.toPx()
            val plotWidth = (size.width - gutter).coerceAtLeast(1f)

            fun y(valueMgdl: Double) = (1f - axis.fraction(valueMgdl)) * size.height

            val firstTime = readings.first().timestampMillis
            // Room on the right for the projection. The window still *ends* at the
            // last reading; this only widens what the axis covers.
            val lastTime = readings.last().timestampMillis +
                (shown?.horizonMillis ?: 0L)
            val timeSpan = (lastTime - firstTime).coerceAtLeast(1L)
            fun x(t: Long) = gutter + ((t - firstTime).toFloat() / timeSpan) * plotWidth

            drawInRangeBand(band, gutter, plotWidth, ::y, thresholds)
            drawGrid(axis, grid, labelColor, gutter, plotWidth, ::y, measurer, labelStyle, unit)
            drawAlarmLevels(alarmLevels, axis, alarmColor, gutter, plotWidth, ::y)
            drawTrace(readings, trace, ::x, ::y, plotWidth)
            shown?.let { drawForecast(it, forecastColor, ::x, ::y) }
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
 * The projection: a shaded band with a dashed centre, and a line marking now.
 *
 * Every choice here exists to stop it being mistaken for measurement. It is
 * dashed where the trace is solid, translucent where the trace is opaque, carries
 * no measurement dots, and is separated from the past by a visible boundary. A
 * forecast drawn in the same language as data is a claim that it *is* data.
 */
private fun DrawScope.drawForecast(
    forecast: Forecast,
    color: Color,
    x: (Long) -> Float,
    y: (Double) -> Float,
) {
    if (forecast.points.isEmpty()) return
    val origin = forecast.originMillis

    // The band. Upper edge forward, lower edge back, closed into one shape.
    val band = Path().apply {
        moveTo(x(origin), y(forecast.originValueMgdl))
        forecast.points.forEach { lineTo(x(it.timestampMillis(origin)), y(it.highMgdl)) }
        forecast.points.reversed().forEach {
            lineTo(x(it.timestampMillis(origin)), y(it.lowMgdl))
        }
        close()
    }
    drawPath(band, color = color.copy(alpha = FORECAST_BAND_ALPHA))

    // The centre line, dashed so it cannot read as the trace.
    val centre = Path().apply {
        moveTo(x(origin), y(forecast.originValueMgdl))
        forecast.points.forEach { lineTo(x(it.timestampMillis(origin)), y(it.valueMgdl)) }
    }
    drawPath(
        path = centre,
        color = color,
        style = Stroke(
            width = FORECAST_STROKE.toPx(),
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(FORECAST_DASH.toPx(), FORECAST_DASH.toPx())
            ),
        ),
    )

    // Where measurement stops and guessing starts.
    drawLine(
        color = color.copy(alpha = FORECAST_DIVIDER_ALPHA),
        start = Offset(x(origin), 0f),
        end = Offset(x(origin), size.height),
        strokeWidth = GRID_STROKE.toPx(),
    )
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
private fun DrawScope.drawAlarmLevels(
    levels: List<Double>,
    axis: ValueAxis,
    color: Color,
    gutter: Float,
    plotWidth: Float,
    y: (Double) -> Float,
) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(DASH_ON.toPx(), DASH_OFF.toPx()))
    levels
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
private val FORECAST_STROKE = 2.dp
private val FORECAST_DASH = 5.dp
private const val FORECAST_BAND_ALPHA = 0.16f
private const val FORECAST_DIVIDER_ALPHA = 0.45f
private val DASH_ON = 4.dp
private val DASH_OFF = 4.dp

/**
 * Below this, dots merge into the line and stop carrying information. Raised with
 * the dot size — wider dots run into each other sooner, and a solid row of them is
 * just a fatter line that costs more to draw.
 */
private val MIN_DOT_SPACING = 9.dp
