package dev.cgm.app.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.cgm.core.Report
import dev.cgm.core.ReportExport
import dev.cgm.core.ReportFormat
import java.io.File

/**
 * Writes a report out and hands it to whatever the user picks.
 *
 * Into the cache rather than anywhere permanent: this is a file on its way
 * somewhere else, and leaving copies of glucose history lying around in app
 * storage is a liability nobody asked for. Android clears the cache when it
 * needs the space, which is exactly the right lifetime.
 */
object ReportFiles {

    private const val DIRECTORY = "reports"

    fun write(context: Context, report: Report, format: ReportFormat): File {
        val directory = File(context.cacheDir, DIRECTORY).apply {
            // Previous exports are not history, they are litter.
            deleteRecursively()
            mkdirs()
        }
        return File(directory, ReportExport.fileName(report, format)).apply {
            writeText(ReportExport.export(report, format))
        }
    }

    fun shareIntent(context: Context, file: File, format: ReportFormat): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = format.mimeType
            putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Opens the file in whatever handles its type — for HTML, a browser.
     *
     * A route to a PDF that does not go through the system print framework at
     * all: every browser can print the page it is showing, and if this app's
     * print path is refused by the device, that one still works.
     */
    fun viewIntent(context: Context, file: File, format: ReportFormat): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriFor(context, file), format.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    private fun uriFor(context: Context, file: File) =
        FileProvider.getUriForFile(context, "${context.packageName}.reports", file)
}
