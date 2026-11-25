package dev.cgm.app.ui

import android.content.Context
import dev.cgm.app.Locales
import dev.cgm.app.R
import dev.cgm.core.ReportLabels

/**
 * The report's own words, read out of `strings.xml`.
 *
 * [ReportLabels] exists because `:core` is plain Kotlin/JVM and cannot see an
 * Android resource; this is the one place that bridges the two, so the exported
 * document is translated by the same file as the rest of the app rather than by a
 * second table living next to the exporter.
 *
 * The context is wrapped, so these resolve in the language the app is *set* to
 * rather than the one the device is in. The report is reachable from a screen
 * whose Activity already carries that override, but the wrap costs nothing and
 * means a caller that is not an Activity — a future scheduled export, say —
 * cannot quietly produce a document in the wrong language.
 */
fun reportLabels(context: Context): ReportLabels {
    val localised = Locales.wrap(context)
    fun string(id: Int) = localised.getString(id)

    return ReportLabels(
        // The resolved locale rather than `Locales.current`, which is null while
        // the app is following the device and would leave `<html lang>` empty.
        languageTag = localised.resources.configuration.locales[0].toLanguageTag(),
        title = string(R.string.report_doc_title),
        overall = string(R.string.report_doc_overall),
        generated = string(R.string.report_doc_generated),
        valuesIn = string(R.string.report_doc_values_in),
        inRange = string(R.string.report_doc_in_range),
        summary = string(R.string.report_doc_summary),
        columnRange = string(R.string.report_doc_column_range),
        columnReadings = string(R.string.report_doc_column_readings),
        columnCoverage = string(R.string.report_doc_column_coverage),
        columnMean = string(R.string.report_doc_column_mean),
        columnMedian = string(R.string.report_doc_column_median),
        columnSd = string(R.string.report_doc_column_sd),
        columnCv = string(R.string.report_doc_column_cv),
        columnInRange = string(R.string.report_doc_column_in_range),
        medianByTimeOfDay = string(R.string.report_doc_median_by_time),
        disclaimer = string(R.string.report_doc_disclaimer),
        skippedRanges = string(R.string.report_doc_skipped_ranges),
        thinRanges = string(R.string.report_doc_thin_ranges),
        bandsNote = string(R.string.report_doc_bands_note),
        rangeHeadline = string(R.string.report_doc_range_headline),
    )
}
