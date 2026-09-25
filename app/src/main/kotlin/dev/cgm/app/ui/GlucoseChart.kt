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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.abs
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.text.font.FontWeight
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import dev.cgm.core.ChartSeries
import dev.cgm.core.ColorVision
import dev.cgm.core.Forecast
import dev.cgm.core.GlucoseReading
import dev.cgm.core.GlucoseThresholds
import dev.cgm.core.GlucoseUnit
import dev.cgm.core.TimeAxis
import dev.cgm.core.TimeTick
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
    val vision = LocalColorVision.current
    val calloutSurface = MaterialTheme.colorScheme.surfaceVariant
    val measurer = rememberTextMeasurer()

    /** The reading being inspected, or null. Read only inside the draw lambda. */
    var selected by remember { mutableStateOf<GlucoseReading?>(null) }
    var widthPx by remember { mutableIntStateOf(0) }

    val shown = forecast?.takeIf { isLive }
    val firstTime = readings.firstOrNull()?.timestampMillis ?: 0L
    val lastTime = (readings.lastOrNull()?.timestampMillis ?: 0L) + (shown?.horizonMillis ?: 0L)
    val timeSpan = (lastTime - firstTime).coerceAtLeast(1L)
    val gutterPx = with(LocalDensity.current) { AXIS_GUTTER.toPx() }

    /**
     * Which reading a touch landed on.
     *
     * Held through [rememberUpdatedState] so the tap detector below can key on
     * `Unit`. Keying it on the readings instead tears the detector down and
     * rebuilds it every time one arrives — including halfway through a pinch,
     * which is what made zooming feel broken the first time this was attempted.
     */
    val hitTest by rememberUpdatedState<(Float) -> GlucoseReading?> { x ->
        if (readings.isEmpty() || widthPx <= 0) null
        else {
            val plot = (widthPx - gutterPx).coerceAtLeast(1f)
            val at = firstTime + (((x - gutterPx) / plot).coerceIn(0f, 1f) * timeSpan).toLong()
            readings.minByOrNull { abs(it.timestampMillis - at) }
        }
    }

    Box(
        modifier
            .onSizeChanged { widthPx = it.width }
            // Tap only. A long press ahead of the transform detector made every
            // drag wait half a second for it to decide, and a drag-to-inspect
            // wrote state on every pointer event. A tap costs one write, once.
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val hit = hitTest(offset.x)
                    selected = if (hit == selected) null else hit
                }
            }
            .pointerInput(Unit) {
            // Reported incrementally through the gesture, and 1f means the fingers
            // rotated or panned without scaling — nothing to do with zoom.
                detectTransformGestures { _, pan, zoom, _ ->
                    // Guarded so this is one write at the start of a gesture
                    // rather than one per pointer event.
                    if (selected != null) selected = null
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

        // The axis has to contain the band, or the projection gets clipped by the
        // top of the chart exactly when it is saying something worth seeing.
        val axis = ValueAxis.of(
            readings.map { it.valueMgdl } +
                (shown?.points?.flatMap { listOf(it.lowMgdl, it.highMgdl) } ?: emptyList()),
            thresholds,
        )
        val labelStyle = TextStyle(fontSize = 11.sp, color = labelColor)

        // Formatting eight labels through java.time on every frame would be work
        // done once per pinch event for an answer that only changes when the window
        // does, so it is remembered against the window's own edges.
        val ticks = remember(firstTime, lastTime) {
            TimeAxis.ticks(firstTime, lastTime, ZoneId.systemDefault())
        }

        Canvas(Modifier.fillMaxSize()) {
            // Left gutter for the axis labels, so the trace never runs under them.
            val gutter = AXIS_GUTTER.toPx()
            val plotWidth = (size.width - gutter).coerceAtLeast(1f)
            // And a strip along the bottom for the clock. Taken off the plot rather
            // than drawn over it: a label sitting on the trace is a label that
            // obscures exactly the reading someone is trying to date.
            //
            // Measured rather than fixed, because 11sp is 11sp times whatever font
            // scale the phone is set to — a hardcoded strip clips its own labels for
            // anyone who has turned text size up, which is precisely the reader who
            // needs them.
            val axisHeight = measurer.measure("00:00", labelStyle).size.height +
                TIME_LABEL_GAP.toPx() * 2
            val plotHeight = (size.height - axisHeight).coerceAtLeast(1f)

            fun y(valueMgdl: Double) = (1f - axis.fraction(valueMgdl)) * plotHeight

            // firstTime, lastTime and timeSpan come from the composable scope, so
            // the hit test and the drawing cannot name different readings.
            fun x(t: Long) = gutter + ((t - firstTime).toFloat() / timeSpan) * plotWidth

            drawInRangeBand(band, gutter, plotWidth, ::y, thresholds)
            drawGrid(axis, grid, labelColor, gutter, plotWidth, ::y, measurer, labelStyle, unit)
            drawTimeAxis(
                ticks = ticks,
                gridColor = grid,
                labelColor = labelColor,
                gutter = gutter,
                plotHeight = plotHeight,
                measurer = measurer,
                labelStyle = labelStyle,
                x = ::x,
            )
            drawAlarmLevels(alarmLevels, axis, alarmColor, gutter, plotWidth, ::y)
            drawTrace(readings, trace, ::x, ::y, plotWidth)
            shown?.let { drawForecast(it, forecastColor, plotHeight, ::x, ::y) }
            selected?.takeIf { it.timestampMillis in firstTime..lastTime }?.let {
                drawInspection(
                    reading = it,
                    unit = unit,
                    colour = ZoneColors.of(thresholds.classify(it.valueMgdl), vision),
                    labelColor = labelColor,
                    surface = calloutSurface,
                    measurer = measurer,
                    plotHeight = plotHeight,
                    x = ::x,
                    y = ::y,
                )
            }

            if (isLive) {
                drawCurrentPoint(
                    vision = vision,
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
    plotHeight: Float,
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
        end = Offset(x(origin), plotHeight),
        strokeWidth = GRID_STROKE.toPx(),
    )
}

/**
 * The callout for an inspected reading: a marker on the trace and its value and
 * time in a box.
 *
 * The time is the point of it. The chart answers "what shape was the day" well
 * enough, but "what exactly was it, and when" needs a number and a clock, and at
 * seven days' zoom a pixel is a quarter of an hour — nothing can be read off the
 * axis.
 *
 * The box is placed on whichever side of the marker has room, because the reading
 * worth inspecting is often the one at the edge of the window.
 */
private fun DrawScope.drawInspection(
    reading: GlucoseReading,
    unit: GlucoseUnit,
    colour: Color,
    labelColor: Color,
    surface: Color,
    measurer: TextMeasurer,
    plotHeight: Float,
    x: (Long) -> Float,
    y: (Double) -> Float,
) {
    val markerX = x(reading.timestampMillis)
    val markerY = y(reading.valueMgdl)

    // A full-height line, so the moment is locatable even where the trace is flat.
    drawLine(
        color = colour.copy(alpha = 0.55f),
        start = Offset(markerX, 0f),
        end = Offset(markerX, plotHeight),
        strokeWidth = GRID_STROKE.toPx(),
    )
    drawCircle(color = colour, radius = CURRENT_RADIUS.toPx() * 0.8f, center = Offset(markerX, markerY))
    drawCircle(
        color = surface,
        radius = CURRENT_RADIUS.toPx() * 0.35f,
        center = Offset(markerX, markerY),
    )

    val value = measurer.measure(
        "${unit.format(reading.valueMgdl)} ${unit.suffix}",
        TextStyle(fontSize = 13.sp, color = labelColor, fontWeight = FontWeight.Bold),
    )
    val stamp = measurer.measure(
        InspectionFormat.of(reading.timestampMillis),
        TextStyle(fontSize = 11.sp, color = labelColor),
    )

    val padding = 8.dp.toPx()
    val boxWidth = maxOf(value.size.width, stamp.size.width) + padding * 2
    val boxHeight = value.size.height + stamp.size.height + padding * 2

    // Prefer the right of the marker, flip when that would run off the chart.
    val left = if (markerX + CALLOUT_GAP.toPx() + boxWidth <= size.width) {
        markerX + CALLOUT_GAP.toPx()
    } else {
        markerX - CALLOUT_GAP.toPx() - boxWidth
    }.coerceAtLeast(0f)
    val top = (markerY - boxHeight - CALLOUT_GAP.toPx())
        .coerceIn(0f, (plotHeight - boxHeight).coerceAtLeast(0f))

    drawRoundRect(
        color = surface,
        topLeft = Offset(left, top),
        size = Size(boxWidth, boxHeight),
        cornerRadius = CornerRadius(8.dp.toPx()),
    )
    drawText(value, topLeft = Offset(left + padding, top + padding))
    drawText(stamp, topLeft = Offset(left + padding, top + padding + value.size.height))
}

/**
 * The time of an inspected reading.
 *
 * Always carries the date, not only the clock: the chart can be browsed back
 * through weeks, and "14:32" alone would be true of every one of them.
 */
private object InspectionFormat {
    private val formatter = DateTimeFormatter.ofPattern("d MMM · HH:mm")

    fun of(millis: Long): String = Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(formatter)
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
 * The clock along the bottom, and a faint line down from each label.
 *
 * Without it the chart says what your glucose did but not when, and the only way
 * to find out was to tap a point — so a window browsed back three days looked
 * exactly like this morning's. The labels are wall-clock times rather than offsets
 * from now, and the tick that crosses local midnight carries its date instead of
 * "00:00", because on this chart *which day* is the thing that must never be
 * ambiguous.
 *
 * The lines are drawn as background rather than as ticks below the axis: they are
 * what lets a dot be read across to a time without a finger on the screen, which
 * is the whole request. Day boundaries get a stronger one — it is a bigger claim
 * than "three hours later".
 *
 * Labels are dropped when they would collide rather than shrunk or rotated. A
 * ladder of round spacings already keeps them sparse, and the one case that still
 * overlaps is a date beside a clock time at the edge of the window, where losing
 * the clock time costs nothing.
 */
private fun DrawScope.drawTimeAxis(
    ticks: List<TimeTick>,
    gridColor: Color,
    labelColor: Color,
    gutter: Float,
    plotHeight: Float,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
    x: (Long) -> Float,
) {
    if (ticks.isEmpty()) return
    val gap = LABEL_GAP.toPx()
    var occupiedTo = gutter - gap

    ticks.forEach { tick ->
        val tickX = x(tick.atMillis)
        drawLine(
            color = gridColor.copy(
                alpha = if (tick.startsDay) DAY_LINE_ALPHA else TIME_LINE_ALPHA
            ),
            start = Offset(tickX, 0f),
            end = Offset(tickX, plotHeight),
            strokeWidth = GRID_STROKE.toPx(),
        )

        val measured = measurer.measure(tick.label, labelStyle)
        // Centred on the tick, then pulled back inside the chart so the first and
        // last labels stay readable instead of running off the edge.
        val left = (tickX - measured.size.width / 2f)
            .coerceIn(gutter, (size.width - measured.size.width).coerceAtLeast(gutter))
        if (left < occupiedTo + gap) return@forEach

        drawText(
            textMeasurer = measurer,
            text = tick.label,
            topLeft = Offset(left, plotHeight + TIME_LABEL_GAP.toPx()),
            style = labelStyle.copy(color = labelColor),
        )
        occupiedTo = left + measured.size.width
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
    vision: ColorVision,
    reading: GlucoseReading,
    thresholds: GlucoseThresholds,
    staleColor: Color?,
    x: (Long) -> Float,
    y: (Double) -> Float,
) {
    drawCircle(
        color = staleColor ?: ZoneColors.of(thresholds.classify(reading.valueMgdl), vision),
        radius = CURRENT_RADIUS.toPx(),
        center = Offset(x(reading.timestampMillis), y(reading.valueMgdl)),
    )
}

private val AXIS_GUTTER = 34.dp
private val LABEL_GAP = 6.dp

/**
 * Air above and below the clock labels.
 *
 * The strip's height is the label's own measured height plus twice this, so it
 * follows the font scale. Kept tight either way: this is height taken away from the
 * trace, which is the reason the screen exists.
 */
private val TIME_LABEL_GAP = 2.5.dp

/**
 * Fainter than the value gridlines. The horizontal lines are read against —
 * "is this above 180" — while these only locate a moment, and a grid of equal
 * weight in both directions turns the chart into graph paper.
 */
private const val TIME_LINE_ALPHA = 0.22f

/** Midnight is a stronger statement than "three hours later", so it is a stronger line. */
private const val DAY_LINE_ALPHA = 0.5f
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
private val CALLOUT_GAP = 10.dp
private val DASH_ON = 4.dp
private val DASH_OFF = 4.dp

/**
 * Below this, dots merge into the line and stop carrying information. Raised with
 * the dot size — wider dots run into each other sooner, and a solid row of them is
 * just a fatter line that costs more to draw.
 */
private val MIN_DOT_SPACING = 9.dp
