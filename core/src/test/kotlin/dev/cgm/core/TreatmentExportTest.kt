package dev.cgm.core

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The diary export: a chosen span, in three formats.
 *
 * The things worth pinning are the span's edges — a half-open range is easy to get
 * wrong by a day — and that an uncounted meal stays absent rather than becoming a
 * zero in any of the three.
 */
class TreatmentExportTest {

    private val zone: ZoneId = ZoneId.of("America/Argentina/Buenos_Aires")
    private val generated = 1_800_000_000_000L

    private fun at(date: String, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(LocalDate.parse(date).atTime(hour, minute), zone)
            .toInstant().toEpochMilli()

    private fun dose(
        date: String,
        hour: Int,
        units: Double,
        kind: InsulinKind,
        note: String? = null,
        glucoseMgdl: Double? = null,
    ) = InsulinDose(
        kind = kind,
        units = units,
        givenAtMillis = at(date, hour),
        note = note,
        glucoseMgdl = glucoseMgdl,
    )

    private fun meal(date: String, hour: Int, grams: Double?, photo: String? = null) =
        CarbEntry(grams = grams, eatenAtMillis = at(date, hour), photoName = photo)

    private fun export(
        doses: List<InsulinDose> = emptyList(),
        carbs: List<CarbEntry> = emptyList(),
        from: String = "2026-10-20",
        to: String = "2026-10-22",
        format: ReportFormat = ReportFormat.CSV,
    ) = TreatmentExport.export(
        doses = doses,
        carbs = carbs,
        fromMillis = LocalDate.parse(from).atStartOfDay(zone).toInstant().toEpochMilli(),
        toMillis = LocalDate.parse(to).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        zone = zone,
        format = format,
        generatedAtMillis = generated,
    )

    // -- the span ---------------------------------------------------------

    @Test
    fun `the end day is included in full`() {
        // 23:59 on the last day is inside the span; a naive inclusive bound on
        // midnight would drop the whole final day's evening.
        val csv = export(doses = listOf(dose("2026-10-22", 23, 6.0, InsulinKind.BOLUS)))
        assertContains(csv, "# doses,1")
    }

    @Test
    fun `the day before the start is excluded`() {
        val csv = export(doses = listOf(dose("2026-10-19", 23, 6.0, InsulinKind.BOLUS)))
        assertContains(csv, "# doses,0")
    }

    @Test
    fun `the first moment of the start day is included`() {
        val csv = export(doses = listOf(dose("2026-10-20", 0, 6.0, InsulinKind.BOLUS)))
        assertContains(csv, "# doses,1")
    }

    @Test
    fun `days come out oldest first, unlike the screen`() {
        val csv = export(
            doses = listOf(
                dose("2026-10-22", 8, 1.0, InsulinKind.BASAL),
                dose("2026-10-20", 8, 2.0, InsulinKind.BASAL),
            )
        )
        val dayRows = csv.lineSequence().filter { it.startsWith("day,") }.map { it.split(",")[1] }
        assertEquals(listOf("2026-10-20", "2026-10-22"), dayRows.toList())
    }

    // -- uncounted meals, in all three formats ----------------------------

    @Test
    fun `csv leaves an uncounted meal's grams empty`() {
        val csv = export(carbs = listOf(meal("2026-10-21", 13, null)))
        val mealRow = csv.lineSequence().first { it.startsWith("meal,") }
        assertEquals("", mealRow.split(",")[6])
        assertContains(csv, "# meals_uncounted,1")
    }

    @Test
    fun `json uses null for an uncounted meal, never zero`() {
        val json = export(carbs = listOf(meal("2026-10-21", 13, null)), format = ReportFormat.JSON)
        assertContains(json, """"grams": null""")
        assertFalse(""""grams": 0""" in json)
    }

    @Test
    fun `html says not counted rather than showing a quantity`() {
        val html = export(carbs = listOf(meal("2026-10-21", 13, null)), format = ReportFormat.HTML)
        assertContains(html, "not counted")
    }

    // -- totals -----------------------------------------------------------

    @Test
    fun `basal and bolus are totalled apart and together`() {
        val json = export(
            doses = listOf(
                dose("2026-10-21", 8, 12.0, InsulinKind.BASAL),
                dose("2026-10-21", 13, 4.0, InsulinKind.BOLUS),
                dose("2026-10-21", 20, 5.0, InsulinKind.BOLUS),
            ),
            format = ReportFormat.JSON,
        )
        assertContains(json, """"basalUnits": 12""")
        assertContains(json, """"bolusUnits": 9""")
        assertContains(json, """"totalUnits": 21""")
    }

    @Test
    fun `the daily mean divides by days with insulin, not by the span`() {
        // Three days requested, insulin on one. 10 U per recorded day, not 3.33.
        val json = export(
            doses = listOf(dose("2026-10-21", 8, 10.0, InsulinKind.BASAL)),
            format = ReportFormat.JSON,
        )
        assertContains(json, """"meanUnitsPerRecordedDay": 10""")
    }

    @Test
    fun `an empty span still produces a valid document in each format`() {
        assertContains(export(), "# doses,0")
        assertContains(export(format = ReportFormat.JSON), """"days": [""")
        val html = export(format = ReportFormat.HTML)
        assertContains(html, "Nothing recorded in this span.")
        assertTrue(html.trimEnd().endsWith("</html>"))
    }

    @Test
    fun `every format carries the disclaimer`() {
        ReportFormat.entries.forEach { format ->
            val text = export(
                doses = listOf(dose("2026-10-21", 8, 10.0, InsulinKind.BASAL)),
                format = format,
            )
            assertTrue(
                ReportExport.DISCLAIMER.take(40) in text ||
                    ReportExport.DISCLAIMER.take(40).replace("&", "&amp;") in text,
                "missing in $format",
            )
        }
    }

    @Test
    fun `html escapes a note rather than letting it close a tag`() {
        val html = export(
            doses = listOf(dose("2026-10-21", 8, 1.0, InsulinKind.BASAL, note = "<b>oops</b>")),
            format = ReportFormat.HTML,
        )
        assertContains(html, "&lt;b&gt;oops&lt;/b&gt;")
    }

    @Test
    fun `json escapes a quote in a note`() {
        val json = export(
            doses = listOf(dose("2026-10-21", 8, 1.0, InsulinKind.BASAL, note = "the \"good\" pen")),
            format = ReportFormat.JSON,
        )
        assertContains(json, """the \"good\" pen""")
    }

    @Test
    fun `a meal with a photo is flagged without leaking the file name`() {
        val json = export(
            carbs = listOf(meal("2026-10-21", 13, 40.0, photo = "secret.jpg")),
            format = ReportFormat.JSON,
        )
        assertContains(json, """"hasPhoto": true""")
        assertFalse("secret.jpg" in json)
    }

    // -- the glucose a dose was given at ----------------------------------

    @Test
    fun `csv carries the dose's glucose in its own mg per dL column`() {
        val csv = export(
            doses = listOf(dose("2026-10-21", 13, 6.0, InsulinKind.BOLUS, glucoseMgdl = 184.0))
        )
        val header = csv.lineSequence().first { it.startsWith("section,date,local_time") }
        val doseRow = csv.lineSequence().first { it.startsWith("dose,") }
        assertEquals("184", doseRow.split(",")[header.split(",").indexOf("glucose_mgdl")])
    }

    /**
     * Empty, never zero. A dose given with no reading nearby recorded no glucose,
     * and 0 mg/dL in that column is a figure a reader would act on.
     */
    @Test
    fun `csv leaves the glucose empty when none was recorded`() {
        val csv = export(doses = listOf(dose("2026-10-21", 13, 6.0, InsulinKind.BOLUS)))
        val header = csv.lineSequence().first { it.startsWith("section,date,local_time") }
        val doseRow = csv.lineSequence().first { it.startsWith("dose,") }
        assertEquals("", doseRow.split(",")[header.split(",").indexOf("glucose_mgdl")])
    }

    /**
     * The note is the last column and the only quoted one. A glucose inserted in
     * the wrong place would push a comma-bearing note out of its quotes and
     * silently shift every field after it.
     */
    @Test
    fun `the note stays the last csv column with a glucose beside it`() {
        val csv = export(
            doses = listOf(
                dose(
                    "2026-10-21", 13, 6.0, InsulinKind.BOLUS,
                    note = "pizza, half a one", glucoseMgdl = 184.0,
                )
            )
        )
        assertContains(csv, ",184,\"pizza, half a one\"")
    }

    @Test
    fun `json uses null for a dose with no glucose, never zero`() {
        val json = export(
            doses = listOf(dose("2026-10-21", 13, 6.0, InsulinKind.BOLUS)),
            format = ReportFormat.JSON,
        )
        assertContains(json, "\"glucoseMgdl\": null")
        assertFalse("\"glucoseMgdl\": 0" in json)
    }

    @Test
    fun `json carries the glucose as a number`() {
        val json = export(
            doses = listOf(dose("2026-10-21", 13, 6.0, InsulinKind.BOLUS, glucoseMgdl = 71.5)),
            format = ReportFormat.JSON,
        )
        assertContains(json, "\"glucoseMgdl\": 71.5")
    }

    @Test
    fun `html shows the glucose beside the dose and leaves a meal's blank`() {
        val html = export(
            doses = listOf(dose("2026-10-21", 13, 6.0, InsulinKind.BOLUS, glucoseMgdl = 184.0)),
            carbs = listOf(meal("2026-10-21", 13, 60.0)),
            format = ReportFormat.HTML,
        )
        assertContains(html, "Glucose mg/dL")
        // One dose row carrying the figure, and a meal row that does not borrow it.
        assertEquals(1, Regex(">184<").findAll(html).count())
    }

    @Test
    fun `the file name carries the span and the extension`() {
        val name = TreatmentExport.fileName(
            LocalDate.parse("2026-10-09"),
            LocalDate.parse("2026-10-22"),
            ReportFormat.JSON,
        )
        assertEquals("cgm-insulin-2026-10-09_2026-10-22.json", name)
    }
}
