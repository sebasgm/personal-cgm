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
}
