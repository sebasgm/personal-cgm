package dev.cgm.core

import kotlin.math.floor
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The finger-prick meter: what counts as a plausible strip result, how a typed figure
 * becomes mg/dL, and how each strip is paired with the sensor reading that describes the
 * same moment.
 *
 * Guards the one property that makes the comparison worth showing: a strip is only ever
 * scored against the reading nearest to it in time, and the figures (MARD, median, Clarke
 * zone) are computed from the strip as the reference, never the other way round. A
 * reversed argument or a window that quietly widens would still produce numbers, just
 * numbers that answer a different question.
 */
class BloodTestTest {

    private val start: Long = 1_790_000_000_000L

    private fun minutes(n: Int): Long = n * 60_000L

    private fun test(mgdl: Double, atMillis: Long = start) =
        BloodTest(mgdl = mgdl, takenAtMillis = atMillis)

    private fun reading(mgdl: Double, atMillis: Long) =
        GlucoseReading(valueMgdl = mgdl, timestampMillis = atMillis)

    private fun pair(strip: Double, sensor: Double, gapMillis: Long = 0L) =
        PairedReading(test = test(strip), sensorMgdl = sensor, gapMillis = gapMillis)

    // -- plausibility and parsing -----------------------------------------

    @Test
    fun `a result is plausible only within the glucose bounds and with a real timestamp`() {
        assertTrue(test(100.0).isPlausible, "a normal result")
        assertTrue(test(BloodTest.MIN_MGDL).isPlausible, "the lower bound is plausible")
        assertTrue(test(BloodTest.MAX_MGDL).isPlausible, "the upper bound is plausible")
        assertFalse(test(BloodTest.MIN_MGDL - 0.1).isPlausible, "just below the lower bound")
        assertFalse(test(BloodTest.MAX_MGDL + 0.1).isPlausible, "just above the upper bound")
        assertFalse(test(100.0, atMillis = 0L).isPlausible, "a zero timestamp is not a real time")
        assertFalse(test(100.0, atMillis = -1L).isPlausible, "a negative timestamp is not a real time")
    }

    @Test
    fun `a typed figure parses with either decimal separator and converts from the displayed unit`() {
        assertEquals(105.0, BloodTest.parse("105", GlucoseUnit.MGDL))

        val comma = BloodTest.parse("5,8", GlucoseUnit.MMOLL)
        val point = BloodTest.parse("5.8", GlucoseUnit.MMOLL)
        assertEquals(comma, point, "a comma and a point must mean the same figure")

        val expected = 5.8 * GlucoseUnit.MGDL_PER_MMOLL
        assertTrue(
            kotlin.math.abs(requireNotNull(point) - expected) < 0.01,
            "5.8 mmol/L should be about 104.5 mg/dL, was $point",
        )
    }

    @Test
    fun `unparseable and empty text give no figure`() {
        assertNull(BloodTest.parse("abc", GlucoseUnit.MGDL), "text is not a figure")
        assertNull(BloodTest.parse("", GlucoseUnit.MMOLL), "an empty field is not zero")
        assertNull(BloodTest.parse("   ", GlucoseUnit.MGDL), "a blank field is not zero")
    }

    @Test
    fun `a parsed figure keeps its fraction and is not rounded`() {
        val mmol = BloodTest.parse("5.83", GlucoseUnit.MMOLL)
        assertEquals(5.83 * GlucoseUnit.MGDL_PER_MMOLL, requireNotNull(mmol), 1e-9,
            "5.83 mmol/L must not be rounded away")

        val mgdl = BloodTest.parse("105.5", GlucoseUnit.MGDL)
        assertEquals(105.5, mgdl, "a fractional mg/dL figure must survive as typed")
    }

    // -- pairing ------------------------------------------------------------

    @Test
    fun `a test pairs with the nearest reading whether it comes before or after`() {
        val pairs = SensorAgreement.of(
            tests = listOf(test(100.0, atMillis = start)),
            readings = listOf(
                reading(90.0, start - minutes(3)),
                reading(120.0, start + minutes(2)),
            ),
        ).pairs

        val only = pairs.single()
        assertEquals(120.0, only.sensorMgdl, "the later reading is closer and must be chosen")
        assertEquals(minutes(2), only.gapMillis)
    }

    @Test
    fun `a test with no reading inside the window is dropped rather than paired with a distant one`() {
        val orphan = test(100.0, atMillis = start)
        val matched = test(110.0, atMillis = start + minutes(60))
        val agreement = SensorAgreement.of(
            tests = listOf(orphan, matched),
            readings = listOf(
                reading(95.0, start + minutes(30)), // nearest to the orphan, but far outside the window
                reading(108.0, start + minutes(61)),
            ),
        )

        assertEquals(1, agreement.count, "the orphan test must not be paired with the 30-minute-away reading")
        assertEquals(listOf(matched), agreement.pairs.map { it.test },
            "only the test with a reading in the window should remain")
    }

    @Test
    fun `a reading exactly at the window edge is accepted and one a millisecond beyond it is not`() {
        val window = SensorAgreement.MATCH_WINDOW_MILLIS

        val atEdge = SensorAgreement.of(
            tests = listOf(test(100.0, atMillis = start)),
            readings = listOf(reading(100.0, start + window)),
        )
        assertEquals(1, atEdge.count, "a reading exactly MATCH_WINDOW_MILLIS away must pair")

        val beyond = SensorAgreement.of(
            tests = listOf(test(100.0, atMillis = start)),
            readings = listOf(reading(100.0, start + window + 1)),
        )
        assertEquals(0, beyond.count, "a reading one millisecond outside the window must not pair")
    }

    @Test
    fun `no tests or no readings gives an agreement with no data`() {
        val fromNoTests = SensorAgreement.of(
            tests = emptyList(),
            readings = listOf(reading(100.0, start)),
        )
        val fromNoReadings = SensorAgreement.of(
            tests = listOf(test(100.0, atMillis = start)),
            readings = emptyList(),
        )

        for ((label, agreement) in listOf("no tests" to fromNoTests, "no readings" to fromNoReadings)) {
            assertFalse(agreement.hasData, "$label: there must be no data")
            assertEquals(0, agreement.count, "$label: count must be zero")
            assertNull(agreement.meanAbsoluteRelativeDifference, "$label: MARD must be absent, not zero")
            assertNull(agreement.medianDifferenceMgdl, "$label: median must be absent, not zero")
            assertNull(agreement.benignFraction, "$label: benign fraction must be absent, not zero")
        }
    }

    @Test
    fun `pairing is the same whatever order the readings arrive in`() {
        val tests = listOf(
            test(100.0, atMillis = start),
            test(150.0, atMillis = start + minutes(40)),
            test(80.0, atMillis = start + minutes(90)),
        )
        val sorted = listOf(
            reading(98.0, start - minutes(1)),
            reading(104.0, start + minutes(3)),
            reading(140.0, start + minutes(38)),
            reading(160.0, start + minutes(45)),
            reading(75.0, start + minutes(88)),
            reading(85.0, start + minutes(94)),
        )
        val shuffled = sorted.shuffled(Random(7))
        assertNotEquals(sorted, shuffled, "the shuffle must actually reorder the readings for this test to mean anything")

        assertEquals(
            SensorAgreement.of(tests, sorted).pairs,
            SensorAgreement.of(tests, shuffled).pairs,
            "readings supplied out of order must pair exactly as they do in order",
        )
    }

    @Test
    fun `the gap is the absolute distance between the test and its reading and is reported`() {
        val agreement = SensorAgreement.of(
            tests = listOf(test(100.0, atMillis = start)),
            readings = listOf(reading(100.0, start - 90_000L)),
        )

        assertEquals(90_000L, agreement.pairs.single().gapMillis,
            "a reading 90 seconds before the test is a 90-second gap, reported as positive")
    }

    // -- the figures --------------------------------------------------------

    @Test
    fun `the difference is sensor minus strip so a higher sensor reads positive`() {
        assertEquals(30.0, pair(strip = 100.0, sensor = 130.0).differenceMgdl)
        assertEquals(-30.0, pair(strip = 100.0, sensor = 70.0).differenceMgdl)
    }

    @Test
    fun `MARD is the mean of the absolute difference over the strip value`() {
        val agreement = SensorAgreement(
            listOf(
                pair(strip = 100.0, sensor = 110.0), // 10 / 100 = 0.10
                pair(strip = 200.0, sensor = 240.0), // 40 / 200 = 0.20
                pair(strip = 100.0, sensor = 90.0),  // |-10| / 100 = 0.10
            )
        )

        assertEquals(0.4 / 3, requireNotNull(agreement.meanAbsoluteRelativeDifference), 1e-9,
            "MARD must average the absolute relative differences, sign ignored")
    }

    @Test
    fun `the median difference is a median and is not moved by one extreme pair`() {
        val fourNormal = SensorAgreement(
            listOf(
                pair(strip = 100.0, sensor = 98.0),   // -2
                pair(strip = 100.0, sensor = 103.0),  // +3
                pair(strip = 100.0, sensor = 105.0),  // +5
                pair(strip = 100.0, sensor = 107.0),  // +7
            )
        )
        assertEquals(4.0, fourNormal.medianDifferenceMgdl,
            "an even count must average the two middle differences, (3 + 5) / 2")

        val withOutlier = SensorAgreement(
            fourNormal.pairs + pair(strip = 100.0, sensor = 1000.0) // +900
        )
        assertEquals(5.0, withOutlier.medianDifferenceMgdl,
            "one extreme pair must move the median to the middle value, not toward the outlier")
    }

    @Test
    fun `the zone is judged with the strip as reference and the sensor as prediction`() {
        // Strip 100, sensor 230: C when the strip is the reference. Reversed, it is B.
        val p = pair(strip = 100.0, sensor = 230.0)

        assertEquals(
            ClarkeErrorGrid.zoneOf(referenceMgdl = 100.0, predictedMgdl = 230.0),
            p.zone,
            "the zone must use the strip as the reference",
        )
        assertEquals(ClarkeZone.C, p.zone, "a strip of 100 read as 230 is a C, not the B of the reversed pair")
    }

    @Test
    fun `benign fraction counts the zones that are not unsafe and the zone counts sum to the total`() {
        val agreement = SensorAgreement(
            listOf(
                pair(strip = 100.0, sensor = 105.0), // A
                pair(strip = 100.0, sensor = 230.0), // C, unsafe
                pair(strip = 230.0, sensor = 100.0), // B
            )
        )

        assertEquals(2.0 / 3, requireNotNull(agreement.benignFraction), 1e-9,
            "A and B are benign; C is not, so two of three")
        assertEquals(agreement.count, agreement.zoneCounts.values.sum(),
            "every pair must land in exactly one zone")
    }

    @Test
    fun `the agreement is thin below the minimum pair count and not at or above it`() {
        fun agreementOf(n: Int) = SensorAgreement(List(n) { pair(strip = 100.0, sensor = 100.0) })

        assertTrue(agreementOf(SensorAgreement.MIN_PAIRS - 1).isThin,
            "one short of the minimum is thin")
        assertFalse(agreementOf(SensorAgreement.MIN_PAIRS).isThin,
            "exactly the minimum is not thin")
    }

    // -- the identity of a meter's record ---------------------------------

    @Test
    fun `a result identifies a meter record only with both halves of it`() {
        val typed = BloodTest(mgdl = 105.0, takenAtMillis = 1)
        assertFalse(typed.hasMeterRecord, "a typed result has no meter record")

        val full = typed.copy(
            origin = BloodTestOrigin.METER,
            meterName = "Accu-Chek Guide",
            meterSequence = 41,
        )
        assertTrue(full.hasMeterRecord)

        assertFalse(
            full.copy(meterName = null).hasMeterRecord,
            "a sequence with no meter cannot be compared with anything: every meter numbers from one",
        )
        assertFalse(
            full.copy(meterSequence = null).hasMeterRecord,
            "a meter with no sequence identifies no particular record",
        )
    }
}
