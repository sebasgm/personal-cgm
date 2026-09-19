package dev.cgm.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cgm.core.ChartZoom
import dev.cgm.core.Freshness
import dev.cgm.core.GlucoseSnapshot
import dev.cgm.core.GlucoseStatistics
import dev.cgm.core.GlucoseThresholds
import dev.cgm.core.GlucoseUnit
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
fun HomeScreen(viewModel: CgmViewModel) {
    val state by viewModel.state.collectAsState()
    val history by viewModel.history.collectAsState()
    val stats by viewModel.statistics.collectAsState()
    val span by viewModel.spanMillis.collectAsState()
    val preset by viewModel.preset.collectAsState()

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
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CurrentReading(snapshot, freshness, now)

        SignalLossBanner(snapshot, freshness, now)

        state.error?.let {
            Spacer(Modifier.height(12.dp))
            AttentionBanner(it.message, it.needsUser)
        }

        // The chart takes whatever is left rather than a fixed 160dp inside a
        // scroller. It is the reason this screen exists, so it gets the space,
        // and nothing here scrolls out of reach on a glance.
        GlucoseChart(
            readings = history,
            thresholds = snapshot?.thresholds ?: GlucoseThresholds.Default,
            unit = snapshot?.unit ?: GlucoseUnit.MGDL,
            stale = freshness == Freshness.STALE,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 12.dp),
            onZoom = viewModel::zoomBy,
        )

        WindowChips(
            selected = preset,
            spanLabel = ChartZoom.label(span),
            onSelect = viewModel::selectWindow,
            onRefresh = viewModel::refreshNow,
        )

        Spacer(Modifier.height(12.dp))

        StatStrip(
            stats = stats,
            windowLabel = ChartZoom.label(span),
            unit = snapshot?.unit,
            sensor = state.sensor,
            now = now,
        )
    }
}

/**
 * The red signal-loss state.
 *
 * Silent staleness is the failure mode that matters: an old number looks exactly
 * like a current one, and it is the kind of thing someone doses on. So once the
 * feed goes quiet this says so in the one colour reserved for "what you are
 * looking at may not be true".
 *
 * It appears at the five-minute mark, which is earlier than the signal-loss
 * *alarm* at twenty. That gap is deliberate and is the same principle recorded in
 * docs/04-alarms.md: the screen should stop claiming a value is current long
 * before it is worth waking someone over.
 */
@Composable
private fun SignalLossBanner(snapshot: GlucoseSnapshot?, freshness: Freshness, now: Long) {
    if (freshness == Freshness.FRESH) return

    val minutes = snapshot?.reading?.ageMillis(now)?.div(60_000)
    val detail = when {
        minutes == null -> "no reading yet"
        freshness == Freshness.STALE -> "$minutes min — value above is not current"
        else -> "$minutes min without data"
    }

    Spacer(Modifier.height(10.dp))
    // Deliberately one compact line. It appears above the chart, and a three-line
    // card would squeeze the trace on a short screen exactly when the feed is
    // misbehaving and the history matters most.
    Card(
        colors = CardDefaults.cardColors(containerColor = ChartColors.signalLoss),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "NO SIGNAL",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Color.White)
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
private fun WindowChips(
    selected: GraphWindow?,
    spanLabel: String,
    onSelect: (GraphWindow) -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        GraphWindow.entries.forEach { w ->
            FilterChip(
                selected = w == selected,
                onClick = { onSelect(w) },
                label = { Text(w.label) },
            )
        }

        Spacer(Modifier.weight(1f))

        // After a pinch the span is no longer any chip's, so no chip is selected
        // and the real span is shown here instead. Saying "3h" while showing 1h47
        // would be the one thing this row must not do.
        if (selected == null) {
            Text(
                spanLabel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(4.dp))
        }

        TextButton(onClick = onRefresh, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text("Refresh")
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
