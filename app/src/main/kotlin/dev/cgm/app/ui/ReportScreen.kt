package dev.cgm.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.cgm.app.R
import dev.cgm.app.data.ReportFiles
import dev.cgm.core.RangeReport
import dev.cgm.core.Report
import dev.cgm.core.ReportExport
import dev.cgm.core.ReportFormat
import dev.cgm.core.ReportSplit
import kotlin.math.roundToInt

/**
 * A report over a chosen period, cut into ranges, and the ways out of the app.
 *
 * Mean and median sit side by side rather than one being chosen for you. Glucose
 * is right-skewed — there is far more room above target than below it — so the
 * two part company whenever a stretch runs high, and that parting is itself the
 * finding. Either number on its own hides it.
 */
@Composable
fun ReportScreen(viewModel: CgmViewModel) {
    val context = LocalContext.current
    val report by viewModel.report.collectAsState()
    val building by viewModel.reportBuilding.collectAsState()
    val period by viewModel.reportPeriod.collectAsState()
    val split by viewModel.reportSplit.collectAsState()
    val rangeCount by viewModel.reportRangeCount.collectAsState()

    LaunchedEffect(Unit) { viewModel.refreshReport() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text(stringResource(R.string.set_report), style = MaterialTheme.typography.titleMedium)

        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.report_period), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ReportPeriod.entries.forEach { option ->
                FilterChip(
                    selected = option == period,
                    onClick = { viewModel.selectReportPeriod(option) },
                    label = { Text(option.label) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.report_split), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ReportSplit.entries.forEach { option ->
                FilterChip(
                    selected = option == split,
                    onClick = { viewModel.selectReportSplit(option) },
                    label = { Text(stringResource(option.labelRes)) },
                )
            }
        }

        // The count only means anything for an equal split; the calendar splits
        // get however many days, weeks or months the period actually contains.
        if (split == ReportSplit.EQUAL) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.report_ranges, rangeCount),
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = rangeCount.toFloat(),
                onValueChange = { viewModel.selectReportRangeCount(it.roundToInt()) },
                valueRange = 2f..CgmViewModel.MAX_REPORT_RANGES.toFloat(),
                steps = CgmViewModel.MAX_REPORT_RANGES - 3,
            )
        }

        Spacer(Modifier.height(20.dp))
        val ready = report?.takeIf { it.hasData }
        when {
            building && report == null -> CircularProgressIndicator()
            ready == null -> Text(
                stringResource(R.string.report_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> ReportPreview(ready)
        }

        if (ready != null) {
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.report_export), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            val noReceiver = stringResource(R.string.report_share_failed)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReportFormat.entries.forEach { format ->
                    OutlinedButton(onClick = {
                        share(context, ready, format, noReceiver)
                    }) { Text(format.extension.uppercase()) }
                }

                // PDF comes out of the HTML through the system print pipeline.
                // It paginates and embeds fonts better than a hand-drawn page
                // renderer would, and it leaves one document to keep correct
                // instead of two that have to agree.
                OutlinedButton(onClick = { printReport(context, ready) }) {
                    Text(stringResource(R.string.report_pdf))
                }
            }
        }
    }
}

@Composable
private fun ReportPreview(report: Report) {
    if (report.hasUnreliableRanges) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(R.string.report_thin_ranges),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
    }

    RangeRow(report.overall, report.spec.unit.suffix, header = true)
    HorizontalDivider()
    report.ranges.filter { it.hasData }.forEach {
        RangeRow(it, report.spec.unit.suffix, header = false)
        HorizontalDivider()
    }
}

@Composable
private fun RangeRow(range: RangeReport, unitSuffix: String, header: Boolean) {
    val unreliable = range.hasData && !range.isReliable
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                range.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
            )
            Text(
                "${stringResource(R.string.report_coverage)} ${(range.coverage * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = if (unreliable) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            buildString {
                append(stringResource(R.string.report_mean)).append(' ')
                append(range.mean?.let { "%.0f".format(it) } ?: "—")
                append("   ").append(stringResource(R.string.report_median)).append(' ')
                append(range.median?.let { "%.0f".format(it) } ?: "—")
                append(' ').append(unitSuffix)
                range.timeInRange?.let {
                    append("   ").append(stringResource(R.string.report_in_range)).append(' ')
                    append((it * 100).roundToInt()).append('%')
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val ReportSplit.labelRes: Int
    get() = when (this) {
        ReportSplit.DAY -> R.string.report_split_day
        ReportSplit.WEEK -> R.string.report_split_week
        ReportSplit.MONTH -> R.string.report_split_month
        ReportSplit.EQUAL -> R.string.report_split_equal
    }

private fun share(context: Context, report: Report, format: ReportFormat, onFailure: String) {
    val file = ReportFiles.write(context, report, format)
    val intent = Intent.createChooser(
        ReportFiles.shareIntent(context, file, format),
        file.name,
    )
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, onFailure, Toast.LENGTH_LONG).show()
    }
}

/**
 * Prints the HTML report, which is also how it becomes a PDF.
 *
 * The WebView is never attached to the view tree: the print adapter holds it for
 * the life of the job, which is all it is for.
 */
private fun printReport(context: Context, report: Report) {
    val html = ReportExport.export(report, ReportFormat.HTML)
    val name = ReportExport.fileName(report, ReportFormat.HTML).removeSuffix(".html")

    val webView = WebView(context)
    webView.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String?) {
            context.getSystemService(PrintManager::class.java).print(
                name,
                view.createPrintDocumentAdapter(name),
                PrintAttributes.Builder().build(),
            )
        }
    }
    webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
}
