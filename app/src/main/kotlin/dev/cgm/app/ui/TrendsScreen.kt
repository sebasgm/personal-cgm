package dev.cgm.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.ripple
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import dev.cgm.app.Features
import dev.cgm.app.data.ZoneWatcher
import dev.cgm.app.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import dev.cgm.core.GlucoseStatistics
import dev.cgm.core.GlucoseThresholds
import dev.cgm.core.GlucoseUnit
import dev.cgm.core.Zone
import kotlin.math.roundToInt

/**
 * Issue #9 (time in range) and #7 (GMI / A1C).
 *
 * Coverage is shown above the charts rather than below them. LibreLinkUp cannot
 * backfill, so a 90-day window is mostly empty until the app has been running
 * for 90 days, and a confident-looking figure over six of those days would
 * mislead in exactly the direction that matters.
 */
@Composable
fun TrendsScreen(viewModel: CgmViewModel) {
    val stats by viewModel.periodStats.collectAsState()
    val period by viewModel.period.collectAsState()
    val timeOfDay by viewModel.timeOfDay.collectAsState()
    val state by viewModel.state.collectAsState()
    val doses by viewModel.periodDoses.collectAsState()
    val agreement by viewModel.sensorAgreement.collectAsState()
    // Observed rather than read once: the chart groups by calendar day, and a day
    // boundary drawn in the zone you left is not a day.
    val zone by ZoneWatcher.zone.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PeriodChips(selected = period, onSelect = viewModel::selectPeriod)

        CoverageNotice(stats, period)

        // An empty window used to end the screen here. It no longer can: the
        // report below spans its own period, which may be a year when this chip
        // is on a day, so an empty day must not hide a report that has data.
        if (stats.readingCount == 0) {
            Text(
                stringResource(R.string.trends_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // A1C first. It is the single figure anyone opening this screen came for,
            // and it was previously below two charts and a scroll — a number you have
            // to go looking for reads as a footnote to the charts rather than as the
            // summary they support. The coverage notice stays above it, because how much
            // of the window this rests on governs how the figure should be read.
            A1cCard(stats, period, state.unit)
            TimeInRangeCard(
                stats = stats,
                // The effective thresholds, not the defaults: the figures were
                // computed against these, and a card naming different ones would
                // be describing a different measurement.
                thresholds = state.snapshot?.thresholds ?: GlucoseThresholds.Default,
                unit = state.unit,
                period = period,
            )
            DailyPatternCard(
                profile = timeOfDay,
                thresholds = state.snapshot?.thresholds ?: GlucoseThresholds.Default,
                unit = state.unit,
            )
            ForecastReliabilityCard(viewModel)
            SummaryCard(stats)
        }

        // Insulin outside the readings branch above, because a dose is not a
        // reading: the diary keeps working through a sensor change, a warm-up or a
        // week the phone was not polling, and a window with no readings in it can
        // still have every injection of the week written down.
        InsulinTrendCard(doses = doses, period = period, zone = zone)

        // Beside the insulin card for the same reason: both are about what the owner
        // did rather than what the sensor said — and this one is the only card here
        // that judges the sensor instead of reporting it.
        SensorAccuracyCard(agreement = agreement, unit = state.unit)

        // The report last, and on its own period rather than the chips above.
        // Everything over it answers "how is it going"; this answers "write that
        // down so someone else can read it", which is a different errand and
        // belongs at the end of the screen rather than competing at the top.
        HorizontalDivider()
        ReportSection(viewModel)
    }
}

/**
 * The window chips: all six across one row, at any width worth supporting.
 *
 * `FilterChip` could not do this. Its own padding and minimum touch size put six
 * of them well past a 360dp phone, so the row had to scroll sideways — and a chip
 * row that scrolls hides its tail behind a gesture nobody makes on something that
 * looks like it is already showing everything. The two longest windows were the
 * ones off the edge.
 *
 * So the tile is sized here instead: a floor of [CHIP_MIN_WIDTH] by
 * [CHIP_MIN_HEIGHT] with tight padding, which puts six of them at 301dp across
 * including the gaps — inside the 320dp a 360dp phone leaves. The floor is also
 * what keeps them even: "7d" is narrower than "24h", and a row of tiles that each
 * hugged its own label would read as six unrelated buttons rather than one
 * choice.
 *
 * A [FlowRow] rather than a plain one, and no `weight`: weight would force all six
 * into a single line whatever the width, which is the clipping problem again.
 * Flowing means the row wraps to a second line in the cases this cannot fit —
 * this app has its own text scale that goes to 1.6x on top of the system's, and
 * past about 1.3x six tiles genuinely do not fit a phone. Two short lines is the
 * right answer there; sideways scrolling and truncated labels are not.
 *
 * Built from `Surface` and `selectable` rather than from a chip, which also means
 * declaring the semantics a chip would have given: a radio role inside a
 * [selectableGroup], so a screen reader reads this as one choice of six rather
 * than as six independent toggles.
 */
@Composable
private fun PeriodChips(selected: TrendPeriod, onSelect: (TrendPeriod) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(CHIP_GAP),
        verticalArrangement = Arrangement.spacedBy(CHIP_GAP),
    ) {
        TrendPeriod.entries.forEach { period ->
            val chosen = period == selected
            val interaction = remember { MutableInteractionSource() }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (chosen) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (chosen) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.selectable(
                    selected = chosen,
                    interactionSource = interaction,
                    // Explicit, because a bare `selectable` takes whatever
                    // indication is in scope rather than Material's: without this
                    // the tiles acknowledge a tap with nothing at all, which on the
                    // one already selected looks like the tap missed.
                    indication = ripple(),
                    role = Role.RadioButton,
                    onClick = { onSelect(period) },
                ),
            ) {
                Box(
                    Modifier
                        .defaultMinSize(minWidth = CHIP_MIN_WIDTH, minHeight = CHIP_MIN_HEIGHT)
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        period.label,
                        style = MaterialTheme.typography.labelLarge,
                        // Selected is carried by the fill as well, so weight is a
                        // second cue rather than the only one.
                        fontWeight = if (chosen) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun CoverageNotice(stats: GlucoseStatistics, period: TrendPeriod) {
    val percent = (stats.coverage * 100).roundToInt()
    val daysCovered = (stats.coverage * period.days).roundToInt()

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (stats.isReliable) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                stringResource(
                    R.string.trends_coverage_based_on,
                    daysCovered,
                    period.days,
                    percent,
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (!stats.isReliable) {
                Text(
                    stringResource(R.string.trends_coverage_warning),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun TimeInRangeCard(
    stats: GlucoseStatistics,
    thresholds: GlucoseThresholds,
    unit: GlucoseUnit,
    period: TrendPeriod,
) {
    var expanded by remember { mutableStateOf(false) }

    Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.trends_time_in_range),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(12.dp))
            // The bar had no behaviour at all. A tap now opens the figures behind
            // it, which is where the boundaries live: the colours mean nothing
            // until you know what "high" was measured against, and those numbers
            // follow the account or an override rather than being fixed.
            StackedZoneBar(
                stats = stats,
                muted = !stats.isReliable,
                onClick = { expanded = true },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.trends_tir_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            ZONE_ORDER.forEach { (zone, labelRes) ->
                val fraction = stats.zoneFractions[zone] ?: 0.0
                ZoneBar(
                    label = stringResource(labelRes),
                    range = thresholds.rangeLabel(zone, unit),
                    fraction = fraction,
                    zone = zone,
                    muted = !stats.isReliable,
                )
            }
        }
    }

    if (expanded) {
        TimeInRangeDetail(
            stats = stats,
            thresholds = thresholds,
            unit = unit,
            period = period,
            onDismiss = { expanded = false },
        )
    }
}

/** Worst at the top, so the bands read down the way a chart reads up. */
private val ZONE_ORDER = listOf(
    Zone.VERY_HIGH to R.string.zone_very_high,
    Zone.HIGH to R.string.zone_high,
    Zone.IN_RANGE to R.string.zone_in_range,
    Zone.LOW to R.string.zone_low,
    Zone.URGENT_LOW to R.string.zone_urgent_low,
)

/**
 * The bands in full: what each one is, where its edges are, and how long was
 * spent in it.
 *
 * The edges are the point of this screen. Five colours and five percentages say
 * nothing until you know that "high" meant above 180 rather than above 140 — and
 * since those numbers come from the account or from an override, they are not
 * something a user can be assumed to know.
 *
 * Time is given per recorded day rather than per calendar day. The fractions are
 * of readings that exist, so multiplying them by 24 hours would describe a day
 * that was never fully measured.
 */
@Composable
private fun TimeInRangeDetail(
    stats: GlucoseStatistics,
    thresholds: GlucoseThresholds,
    unit: GlucoseUnit,
    period: TrendPeriod,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.trends_time_in_range),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.trends_close))
                    }
                }

                Text(
                    stringResource(
                        R.string.trends_tir_window,
                        period.label,
                        (stats.coverage * 100).roundToInt(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(16.dp))
                StackedZoneBar(stats, muted = !stats.isReliable, height = 40.dp)

                Spacer(Modifier.height(20.dp))
                ZONE_ORDER.forEach { (zone, labelRes) ->
                    val fraction = stats.zoneFractions[zone] ?: 0.0
                    ZoneDetailRow(
                        label = stringResource(labelRes),
                        range = thresholds.rangeLabel(zone, unit),
                        unitSuffix = unit.suffix,
                        fraction = fraction,
                        zone = zone,
                        muted = !stats.isReliable,
                    )
                    HorizontalDivider()
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.trends_tir_explain),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!stats.isReliable) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.trends_coverage_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoneDetailRow(
    label: String,
    range: String,
    unitSuffix: String,
    fraction: Double,
    zone: Zone,
    muted: Boolean,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(14.dp)
                .height(34.dp)
                .background(ZoneColors.of(zone, reliable = !muted), RoundedCornerShape(3.dp))
        )
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "$range $unitSuffix",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "${(fraction * 100).roundToInt()}%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                perRecordedDay(fraction),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * How much of an average recorded day was spent in a band.
 *
 * Of a *recorded* day, which is why the caption below the list says so: the
 * fractions describe the readings that exist, and a window only half covered has
 * no claim on the hours nobody measured.
 */
@Composable
private fun perRecordedDay(fraction: Double): String {
    val minutes = (fraction * 24 * 60).roundToInt()
    return if (minutes >= 60) {
        stringResource(R.string.trends_tir_hm, minutes / 60, minutes % 60)
    } else {
        stringResource(R.string.trends_tir_m, minutes)
    }
}

/**
 * The whole day as one bar, in zone colours.
 *
 * The rows below give exact figures; this gives the shape at a glance, which is the
 * question "how was this week" actually asks. Ordered low to high left to right, so
 * the in-range block sits in the middle and a bar that is mostly middle is mostly
 * good — the reading is positional, not just chromatic.
 *
 * Zero-width zones are dropped rather than given a hairline. A zone you never
 * entered should be absent, not a sliver that invites squinting at it.
 */
@Composable
private fun StackedZoneBar(
    stats: GlucoseStatistics,
    muted: Boolean,
    height: Dp = 22.dp,
    onClick: (() -> Unit)? = null,
) {
    val order = listOf(
        Zone.URGENT_LOW,
        Zone.LOW,
        Zone.IN_RANGE,
        Zone.HIGH,
        Zone.VERY_HIGH,
    )
    val present = order.mapNotNull { zone ->
        val fraction = stats.zoneFractions[zone] ?: 0.0
        if (fraction > 0) zone to fraction.toFloat() else null
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        if (present.isEmpty()) return@Box
        Row(Modifier.fillMaxWidth().height(height)) {
            present.forEach { (zone, fraction) ->
                Box(
                    Modifier
                        .weight(fraction)
                        .fillMaxHeight()
                        .background(ZoneColors.of(zone, reliable = !muted))
                )
            }
        }
    }
}

@Composable
private fun ZoneBar(
    label: String,
    range: String,
    fraction: Double,
    zone: Zone,
    muted: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Name and boundary together, because the name alone is the ambiguous
        // half: "high" is a word, "180–240" is the thing being counted.
        Column(Modifier.fillMaxWidth(0.34f)) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(
                range,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            Modifier
                .weight(1f)
                .height(16.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(4.dp),
                )
        ) {
            if (fraction > 0) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction.toFloat())
                        .height(16.dp)
                        .background(
                            ZoneColors.of(zone, reliable = !muted),
                            RoundedCornerShape(4.dp),
                        )
                )
            }
        }
        Text(
            "${(fraction * 100).roundToInt()}%",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun A1cCard(stats: GlucoseStatistics, period: TrendPeriod, unit: GlucoseUnit) {
    val enoughDays = period.days >= GlucoseStatistics.MIN_DAYS_FOR_A1C

    Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Metric(
                    stringResource(R.string.trends_gmi),
                    stats.gmiPercent?.let { "%.1f%%".format(it) } ?: "—",
                )
                Metric(
                    stringResource(R.string.trends_a1c),
                    stats.estimatedA1cPercent?.let { "%.1f%%".format(it) } ?: "—",
                )
                FormulaTooltip(meanMgdl = stats.meanMgdl, unit = unit)
            }
            if (!enoughDays) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(
                        R.string.trends_a1c_needs_days,
                        GlucoseStatistics.MIN_DAYS_FOR_A1C,
                        period.days,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * How GMI and estimated A1C are calculated, on an ⓘ.
 *
 * A tooltip rather than inline text: the formulas matter to anyone who wants to
 * check the numbers and are clutter to everyone else, and a card that shows two
 * figures should read as two figures.
 *
 * Persistent, because it is several sentences of arithmetic — a tooltip that
 * vanishes on the next touch cannot be read.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormulaTooltip(meanMgdl: Double?, unit: GlucoseUnit) {
    val state = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()

    TooltipBox(
        positionProvider = TooltipDefaults.rememberRichTooltipPositionProvider(),
        state = state,
        tooltip = {
            RichTooltip(
                title = { Text(stringResource(R.string.trends_formula_title)) },
                action = {
                    TextButton(onClick = { scope.launch { state.dismiss() } }) {
                        Text(stringResource(R.string.trends_formula_close))
                    }
                },
            ) {
                Column {
                    Text(stringResource(R.string.trends_formula_explanation))
                    Spacer(Modifier.height(14.dp))
                    EstimateShapeChart(
                        meanMgdl = meanMgdl,
                        unit = unit,
                        modifier = Modifier.fillMaxWidth().height(140.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(
                            R.string.trends_formula_shape,
                            unit.format(GlucoseStatistics.AGREEMENT_MGDL),
                            unit.suffix,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
    ) {
        Text(
            "\u24D8",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable { scope.launch { state.show() } }
                .padding(8.dp),
        )
    }
}

/**
 * Whether the chart's projection is worth believing.
 *
 * Shown here rather than on the chart because it is a claim about the model over
 * weeks, not about the line currently on screen. The coverage figure is measured
 * against history the band was not fitted to, so it can disagree with the band's
 * nominal width — and when it does, that disagreement is the whole answer.
 */
@Composable
private fun ForecastReliabilityCard(viewModel: CgmViewModel) {
    if (!Features.PROJECTION) return
    val enabled by viewModel.forecastEnabled.collectAsState()
    if (!enabled) return
    val calibration by viewModel.calibration.collectAsState()

    Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.trends_forecast_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(8.dp))

            val current = calibration
            val coverage = current?.measuredCoverage
            Text(
                text = if (current == null || !current.isUsable || coverage == null) {
                    stringResource(R.string.forecast_uncalibrated)
                } else {
                    stringResource(
                        R.string.forecast_coverage,
                        (current.bandFraction * 100).roundToInt(),
                        (coverage * 100).roundToInt(),
                        current.coverageSampleCount,
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (current?.isWellCalibrated == false) {
                Text(
                    stringResource(R.string.forecast_overconfident),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(stats: GlucoseStatistics) {
    Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Metric(
                stringResource(R.string.home_stat_average),
                stats.meanMgdl?.roundToInt()?.toString() ?: "—",
            )
            Metric(stringResource(R.string.trends_readings), stats.readingCount.toString())
        }
    }
}

/** Shared with the insulin card below, so a figure is laid out one way on this screen. */
@Composable
internal fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * GMI and the A1C estimate drawn against mean glucose, with the reader's own mean
 * marked on both.
 *
 * The explanation says the two figures "legitimately disagree", which is true and is
 * not something a sentence can show. Both are straight lines in mean glucose, so the
 * disagreement is not noise and not a sensor fault: it is two published formulas with
 * different slopes, agreeing at exactly one point and diverging in a fixed direction
 * either side of it. A reader who sees 7.2 and 7.6 on the card wants to know whether
 * one of them is wrong, and the answer — neither, and here is how far apart they are
 * where you sit — is a picture.
 *
 * Both lines are sampled through [GlucoseStatistics.gmiPercentFor] and
 * [GlucoseStatistics.estimatedA1cPercentFor] rather than drawn as two segments between
 * endpoints. Straightness is the thing being shown, so it has to be the formulas'
 * straightness and not the drawing's: if either published relationship were ever
 * replaced by a curve, this would bend with it instead of quietly flattening it.
 *
 * One y axis, because both series are in the same unit. Two scales would make their
 * slopes a drawing decision rather than a fact, which is the one thing this chart must
 * not do.
 */
@Composable
private fun EstimateShapeChart(meanMgdl: Double?, unit: GlucoseUnit, modifier: Modifier = Modifier) {
    val tones = EstimateColors.tones
    val grid = MaterialTheme.colorScheme.outlineVariant
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 9.sp, color = ink)
    val seriesStyle = TextStyle(fontSize = 10.sp, color = ink, fontWeight = FontWeight.Medium)

    val gmiLabel = stringResource(R.string.trends_gmi)
    val a1cLabel = stringResource(R.string.trends_a1c)
    val yoursLabel = stringResource(R.string.trends_formula_yours)
    val axisLabel = stringResource(R.string.trends_formula_axis_x, unit.suffix)

    Canvas(modifier) {
        val gutter = 24.dp.toPx()
        // Measured, not guessed. These two labels are translated, and "Est. A1C"
        // already overruns a reserve picked by eye in English — in another language
        // or at another font scale the overrun is simply larger. The room the labels
        // need is a fact about the labels.
        val endRoom = maxOf(
            measurer.measure(gmiLabel, seriesStyle).size.width,
            measurer.measure(a1cLabel, seriesStyle).size.width,
        ) + 8.dp.toPx()
        val footer = 26.dp.toPx()
        val plotWidth = (size.width - gutter - endRoom).coerceAtLeast(1f)
        val plotHeight = (size.height - footer).coerceAtLeast(1f)

        fun x(mgdl: Double) = gutter + ((mgdl - X_MIN) / (X_MAX - X_MIN) * plotWidth).toFloat()
        fun y(percent: Double) =
            (plotHeight - (percent - Y_MIN) / (Y_MAX - Y_MIN) * plotHeight).toFloat()

        // Grid first, so every mark sits on top of it. Recessive on purpose: it is
        // there to let a value be read off, not to be looked at.
        for (tick in Y_TICKS) {
            val ty = y(tick)
            drawLine(grid, Offset(gutter, ty), Offset(gutter + plotWidth, ty), 1.dp.toPx())
            val text = measurer.measure("${tick.toInt()}%", labelStyle)
            drawText(
                text,
                topLeft = Offset(
                    gutter - text.size.width - 4.dp.toPx(),
                    ty - text.size.height / 2f,
                ),
            )
        }

        fun lineOf(formula: (Double) -> Double): Path = Path().apply {
            repeat(SAMPLES + 1) { step ->
                val mgdl = X_MIN + (X_MAX - X_MIN) * step / SAMPLES
                val point = Offset(x(mgdl), y(formula(mgdl)))
                if (step == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
            }
        }

        val stroke = Stroke(width = 2.dp.toPx())
        drawPath(lineOf(GlucoseStatistics::estimatedA1cPercentFor), tones.a1c, style = stroke)
        drawPath(lineOf(GlucoseStatistics::gmiPercentFor), tones.gmi, style = stroke)

        // Where they agree. A ring rather than a filled dot, because nothing was
        // measured here — it is the one point the arithmetic says they share.
        val crossX = x(GlucoseStatistics.AGREEMENT_MGDL)
        val crossY = y(GlucoseStatistics.gmiPercentFor(GlucoseStatistics.AGREEMENT_MGDL))
        drawCircle(ink, radius = 4.dp.toPx(), center = Offset(crossX, crossY), style = Stroke(1.5.dp.toPx()))

        // The labels sit in ink rather than in each series' colour, and each one sits
        // at the end of its own line: identity by position, which survives any colour
        // deficiency and greyscale both.
        val labelEnd = gutter + plotWidth + 4.dp.toPx()

        /**
         * Centred on its line's end, but never off the top or bottom of the canvas.
         * The A1C estimate reaches 12.1% at 300 mg/dL, which is within a label's
         * half-height of the ceiling — centred naively its text is clipped.
         */
        fun endLabel(label: String, percent: Double) {
            val text = measurer.measure(label, seriesStyle)
            val top = (y(percent) - text.size.height / 2f)
                .coerceIn(0f, (plotHeight - text.size.height).coerceAtLeast(0f))
            drawText(text, topLeft = Offset(labelEnd, top))
        }

        endLabel(gmiLabel, GlucoseStatistics.gmiPercentFor(X_MAX))
        endLabel(a1cLabel, GlucoseStatistics.estimatedA1cPercentFor(X_MAX))

        // Where the reader actually sits, if the window has a mean at all, and only
        // when it is on the chart: a mean outside 70-300 would otherwise be drawn
        // clamped to the edge, which is a marker pointing at the wrong glucose.
        val mean = meanMgdl?.takeIf { it in X_MIN..X_MAX }
        if (mean != null) {
            val mx = x(mean)
            drawLine(
                ink,
                Offset(mx, 0f),
                Offset(mx, plotHeight),
                1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(3.dp.toPx(), 3.dp.toPx()),
                ),
            )
            drawCircle(tones.gmi, 4.dp.toPx(), Offset(mx, y(GlucoseStatistics.gmiPercentFor(mean))))
            drawCircle(
                tones.a1c,
                4.dp.toPx(),
                Offset(mx, y(GlucoseStatistics.estimatedA1cPercentFor(mean))),
            )

            val yours = measurer.measure("$yoursLabel ${unit.format(mean)}", labelStyle)
            // Kept inside the plot: at a mean near either end the label would
            // otherwise hang off the chart.
            val left = (mx - yours.size.width / 2f)
                .coerceIn(gutter, gutter + plotWidth - yours.size.width)
            drawText(yours, topLeft = Offset(left, plotHeight + 2.dp.toPx()))
        }

        val axis = measurer.measure(axisLabel, labelStyle)
        drawText(
            axis,
            topLeft = Offset(
                gutter + (plotWidth - axis.size.width) / 2f,
                size.height - axis.size.height,
            ),
        )
    }
}

/** The span of mean glucose worth drawing: below 70 and above 300 nobody lives. */
private const val X_MIN = 70.0
private const val X_MAX = 300.0

/** Chosen to hold both lines across that span, with a little air. */
private const val Y_MIN = 3.8
private const val Y_MAX = 12.4

/** Four gridlines, not eight: this is 140dp tall and a label every point is clutter. */
private val Y_TICKS = listOf(5.0, 7.0, 9.0, 11.0)

/**
 * Points per line.
 *
 * More than the two a straight line needs, because the straightness is the finding
 * rather than an assumption the drawing is allowed to make.
 */
private const val SAMPLES = 48

/**
 * Wide enough for "24h" with room around it, and the same floor for all six.
 *
 * Six of these plus five [CHIP_GAP]s come to 301dp, inside the 320dp a 360dp
 * phone leaves after this screen's 20dp padding, and well inside the 372dp of a
 * 412dp one. A 320dp phone has 280dp and wraps to two lines, which is the right
 * outcome there and the reason this is a [FlowRow].
 *
 * Sized up to the largest floor that still fits rather than down to the smallest
 * that works: this is a touch target, and the 19dp of slack left on a 360dp phone
 * buys nothing.
 */
private val CHIP_MIN_WIDTH = 46.dp

/**
 * As tall as a Material segmented button, which is what this row now is.
 *
 * Below the 48dp a standalone button should be, and deliberately: six windows have
 * to fit across a phone and `FilterChip` reserving 48dp apiece is what forced the
 * row to scroll. 40dp is the height Material's own single-choice row ships at, so
 * it is a documented density rather than a number picked to make the sums work.
 */
private val CHIP_MIN_HEIGHT = 40.dp

private val CHIP_GAP = 5.dp
