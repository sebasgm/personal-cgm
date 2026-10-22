package dev.cgm.app.ui

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.cgm.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * An export button with the sentence that says what the file contains.
 *
 * One composable for the Logbook and the Diary rather than two, because the only
 * things that differ are the wording, the file's name and which query runs — and a
 * second copy is a second place for the share intent and its failure case to drift.
 *
 * Each screen exports its own subject: the Logbook exports readings, the Diary
 * exports insulin and meals. They were briefly one combined file on the report
 * screen, which put the logbook's export two screens away from the logbook.
 */
@Composable
fun CsvExportRow(
    @StringRes titleRes: Int,
    @StringRes noteRes: Int,
    /** Names the file, as in `cgm-readings-...csv`. */
    kind: String,
    /** Runs off the main thread; null or a throw is reported, not crashed on. */
    load: suspend () -> String,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val noReceiver = stringResource(R.string.report_share_failed)
    val nothingStored = stringResource(R.string.export_empty)

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(titleRes), style = MaterialTheme.typography.labelLarge)
        Text(
            stringResource(noteRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = {
            scope.launch {
                // Reading the whole table and formatting it is not main-thread work:
                // two years of readings is a hundred thousand rows.
                val csv = withContext(Dispatchers.IO) {
                    runCatching { load() }.getOrNull()
                }
                if (csv == null) {
                    Toast.makeText(context, nothingStored, Toast.LENGTH_LONG).show()
                } else {
                    shareCsv(context, csv, kind, noReceiver)
                }
            }
        }) { Text(stringResource(R.string.log_export_button)) }
    }
}
