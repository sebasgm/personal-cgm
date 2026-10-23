package dev.cgm.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The diary — insulin and meals — as a file, over a chosen span.
 *
 * Separate from [ReportExport] because it is a different document. A report is a
 * statistical summary of what the sensor measured; this is a record of what the
 * person did, and the two must not be merged into one total: units and grams are
 * different quantities and a schema that could add them is a schema that
 * eventually does.
 *
 * Every format carries the same things, as the report's do: the span requested, the
 * day totals with basal and bolus apart, how many meals went uncounted, and the
 * disclaimer. A file outlives the screen it came from — it gets mailed and read
 * months later by someone who was not there — so nothing implicit on screen may be
 * left implicit here.
 */
object TreatmentExport {

    /** Dates, in the zone the diary is read in, not UTC. */
    private val DAY: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    private val STAMP_UTC: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)

    fun fileName(fromDate: LocalDate, toDate: LocalDate, format: ReportFormat): String =
        "cgm-insulin-${DAY.format(fromDate)}_${DAY.format(toDate)}.${format.extension}"

    /**
     * @param fromMillis inclusive start, [toMillis] exclusive end.
     *
     * Half-open rather than inclusive on both sides, so a span of whole days can be
     * built by adding days without the last second of one day landing in two spans.
     */
    fun export(
        doses: List<InsulinDose>,
        carbs: List<CarbEntry>,
        fromMillis: Long,
        toMillis: Long,
        zone: ZoneId,
        format: ReportFormat,
        generatedAtMillis: Long,
    ): String {
        val inSpan = Span(
            doses = doses.filter { it.givenAtMillis >= fromMillis && it.givenAtMillis < toMillis },
            carbs = carbs.filter { it.eatenAtMillis >= fromMillis && it.eatenAtMillis < toMillis },
            fromMillis = fromMillis,
            toMillis = toMillis,
            zone = zone,
            generatedAtMillis = generatedAtMillis,
        )
        return when (format) {
            ReportFormat.CSV -> csv(inSpan)
            ReportFormat.JSON -> json(inSpan)
            ReportFormat.HTML -> html(inSpan)
        }
    }

    private class Span(
        val doses: List<InsulinDose>,
        val carbs: List<CarbEntry>,
        val fromMillis: Long,
        val toMillis: Long,
        val zone: ZoneId,
        val generatedAtMillis: Long,
    ) {
        /**
         * Oldest first, unlike the screen's own list.
         *
         * A diary that is read as a document is read forwards; the newest-first
         * order exists so the phone shows today without scrolling, which is not a
         * concern a printed page has.
         */
        val days: List<InsulinDay> =
            InsulinDiary.byDay(doses, carbs, zone).sortedBy { it.date }
        val totalUnits: Double get() = days.sumOf { it.totals.totalUnits }
        val basalUnits: Double get() = days.sumOf { it.totals.basalUnits }
        val bolusUnits: Double get() = days.sumOf { it.totals.bolusUnits }
        val grams: Double get() = days.sumOf { it.carbGrams }
        val uncounted: Int get() = days.sumOf { it.uncountedMeals }

        /**
         * Mean units per day over the days that have an entry, not over the span.
         *
         * Dividing by the span's length would quietly average in days nothing was
         * recorded, which reads as taking less insulin rather than as not having
         * written it down.
         */
        val meanUnitsPerRecordedDay: Double?
            get() = days.count { it.doseCount > 0 }.takeIf { it > 0 }?.let { totalUnits / it }

        fun localDate(millis: Long): LocalDate =
            Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

        fun time(millis: Long): String =
            Instant.ofEpochMilli(millis).atZone(zone).format(TIME)
    }

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    // -- CSV ---------------------------------------------------------------

    private fun csv(span: Span): String = buildString {
        appendLine("# ${ReportExport.DISCLAIMER}")
        appendLine("# generated,${STAMP_UTC.format(Instant.ofEpochMilli(span.generatedAtMillis))}")
        appendLine("# span,${DAY.format(span.localDate(span.fromMillis))}," +
            "${DAY.format(span.localDate(span.toMillis - 1))}")
        appendLine("# zone,${span.zone.id}")
        appendLine("# doses,${span.doses.size}")
        appendLine("# meals,${span.carbs.size}")
        appendLine("# meals_uncounted,${span.uncounted}")
        appendLine()

        appendLine("section,date,basal_units,bolus_units,total_units,carb_grams," +
            "meals_counted,meals_uncounted")
        span.days.forEach {
            appendLine(
                listOf(
                    "day", DAY.format(it.date),
                    number(it.totals.basalUnits), number(it.totals.bolusUnits),
                    number(it.totals.totalUnits), number(it.carbGrams),
                    it.countedMeals.toString(), it.uncountedMeals.toString(),
                ).joinToString(",")
            )
        }
        appendLine()

        appendLine("section,date,local_time,epoch_millis,insulin_kind,units,grams,has_photo,note")
        val rows = span.doses.map { d ->
            d.givenAtMillis to listOf(
                "dose", DAY.format(span.localDate(d.givenAtMillis)), span.time(d.givenAtMillis),
                d.givenAtMillis.toString(), d.kind.name, number(d.units), "", "", quote(d.note),
            ).joinToString(",")
        } + span.carbs.map { c ->
            c.eatenAtMillis to listOf(
                "meal", DAY.format(span.localDate(c.eatenAtMillis)), span.time(c.eatenAtMillis),
                c.eatenAtMillis.toString(), "", "",
                // Empty, not zero: an uncounted meal recorded no number.
                c.grams?.let(::number) ?: "",
                if (c.photoName != null) "1" else "0", quote(c.note),
            ).joinToString(",")
        }
        rows.sortedBy { it.first }.forEach { appendLine(it.second) }
    }

    // -- JSON --------------------------------------------------------------

    private fun json(span: Span): String = buildString {
        appendLine("{")
        appendLine("""  "disclaimer": ${jsonString(ReportExport.DISCLAIMER)},""")
        appendLine("""  "generated": ${jsonString(
            STAMP_UTC.format(Instant.ofEpochMilli(span.generatedAtMillis))
        )},""")
        appendLine("""  "zone": ${jsonString(span.zone.id)},""")
        appendLine("""  "from": ${jsonString(DAY.format(span.localDate(span.fromMillis)))},""")
        appendLine("""  "to": ${jsonString(DAY.format(span.localDate(span.toMillis - 1)))},""")
        appendLine("""  "totals": {""")
        appendLine("""    "basalUnits": ${number(span.basalUnits)},""")
        appendLine("""    "bolusUnits": ${number(span.bolusUnits)},""")
        appendLine("""    "totalUnits": ${number(span.totalUnits)},""")
        appendLine("""    "carbGrams": ${number(span.grams)},""")
        appendLine("""    "mealsUncounted": ${span.uncounted},""")
        appendLine("""    "meanUnitsPerRecordedDay": ${
            span.meanUnitsPerRecordedDay?.let(::number) ?: "null"
        }""")
        appendLine("""  },""")

        appendLine("""  "days": [""")
        span.days.forEachIndexed { i, d ->
            appendLine(
                """    {"date": ${jsonString(DAY.format(d.date))}, """ +
                    """"basalUnits": ${number(d.totals.basalUnits)}, """ +
                    """"bolusUnits": ${number(d.totals.bolusUnits)}, """ +
                    """"totalUnits": ${number(d.totals.totalUnits)}, """ +
                    """"carbGrams": ${number(d.carbGrams)}, """ +
                    """"mealsCounted": ${d.countedMeals}, """ +
                    """"mealsUncounted": ${d.uncountedMeals}}""" +
                    if (i == span.days.lastIndex) "" else ","
            )
        }
        appendLine("""  ],""")

        appendLine("""  "doses": [""")
        span.doses.sortedBy { it.givenAtMillis }.forEachIndexed { i, d ->
            appendLine(
                """    {"at": ${jsonString(STAMP_UTC.format(Instant.ofEpochMilli(d.givenAtMillis)))}, """ +
                    """"epochMillis": ${d.givenAtMillis}, """ +
                    """"kind": ${jsonString(d.kind.name)}, """ +
                    """"units": ${number(d.units)}, """ +
                    """"note": ${d.note?.let(::jsonString) ?: "null"}}""" +
                    if (i == span.doses.lastIndex) "" else ","
            )
        }
        appendLine("""  ],""")

        appendLine("""  "meals": [""")
        span.carbs.sortedBy { it.eatenAtMillis }.forEachIndexed { i, c ->
            appendLine(
                """    {"at": ${jsonString(STAMP_UTC.format(Instant.ofEpochMilli(c.eatenAtMillis)))}, """ +
                    """"epochMillis": ${c.eatenAtMillis}, """ +
                    // null, never 0: an uncounted meal recorded no number, and a
                    // zero here would be read as a meal with no carbohydrate in it.
                    """"grams": ${c.grams?.let(::number) ?: "null"}, """ +
                    """"hasPhoto": ${c.photoName != null}, """ +
                    """"note": ${c.note?.let(::jsonString) ?: "null"}}""" +
                    if (i == span.carbs.lastIndex) "" else ","
            )
        }
        appendLine("""  ]""")
        append("}")
    }

    // -- HTML, which is also the PDF ---------------------------------------

    private fun html(span: Span): String = buildString {
        appendLine("<!doctype html><html><head><meta charset=\"utf-8\">")
        appendLine("<title>${escape(title(span))}</title>")
        // Print-first styling: the only reason this format exists is that the
        // system print pipeline paginates and embeds fonts better than a
        // hand-drawn page renderer, and it is one document to keep correct
        // rather than two that have to agree.
        appendLine(
            """
            <style>
              @page { size: A4; margin: 16mm; }
              body { font: 11pt/1.45 -apple-system, Roboto, sans-serif; color: #111; }
              h1 { font-size: 16pt; margin: 0 0 2mm; }
              h2 { font-size: 12pt; margin: 8mm 0 2mm; }
              .muted { color: #555; font-size: 9pt; }
              .disclaimer { border: 1pt solid #999; padding: 3mm; font-size: 9pt; margin: 4mm 0; }
              table { border-collapse: collapse; width: 100%; margin-top: 2mm; }
              th, td { border-bottom: 0.5pt solid #ccc; padding: 1.5mm 2mm; text-align: left; }
              th { font-size: 9pt; text-transform: uppercase; color: #555; }
              td.n, th.n { text-align: right; }
              tfoot td { font-weight: 600; border-top: 1pt solid #666; }
            </style>
            """.trimIndent()
        )
        appendLine("</head><body>")
        appendLine("<h1>${escape(title(span))}</h1>")
        appendLine(
            "<p class=\"muted\">${escape(span.zone.id)} · generated " +
                "${escape(STAMP_UTC.format(Instant.ofEpochMilli(span.generatedAtMillis)))} UTC</p>"
        )
        appendLine("<p class=\"disclaimer\">${escape(ReportExport.DISCLAIMER)}</p>")

        appendLine("<h2>By day</h2>")
        if (span.days.isEmpty()) {
            appendLine("<p class=\"muted\">Nothing recorded in this span.</p>")
        } else {
            appendLine("<table><thead><tr><th>Date</th><th class=\"n\">Basal</th>")
            appendLine("<th class=\"n\">Bolus</th><th class=\"n\">Total U</th>")
            appendLine("<th class=\"n\">Carbs g</th><th class=\"n\">Meals</th></tr></thead><tbody>")
            span.days.forEach {
                val meals = if (it.uncountedMeals > 0) {
                    "${it.countedMeals} + ${it.uncountedMeals} uncounted"
                } else {
                    it.countedMeals.toString()
                }
                appendLine(
                    "<tr><td>${DAY.format(it.date)}</td>" +
                        "<td class=\"n\">${number(it.totals.basalUnits)}</td>" +
                        "<td class=\"n\">${number(it.totals.bolusUnits)}</td>" +
                        "<td class=\"n\">${number(it.totals.totalUnits)}</td>" +
                        "<td class=\"n\">${number(it.carbGrams)}</td>" +
                        "<td class=\"n\">$meals</td></tr>"
                )
            }
            appendLine("</tbody><tfoot><tr><td>Total</td>")
            appendLine("<td class=\"n\">${number(span.basalUnits)}</td>")
            appendLine("<td class=\"n\">${number(span.bolusUnits)}</td>")
            appendLine("<td class=\"n\">${number(span.totalUnits)}</td>")
            appendLine("<td class=\"n\">${number(span.grams)}</td>")
            appendLine("<td class=\"n\"></td></tr></tfoot></table>")
            span.meanUnitsPerRecordedDay?.let {
                // Stated with its denominator, because "per day" over a span with
                // unrecorded days in it would understate the dose.
                appendLine(
                    "<p class=\"muted\">${number(it)} U per day, " +
                        "over the ${span.days.count { d -> d.doseCount > 0 }} " +
                        "day(s) with insulin recorded.</p>"
                )
            }
            if (span.uncounted > 0) {
                appendLine(
                    "<p class=\"muted\">${span.uncounted} meal(s) were logged without a " +
                        "carbohydrate count and contribute nothing to the gram totals.</p>"
                )
            }
        }

        appendLine("<h2>Entries</h2>")
        val entries = span.doses.map { it.givenAtMillis to it } +
            span.carbs.map { it.eatenAtMillis to it }
        if (entries.isEmpty()) {
            appendLine("<p class=\"muted\">Nothing recorded in this span.</p>")
        } else {
            appendLine("<table><thead><tr><th>Date</th><th>Time</th><th>What</th>")
            appendLine("<th class=\"n\">Amount</th><th>Note</th></tr></thead><tbody>")
            entries.sortedBy { it.first }.forEach { (at, entry) ->
                val (what, amount, note) = when (entry) {
                    is InsulinDose -> Triple(
                        if (entry.kind == InsulinKind.BASAL) "Basal" else "Bolus",
                        "${number(entry.units)} U",
                        entry.note,
                    )
                    is CarbEntry -> Triple(
                        if (entry.photoName != null) "Meal (photo)" else "Meal",
                        entry.grams?.let { "${number(it)} g" } ?: "not counted",
                        entry.note,
                    )
                    else -> Triple("", "", null)
                }
                appendLine(
                    "<tr><td>${DAY.format(span.localDate(at))}</td>" +
                        "<td>${span.time(at)}</td><td>${escape(what)}</td>" +
                        "<td class=\"n\">${escape(amount)}</td>" +
                        "<td>${escape(note ?: "")}</td></tr>"
                )
            }
            appendLine("</tbody></table>")
        }
        appendLine("</body></html>")
    }

    private fun title(span: Span): String {
        val from = DAY.format(span.localDate(span.fromMillis))
        val to = DAY.format(span.localDate(span.toMillis - 1))
        return if (from == to) "Insulin diary · $from" else "Insulin diary · $from to $to"
    }

    private fun number(value: Double): String =
        String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.').ifEmpty { "0" }

    private fun quote(text: String?): String =
        text?.takeIf { it.isNotEmpty() }?.let { "\"${it.replace("\"", "\"\"")}\"" } ?: ""

    private fun jsonString(text: String): String =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

    private fun escape(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")
}
