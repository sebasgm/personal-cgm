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
 * Readings only. Insulin and meals have their own document, [TreatmentExport],
 * reached from the Diary: they answer a different question and a combined file
 * made both halves awkward — one header could not fit both shapes, and the one
 * that tried silently shifted a column.
 *
 * Timestamps are UTC and carry their epoch millis beside them. The text is for
 * reading, the number is the one to compute with, and shipping both means a
 * timezone argument can never silently move a reading to another hour.
 */
object RawExport {

    private val TIMESTAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)

    const val MIME_TYPE = "text/csv"

    fun fileName(generatedAtMillis: Long, kind: String = "raw"): String =
        "cgm-$kind-" +
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
                .withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochMilli(generatedAtMillis)) +
            ".csv"

    /**
     * The logbook alone: every reading, nothing else.
     *
     * Its own export rather than a filter over the combined one, because it is
     * reached from the Logbook and that screen's subject is the readings.
     *
     * The trend column is the useful one here beyond the value. `UNKNOWN` marks a
     * reading that came from the vendor's 15-minute history rather than a live
     * poll, because the graph points carry no arrow — which makes it the only
     * thing in the file that says a value arrived after the fact.
     */
    fun readings(readings: List<GlucoseReading>, generatedAtMillis: Long): String = buildString {
        appendLine(
            "# Raw export of every reading stored on the device. Not medical advice " +
                "and not a medical device; these values come from a consumer sensor " +
                "read through an unofficial interface and have not been assessed by " +
                "any regulator."
        )
        appendLine("# generated,${stamp(generatedAtMillis)}")
        appendLine("# readings,${readings.size}")
        readings.minOfOrNull { it.timestampMillis }
            ?.let { appendLine("# first_reading,${stamp(it)}") }
        readings.maxOfOrNull { it.timestampMillis }
            ?.let { appendLine("# last_reading,${stamp(it)}") }
        appendLine()
        appendLine(
            "section,timestamp_utc,epoch_millis,value_mgdl,trend," +
                "stored_at_utc,arrival_lag_min"
        )
        readings.sortedBy { it.timestampMillis }.forEach {
            appendLine(
                listOf(
                    "reading",
                    stamp(it.timestampMillis),
                    it.timestampMillis.toString(),
                    number(it.valueMgdl),
                    it.trend.name,
                    it.storedAtMillis?.let(::stamp) ?: "",
                    // Minutes, because that is the scale the question is asked at:
                    // a lag of 1 means it was polled live, a lag of 40 means it
                    // was swept up long afterwards.
                    it.arrivalLagMillis()?.let { lag -> (lag / 60_000).toString() } ?: "",
                ).joinToString(",")
            )
        }
    }

    /**
     * Insulin and meals, without the readings.
     *
     * Reached from the Diary, whose subject is what the person did rather than
     * what the sensor measured. Units and grams stay in separate columns, as they
     * do everywhere else: one column holding both is a column something will
     * eventually add up.
     */
    private fun stamp(millis: Long): String = TIMESTAMP.format(Instant.ofEpochMilli(millis))

    private fun number(value: Double): String =
        String.format(java.util.Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')

    /** Quotes a free-text field, doubling any quote inside it, as CSV requires. */
    private fun quote(text: String?): String =
        text?.takeIf { it.isNotEmpty() }?.let { "\"${it.replace("\"", "\"\"")}\"" } ?: ""
}
