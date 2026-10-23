package dev.cgm.app.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.cgm.app.R
import dev.cgm.app.data.ReportFiles
import dev.cgm.core.ReportFormat
import java.io.File

/**
 * An HTML file on screen, with a route to the printer and to a browser.
 *
 * Extracted from the report's own viewer so the diary's export can reach the same
 * print pipeline. PDF is produced by the system print framework rather than drawn
 * by hand: it paginates and embeds fonts properly, and it leaves one document to
 * keep correct instead of two that have to agree.
 *
 * It goes through a visible page rather than straight to the printer because the
 * page has to be laid out in a real window before it can be printed — and seeing
 * it first also tells you whether a failure is the document or the printer.
 *
 * @param file the page to show, or null when writing it failed.
 * @param name the job name, which becomes the PDF's file name.
 */
@Composable
fun PrintableDocument(file: File?, name: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
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
    }}

/** Walks out of whatever context wrappers Compose was handed, to the Activity. */
private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
