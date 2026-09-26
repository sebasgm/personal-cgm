package dev.cgm.core

import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ReportBuilderTest {

    private val madrid = ZoneId.of("Europe/Madrid")

    private fun at(text: String) =
        LocalDateTime.parse(text).atZone(madrid).toInstant().toEpochMilli()

    /** One fully covered hour whose readings all sit at [value]. */
    private fun hour(startMillis: Long, value: Double, count: Int = 12) = HourlyBin(
        localDate = "",
        localHour = 0,
        bins = GlucoseHistogram.of(List(count) { value }),
        readingCount = count,
        coverageBuckets = 12,
    ) to startMillis

    private fun build(
        spec: ReportSpec,
        hours: List<Pair<HourlyBin, Long>>,
    ) = ReportBuilder.build(
        spec = spec,
        hours = hours.map { it.first },
        hourStartMillis = { bin -> hours.first { it.first === bin }.second },
        generatedAtMillis = at("2026-09-25T12:00"),
        zone = madrid,
    )

    private fun span(from: String, to: String, split: ReportSplit, count: Int = 4) = ReportSpec(
        startMillis = at(from),
        endMillis = at(to),
        split = split,
        rangeCount = count,
    )

    // -- how a period is cut ---------------------------------------------------

    @Test
    fun `daily split gives one range per day`() {
        val bounds = ReportBuilder.boundaries(
            span("2026-09-01T00:00", "2026-09-05T00:00", ReportSplit.DAY), madrid,
        )
        assertEquals(5, bounds.size, "four days is five edges")
    }

    @Test
    fun `equal split honours the requested count`() {
        val bounds = ReportBuilder.boundaries(
            span("2026-09-01T00:00", "2026-09-29T00:00", ReportSplit.EQUAL, count = 7), madrid,
        )
        assertEquals(8, bounds.size)
        assertEquals(at("2026-09-29T00:00"), bounds.last(), "the last edge is the end, exactly")
    }

    @Test
    fun `calendar splits keep wall-clock boundaries across daylight saving`() {
        // Madrid turns the clocks back on 2026-10-25, so one of these days is 25
        // hours long. Asking the calendar keeps each range on its own date.
        val bounds = ReportBuilder.boundaries(
            span("2026-10-24T00:00", "2026-10-27T00:00", ReportSplit.DAY), madrid,
        )
        assertEquals(4, bounds.size)
        val lengths = bounds.zipWithNext { a, b -> b - a }
        assertTrue(
            lengths.any { it != 24 * 60 * 60 * 1000L },
            "a fixed 24h step would have drifted off the day boundaries",
        )
    }

    @Test
    fun `a period with no span yields no ranges`() {
        val report = build(span("2026-09-01T00:00", "2026-09-01T00:00", ReportSplit.DAY), emptyList())
        assertTrue(report.ranges.isEmpty() || report.ranges.all { !it.hasData })
        assertTrue(!report.hasData)
    }

    @Test
    fun `an absurd range count is capped rather than obeyed`() {
        val bounds = ReportBuilder.boundaries(
            span("2026-09-01T00:00", "2026-09-02T00:00", ReportSplit.EQUAL, count = 100_000), madrid,
        )
        assertTrue(bounds.size <= ReportBuilder.MAX_RANGES + 1, "got ${bounds.size} edges")
    }

    // -- what each range says ---------------------------------------------------

    @Test
    fun `hours land in the range that contains them`() {
        val spec = span("2026-09-01T00:00", "2026-09-03T00:00", ReportSplit.DAY)
        val report = build(
            spec,
            listOf(
                hour(at("2026-09-01T08:00"), 100.0),
                hour(at("2026-09-02T08:00"), 200.0),
            ),
        )
        assertEquals(2, report.ranges.size)
        assertEquals(100.0, report.ranges[0].mean!!, 3.0)
        assertEquals(200.0, report.ranges[1].mean!!, 3.0)
    }

    @Test
    fun `the overall row covers every range`() {
        val spec = span("2026-09-01T00:00", "2026-09-03T00:00", ReportSplit.DAY)
        val report = build(
            spec,
            listOf(hour(at("2026-09-01T08:00"), 100.0), hour(at("2026-09-02T08:00"), 200.0)),
        )
        assertEquals(24, report.overall.readingCount)
        assertEquals(150.0, report.overall.mean!!, 3.0)
    }

    @Test
    fun `mean and median separate when the distribution is skewed`() {
        // Eleven hours near target and one very high: the mean is dragged, the
        // median is not. Reporting one of them alone hides that.
        val hours = (0 until 11).map { hour(at("2026-09-01T00:00") + it * 3_600_000L, 110.0) } +
            listOf(hour(at("2026-09-01T11:00"), 360.0))
        val report = build(span("2026-09-01T00:00", "2026-09-02T00:00", ReportSplit.DAY), hours)
        val r = report.ranges.single()

        assertTrue(r.mean!! > r.median!! + 10, "mean ${r.mean} vs median ${r.median}")
        assertEquals(112.5, r.median!!, 5.0)
    }

    @Test
    fun `a thin range reports low coverage and flags itself`() {
        // Two hours of data inside a whole day.
        val hours = listOf(hour(at("2026-09-01T08:00"), 120.0), hour(at("2026-09-01T09:00"), 120.0))
        val report = build(span("2026-09-01T00:00", "2026-09-02T00:00", ReportSplit.DAY), hours)
        val r = report.ranges.single()

        assertTrue(r.coverage < 0.15, "coverage was ${r.coverage}")
        assertTrue(!r.isReliable)
        assertTrue(report.hasUnreliableRanges)
    }

    @Test
    fun `an empty range says it has nothing rather than reporting zero`() {
        val report = build(
            span("2026-09-01T00:00", "2026-09-03T00:00", ReportSplit.DAY),
            listOf(hour(at("2026-09-01T08:00"), 120.0)),
        )
        val empty = report.ranges[1]
        assertTrue(!empty.hasData)
        assertNull(empty.mean)
        assertNull(empty.median)
    }

    // -- bands ----------------------------------------------------------------

    @Test
    fun `bands divide the local day and keep their wall-clock meaning`() {
        val spec = span("2026-09-01T00:00", "2026-09-02T00:00", ReportSplit.DAY)
            .copy(bandsPerDay = 6)
        // Two hours, one at 02:00 and one at 14:00 local, with different values.
        val early = HourlyBin(
            localDate = "2026-09-01", localHour = 2,
            bins = GlucoseHistogram.of(List(12) { 90.0 }),
            readingCount = 12, coverageBuckets = 12,
        ) to at("2026-09-01T02:00")
        val afternoon = HourlyBin(
            localDate = "2026-09-01", localHour = 14,
            bins = GlucoseHistogram.of(List(12) { 210.0 }),
            readingCount = 12, coverageBuckets = 12,
        ) to at("2026-09-01T14:00")

        val bands = build(spec, listOf(early, afternoon)).ranges.single().bands
        assertEquals(6, bands.size, "six bands a day")
        assertEquals(4, bands[0].endHour - bands[0].startHour, "four hours each")
        assertEquals("00–04", bands[0].label)
        assertEquals("20–00", bands[5].label, "the last band wraps its end to midnight")

        // 02:00 lands in 00–04 and 14:00 in 12–16, whatever the instants were.
        assertEquals(90.0, bands[0].median!!, 3.0)
        assertEquals(210.0, bands[3].median!!, 3.0)
        assertTrue(!bands[1].hasData, "an unrecorded band stays in the list, empty")
    }

    @Test
    fun `asking for no bands produces none`() {
        val spec = span("2026-09-01T00:00", "2026-09-02T00:00", ReportSplit.DAY)
        val report = build(spec, listOf(hour(at("2026-09-01T02:00"), 100.0)))
        assertTrue(report.ranges.single().bands.isEmpty())
        assertTrue(report.overall.bands.isEmpty())
    }

    @Test
    fun `only divisors of 24 are accepted, so every band is the same width`() {
        // Five bands would be 4.8 hours each, putting a boundary at 04:48 and
        // making two adjacent figures incomparable.
        assertEquals(4, ReportBands.nearest(5))
        assertEquals(6, ReportBands.nearest(7))
        assertEquals(24, ReportBands.nearest(99))
        ReportBands.OPTIONS.filter { it > 0 }.forEach {
            assertEquals(0, 24 % it, "$it bands would not divide the day evenly")
        }
        // A stored value from a hand edit is pulled onto the nearest legal one
        // rather than dividing the day into ragged pieces.
        assertEquals(4, ReportPreferences(bandsPerDay = 5).sanitised().bandsPerDay)
    }
}

class ReportExportTest {

    private val madrid = ZoneId.of("Europe/Madrid")
    private val now = LocalDateTime.parse("2026-09-25T12:00").atZone(madrid).toInstant().toEpochMilli()

    private val report: Report = run {
        val bin = HourlyBin(
            localDate = "", localHour = 8,
            bins = GlucoseHistogram.of(List(12) { 120.0 } + List(4) { 250.0 }),
            readingCount = 16, coverageBuckets = 12,
        )
        val start = LocalDateTime.parse("2026-09-24T00:00").atZone(madrid).toInstant().toEpochMilli()
        ReportBuilder.build(
            spec = ReportSpec(start, start + 86_400_000L, ReportSplit.DAY),
            hours = listOf(bin),
            hourStartMillis = { start + 8 * 3_600_000L },
            generatedAtMillis = now,
            zone = madrid,
        )
    }

    private fun export(format: ReportFormat) = ReportExport.export(report, format, madrid)

    @Test
    fun `every format carries the disclaimer`() {
        ReportFormat.entries.forEach { format ->
            val text = export(format)
            assertTrue(
                text.contains("Not medical advice"),
                "${format.name} exported without the disclaimer",
            )
        }
    }

    @Test
    fun `every format states the thresholds the zones were computed against`() {
        ReportFormat.entries.forEach { format ->
            assertTrue(export(format).contains("180"), "${format.name} omits the thresholds")
        }
    }

    @Test
    fun `every format reports coverage`() {
        ReportFormat.entries.forEach { format ->
            val text = export(format).lowercase()
            assertTrue(text.contains("coverage"), "${format.name} omits coverage")
        }
    }

    @Test
    fun `csv is long rather than wide, so a spreadsheet can pivot it`() {
        val csv = export(ReportFormat.CSV)
        assertTrue(csv.contains("histogram_range,bin_low_mgdl,bin_high_mgdl,count"))
        assertTrue(csv.lines().count { it.startsWith("\"") } >= 2)
    }

    @Test
    fun `csv quotes labels that could contain its separator`() {
        assertTrue(export(ReportFormat.CSV).contains("\"24 Sep 2026\""))
    }

    @Test
    fun `json is parseable and carries the histogram`() {
        val json = export(ReportFormat.JSON)
        kotlinx.serialization.json.Json.parseToJsonElement(json)
        assertTrue(json.contains("\"histogram\""))
        assertTrue(json.contains("\"medianMgdl\""))
    }

    @Test
    fun `html is self-contained, with no scripts and nothing to fetch`() {
        val html = export(ReportFormat.HTML)
        assertTrue(html.contains("<svg"), "histograms should be inline SVG")
        assertTrue(!html.contains("<script"), "a report must not need to run code")
        assertTrue(!html.contains("http://") && !html.contains("https://"),
            "a report must not need the network to draw itself")
    }

    @Test
    fun `file names are stamped and carry the right extension`() {
        assertEquals(
            "cgm-report-20260925-1200.csv",
            ReportExport.fileName(report, ReportFormat.CSV, madrid),
        )
        assertTrue(ReportExport.fileName(report, ReportFormat.HTML, madrid).endsWith(".html"))
    }

    // -- locale ---------------------------------------------------------------

    /**
     * Runs [block] as a phone set to Spanish would run it.
     *
     * Half of Europe writes decimals with a comma, and `String.format` follows the
     * default locale unless told otherwise. Every export here passed on a machine
     * set to English while producing unparseable files on the phone it was written
     * for, so the test has to do the switching the laptop will not.
     */
    private fun <T> asSpanishPhone(block: () -> T): T {
        val original = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("es-ES"))
        try {
            return block()
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `json parses on a device that writes decimals with a comma`() {
        val json = asSpanishPhone { export(ReportFormat.JSON) }
        kotlinx.serialization.json.Json.parseToJsonElement(json)
        assertTrue(
            !Regex(""": -?\d+,\d""").containsMatchIn(json),
            "a comma decimal is not a number in JSON",
        )
    }

    @Test
    fun `csv keeps one figure per column on a comma-decimal device`() {
        // The fixture covers one hour of a day, so coverage is 1/24. Written the
        // Spanish way that is "0,04" — which a spreadsheet reads as two columns,
        // shifting every figure after it one place left for the rest of the row.
        val csv = asSpanishPhone { export(ReportFormat.CSV) }
        assertTrue(csv.contains("0.04"), "coverage should be written with a dot")
        assertTrue(!csv.contains("0,04"), "a comma decimal splits a figure in two")
    }

    @Test
    fun `svg coordinates stay lengths on a comma-decimal device`() {
        // `x="12,3"` is not a length, so every bar of every histogram silently
        // fails to draw — and takes the printed report with it.
        val html = asSpanishPhone { export(ReportFormat.HTML) }
        assertTrue(
            !Regex("""="-?\d+,\d""").containsMatchIn(html),
            "SVG attributes must not carry comma decimals",
        )
        assertTrue(html.contains("<rect"), "the histogram should have bars at all")
    }

    @Test
    fun `a range with nothing in it still exports as valid json`() {
        // The overall row always has data. An empty range is what used to emit
        // `"meanMgdl": ,` and take the whole file down with it.
        val start = LocalDateTime.parse("2026-09-01T00:00").atZone(madrid).toInstant().toEpochMilli()
        val empty = ReportBuilder.build(
            spec = ReportSpec(start, start + 2 * 86_400_000L, ReportSplit.DAY),
            hours = emptyList(),
            hourStartMillis = { 0 },
            generatedAtMillis = now,
            zone = madrid,
        )
        val json = ReportExport.export(empty, ReportFormat.JSON, madrid)
        kotlinx.serialization.json.Json.parseToJsonElement(json)
        assertTrue(json.contains("\"meanMgdl\": null"))
    }

    // -- preferences ----------------------------------------------------------

    @Test
    fun `stored preferences are clamped rather than trusted`() {
        // These come back off disk, where an older build or a hand edit can have
        // left anything. A zero range count divides by zero downstream; a
        // negative period asks for a window that ends before it starts.
        val absurd = ReportPreferences(periodDays = -5, rangeCount = 0)
        assertEquals(1, absurd.sanitised().periodDays)
        assertEquals(ReportPreferences.MIN_RANGES, absurd.sanitised().rangeCount)

        val greedy = ReportPreferences(periodDays = 100_000, rangeCount = 5_000)
        assertEquals(ReportPreferences.MAX_PERIOD_DAYS, greedy.sanitised().periodDays)
        assertEquals(ReportPreferences.MAX_EQUAL_RANGES, greedy.sanitised().rangeCount)
    }

    @Test
    fun `the period converts to the span the report is built over`() {
        assertEquals(30L * 24 * 60 * 60 * 1000, ReportPreferences(periodDays = 30).periodMillis)
    }
}
