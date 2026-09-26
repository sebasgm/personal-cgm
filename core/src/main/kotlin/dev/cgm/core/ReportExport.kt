package dev.cgm.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class ReportFormat(val extension: String, val mimeType: String) {
    CSV("csv", "text/csv"),
    JSON("json", "application/json"),
    HTML("html", "text/html"),
}

/**
 * Turns a report into something that leaves the app.
 *
 * Everything here carries the same three things, in every format: the thresholds
 * the zones were computed against, the coverage behind each range, and the
 * disclaimer. A file outlives the screen it came from — it gets mailed, printed
 * and read months later by someone who was not there — so context that is
 * implicit on screen has to be explicit in the export.
 */
object ReportExport {

    const val DISCLAIMER =
        "Not medical advice and not a medical device. These figures come from a " +
            "consumer sensor read through an unofficial interface, may be incomplete, " +
            "and have not been assessed by any regulator. Do not use them to make " +
            "treatment decisions."

    fun export(report: Report, format: ReportFormat, zone: ZoneId = ZoneId.systemDefault()): String =
        when (format) {
            ReportFormat.CSV -> csv(report, zone)
            ReportFormat.JSON -> json(report, zone)
            ReportFormat.HTML -> html(report, zone)
        }

    fun fileName(report: Report, format: ReportFormat, zone: ZoneId = ZoneId.systemDefault()): String {
        val stamp = Instant.ofEpochMilli(report.generatedAtMillis).atZone(zone).format(FILE_STAMP)
        return "cgm-report-$stamp.${format.extension}"
    }

    // -- CSV ------------------------------------------------------------------

    /**
     * One row per range, then one row per histogram bin.
     *
     * Long rather than wide on purpose: a spreadsheet can pivot long data into
     * any shape, and cannot easily unpivot a table whose columns are bins.
     */
    private fun csv(report: Report, zone: ZoneId): String = buildString {
        appendLine("# $DISCLAIMER")
        appendLine("# generated,${iso(report.generatedAtMillis, zone)}")
        appendLine("# unit,${report.spec.unit.suffix}")
        appendLine(
            "# thresholds_mgdl,urgent_low,${report.spec.thresholds.urgentLowMgdl}," +
                "low,${report.spec.thresholds.lowMgdl}," +
                "high,${report.spec.thresholds.highMgdl}," +
                "very_high,${report.spec.thresholds.veryHighMgdl}"
        )
        appendLine()

        appendLine(
            "section,range,start,end,readings,coverage,mean_mgdl,median_mgdl," +
                "sd_mgdl,cv_percent,p10_mgdl,p25_mgdl,p75_mgdl,p90_mgdl," +
                "time_in_range,time_low,time_high"
        )
        (report.ranges + report.overall).forEach { r ->
            appendLine(
                listOf(
                    if (r === report.overall) "overall" else "range",
                    quote(r.label),
                    iso(r.startMillis, zone),
                    iso(r.endMillis, zone),
                    r.readingCount,
                    fmt(r.coverage),
                    fmt(r.mean), fmt(r.median),
                    fmt(r.standardDeviation), fmt(r.coefficientOfVariation),
                    fmt(r.p10), fmt(r.p25), fmt(r.p75), fmt(r.p90),
                    fmt(r.timeInRange),
                    fmt((r.zoneFractions[Zone.URGENT_LOW] ?: 0.0) + (r.zoneFractions[Zone.LOW] ?: 0.0)),
                    fmt((r.zoneFractions[Zone.HIGH] ?: 0.0) + (r.zoneFractions[Zone.VERY_HIGH] ?: 0.0)),
                ).joinToString(",")
            )
        }

        appendLine()
        appendLine("histogram_range,bin_low_mgdl,bin_high_mgdl,count")
        report.ranges.forEach { r ->
            r.bins.forEachIndexed { index, count ->
                if (count == 0) return@forEachIndexed
                val low = GlucoseHistogram.MIN_MGDL + index * GlucoseHistogram.BIN_WIDTH
                appendLine("${quote(r.label)},$low,${low + GlucoseHistogram.BIN_WIDTH},$count")
            }
        }
    }

    // -- JSON -----------------------------------------------------------------

    private fun json(report: Report, zone: ZoneId): String = buildString {
        appendLine("{")
        appendLine("""  "disclaimer": ${jsonString(DISCLAIMER)},""")
        appendLine("""  "generated": ${jsonString(iso(report.generatedAtMillis, zone))},""")
        appendLine("""  "unit": ${jsonString(report.spec.unit.suffix)},""")
        appendLine("""  "thresholdsMgdl": {""")
        appendLine("""    "urgentLow": ${report.spec.thresholds.urgentLowMgdl},""")
        appendLine("""    "low": ${report.spec.thresholds.lowMgdl},""")
        appendLine("""    "high": ${report.spec.thresholds.highMgdl},""")
        appendLine("""    "veryHigh": ${report.spec.thresholds.veryHighMgdl}""")
        appendLine("""  },""")
        appendLine("""  "overall": ${rangeJson(report.overall, zone, indent = 2)},""")
        appendLine("""  "ranges": [""")
        report.ranges.forEachIndexed { index, r ->
            val comma = if (index == report.ranges.lastIndex) "" else ","
            appendLine("    ${rangeJson(r, zone, indent = 4)}$comma")
        }
        appendLine("  ]")
        append("}")
    }

    private fun rangeJson(r: RangeReport, zone: ZoneId, indent: Int): String {
        val pad = " ".repeat(indent)
        val bins = r.bins.mapIndexed { index, count -> index to count }
            .filter { it.second > 0 }
            .joinToString(", ") { (index, count) ->
                """{"lowMgdl": ${GlucoseHistogram.MIN_MGDL + index * GlucoseHistogram.BIN_WIDTH}, "count": $count}"""
            }
        return """{
$pad  "label": ${jsonString(r.label)},
$pad  "start": ${jsonString(iso(r.startMillis, zone))},
$pad  "end": ${jsonString(iso(r.endMillis, zone))},
$pad  "readings": ${r.readingCount},
$pad  "coverage": ${fmt(r.coverage)},
$pad  "reliable": ${r.isReliable},
$pad  "meanMgdl": ${fmt(r.mean)},
$pad  "medianMgdl": ${fmt(r.median)},
$pad  "sdMgdl": ${fmt(r.standardDeviation)},
$pad  "cvPercent": ${fmt(r.coefficientOfVariation)},
$pad  "timeInRange": ${fmt(r.timeInRange)},
$pad  "histogram": [$bins]
$pad}"""
    }

    // -- HTML -----------------------------------------------------------------

    /**
     * A self-contained page: no scripts, no network, inline SVG histograms.
     *
     * Self-contained because the destination is usually an attachment or a
     * printer, and a report that needs the internet to draw its own charts is a
     * report that will one day be blank. It is also the PDF path — the system's
     * print pipeline renders this, which types it better than hand-drawing one.
     */
    private fun html(report: Report, zone: ZoneId): String = buildString {
        appendLine("<!doctype html><html><head><meta charset=\"utf-8\">")
        appendLine("<title>Glucose report</title><style>")
        appendLine(
            """
            body{font:14px/1.5 system-ui,sans-serif;margin:24px;color:#171d1a;max-width:900px}
            h1{font-size:1.4rem;margin:0 0 4px} h2{font-size:1rem;margin:28px 0 8px}
            .muted{color:#5b6763;font-size:.85rem}
            .warn{background:#f7dcd9;color:#5f1a15;padding:10px 12px;border-radius:8px;margin:12px 0}
            table{border-collapse:collapse;width:100%;font-size:.85rem;margin-top:8px}
            th,td{text-align:right;padding:6px 8px;border-bottom:1px solid #dbe5df}
            th:first-child,td:first-child{text-align:left}
            .thin{color:#8a7a2a}
            @media print{body{margin:0} .page{page-break-inside:avoid}}
            """.trimIndent()
        )
        appendLine("</style></head><body>")

        appendLine("<h1>Glucose report</h1>")
        appendLine("<p class=\"muted\">Generated ${iso(report.generatedAtMillis, zone)} · ")
        appendLine("values in ${escapeHtml(report.spec.unit.suffix)} · ")
        appendLine("in range ${report.spec.thresholds.lowMgdl.toInt()}–${report.spec.thresholds.highMgdl.toInt()} mg/dL</p>")
        appendLine("<div class=\"warn\">${escapeHtml(DISCLAIMER)}</div>")

        appendLine("<h2>Summary</h2><table>")
        appendLine(
            "<tr><th>Range</th><th>Readings</th><th>Coverage</th><th>Mean</th>" +
                "<th>Median</th><th>SD</th><th>CV</th><th>In range</th></tr>"
        )
        (report.ranges + report.overall).forEach { r ->
            val thin = if (r.hasData && !r.isReliable) " class=\"thin\"" else ""
            appendLine(
                "<tr$thin><td>${escapeHtml(r.label)}</td><td>${r.readingCount}</td>" +
                    "<td>${pct(r.coverage)}</td><td>${display(r.mean, report.spec.unit)}</td>" +
                    "<td>${display(r.median, report.spec.unit)}</td>" +
                    "<td>${display(r.standardDeviation, report.spec.unit)}</td>" +
                    "<td>${pct(r.coefficientOfVariation?.div(100))}</td>" +
                    "<td>${pct(r.timeInRange)}</td></tr>"
            )
        }
        appendLine("</table>")

        if (report.hasUnreliableRanges) {
            appendLine(
                "<p class=\"muted\">Ranges shown in amber have under " +
                    "${(GlucoseStatistics.RELIABLE_COVERAGE * 100).toInt()}% coverage. " +
                    "Their figures describe the hours recorded, not the whole range.</p>"
            )
        }

        report.ranges.filter { it.hasData }.forEach { r ->
            appendLine("<div class=\"page\"><h2>${escapeHtml(r.label)}</h2>")
            appendLine("<p class=\"muted\">${r.readingCount} readings · ${pct(r.coverage)} coverage</p>")
            appendLine(histogramSvg(r, report.spec))
            appendLine("</div>")
        }

        append("</body></html>")
    }

    /** A bar per occupied bin, drawn as plain SVG so it survives any renderer. */
    private fun histogramSvg(r: RangeReport, spec: ReportSpec): String {
        val width = 860
        val height = 180
        val tallest = r.bins.maxOrNull()?.takeIf { it > 0 } ?: return ""
        val barWidth = width.toDouble() / GlucoseHistogram.BIN_COUNT

        val bars = r.bins.mapIndexed { index, count ->
            if (count == 0) return@mapIndexed ""
            val mgdl = GlucoseHistogram.midpointOf(index)
            val h = (count.toDouble() / tallest) * (height - 24)
            val x = index * barWidth
            val colour = when (spec.thresholds.classify(mgdl)) {
                Zone.URGENT_LOW, Zone.LOW -> "#c62828"
                Zone.IN_RANGE -> "#2e7d32"
                Zone.HIGH -> "#f9a825"
                Zone.VERY_HIGH -> "#ef6c00"
            }
            """<rect x="${"%.1f".format(x)}" y="${"%.1f".format(height - 20 - h)}" """ +
                """width="${"%.1f".format(barWidth - 1)}" height="${"%.1f".format(h)}" fill="$colour"/>"""
        }.joinToString("")

        val axis = listOf(54, 100, 150, 200, 250, 300, 350).joinToString("") { mgdl ->
            val x = ((mgdl - GlucoseHistogram.MIN_MGDL).toDouble() / GlucoseHistogram.BIN_WIDTH) * barWidth
            """<text x="${"%.1f".format(x)}" y="$height" font-size="10" fill="#5b6763">$mgdl</text>"""
        }

        return """<svg viewBox="0 0 $width $height" width="100%" height="$height" role="img">$bars$axis</svg>"""
    }

    // -- helpers ---------------------------------------------------------------

    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

    private fun iso(millis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(ISO)

    private fun fmt(value: Double?): String =
        value?.let { "%.2f".format(it).trimEnd('0').trimEnd('.') } ?: ""

    private fun pct(fraction: Double?): String =
        fraction?.let { "${Math.round(it * 100)}%" } ?: "—"

    private fun display(mgdl: Double?, unit: GlucoseUnit): String =
        mgdl?.let { unit.format(it) } ?: "—"

    private fun quote(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private fun jsonString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
