package dev.cgm.app.ui

import android.content.Context

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.cgm.app.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.cgm.core.GlucoseReading
import dev.cgm.core.GlucoseThresholds
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Issue #10: the raw readings.
 *
 * Deliberately unprocessed — this is the screen that shows what the app actually
 * received, which is the thing no other app exposes. Day headers carry that day's
 * summary so scrolling gives a sense of the week without opening Trends.
 */
@Composable
fun LogbookScreen(viewModel: CgmViewModel) {
    val readings by viewModel.logbook.collectAsState()
    val state by viewModel.state.collectAsState()
    val thresholds = state.snapshot?.thresholds ?: GlucoseThresholds.Default
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val timeFormat = remember { DateTimeFormatter.ofPattern("HH:mm") }

    if (readings.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.log_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val byDay = remember(readings) {
        readings.groupBy {
            Instant.ofEpochMilli(it.timestampMillis).atZone(zone).toLocalDate()
        }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        byDay.forEach { (day, dayReadings) ->
            item(key = "header-$day") {
                DayHeader(day, dayReadings, thresholds)
            }
            items(dayReadings, key = { it.timestampMillis }) { reading ->
                ReadingRow(reading, thresholds, zone, timeFormat)
            }
        }
    }
}

@Composable
private fun DayHeader(
    day: LocalDate,
    readings: List<GlucoseReading>,
    thresholds: GlucoseThresholds,
) {
    val context = LocalContext.current
    val mean = readings.map { it.valueMgdl }.average()
    val inRange = readings.count { thresholds.classify(it.valueMgdl) == dev.cgm.core.Zone.IN_RANGE }
    val tir = (inRange * 100.0 / readings.size).roundToInt()

    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(dayLabel(day, context), fontWeight = FontWeight.Medium)
        Text(
            stringResource(R.string.log_day_summary, mean.roundToInt().toString(), tir, readings.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Takes a [Context] because it is not a composable and the day names have to come
 * from the app's chosen language, not the process default.
 */
private fun dayLabel(day: LocalDate, context: Context): String = when (day) {
    LocalDate.now() -> context.getString(R.string.log_today)
    LocalDate.now().minusDays(1) -> context.getString(R.string.log_yesterday)
    // Month and weekday names follow the default locale, which Locales.wrap sets.
    else -> day.format(DateTimeFormatter.ofPattern("EEE d MMM"))
}

@Composable
private fun ReadingRow(
    reading: GlucoseReading,
    thresholds: GlucoseThresholds,
    zone: ZoneId,
    timeFormat: DateTimeFormatter,
) {
    val time: LocalTime = Instant.ofEpochMilli(reading.timestampMillis)
        .atZone(zone).toLocalTime()

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            time.format(timeFormat),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 16.dp),
        )
        // Zone as a dot, not as coloured text: a long list of coloured numbers
        // is much harder to read than a list of plain ones.
        Box(
            Modifier
                .size(10.dp)
                .background(ZoneColors.of(thresholds.classify(reading.valueMgdl)), CircleShape)
        )
        Text(
            reading.valueMgdl.roundToInt().toString(),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 12.dp),
        )
        Text(
            reading.trend.glyph,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}
