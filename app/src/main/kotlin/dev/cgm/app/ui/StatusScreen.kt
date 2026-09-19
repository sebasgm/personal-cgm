package dev.cgm.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cgm.core.Freshness
import dev.cgm.core.GlucoseRange
import dev.cgm.core.GlucoseReading
import dev.cgm.core.GlucoseSnapshot
import dev.cgm.core.Zone
import kotlinx.coroutines.delay

@Composable
fun StatusScreen(
    viewModel: CgmViewModel,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val history by viewModel.history.collectAsState()

    // The age must keep counting even when no new data arrives — a frozen "2 min
    // ago" on a dead feed is exactly the failure this app has to avoid. Stage 4
    // gets this for free on the watch from the platform's time bindings.
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
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (snapshot == null) {
            Text("Waiting for the first reading…", style = MaterialTheme.typography.titleMedium)
        } else {
            ReadingCard(snapshot, freshness, now)
        }

        state.error?.let { error ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (error.needsUser) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (error.needsUser) "Needs your attention" else "Last poll failed",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(error.message, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (history.size > 1) {
            Text("Last 3 hours", style = MaterialTheme.typography.labelLarge)
            Sparkline(
                readings = history,
                range = snapshot?.range ?: GlucoseRange(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = viewModel::refreshNow) { Text("Refresh now") }
            OutlinedButton(onClick = onStartService) { Text("Start") }
            OutlinedButton(onClick = onStopService) { Text("Stop") }
        }

        state.lastSuccessMillis?.let {
            Text(
                "Last successful poll ${(now - it) / 1000}s ago",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        TextButton(onClick = viewModel::signOut) { Text("Sign out") }
    }
}

@Composable
private fun ReadingCard(snapshot: GlucoseSnapshot, freshness: Freshness, now: Long) {
    val zoneColor = when (snapshot.zone()) {
        Zone.URGENT_LOW, Zone.LOW -> Color(0xFFD32F2F)
        Zone.HIGH -> Color(0xFFF9A825)
        Zone.IN_RANGE -> Color(0xFF2E7D32)
    }
    // Staleness outranks the zone: a stale in-range value must not look calm.
    val valueColor = if (freshness == Freshness.STALE) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        zoneColor
    }

    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                snapshot.formattedValue(),
                fontSize = 72.sp,
                fontWeight = FontWeight.Bold,
                color = valueColor,
            )
            Column(Modifier.padding(start = 12.dp, bottom = 14.dp)) {
                Text(snapshot.reading.trend.glyph, fontSize = 28.sp, color = valueColor)
                Text(snapshot.unit.suffix, style = MaterialTheme.typography.bodySmall)
            }
            snapshot.formattedDelta()?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 12.dp, bottom = 16.dp),
                )
            }
        }

        val minutes = snapshot.reading.ageMillis(now) / 60_000
        val age = if (minutes < 1) "just now" else "$minutes min ago"
        Text(
            when (freshness) {
                Freshness.FRESH -> age
                Freshness.AGING -> "$age · later than expected"
                Freshness.STALE -> "$age · STALE — do not rely on this"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (freshness == Freshness.FRESH) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}

/**
 * Trend graph.
 *
 * Kept simple deliberately: this is a dry run for the bitmap the Wear app will
 * render in stage 5, so the drawing logic is worth getting right on a screen
 * that is easy to iterate on.
 */
@Composable
private fun Sparkline(
    readings: List<GlucoseReading>,
    range: GlucoseRange,
    modifier: Modifier = Modifier,
) {
    val inRangeColor = MaterialTheme.colorScheme.surfaceVariant
    val lineColor = MaterialTheme.colorScheme.primary

    Canvas(modifier) {
        if (readings.size < 2) return@Canvas

        val values = readings.map { it.valueMgdl }
        // Always include the target band so the graph does not rescale wildly
        // when everything happens to sit inside it.
        val minValue = minOf(values.min(), range.lowMgdl) - 10
        val maxValue = maxOf(values.max(), range.highMgdl) + 10
        val span = (maxValue - minValue).coerceAtLeast(1.0)

        val firstTime = readings.first().timestampMillis
        val lastTime = readings.last().timestampMillis
        val timeSpan = (lastTime - firstTime).coerceAtLeast(1L)

        fun x(t: Long) = ((t - firstTime).toFloat() / timeSpan) * size.width
        fun y(v: Double) = (1f - ((v - minValue) / span).toFloat()) * size.height

        // Target band
        drawRect(
            color = inRangeColor,
            topLeft = Offset(0f, y(range.highMgdl)),
            size = androidx.compose.ui.geometry.Size(
                size.width,
                (y(range.lowMgdl) - y(range.highMgdl)).coerceAtLeast(0f),
            ),
        )

        val path = Path().apply {
            moveTo(x(readings.first().timestampMillis), y(readings.first().valueMgdl))
            readings.drop(1).forEach { lineTo(x(it.timestampMillis), y(it.valueMgdl)) }
        }
        drawPath(path, color = lineColor, style = Stroke(width = 3f, cap = StrokeCap.Round))
    }
}
