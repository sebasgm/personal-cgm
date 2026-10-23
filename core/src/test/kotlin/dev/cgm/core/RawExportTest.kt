package dev.cgm.core

import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The logbook export, which exists to be diffed against a report that claims a gap.
 *
 * Its whole value is being unprocessed, so what is worth testing is that it omits
 * nothing, keeps one time order, and states when each reading arrived — the last of
 * which is the only thing that distinguishes a poller that was asleep from one that
 * was awake and being handed nothing new.
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
    fun `every reading is present and in time order`() {
        val csv = RawExport.readings(
            readings = listOf(reading(now + 2 * minute, 104.0), reading(now, 100.0)),
            generatedAtMillis = now,
        )

        assertEquals(listOf("100", "104"), rows(csv).map { it.split(",")[3] })
        assertContains(csv, "# readings,2")
    }

    @Test
    fun `the header states the span so a truncated file is obvious`() {
        val csv = RawExport.readings(
            readings = listOf(reading(now, 100.0), reading(now + 60 * minute, 110.0)),
            generatedAtMillis = now,
        )

        assertContains(csv, "# first_reading,")
        assertContains(csv, "# last_reading,")
    }

    @Test
    fun `each row carries both a readable stamp and its epoch millis`() {
        val cells = rows(
            RawExport.readings(listOf(reading(now, 100.0)), now)
        ).single().split(",")

        assertEquals(now.toString(), cells[2], "the number is the one to compute with")
        assertTrue(cells[1].startsWith("20"), "and a UTC stamp beside it for reading")
    }

    @Test
    fun `insulin and meals are not this document's subject`() {
        val csv = RawExport.readings(listOf(reading(now, 100.0)), now)

        val header = csv.lineSequence().first { it.startsWith("section,") }
        assertTrue("units" !in header && "grams" !in header, "header: $header")
    }

    @Test
    fun `an empty database still exports a usable file`() {
        val csv = RawExport.readings(emptyList(), now)

        assertContains(csv, "# readings,0")
        assertContains(csv, "section,timestamp_utc")
        assertTrue(rows(csv).isEmpty())
    }

    @Test
    fun `the file name is stamped and carries the csv extension`() {
        assertTrue(RawExport.fileName(now, "readings").startsWith("cgm-readings-"))
        assertTrue(RawExport.fileName(now).endsWith(".csv"))
    }

    // -- arrival time ------------------------------------------------------

    @Test
    fun `the export states when each reading arrived and how late`() {
        val csv = RawExport.readings(
            readings = listOf(
                // Polled live: measured and received in the same minute.
                GlucoseReading(
                    valueMgdl = 90.0,
                    timestampMillis = now,
                    trend = TrendArrow.STEADY,
                    storedAtMillis = now + minute,
                ),
                // Swept up forty minutes later, which is what a filled gap looks like.
                GlucoseReading(
                    valueMgdl = 95.0,
                    timestampMillis = now + 5 * minute,
                    trend = TrendArrow.UNKNOWN,
                    storedAtMillis = now + 45 * minute,
                ),
            ),
            generatedAtMillis = now,
        )

        assertEquals(listOf("1", "40"), rows(csv).map { it.split(",")[6] })
    }

    @Test
    fun `a reading with no recorded arrival exports empty, not zero`() {
        // Rows written before the column existed know nothing about their arrival,
        // and a 0 there would read as having arrived at the moment of measurement.
        val cells = rows(
            RawExport.readings(listOf(reading(now, 100.0)), now)
        ).single().split(",")

        assertEquals("", cells[5])
        assertEquals("", cells[6])
    }

    @Test
    fun `arrival lag is never negative`() {
        // A clock correction can place arrival before measurement; that is a broken
        // clock, not a reading received before it was taken.
        val r = GlucoseReading(
            valueMgdl = 90.0,
            timestampMillis = now,
            storedAtMillis = now - 10 * minute,
        )

        assertEquals(0L, r.arrivalLagMillis())
    }

    @Test
    fun `arrival time is not sent to the watch`() {
        // It is a fact about this phone's fetching, not about the glucose.
        val json = kotlinx.serialization.json.Json.encodeToString(
            GlucoseReading.serializer(),
            GlucoseReading(valueMgdl = 90.0, timestampMillis = now, storedAtMillis = now),
        )

        assertTrue("storedAt" !in json, "payload: $json")
    }
}
