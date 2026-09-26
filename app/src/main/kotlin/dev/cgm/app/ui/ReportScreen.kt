package dev.cgm.app.ui

import android.content.ActivityNotFoundException
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintDocumentAdapter
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

    /**
     * The WebView doing the printing, held for as long as the job runs.
     *
     * Nothing else references it — it is never attached to the view tree — so
     * without this it can be collected between loading the page and the print
     * service asking for it, which is what made printing abort. Cleared when the
     * job ends, because it holds the Activity.
     */
    var printJob by remember { mutableStateOf<WebView?>(null) }

    // Rebuilds on arrival and whenever the choices change, which is the only
    // thing that can alter the shape of it.
    LaunchedEffect(preferences) { viewModel.refreshReport(preferences) }

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
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.report_export), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            val noReceiver = stringResource(R.string.report_share_failed)
            val printFailed = stringResource(R.string.report_print_failed)
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
                OutlinedButton(onClick = {
                    printJob = printReport(
                        context = context,
                        report = ready,
                        onFinished = { printJob = null },
                        onFailure = {
                            printJob = null
                            Toast.makeText(context, printFailed, Toast.LENGTH_LONG).show()
                        },
                    )
                }) {
                    Text(stringResource(R.string.report_pdf))
                }
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

/**
 * Prints the HTML report, which is also how it becomes a PDF.
 *
 * Returns the WebView so the caller can keep it alive. The print framework does
 * not take ownership of it, and an off-tree WebView with no other reference can
 * be collected mid-job — which shows up as a print that simply gives up.
 */
private fun printReport(
    context: Context,
    report: Report,
    onFinished: () -> Unit,
    onFailure: () -> Unit,
): WebView? {
    // Printing opens a system UI, which needs an Activity rather than whatever
    // context happens to be in scope.
    val activity = context.activity() ?: run { onFailure(); return null }
    val printManager = activity.getSystemService(PrintManager::class.java)
        ?: run { onFailure(); return null }

    // The HTML is written out on the way past, and printed from the file rather
    // than from a string. Asking for a PDF leaves the HTML beside it — the same
    // bytes, so what came off the printer can be checked against a file that
    // exists — and there is one document rather than two that have to agree.
    val file = runCatching { ReportFiles.write(activity, report, ReportFormat.HTML) }
        .getOrElse { onFailure(); return null }
    val name = file.name.removeSuffix(".html")

    val webView = WebView(activity)
    // The page has no scripts and fetches nothing; this is only so it can read
    // the file just written into the app's own cache.
    webView.settings.allowFileAccess = true
    webView.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String?) {
            val adapter = ReleasingAdapter(view.createPrintDocumentAdapter(name), onFinished)
            runCatching {
                printManager.print(
                    name,
                    adapter,
                    PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .build(),
                )
            }.onFailure { onFailure() }
        }
    }
    webView.loadUrl(Uri.fromFile(file).toString())
    return webView
}

/** Walks out of whatever context wrappers Compose was handed, to the Activity. */
private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/**
 * The WebView's own adapter, plus a signal when the job is over.
 *
 * Every call is forwarded untouched; the only addition is telling the caller it
 * can stop holding the WebView. `onFinish` is the framework's own guarantee that
 * nothing further will be asked of the adapter, whether the job printed, failed
 * or was cancelled.
 */
private class ReleasingAdapter(
    private val delegate: PrintDocumentAdapter,
    private val onFinished: () -> Unit,
) : PrintDocumentAdapter() {

    override fun onStart() = delegate.onStart()

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback?,
        extras: Bundle?,
    ) = delegate.onLayout(oldAttributes, newAttributes, cancellationSignal, callback, extras)

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor?,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback?,
    ) = delegate.onWrite(pages, destination, cancellationSignal, callback)

    override fun onFinish() {
        delegate.onFinish()
        onFinished()
    }
}
