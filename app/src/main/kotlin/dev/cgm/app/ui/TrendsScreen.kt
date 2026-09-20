package dev.cgm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.cgm.app.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.cgm.core.GlucoseStatistics
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

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TrendPeriod.entries.forEach { p ->
                FilterChip(
                    selected = p == period,
                    onClick = { viewModel.selectPeriod(p) },
                    label = { Text(p.label) },
                )
            }
        }

        CoverageNotice(stats, period)

        if (stats.readingCount == 0) {
            Text(
                stringResource(R.string.trends_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        TimeInRangeCard(stats)
        ForecastReliabilityCard(viewModel)
        A1cCard(stats, period)
        SummaryCard(stats)
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
private fun TimeInRangeCard(stats: GlucoseStatistics) {
    Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.trends_time_in_range),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(12.dp))
            StackedZoneBar(stats, muted = !stats.isReliable)
            Spacer(Modifier.height(14.dp))
            listOf(
                Zone.VERY_HIGH to R.string.zone_very_high,
                Zone.HIGH to R.string.zone_high,
                Zone.IN_RANGE to R.string.zone_in_range,
                Zone.LOW to R.string.zone_low,
                Zone.URGENT_LOW to R.string.zone_urgent_low,
            ).forEach { (zone, labelRes) ->
                val fraction = stats.zoneFractions[zone] ?: 0.0
                ZoneBar(stringResource(labelRes), fraction, zone, muted = !stats.isReliable)
            }
        }
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
private fun StackedZoneBar(stats: GlucoseStatistics, muted: Boolean) {
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
            .height(22.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(5.dp))
    ) {
        if (present.isEmpty()) return@Box
        Row(Modifier.fillMaxWidth().height(22.dp)) {
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
private fun ZoneBar(label: String, fraction: Double, zone: Zone, muted: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(0.3f),
        )
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
private fun A1cCard(stats: GlucoseStatistics, period: TrendPeriod) {
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
                FormulaTooltip()
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
private fun FormulaTooltip() {
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
                Text(stringResource(R.string.trends_formula_explanation))
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

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
