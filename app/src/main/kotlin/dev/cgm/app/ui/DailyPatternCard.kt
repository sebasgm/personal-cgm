package dev.cgm.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cgm.app.R
import dev.cgm.core.ColorVision
import dev.cgm.core.GlucoseThresholds
import dev.cgm.core.GlucoseUnit
import dev.cgm.core.TimeOfDayBucket
import dev.cgm.core.TimeOfDayProfile
import dev.cgm.core.ValueAxis

/**
 * The shape of a typical day, drawn twice.
 *
 * The two charts are not alternatives and neither replaces the other. They are the
 * same percentiles over the same window, cut at two widths, and they answer
 * different questions:
 *
 *  - **The ribbon** is continuous across the 24 hours at one-hour resolution — the
 *    view the FreeStyle Libre app calls Daily Patterns. What it answers is *when*:
 *    where a rise starts, how long a plateau lasts, whether the night drifts. A
 *    question whose answer is a time needs a chart that is continuous in time.
 *  - **The slices** are eight three-hour boxes, the construction LibreView shows and
 *    issue #8 asked for. What they answer is *how much*: the exact middle 50% and
 *    10–90 spread of a named part of the day, as discrete figures that can be read
 *    off and compared without interpolating anything.
 *
 * Both are bands rather than averages, which is the point of either of them: an hour
 * averaging 140 with a 60–260 spread and one averaging 140 with a 125–155 spread are
 * completely different situations, and a chart of averages draws them identically.
 *
 * Tapping either chart gives the numbers behind a slice, including how many days are
 * behind it — a slice built from two days is a different claim from one built from
 * thirty, and that is not visible in a band's width.
 *
 * One card rather than two so the caveats underneath — thin data, mixed time zones —
 * are stated once. They apply to both charts, and the same warning printed twice in a
 * row reads as a bug rather than as emphasis.
 */
@Composable
fun DailyPatternCard(
    profile: TimeOfDayProfile,
    thresholds: GlucoseThresholds,
    unit: GlucoseUnit,
) {
    // Read here rather than inside the drawing lambdas: both are composition-scoped
    // values, and a draw lambda is not a composable.
    val ribbonColour = ChartColors.trace
    val vision = LocalColorVision.current

    Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.trends_daily_pattern),
                style = MaterialTheme.typography.titleSmall,
            )

            if (!profile.hasData) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.trends_daily_pattern_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.trends_daily_pattern_days, profile.dayCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // One range for both charts, so neither can look calmer than the other
            // purely through a scale of its own.
            val range = profile.valueRange()

            Spacer(Modifier.height(12.dp))
            PatternChart(
                // Hours, falling back to the three-hour slices for a profile computed
                // before the finer resolution existed. The ribbon does not care how
                // wide a slice is, only where its centre sits on the day.
                slices = profile.hours.ifEmpty { profile.buckets },
                range = range,
                thresholds = thresholds,
                unit = unit,
                modifier = Modifier.fillMaxWidth().height(190.dp),
            ) { slices, x, y ->
                slices.runsWithData().forEach { run ->
                    drawRibbon(run, ribbonColour, vision, thresholds, x, y)
                }
            }
            Text(
                stringResource(R.string.trends_daily_pattern_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            Spacer(Modifier.height(18.dp))
            HorizontalDivider()
            Spacer(Modifier.height(14.dp))

            Text(
                stringResource(R.string.trends_slices),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(10.dp))
            PatternChart(
                slices = profile.buckets,
                range = range,
                thresholds = thresholds,
                unit = unit,
                modifier = Modifier.fillMaxWidth().height(180.dp),
            ) { slices, x, y ->
                slices.forEach { slice ->
                    if (slice.hasData) drawSlice(slice, vision, thresholds, x, y)
                }
            }
            Text(
                stringResource(R.string.trends_slices_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            Spacer(Modifier.height(10.dp))
            profile.mostVariable()?.takeIf { it.hasData }?.let {
                Text(
                    stringResource(R.string.trends_most_variable, it.label()),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                stringResource(R.string.trends_inspect_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            if (profile.spansMultipleZones) {
                Text(
                    stringResource(
                        R.string.trends_daily_pattern_zones,
                        profile.zoneIds.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (profile.dayCount < TimeOfDayProfile.MIN_DAYS) {
                Text(
                    stringResource(
                        R.string.trends_daily_pattern_thin,
                        TimeOfDayProfile.MIN_DAYS,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * Everything both charts share: the target band, the value axis, the clock along the
 * bottom, the tap that selects a slice and the callout that reports it.
 *
 * Only the series itself differs, so only the series is passed in. Two copies of the
 * axis arithmetic would be two chances for the charts to disagree about which hour a
 * pixel is — and they sit one above the other, where any disagreement is visible.
 */
@Composable
private fun PatternChart(
    slices: List<TimeOfDayBucket>,
    range: ClosedFloatingPointRange<Double>?,
    thresholds: GlucoseThresholds,
    unit: GlucoseUnit,
    modifier: Modifier = Modifier,
    drawSeries: DrawScope.(
        slices: List<TimeOfDayBucket>,
        x: (Double) -> Float,
        y: (Double) -> Float,
    ) -> Unit,
) {
    val band = ChartColors.band
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val calloutSurface = MaterialTheme.colorScheme.surfaceVariant
    val vision = LocalColorVision.current
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)
    val titleStyle = TextStyle(fontSize = 12.sp, color = labelColor, fontWeight = FontWeight.Bold)
    val bodyStyle = TextStyle(fontSize = 11.sp, color = labelColor)

    /**
     * Which slice is open, held as its start hour rather than as the slice itself.
     *
     * Switching the Trends period rebuilds every slice, and a held object would keep
     * drawing figures from a window that is no longer on screen. A start hour is
     * re-resolved against whatever the current window says about that hour, and simply
     * closes if that hour now has nothing.
     */
    var selectedHour by remember { mutableStateOf<Int?>(null) }
    var widthPx by remember { mutableIntStateOf(0) }
    val gutterPx = with(LocalDensity.current) { AXIS_GUTTER.toPx() }

    val hitTest by rememberUpdatedState<(Float) -> Int?> { tapX ->
        if (widthPx <= 0 || slices.isEmpty()) null
        else {
            val plot = (widthPx - gutterPx).coerceAtLeast(1f)
            val hour = ((tapX - gutterPx) / plot).coerceIn(0f, 1f) * HOURS_IN_DAY
            slices.firstOrNull { hour >= it.startHour && hour < it.endHour }?.startHour
        }
    }

    val selected = selectedHour
        ?.let { hour -> slices.firstOrNull { it.startHour == hour } }
        ?.takeIf { it.hasData }
    val callout = selected?.let { calloutLines(it, unit) }

    Canvas(
        modifier
            .onSizeChanged { widthPx = it.width }
            // Tap only, as on the main chart. A second tap on the same slice closes
            // it, so nothing has to be dismissed from somewhere else.
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val hit = hitTest(offset.x)
                    selectedHour = if (hit == selectedHour) null else hit
                }
            }
    ) {
        val gutter = AXIS_GUTTER.toPx()
        // Measured, so the hour labels are not clipped on a phone with text size
        // turned up: 10sp is 10sp times the device's font scale.
        val axisHeight = measurer.measure("00", labelStyle).size.height + HOUR_LABEL_GAP.toPx() * 2
        val plotWidth = (size.width - gutter).coerceAtLeast(1f)
        val plotHeight = (size.height - axisHeight).coerceAtLeast(1f)

        // Scaled to the bands rather than to single values, so a wide slice is not
        // clipped at the top of the chart.
        val axis = ValueAxis.of(listOfNotNull(range?.start, range?.endInclusive), thresholds)

        fun y(value: Double) = (1f - axis.fraction(value)) * plotHeight
        fun x(hour: Double) = gutter + (hour / HOURS_IN_DAY).toFloat() * plotWidth

        drawRect(
            color = band,
            topLeft = Offset(gutter, y(thresholds.highMgdl)),
            size = Size(
                plotWidth,
                (y(thresholds.lowMgdl) - y(thresholds.highMgdl)).coerceAtLeast(0f),
            ),
        )

        axis.gridLines().forEach { level ->
            drawLine(
                color = grid,
                start = Offset(gutter, y(level)),
                end = Offset(size.width, y(level)),
                strokeWidth = 1.dp.toPx(),
            )
            drawText(
                textMeasurer = measurer,
                text = unit.format(level),
                topLeft = Offset(0f, y(level) - 7.dp.toPx()),
                style = labelStyle,
            )
        }

        // The clock: a line every three hours, a number every six. Lines at the finer
        // spacing and labels at the coarser one — reading a rise off the ribbon needs
        // something to measure against, and nine numbers along the bottom of a card
        // this size is a row of digits rather than an axis.
        for (hour in 0..HOURS_IN_DAY.toInt() step HOUR_GRID) {
            val lineX = x(hour.toDouble())
            drawLine(
                color = grid.copy(alpha = if (hour % HOUR_LABEL == 0) 0.7f else 0.35f),
                start = Offset(lineX, 0f),
                end = Offset(lineX, plotHeight),
                strokeWidth = 1.dp.toPx(),
            )
            if (hour % HOUR_LABEL != 0) continue

            // Midnight sits at both ends of the axis, and the right-hand one would
            // hang off the edge, so labels are pulled back inside.
            val text = "%02d".format(hour % 24)
            val measured = measurer.measure(text, labelStyle)
            drawText(
                textMeasurer = measurer,
                text = text,
                topLeft = Offset(
                    x = (lineX - measured.size.width / 2f)
                        .coerceIn(gutter, (size.width - measured.size.width).coerceAtLeast(gutter)),
                    y = plotHeight + HOUR_LABEL_GAP.toPx(),
                ),
                style = labelStyle,
            )
        }

        drawSeries(slices, ::x, ::y)

        if (selected != null && callout != null) {
            drawCallout(
                lines = callout,
                anchorX = x(selected.centreHour),
                plotHeight = plotHeight,
                accent = selected.median
                    ?.let { ZoneColors.of(thresholds.classify(it), vision) }
                    ?: labelColor,
                surface = calloutSurface,
                titleStyle = titleStyle,
                bodyStyle = bodyStyle,
                measurer = measurer,
            )
        }
    }
}

/**
 * What a tapped slice actually says, in words.
 *
 * The day count is on it deliberately. A band's width says how variable that part of
 * the day was; it says nothing about how much was behind it, and "07–08 is usually 90"
 * means something different over three days than over thirty. Nothing else on this
 * card can tell you which you are looking at.
 */
@Composable
private fun calloutLines(slice: TimeOfDayBucket, unit: GlucoseUnit): List<String> = buildList {
    add(slice.label())

    slice.median?.let {
        add(
            stringResource(
                R.string.trends_inspect_median,
                "${unit.format(it)} ${unit.suffix}",
            )
        )
    }

    // Read into locals first: these are public vals from another module, which Kotlin
    // will not smart-cast however obviously non-null the check makes them.
    val p25 = slice.p25
    val p75 = slice.p75
    if (p25 != null && p75 != null) {
        add(stringResource(R.string.trends_inspect_middle, unit.format(p25), unit.format(p75)))
    }

    val p10 = slice.p10
    val p90 = slice.p90
    if (p10 != null && p90 != null) {
        add(stringResource(R.string.trends_inspect_spread, unit.format(p10), unit.format(p90)))
    }

    add(stringResource(R.string.trends_inspect_days, slice.dayCount, slice.readingCount))
}

/**
 * The tapped slice's figures, in a box beside a line marking it.
 *
 * Deliberately the same idiom as the callout on the main chart — a full-height line
 * through the moment, a rounded panel of text on whichever side has room. Someone who
 * has learned that tapping the trace gives numbers should not have to discover a
 * second convention here.
 */
private fun DrawScope.drawCallout(
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

    // Prefer the right of the line, flip when that would run off the chart: the slice
    // worth inspecting is often the one at the edge of the day.
    val gap = CALLOUT_GAP.toPx()
    val left = if (anchorX + gap + boxWidth <= size.width) anchorX + gap
    else anchorX - gap - boxWidth
    val clampedLeft = left.coerceIn(0f, (size.width - boxWidth).coerceAtLeast(0f))
    // Vertically centred rather than tied to the median: the panel is five lines tall
    // and would cover the band it is describing.
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

/**
 * Splits the day wherever a slice has nothing behind it.
 *
 * The same rule as [dev.cgm.core.ChartSeries.segments] on the live trace, for the same
 * reason: interpolating across a hole invents the very thing being described.
 */
private fun List<TimeOfDayBucket>.runsWithData(): List<List<TimeOfDayBucket>> {
    val out = mutableListOf<List<TimeOfDayBucket>>()
    var current = mutableListOf<TimeOfDayBucket>()
    forEach { slice ->
        if (slice.hasData && slice.median != null) {
            current += slice
        } else if (current.isNotEmpty()) {
            out += current
            current = mutableListOf()
        }
    }
    if (current.isNotEmpty()) out += current
    return out
}

/**
 * One unbroken stretch of the day: two bands and a median.
 *
 * The median is drawn segment by segment in the zone colour of each segment, which is
 * the app's rule that colour always means the same thing — the stretch of the ribbon
 * that is amber is the stretch of the day spent high. The bands stay in the trace's
 * own near-black, because shading them by zone would make a five-colour wash of a
 * chart whose subject is *shape*.
 */
private fun DrawScope.drawRibbon(
    run: List<TimeOfDayBucket>,
    colour: Color,
    vision: ColorVision,
    thresholds: GlucoseThresholds,
    x: (Double) -> Float,
    y: (Double) -> Float,
) {
    // A lone slice between two gaps has no neighbour to draw a ribbon to, so it gets a
    // bar of its own width instead of vanishing.
    if (run.size == 1) {
        drawLoneSlice(run.single(), colour, vision, thresholds, x, y)
        return
    }

    fun hourOf(slice: TimeOfDayBucket): Double = when {
        // The first and last slices are stretched to the edges of the axis. A slice
        // describes its whole span, so its band reaching the end of the day is a
        // statement about data that exists — where stopping half an hour short would
        // just look like the day ending at 23:30.
        slice.startHour == 0 -> 0.0
        slice.endHour >= HOURS_IN_DAY.toInt() -> HOURS_IN_DAY
        else -> slice.centreHour
    }

    fun bandBetween(
        low: (TimeOfDayBucket) -> Double?,
        high: (TimeOfDayBucket) -> Double?,
        alpha: Float,
    ) {
        val points = run.mapNotNull { slice ->
            val bottom = low(slice) ?: return@mapNotNull null
            val top = high(slice) ?: return@mapNotNull null
            Triple(x(hourOf(slice)), bottom, top)
        }
        if (points.size < 2) return

        val path = Path().apply {
            moveTo(points.first().first, y(points.first().third))
            points.drop(1).forEach { lineTo(it.first, y(it.third)) }
            points.reversed().forEach { lineTo(it.first, y(it.second)) }
            close()
        }
        drawPath(path, color = colour.copy(alpha = alpha))
    }

    bandBetween({ it.p10 }, { it.p90 }, OUTER_BAND_ALPHA)
    bandBetween({ it.p25 }, { it.p75 }, INNER_BAND_ALPHA)

    run.zipWithNext().forEach { (from, to) ->
        val a = from.median ?: return@forEach
        val b = to.median ?: return@forEach
        drawLine(
            // Classified on the midpoint, so a segment is coloured by where it spends
            // its length rather than by which end you read first.
            color = ZoneColors.of(thresholds.classify((a + b) / 2), vision),
            start = Offset(x(hourOf(from)), y(a)),
            end = Offset(x(hourOf(to)), y(b)),
            strokeWidth = MEDIAN_STROKE.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

/** A slice surrounded by gaps: a bar of its own width, so it is still visible. */
private fun DrawScope.drawLoneSlice(
    slice: TimeOfDayBucket,
    colour: Color,
    vision: ColorVision,
    thresholds: GlucoseThresholds,
    x: (Double) -> Float,
    y: (Double) -> Float,
) {
    val left = x(slice.startHour.toDouble())
    val width = (x(slice.endHour.toDouble()) - left).coerceAtLeast(1f)

    fun bar(low: Double?, high: Double?, alpha: Float) {
        if (low == null || high == null) return
        drawRect(
            color = colour.copy(alpha = alpha),
            topLeft = Offset(left, y(high)),
            size = Size(width, (y(low) - y(high)).coerceAtLeast(1f)),
        )
    }

    bar(slice.p10, slice.p90, OUTER_BAND_ALPHA)
    bar(slice.p25, slice.p75, INNER_BAND_ALPHA)

    slice.median?.let { median ->
        drawLine(
            color = ZoneColors.of(thresholds.classify(median), vision),
            start = Offset(left, y(median)),
            end = Offset(left + width, y(median)),
            strokeWidth = MEDIAN_STROKE.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

/**
 * One three-hour slice: whisker, box, median — the clinical box plot.
 *
 * Coloured by the zone its median falls in, so this card keeps the app's rule that
 * colour always means the same thing: green here is the same green as a reading in
 * range.
 */
private fun DrawScope.drawSlice(
    slice: TimeOfDayBucket,
    vision: ColorVision,
    thresholds: GlucoseThresholds,
    x: (Double) -> Float,
    y: (Double) -> Float,
) {
    val median = slice.median ?: return
    val colour: Color = ZoneColors.of(thresholds.classify(median), vision)

    val centre = x(slice.centreHour)
    val slot = x(slice.endHour.toDouble()) - x(slice.startHour.toDouble())
    val boxWidth = slot * BOX_WIDTH_FRACTION

    // Whisker first, so the box sits over it.
    val low = slice.p10
    val high = slice.p90
    if (low != null && high != null) {
        drawLine(
            color = colour.copy(alpha = 0.55f),
            start = Offset(centre, y(high)),
            end = Offset(centre, y(low)),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }

    val top = slice.p75
    val bottom = slice.p25
    if (top != null && bottom != null) {
        drawRect(
            color = colour.copy(alpha = 0.35f),
            topLeft = Offset(centre - boxWidth / 2f, y(top)),
            size = Size(boxWidth, (y(bottom) - y(top)).coerceAtLeast(1f)),
        )
    }

    drawLine(
        color = colour,
        start = Offset(centre - boxWidth / 2f, y(median)),
        end = Offset(centre + boxWidth / 2f, y(median)),
        strokeWidth = 3.dp.toPx(),
        cap = StrokeCap.Round,
    )
}

/** Matches the main chart's gutter, so the two charts' value axes line up. */
private val AXIS_GUTTER = 34.dp
private val HOUR_LABEL_GAP = 2.5.dp
private val MEDIAN_STROKE = 2.5.dp
private val CALLOUT_GAP = 10.dp

private const val HOURS_IN_DAY = 24.0

/** A line every three hours, a number every six. */
private const val HOUR_GRID = 3
private const val HOUR_LABEL = 6

/** Boxes narrower than their slot, so neighbouring slices stay visibly separate. */
private const val BOX_WIDTH_FRACTION = 0.52f

/**
 * The 10–90 band is background and the 25–75 band is the answer, so they are far
 * enough apart in weight that the middle reads first. Both are drawn from the same
 * colour: two hues would imply two different kinds of thing.
 */
private const val OUTER_BAND_ALPHA = 0.13f
private const val INNER_BAND_ALPHA = 0.3f
