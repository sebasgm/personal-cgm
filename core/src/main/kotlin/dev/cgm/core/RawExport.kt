package dev.cgm.core

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Every stored row, exactly as stored.
 *
 * The counterpart to [ReportExport], and deliberately not a report: no windows, no
 * statistics, no rollups, no omitted ranges. A report is assembled from the hourly
 * summaries and therefore can only show what those summaries contain — which is
 * precisely what makes it the wrong thing to reach for when the question is whether
 * a gap is in the data or in the summarising.
 *
 * One table with a `section` column rather than three files, because the rows are
 * read together and a single sorted stream is the easiest thing to diff against the
 * report that claimed a gap.
 *
 * Timestamps are UTC and carry their epoch millis beside them. The text is for
 * reading, the number is the one to compute with, and shipping both means a
 * timezone argument can never silently move a reading to another hour.
 */
object RawExport {

    private val TIMESTAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)

    const val MIME_TYPE = "text/csv"

    fun fileName(generatedAtMillis: Long): String =
        "cgm-raw-" +
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
                .withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochMilli(generatedAtMillis)) +
            ".csv"

    fun export(
        readings: List<GlucoseReading>,
        doses: List<InsulinDose>,
        carbs: List<CarbEntry>,
        generatedAtMillis: Long,
    ): String = buildString {
        appendLine(
            "# Raw export of everything stored on the device. Not medical advice and " +
                "not a medical device; these values come from a consumer sensor read " +
                "through an unofficial interface and have not been assessed by any regulator."
        )
        appendLine("# generated,${stamp(generatedAtMillis)}")
        appendLine("# readings,${readings.size}")
        appendLine("# doses,${doses.size}")
        appendLine("# meals,${carbs.size}")
        // The span is stated so a truncated file is obvious at a glance rather than
        // looking like a period with nothing in it.
        readings.minOfOrNull { it.timestampMillis }?.let { appendLine("# first_reading,${stamp(it)}") }
        readings.maxOfOrNull { it.timestampMillis }?.let { appendLine("# last_reading,${stamp(it)}") }
        appendLine()

        appendLine(
            "section,timestamp_utc,epoch_millis,value_mgdl,trend,insulin_kind," +
                "units,grams,has_photo,note"
        )

        // Interleaved in time order: a dose and the meal it covered belong next to
        // the readings around them, which is the whole reason to export them together.
        val rows = readings.map { Row(it.timestampMillis, readingCells(it)) } +
            doses.map { Row(it.givenAtMillis, doseCells(it)) } +
            carbs.map { Row(it.eatenAtMillis, carbCells(it)) }

        rows.sortedBy { it.atMillis }.forEach { appendLine(it.cells) }
    }

    private class Row(val atMillis: Long, val cells: String)

    private fun readingCells(r: GlucoseReading) = listOf(
        "reading",
        stamp(r.timestampMillis),
        r.timestampMillis.toString(),
        number(r.valueMgdl),
        r.trend.name,
        "", "", "", "", "",
    ).joinToString(",")

    private fun doseCells(d: InsulinDose) = listOf(
        "dose",
        stamp(d.givenAtMillis),
        d.givenAtMillis.toString(),
        "", "",
        d.kind.name,
        number(d.units),
        "", "",
        quote(d.note),
    ).joinToString(",")

    private fun carbCells(c: CarbEntry) = listOf(
        "meal",
        stamp(c.eatenAtMillis),
        c.eatenAtMillis.toString(),
        "", "", "", "",
        // Empty, not zero: an uncounted meal recorded no number, and a zero here
        // would be read as a meal with no carbohydrate in it.
        c.grams?.let(::number) ?: "",
        if (c.photoName != null) "1" else "0",
        quote(c.note),
    ).joinToString(",")

    private fun stamp(millis: Long): String = TIMESTAMP.format(Instant.ofEpochMilli(millis))

    private fun number(value: Double): String =
        String.format(java.util.Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')

    /** Quotes a free-text field, doubling any quote inside it, as CSV requires. */
    private fun quote(text: String?): String =
        text?.takeIf { it.isNotEmpty() }?.let { "\"${it.replace("\"", "\"\"")}\"" } ?: ""
}
