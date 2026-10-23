package dev.cgm.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.cgm.app.R
import dev.cgm.app.data.ReportFiles
import dev.cgm.core.ReportFormat
import dev.cgm.core.TreatmentExport
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The diary's export: a span to cover, and three ways to leave the app.
 *
 * The span is explicit because the diary's natural unit is a visit — "the last
 * fortnight", "since the dose changed" — and an export fixed to everything stored
 * is the wrong document for every one of those. Fourteen days by default, which is
 * what the summary above it shows.
 *
 * CSV and JSON are shared as files. PDF goes through the same visible page and
 * system print pipeline the report uses, which is why it opens a document rather
 * than producing a file directly.
 */
@Composable
fun DiaryExport(viewModel: CgmViewModel, zone: ZoneId) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val today = remember(zone) { LocalDate.now(zone) }

    var from by remember(today) { mutableStateOf(today.minusDays(13)) }
    var to by remember(today) { mutableStateOf(today) }
    var picking by remember { mutableStateOf<Bound?>(null) }
    var printing by remember { mutableStateOf<File?>(null) }

    val noReceiver = stringResource(R.string.report_share_failed)
    val nothing = stringResource(R.string.diary_export_nothing)

    /** Half-open: the end day is included in full, so the bound is its midnight. */
    fun spanMillis(): Pair<Long, Long> =
        from.atStartOfDay(zone).toInstant().toEpochMilli() to
            to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    fun export(format: ReportFormat) {
        scope.launch {
            val (start, end) = spanMillis()
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    viewModel.treatmentsExport(start, end, zone, format)
                }.getOrNull()
            }
            if (text == null) {
                Toast.makeText(context, nothing, Toast.LENGTH_LONG).show()
                return@launch
            }
            val name = TreatmentExport.fileName(from, to, format)
            val file = withContext(Dispatchers.IO) {
                runCatching { ReportFiles.writeRaw(context, text, name) }.getOrNull()
            }
            if (file == null) {
                Toast.makeText(context, nothing, Toast.LENGTH_LONG).show()
            } else if (format == ReportFormat.HTML) {
                // Not shared as a file: HTML exists here to be printed, and the
                // print framework needs the page laid out in a window first.
                printing = file
            } else {
                shareCsv(context, text, "insulin-${from}_$to", noReceiver)
            }
        }
    }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.diary_export), style = MaterialTheme.typography.labelLarge)
        Text(
            stringResource(R.string.diary_export_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.height(6.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { picking = Bound.FROM }) {
                Text("${stringResource(R.string.diary_export_from)}: ${DAY.format(from)}")
            }
            OutlinedButton(onClick = { picking = Bound.TO }) {
                Text("${stringResource(R.string.diary_export_to)}: ${DAY.format(to)}")
            }
        }
        Text(
            stringResource(R.string.diary_export_range_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { export(ReportFormat.CSV) }) { Text("CSV") }
            OutlinedButton(onClick = { export(ReportFormat.JSON) }) { Text("JSON") }
            OutlinedButton(onClick = { export(ReportFormat.HTML) }) {
                Text(stringResource(R.string.diary_export_pdf))
            }
        }
    }

    picking?.let { bound ->
        DayBoundPicker(
            initial = if (bound == Bound.FROM) from else to,
            zone = zone,
            onPick = { picked ->
                // Kept ordered rather than refused: dragging the start past the end
                // is an ordinary slip, and an error message for it is friction for
                // something the screen can simply absorb.
                if (bound == Bound.FROM) {
                    from = picked
                    if (to < picked) to = picked
                } else {
                    to = picked
                    if (picked < from) from = picked
                }
                picking = null
            },
            onDismiss = { picking = null },
        )
    }

    printing?.let { file ->
        PrintableDocument(
            file = file,
            name = file.name.removeSuffix(".html"),
            onDismiss = { printing = null },
        )
    }
}

private enum class Bound { FROM, TO }

/**
 * One calendar day.
 *
 * The picker works in UTC internally, so its selection is converted through the
 * date rather than the instant — reading the instant in the local zone can land on
 * the previous day for anyone west of Greenwich, which is everyone here.
 */
@Composable
private fun DayBoundPicker(
    initial: LocalDate,
    zone: ZoneId,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let {
                        onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    } ?: onDismiss()
                }
            ) { Text(stringResource(R.string.dose_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.trends_close)) }
        },
    ) { DatePicker(state = state) }
}

private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
