package dev.cgm.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.cgm.app.R
import dev.cgm.core.InsulinDayBar
import dev.cgm.core.InsulinDose
import dev.cgm.core.InsulinTrend
import dev.cgm.core.UnitsAxis
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.time.format.TextStyle as JavaTextStyle

/**
 * How much insulin a day takes, day by day.
 *
 * The diary already answers "how much today" and lists the last fortnight as
 * figures. What it cannot show is the shape: whether a week is drifting upward,
 * whether the weekend is different from the weekdays, whether a change made a
 * fortnight ago actually moved anything. That is a question about a series, and a
 * series has to be drawn.
 *
 * **Stacked rather than paired.** Basal sits at the bottom and bolus on top of it,
 * so the height of a bar is the day's total and the join in it is the split. The
 * alternative — two bars per day, side by side — makes each kind easy to follow on
 * its own and makes the total, the figure a day is actually reasoned about in,
 * something to add up by eye. Stacking keeps both: the whole is the height, the
 * parts are the segments.
 *
 * **From zero, always.** See [UnitsAxis]: a bar is read as a height, so an axis that
 * started at thirty units would draw an ordinary two-unit difference as a tripling.
 *
 * **A day nobody logged is a gap, not a zero.** This is the diary's rule and it
 * matters more here, because a zero-height bar in a row of bars is a loud claim that
 * no insulin was taken that day. A gap says what is true: nothing was written down.
 * The caption says how many of the window's days carry anything at all, since a
 * chart of four logged days out of thirty should not be read as a month's dosing.
 *
 * **Today is drawn faintly and left out of the averages.** At nine in the morning
 * today's bar holds the morning basal and nothing else; counted as a whole day it
 * would report a fall in dosing that is only the time of day.
 *
 * What this chart is *not* is a record of insulin activity. It is a record of what
 * was typed into the diary — a forgotten injection is a gap here, and a dose logged
 * twice is a tall bar.
 */
@Composable
fun InsulinTrendCard(doses: List<InsulinDose>, period: TrendPeriod, zone: ZoneId) {
    // Read in composition like the diary's day labels: the day boundary has to be
    // the one the person is living in, and that moves when they fly.
    val today = LocalDate.now(zone)
    val trend = remember(doses, period, zone, today) {
        InsulinTrend.of(doses, zone, today, period.days)
    }

    var expanded by remember { mutableStateOf(false) }

    Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.trends_insulin),
                style = MaterialTheme.typography.titleSmall,
            )

            if (!trend.hasData) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.trends_insulin_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.trends_insulin_days, trend.loggedDays, period.days),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            InsulinBars(
                trend = trend,
                zone = zone,
                modifier = Modifier.fillMaxWidth().height(170.dp),
                onExpand = { expanded = true },
            )

            Spacer(Modifier.height(10.dp))
            InsulinLegend()

            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Metric(
                    stringResource(R.string.trends_insulin_mean_day),
                    trend.meanTotalUnits?.let { "${formatUnits(it)} U" } ?: "—",
                )
                Metric(
                    stringResource(R.string.dose_basal),
                    trend.meanBasalUnits?.let(::formatUnits) ?: "—",
                )
                Metric(
                    stringResource(R.string.dose_bolus),
                    trend.meanBolusUnits?.let(::formatUnits) ?: "—",
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.trends_insulin_mean_note, trend.averagedDays),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.trends_insulin_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                stringResource(R.string.trends_insulin_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    if (expanded) {
        FullScreenInsulin(trend = trend, zone = zone, onDismiss = { expanded = false })
    }
}

/**
 * What each tone means, and what the dashed line is.
 *
 * Spelled out rather than left to the tap: a stacked bar is only readable once you
 * know which half is which, and a legend is the one place a colour can say what it
 * stands for without being tapped.
 */
@Composable
private fun InsulinLegend() {
    val tones = InsulinColors.tones

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendSwatch(tones.bolus, stringResource(R.string.dose_bolus))
        LegendSwatch(tones.basal, stringResource(R.string.dose_basal))
        // The average is not a measurement of any day, so it is drawn in the label
        // grey the axis uses rather than in either insulin tone.
        LegendSwatch(
            MaterialTheme.colorScheme.onSurfaceVariant,
            stringResource(R.string.trends_insulin_mean_day),
            dashed = true,
        )
    }
}

@Composable
private fun LegendSwatch(colour: Color, label: String, dashed: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (dashed) {
            Canvas(Modifier.size(width = 14.dp, height = 10.dp)) {
                drawLine(
                    color = colour,
                    start = Offset(0f, size.height / 2f),
                    end = Offset(size.width, size.height / 2f),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = dashes(),
                )
            }
        } else {
            Box(
                Modifier
                    .size(10.dp)
                    .background(colour, RoundedCornerShape(2.dp))
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 5.dp),
        )
    }
}

/**
 * The bars themselves: the axis, the days along the bottom, the average across, and
 * the day a finger is on.
 *
 * The same interaction as the daily pattern — slide to read a day, tap to open it
 * full screen — because someone who has learned that on the chart above should not
 * have to discover a second convention one card down.
 */
@Composable
private fun InsulinBars(
    trend: InsulinTrend,
    zone: ZoneId,
    modifier: Modifier = Modifier,
    /** What a tap does, or null in the full-screen view where there is nothing to open. */
    onExpand: (() -> Unit)? = null,
) {
    val tones = InsulinColors.tones
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val calloutSurface = MaterialTheme.colorScheme.surfaceVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)
    val titleStyle = TextStyle(fontSize = 12.sp, color = labelColor, fontWeight = FontWeight.Bold)
    val bodyStyle = TextStyle(fontSize = 11.sp, color = labelColor)

    /**
     * Which day is open, held as the date rather than as the bar.
     *
     * Switching the period rebuilds every bar, and a held bar would keep reporting
     * figures from a window no longer on screen. A date is re-resolved against the
     * current window and simply closes if it has fallen out of it.
     */
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var widthPx by remember { mutableIntStateOf(0) }
    val gutterPx = with(LocalDensity.current) { AXIS_GUTTER.toPx() }

    val hitTest by rememberUpdatedState<(Float) -> LocalDate?> { tapX ->
        if (widthPx <= 0 || trend.bars.isEmpty()) {
            null
        } else {
            val plot = (widthPx - gutterPx).coerceAtLeast(1f)
            val slot = ((tapX - gutterPx) / plot * trend.bars.size).toInt()
            trend.bars[slot.coerceIn(0, trend.bars.lastIndex)].date
        }
    }

    val selected = selectedDate
        ?.let { date -> trend.bars.firstOrNull { it.date == date } }
        ?.takeIf { it.isLogged }
    val callout = selected?.let { calloutLines(it, zone) }

    // Weekday initials while they fit, day numbers once they cannot: over a week the
    // useful question is which day of the week a tall bar falls on, and over three
    // months it is roughly when, which a date answers and a letter does not.
    val dayLabels = remember(trend) {
        val locale = Locale.getDefault()
        val weekdays = trend.bars.size <= WEEKDAY_LABEL_MAX
        trend.bars.map { bar ->
            if (weekdays) {
                bar.date.dayOfWeek.getDisplayName(JavaTextStyle.NARROW, locale)
            } else {
                bar.date.dayOfMonth.toString()
            }
        }
    }

    Canvas(
        modifier
            .onSizeChanged { widthPx = it.width }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> selectedDate = hitTest(offset.x) },
                ) { change, _ ->
                    selectedDate = hitTest(change.position.x)
                    // Claimed, or the vertical scroller underneath takes the gesture
                    // halfway across the chart.
                    change.consume()
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    if (onExpand != null) {
                        onExpand()
                    } else {
                        val hit = hitTest(offset.x)
                        selectedDate = if (hit == selectedDate) null else hit
                    }
                }
            }
    ) {
        val gutter = AXIS_GUTTER.toPx()
        // Measured rather than fixed, so the day labels are not clipped on a phone
        // with the font scale turned up.
        val axisHeight = measurer.measure("00", labelStyle).size.height + DAY_LABEL_GAP.toPx() * 2
        val plotWidth = (size.width - gutter).coerceAtLeast(1f)
        val plotHeight = (size.height - axisHeight).coerceAtLeast(1f)

        val axis = UnitsAxis.of(trend.peakUnits)
        fun y(units: Double) = (1f - axis.fraction(units)) * plotHeight

        val slot = plotWidth / trend.bars.size
        fun centreOf(index: Int) = gutter + slot * (index + 0.5f)

        axis.gridLines().forEach { level ->
            drawLine(
                color = grid,
                start = Offset(gutter, y(level)),
                end = Offset(size.width, y(level)),
                strokeWidth = 1.dp.toPx(),
            )
            drawText(
                textMeasurer = measurer,
                text = formatUnits(level),
                // Pulled back inside the plot, because the top line sits exactly at
                // the ceiling here: this axis ends on a round number rather than
                // padding past the data, so a label centred on it would be clipped.
                topLeft = Offset(0f, (y(level) - 7.dp.toPx()).coerceAtLeast(0f)),
                style = labelStyle,
            )
        }

        // The baseline is drawn whatever the data does: it is the zero the bars are
        // measured from, and a bar chart with no floor is a row of floating blocks.
        drawLine(
            color = grid,
            start = Offset(gutter, plotHeight),
            end = Offset(size.width, plotHeight),
            strokeWidth = 1.dp.toPx(),
        )

        trend.bars.forEachIndexed { index, bar ->
            if (!bar.isLogged) return@forEachIndexed

            val width = (slot * BAR_WIDTH_FRACTION).coerceAtLeast(1f)
            val left = centreOf(index) - width / 2f
            // Today is still being lived, so it is drawn as provisional rather than
            // as a day that came to this much.
            val alpha = if (bar.isToday) PARTIAL_DAY_ALPHA else 1f

            val basalTop = y(bar.basalUnits)
            if (bar.basalUnits > 0) {
                drawRect(
                    color = tones.basal.copy(alpha = alpha),
                    topLeft = Offset(left, basalTop),
                    size = Size(width, (plotHeight - basalTop).coerceAtLeast(1f)),
                )
            }
            if (bar.bolusUnits > 0) {
                val top = y(bar.totalUnits)
                drawRect(
                    color = tones.bolus.copy(alpha = alpha),
                    topLeft = Offset(left, top),
                    size = Size(width, (basalTop - top).coerceAtLeast(1f)),
                )
            }
        }

        trend.meanTotalUnits?.let { mean ->
            drawLine(
                color = labelColor,
                start = Offset(gutter, y(mean)),
                end = Offset(size.width, y(mean)),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = dashes(),
            )
        }

        // Labels are placed greedily left to right and any that would touch its
        // neighbour is dropped, which keeps the spacing even at every window length
        // without a label step to get wrong. The callout carries the full date.
        var lastRight = gutter - DAY_LABEL_PAD.toPx()
        dayLabels.forEachIndexed { index, text ->
            val measured = measurer.measure(text, labelStyle)
            val left = centreOf(index) - measured.size.width / 2f
            if (left < lastRight + DAY_LABEL_PAD.toPx()) return@forEachIndexed
            if (left + measured.size.width > size.width) return@forEachIndexed

            drawText(
                textMeasurer = measurer,
                text = text,
                topLeft = Offset(left, plotHeight + DAY_LABEL_GAP.toPx()),
                style = labelStyle,
            )
            lastRight = left + measured.size.width
        }

        if (selected != null && callout != null) {
            drawCallout(
                lines = callout,
                anchorX = centreOf(trend.bars.indexOfFirst { it.date == selected.date }),
                plotHeight = plotHeight,
                accent = tones.basal,
                surface = calloutSurface,
                titleStyle = titleStyle,
                bodyStyle = bodyStyle,
                measurer = measurer,
            )
        }
    }
}

/**
 * What a selected day says.
 *
 * The total first, because that is the figure a day is reasoned about in, then the
 * split and how many injections it came from — eight units in one dose and eight in
 * four are the same bar and not the same day.
 */
@Composable
private fun calloutLines(bar: InsulinDayBar, zone: ZoneId): List<String> = buildList {
    add(dayTitle(bar.date, zone))
    add(stringResource(R.string.trends_insulin_day_total, formatUnits(bar.totalUnits)))
    add(
        stringResource(
            R.string.trends_insulin_day_split,
            formatUnits(bar.basalUnits),
            formatUnits(bar.bolusUnits),
        )
    )
    add(stringResource(R.string.trends_insulin_day_doses, bar.doseCount))
    if (bar.isToday) add(stringResource(R.string.trends_insulin_day_partial))
}

/**
 * A day's name, in the diary's words.
 *
 * The same strings the diary's own by-day list uses, so "Hoy" means the same thing
 * on both screens rather than being a date on one of them.
 */
@Composable
private fun dayTitle(date: LocalDate, zone: ZoneId): String {
    val today = LocalDate.now(zone)
    return when (date) {
        today -> stringResource(R.string.diary_day_today)
        today.minusDays(1) -> stringResource(R.string.diary_day_yesterday)
        else -> date.format(CALLOUT_DATE_FORMAT)
    }
}

/**
 * The same chart, given the screen.
 *
 * Ninety bars across a card are three pixels each: enough to see a trend, not enough
 * to put a finger on one of them. Here they get the full width, and turning the phone
 * sideways gives them considerably more.
 */
@Composable
private fun FullScreenInsulin(trend: InsulinTrend, zone: ZoneId, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        // Not the default dialog width: the width is the point of this view.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Box(Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.trends_insulin),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterEnd)) {
                        Text(stringResource(R.string.trends_close))
                    }
                }

                InsulinBars(
                    trend = trend,
                    zone = zone,
                    modifier = Modifier.fillMaxWidth().height(320.dp),
                    // No expand: this is already it. A tap selects a day instead.
                    onExpand = null,
                )

                Spacer(Modifier.height(10.dp))
                InsulinLegend()

                Text(
                    stringResource(R.string.trends_insulin_fullscreen_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                )
            }
        }
    }
}

/**
 * The dash the average line and its legend swatch share, so they read as one thing.
 *
 * In density-independent lengths, like every other stroke here: a dash measured in
 * raw pixels is a dotted line on one phone and a dashed one on the next.
 */
private fun DrawScope.dashes(): PathEffect =
    PathEffect.dashPathEffect(floatArrayOf(DASH_ON.toPx(), DASH_OFF.toPx()), 0f)

private val DASH_ON = 4.dp
private val DASH_OFF = 3.dp

private val DAY_LABEL_GAP = 2.5.dp

/** Clear air between day labels, so a dropped one is obvious as a gap and not a collision. */
private val DAY_LABEL_PAD = 5.dp

/** Bars narrower than their slot, so neighbouring days stay visibly separate. */
private const val BAR_WIDTH_FRACTION = 0.62f

/** How faintly the day still in progress is drawn. */
private const val PARTIAL_DAY_ALPHA = 0.45f

/**
 * Up to a fortnight gets weekday initials; past that there is no room for them.
 *
 * Fourteen because the window chips stop at 14 before jumping to 30, so the cut
 * lands on a chip boundary rather than inside one.
 */
private const val WEEKDAY_LABEL_MAX = 14

private val CALLOUT_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")
