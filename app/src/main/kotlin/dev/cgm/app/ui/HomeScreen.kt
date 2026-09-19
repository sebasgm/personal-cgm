package dev.cgm.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cgm.core.Freshness
import dev.cgm.core.GlucoseReading
import dev.cgm.core.GlucoseSnapshot
import dev.cgm.core.GlucoseStatistics
import dev.cgm.core.GlucoseThresholds
import dev.cgm.core.SensorInfo
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Home.
 *
 * Answers one question — what is my glucose now — and gives it the upper half of
 * the screen. Everything else is secondary by construction. See
 * docs/03-ui-design.md for why the density lives one tap away instead of here.
 */
@Composable
fun HomeScreen(
    viewModel: CgmViewModel,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val history by viewModel.history.collectAsState()
    val stats by viewModel.statistics.collectAsState()
    val window by viewModel.window.collectAsState()

    // Ticks regardless of whether data arrives. A frozen "2 min ago" over a dead
    // feed is the exact failure this screen exists to prevent.
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }

    val snapshot = state.snapshot
    val freshness = state.freshness(now)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CurrentReading(snapshot, freshness, now)

        Spacer(Modifier.height(20.dp))

        state.error?.let { AttentionBanner(it.message, it.needsUser) }

        GlucoseGraph(
            readings = history,
            thresholds = snapshot?.thresholds ?: GlucoseThresholds.Default,
            stale = freshness == Freshness.STALE,
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .padding(vertical = 12.dp),
        )

        WindowChips(selected = window, onSelect = viewModel::selectWindow)

        Spacer(Modifier.height(20.dp))

        StatStrip(
            stats = stats,
            windowLabel = window.label,
            unit = snapshot?.unit,
            sensor = state.sensor,
            now = now,
        )

        Spacer(Modifier.height(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = viewModel::refreshNow) { Text("Refresh") }
            TextButton(onClick = onStartService) { Text("Start") }
            TextButton(onClick = onStopService) { Text("Stop") }
            TextButton(onClick = viewModel::signOut) { Text("Sign out") }
        }
    }
}

/**
 * The number, and the age beneath it.
 *
 * Four states, and they have to be unmistakable. In STALE the zone colour is
 * dropped entirely: a grey number reads as "unknown", which is true, where a
 * green number that happens to be forty minutes old reads as "fine", which is
 * the dangerous lie.
 */
@Composable
private fun CurrentReading(snapshot: GlucoseSnapshot?, freshness: Freshness, now: Long) {
    if (snapshot == null) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "——",
                fontSize = 84.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("no reading yet", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    val stale = freshness == Freshness.STALE
    val valueColor = if (stale) ZoneColors.stale else ZoneColors.of(snapshot.zone())

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            snapshot.formattedValue(),
            fontSize = 84.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            // Tabular figures: the layout must not jitter as digits change.
            fontFamily = FontFamily.SansSerif,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(snapshot.unit.suffix, style = MaterialTheme.typography.bodyLarge)
            Text(snapshot.reading.trend.glyph, fontSize = 22.sp, color = valueColor)
            snapshot.formattedDelta()?.let {
                Text(it, style = MaterialTheme.typography.titleMedium)
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = ageText(snapshot, freshness, now),
            style = MaterialTheme.typography.bodyMedium,
            color = when (freshness) {
                Freshness.FRESH -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.error
            },
            fontWeight = if (freshness == Freshness.STALE) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

private fun ageText(snapshot: GlucoseSnapshot, freshness: Freshness, now: Long): String {
    val seconds = snapshot.reading.ageMillis(now) / 1000
    val age = when {
        seconds < 60 -> "$seconds seconds ago"
        seconds < 120 -> "1 minute ago"
        else -> "${seconds / 60} minutes ago"
    }
    return when (freshness) {
        Freshness.FRESH -> age
        Freshness.AGING -> "$age · later than usual"
        Freshness.STALE -> "$age · NOT CURRENT"
    }
}

@Composable
private fun AttentionBanner(message: String, needsUser: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (needsUser) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                if (needsUser) "Needs your attention" else "Last poll failed",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun WindowChips(selected: GraphWindow, onSelect: (GraphWindow) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GraphWindow.entries.forEach { w ->
            FilterChip(
                selected = w == selected,
                onClick = { onSelect(w) },
                label = { Text(w.label) },
            )
        }
    }
}

/**
 * Where Gluroo's density enters, and it stops at three cells.
 *
 * Stats are computed over the same window the graph shows, so the strip and the
 * picture can never disagree.
 */
@Composable
private fun StatStrip(
    stats: GlucoseStatistics,
    windowLabel: String,
    unit: dev.cgm.core.GlucoseUnit?,
    sensor: SensorInfo?,
    now: Long,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        StatCell(
            label = "In range $windowLabel",
            value = stats.timeInRange?.let { "${(it * 100).roundToInt()}%" } ?: "—",
            // Coverage is the honest caveat: this app can never backfill history,
            // so a window can be legitimately half empty.
            muted = !stats.isReliable,
        )
        StatCell(
            label = "Average",
            value = stats.meanMgdl?.let { unit?.format(it) ?: it.roundToInt().toString() } ?: "—",
            muted = !stats.isReliable,
        )
        StatCell(
            label = "Sensor",
            value = sensor?.dayOfSession(now)?.let { "day $it/${SensorInfo.SESSION_DAYS}" } ?: "—",
            muted = sensor?.isExpired(now) == true,
        )
    }
}

@Composable
private fun StatCell(label: String, value: String, muted: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            color = if (muted) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Trend graph with the configured thresholds drawn in.
 *
 * Also the dry run for the bitmap the Wear app renders in stage 5, so the
 * scaling and banding logic is worth getting right on a screen that is easy to
 * iterate on.
 */
@Composable
private fun GlucoseGraph(
    readings: List<GlucoseReading>,
    thresholds: GlucoseThresholds,
    stale: Boolean,
    modifier: Modifier = Modifier,
) {
    val bandColor = MaterialTheme.colorScheme.surfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val lineColor =
        if (stale) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
    val emptyColor = MaterialTheme.colorScheme.onSurfaceVariant

    Box(modifier) {
        if (readings.size < 2) {
            Text(
                "not enough data for this window yet",
                style = MaterialTheme.typography.bodySmall,
                color = emptyColor,
                modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }

        Canvas(Modifier.fillMaxSize()) {
            val values = readings.map { it.valueMgdl }
            // Always include the target band so the graph does not rescale wildly
            // when every reading happens to sit inside it.
            val minValue = minOf(values.min(), thresholds.lowMgdl) - 15
            val maxValue = maxOf(values.max(), thresholds.highMgdl) + 15
            val span = (maxValue - minValue).coerceAtLeast(1.0)

            val firstTime = readings.first().timestampMillis
            val lastTime = readings.last().timestampMillis
            val timeSpan = (lastTime - firstTime).coerceAtLeast(1L)

            fun x(t: Long) = ((t - firstTime).toFloat() / timeSpan) * size.width
            fun y(v: Double) = (1f - ((v - minValue) / span).toFloat()) * size.height

            // In-range band
            drawRect(
                color = bandColor,
                topLeft = Offset(0f, y(thresholds.highMgdl)),
                size = Size(
                    size.width,
                    (y(thresholds.lowMgdl) - y(thresholds.highMgdl)).coerceAtLeast(0f),
                ),
            )

            // Threshold lines, so the zones read without a legend.
            val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
            listOf(thresholds.veryHighMgdl, thresholds.urgentLowMgdl).forEach { level ->
                if (level in minValue..maxValue) {
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y(level)),
                        end = Offset(size.width, y(level)),
                        strokeWidth = 2f,
                        pathEffect = dash,
                    )
                }
            }

            val path = Path().apply {
                moveTo(x(readings.first().timestampMillis), y(readings.first().valueMgdl))
                readings.drop(1).forEach { lineTo(x(it.timestampMillis), y(it.valueMgdl)) }
            }
            drawPath(path, color = lineColor, style = Stroke(width = 4f, cap = StrokeCap.Round))

            // Current point
            readings.last().let {
                drawCircle(
                    color = if (stale) emptyColor else Color(ZoneColors.of(thresholds.classify(it.valueMgdl)).value),
                    radius = 7f,
                    center = Offset(x(it.timestampMillis), y(it.valueMgdl)),
                )
            }
        }
    }
}
