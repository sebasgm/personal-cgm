package dev.cgm.core

import java.time.LocalDateTime
import java.time.ZoneId
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
}
