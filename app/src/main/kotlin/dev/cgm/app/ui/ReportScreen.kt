package dev.cgm.app.ui

import android.content.ActivityNotFoundException
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.cgm.app.R
import dev.cgm.app.data.ReportFiles
import dev.cgm.core.RangeReport
import dev.cgm.core.Report
import dev.cgm.core.ReportBands
import dev.cgm.core.ReportExport
import dev.cgm.core.ReportFormat
import dev.cgm.core.ReportPreferences
import dev.cgm.core.ReportSplit
import kotlin.math.roundToInt

/**
 * The report, at the foot of the Trends tab: how it is cut, what it says, and the
 * ways out of the app.
 *
 * The controls sit with the exports rather than in Settings. Choosing a period
 * and choosing a file format are one task — you are deciding what to send
 * someone — and splitting them across two tabs meant changing a period, walking
 * back here, and checking whether the thing you exported was the thing you meant.
 *
 * Mean and median sit side by side rather than one being chosen for you. Glucose
 * is right-skewed — there is far more room above target than below it — so the
 * two part company whenever a stretch runs high, and that parting is itself the
 * finding. Either number on its own hides it.
 */
@Composable
fun ReportSection(viewModel: CgmViewModel) {
    val context = LocalContext.current
    val preferences by viewModel.reportPreferences.collectAsState()
    val report by viewModel.report.collectAsState()
    val building by viewModel.reportBuilding.collectAsState()

    /** True while the printable report is open. */
    var previewing by remember { mutableStateOf(false) }

    // Rebuilds on arrival and whenever the choices change, which is the only
    // thing that can alter the shape of it.
    LaunchedEffect(preferences) { viewModel.refreshReport(preferences) }

    /** The report once it has something in it, which is when it can be exported. */
    val ready = report?.takeIf { it.hasData }

    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.set_report), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.report_explain),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(14.dp))
        ChipRow(
            label = stringResource(R.string.report_period),
            options = ReportPeriod.entries,
            selected = ReportPeriod.ofDays(preferences.periodDays),
            text = { it.label },
        ) { viewModel.saveReportPreferences(preferences.copy(periodDays = it.days)) }

        Spacer(Modifier.height(12.dp))
        ChipRow(
            label = stringResource(R.string.report_split),
            options = ReportSplit.entries,
            selected = preferences.split,
            text = { stringResource(it.labelRes) },
        ) { viewModel.saveReportPreferences(preferences.copy(split = it)) }

        // The count only means anything for an equal split: the calendar splits
        // get however many days, weeks or months the period actually contains.
        if (preferences.split == ReportSplit.EQUAL) {
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.report_ranges, preferences.rangeCount),
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = preferences.rangeCount.toFloat(),
                onValueChange = {
                    viewModel.saveReportPreferences(
                        preferences.copy(rangeCount = it.roundToInt())
                    )
                },
                valueRange = ReportPreferences.MIN_RANGES.toFloat()..
                    ReportPreferences.MAX_EQUAL_RANGES.toFloat(),
                steps = ReportPreferences.MAX_EQUAL_RANGES - ReportPreferences.MIN_RANGES - 1,
            )
        }

        Spacer(Modifier.height(12.dp))
        ChipRow(
            label = stringResource(R.string.report_bands),
            options = ReportBands.OPTIONS,
            selected = preferences.bandsPerDay,
            text = { if (it == 0) stringResource(R.string.report_bands_none) else "$it" },
        ) { viewModel.saveReportPreferences(preferences.copy(bandsPerDay = it)) }
        Text(
            stringResource(R.string.report_bands_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(18.dp))
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
            Spacer(Modifier.height(20.dp))
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

                // PDF comes out of the HTML through the system print pipeline,
                // which paginates and embeds fonts better than a hand-drawn page
                // renderer would and leaves one document to keep correct rather
                // than two that have to agree. It goes through the preview rather
                // than straight to the printer: the page has to be laid out in a
                // real window before it can be printed, and seeing it first also
                // tells you whether a failure is the document or the printer.
                OutlinedButton(onClick = { previewing = true }) {
                    Text(stringResource(R.string.report_pdf))
                }
            }
        }
    }

    if (previewing && ready != null) {
        ReportDocument(report = ready, onDismiss = { previewing = false })
    }
}

/**
 * The printable report, on screen, with the print action on it.
 *
 * The WebView is inside the composition rather than built off to one side. An
 * unattached WebView has no window, is never laid out, and is referenced by
 * nothing the framework keeps — three separate ways for a print job to come to
 * nothing, all of which this avoids by simply showing the page. It is also the
 * honest version of the feature: what gets printed is what is on the screen.
 */
@Composable
private fun ReportDocument(report: Report, onDismiss: () -> Unit) {
    val context = LocalContext.current

    /**
     * The HTML written out, or null if writing it failed.
     *
     * Written here rather than at print time so asking for a PDF leaves the HTML
     * beside it, and so the page printed is read from the same bytes that were
     * exported.
     */
    val file = remember(report) {
        runCatching { ReportFiles.write(context, report, ReportFormat.HTML) }.getOrNull()
    }
    val name = file?.name?.removeSuffix(".html") ?: "report"

    var webView by remember { mutableStateOf<WebView?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(file == null) }

    /**
     * Why printing failed, shown verbatim.
     *
     * "Printing could not be opened" is true and useless. Whatever the print
     * framework refused with is the one piece of information that identifies the
     * problem, and it is not worth making someone attach a cable to read it.
     */
    var failure by remember { mutableStateOf<String?>(null) }
    val noPrintService = stringResource(R.string.report_print_unsupported)
    val noViewer = stringResource(R.string.report_view_failed)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.report_document),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                    // A second way out, which does not touch the print
                    // framework: every browser can print what it is showing, so
                    // this works even where the system printer refuses.
                    if (file != null) {
                        TextButton(onClick = {
                            try {
                                context.startActivity(
                                    ReportFiles.viewIntent(context, file, ReportFormat.HTML)
                                )
                            } catch (_: ActivityNotFoundException) {
                                failure = noViewer
                            }
                        }) { Text(stringResource(R.string.report_open_browser)) }
                    }

                    TextButton(
                        enabled = loaded && !error,
                        onClick = {
                            val view = webView
                            val activity = context.activity()
                            val printManager = activity
                                ?.getSystemService(PrintManager::class.java)
                            when {
                                view == null -> failure = "WebView unavailable"
                                activity == null ->
                                    failure = "No Activity: ${context.javaClass.name}"
                                printManager == null -> failure = noPrintService
                                else -> runCatching {
                                    printManager.print(
                                        name,
                                        view.createPrintDocumentAdapter(name),
                                        PrintAttributes.Builder()
                                            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                                            .build(),
                                    )
                                }.onFailure {
                                    failure = "${it.javaClass.simpleName}: ${it.message}"
                                }
                            }
                        },
                    ) { Text(stringResource(R.string.report_print)) }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.trends_close))
                    }
                }

                if (error) {
                    Text(
                        stringResource(R.string.report_document_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }

                failure?.let { reason ->
                    AlertDialog(
                        onDismissRequest = { failure = null },
                        title = { Text(stringResource(R.string.report_print_failed_title)) },
                        text = {
                            Column {
                                Text(stringResource(R.string.report_print_failed))
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    reason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { failure = null }) {
                                Text(stringResource(R.string.trends_close))
                            }
                        },
                    )
                }

                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            // The page has no scripts and fetches nothing. This is
                            // only so it can read the file just written into the
                            // app's own cache.
                            settings.allowFileAccess = true
                            settings.javaScriptEnabled = false
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String?) {
                                    loaded = true
                                }

                                // A failure to load must not look like an empty
                                // report: the print button stays off and says why.
                                override fun onReceivedError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    err: WebResourceError,
                                ) {
                                    if (request.isForMainFrame) error = true
                                }
                            }
                            webView = this
                            file?.let { loadUrl(Uri.fromFile(it).toString()) }
                        }
                    },
                )
            }
        }
    }
}

/**
 * A labelled row of chips, scrollable sideways.
 *
 * Four of these on one screen, so they are one composable rather than four
 * near-identical blocks that drift apart the first time one is adjusted.
 */
@Composable
private fun <T> ChipRow(
    label: String,
    options: Iterable<T>,
    selected: T?,
    text: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(6.dp))
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(text(option)) },
            )
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

        // The day across, in whatever bands were asked for. Medians only: this is
        // the line someone scans for where the day goes wrong, and four figures
        // per band would turn the scan into a reading exercise.
        if (range.bands.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                range.bands.joinToString("   ") { band ->
                    "${band.startHour}h ${band.median?.let { "%.0f".format(it) } ?: "—"}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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

/** Walks out of whatever context wrappers Compose was handed, to the Activity. */
private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
