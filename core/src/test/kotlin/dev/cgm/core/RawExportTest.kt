package dev.cgm.core

import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The raw export, which exists to be diffed against a report that claims a gap.
 *
 * Its whole value is being unprocessed, so the things worth testing are that it
 * omits nothing, keeps one time order across all three kinds of row, and never
 * turns an absent figure into a zero.
 */
class RawExportTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    private fun reading(atMillis: Long, mgdl: Double) = GlucoseReading(
        valueMgdl = mgdl,
        timestampMillis = atMillis,
        trend = TrendArrow.STEADY,
    )

    private fun rows(csv: String): List<String> =
        csv.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("section,") }
            .toList()

    @Test
    fun `every row is present and in one time order`() {
        val csv = RawExport.export(
            readings = listOf(reading(now, 100.0), reading(now + 2 * minute, 104.0)),
            doses = listOf(
                InsulinDose(kind = InsulinKind.BOLUS, units = 4.0, givenAtMillis = now + minute)
            ),
            carbs = listOf(CarbEntry(grams = 30.0, eatenAtMillis = now + 3 * minute)),
            generatedAtMillis = now,
        )

        val sections = rows(csv).map { it.substringBefore(",") }
        assertEquals(listOf("reading", "dose", "reading", "meal"), sections)
    }

    @Test
    fun `the header counts what the file contains`() {
        val csv = RawExport.export(
            readings = listOf(reading(now, 100.0)),
            doses = emptyList(),
            carbs = listOf(CarbEntry(grams = null, eatenAtMillis = now)),
            generatedAtMillis = now,
        )

        assertContains(csv, "# readings,1")
        assertContains(csv, "# doses,0")
        assertContains(csv, "# meals,1")
    }

    @Test
    fun `an uncounted meal exports an empty figure, never a zero`() {
        val csv = RawExport.export(
            readings = emptyList(),
            doses = emptyList(),
            carbs = listOf(CarbEntry(grams = null, eatenAtMillis = now)),
            generatedAtMillis = now,
        )

        val meal = rows(csv).single()
        // grams is the 8th column; empty means "not counted", 0 would be a claim.
        assertEquals("", meal.split(",")[7])
    }

    @Test
    fun `a photo is reported as a flag, not a path`() {
        val csv = RawExport.export(
            readings = emptyList(),
            doses = emptyList(),
            carbs = listOf(
                CarbEntry(grams = 20.0, eatenAtMillis = now, photoName = "abc.jpg"),
                CarbEntry(grams = 20.0, eatenAtMillis = now + minute),
            ),
            generatedAtMillis = now,
        )

        val cells = rows(csv).map { it.split(",")[8] }
        assertEquals(listOf("1", "0"), cells)
        assertTrue("abc.jpg" !in csv, "a private file name is not export material")
    }

    @Test
    fun `a note with a comma and a quote survives the round trip`() {
        val csv = RawExport.export(
            readings = emptyList(),
            doses = emptyList(),
            carbs = listOf(
                CarbEntry(grams = 20.0, eatenAtMillis = now, note = """rice, the "good" kind""")
            ),
            generatedAtMillis = now,
        )

        assertContains(csv, """"rice, the ""good"" kind"""")
    }

    @Test
    fun `each row carries both a readable stamp and its epoch millis`() {
        val csv = RawExport.export(
            readings = listOf(reading(now, 100.0)),
            doses = emptyList(),
            carbs = emptyList(),
            generatedAtMillis = now,
        )

        val cells = rows(csv).single().split(",")
        assertEquals(now.toString(), cells[2], "the number is the one to compute with")
        assertTrue(cells[1].startsWith("20"), "and a UTC stamp beside it for reading")
    }

    @Test
    fun `the span is stated so a truncated file is obvious`() {
        val csv = RawExport.export(
            readings = listOf(reading(now, 100.0), reading(now + 60 * minute, 110.0)),
            doses = emptyList(),
            carbs = emptyList(),
            generatedAtMillis = now,
        )

        assertContains(csv, "# first_reading,")
        assertContains(csv, "# last_reading,")
    }

    @Test
    fun `an empty database still exports a usable file`() {
        val csv = RawExport.export(emptyList(), emptyList(), emptyList(), now)

        assertContains(csv, "# readings,0")
        assertContains(csv, "section,timestamp_utc")
        assertTrue(rows(csv).isEmpty())
    }

    @Test
    fun `the file name is stamped and carries the csv extension`() {
        assertTrue(RawExport.fileName(now).startsWith("cgm-raw-"))
        assertTrue(RawExport.fileName(now).endsWith(".csv"))
    }

    // -- the per-screen exports -------------------------------------------

    @Test
    fun `the logbook export carries only readings`() {
        val csv = RawExport.readings(
            readings = listOf(reading(now, 100.0), reading(now + minute, 101.0)),
            generatedAtMillis = now,
        )

        assertContains(csv, "# readings,2")
        assertTrue("dose" !in csv && "meal" !in csv)
        assertEquals("section,timestamp_utc,epoch_millis,value_mgdl,trend", csv.lineSequence()
            .first { it.startsWith("section,") })
    }

    @Test
    fun `a backfilled reading is distinguishable by its trend`() {
        // UNKNOWN is how a value that arrived from the 15-minute history is told
        // apart from one polled live, which is the whole point of the column.
        val csv = RawExport.readings(
            readings = listOf(
                GlucoseReading(valueMgdl = 90.0, timestampMillis = now, trend = TrendArrow.UNKNOWN),
                reading(now + minute, 91.0),
            ),
            generatedAtMillis = now,
        )

        val trends = rows(csv).map { it.split(",")[4] }
        assertEquals(listOf("UNKNOWN", "STEADY"), trends)
    }

    @Test
    fun `the diary export carries only treatments, in one time order`() {
        val csv = RawExport.treatments(
            doses = listOf(
                InsulinDose(kind = InsulinKind.BASAL, units = 12.0, givenAtMillis = now + minute)
            ),
            carbs = listOf(CarbEntry(grams = 40.0, eatenAtMillis = now)),
            generatedAtMillis = now,
        )

        assertContains(csv, "# doses,1")
        assertContains(csv, "# meals,1")
        assertEquals(listOf("meal", "dose"), rows(csv).map { it.substringBefore(",") })
        assertTrue("value_mgdl" !in csv, "readings are not this file's subject")
    }

    @Test
    fun `every treatment row has as many cells as the header`() {
        // The two files have different shapes, and reusing the wide row builders
        // under the narrow header is exactly how a column silently shifts.
        val csv = RawExport.treatments(
            doses = listOf(
                InsulinDose(kind = InsulinKind.BOLUS, units = 4.0, givenAtMillis = now)
            ),
            carbs = listOf(CarbEntry(grams = null, eatenAtMillis = now + minute)),
            generatedAtMillis = now,
        )

        val columns = csv.lineSequence().first { it.startsWith("section,") }.split(",").size
        rows(csv).forEach { assertEquals(columns, it.split(",").size, "row: $it") }
    }

    @Test
    fun `the file name says which export it is`() {
        assertTrue(RawExport.fileName(now, "readings").startsWith("cgm-readings-"))
        assertTrue(RawExport.fileName(now, "insulin").startsWith("cgm-insulin-"))
    }
}
