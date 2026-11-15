package dev.cgm.core

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The standard Bluetooth Glucose Service, parsed from bytes.
 *
 * Every vector here is written out by hand from the specification rather than captured
 * from a device, which is the point: there is no meter in this test, and the arithmetic
 * that turns a 12-bit mantissa and a 4-bit exponent into 105 mg/dL has to be right
 * before any meter is connected to it. A record misread by a factor of ten is not a
 * visible failure — it is a plausible-looking glucose, stored as the reference that
 * everything else in the app is judged against.
 */
class MeterProtocolTest {

    private val zone: ZoneId = ZoneId.of("America/Argentina/Buenos_Aires")

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun at(text: String): Long =
        LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    /**
     * A record of 105 mg/dL, sequence 41, taken at 08:30 on 15 November 2026.
     *
     * Flags 0x03: a time offset is present and so is a concentration, in kg/L because
     * bit 2 is clear. The concentration is the SFLOAT 0xB069 — mantissa 105, exponent
     * −5, so 0.00105 kg/L, which is 105 mg/dL. The last byte is sample type 1 in its
     * low nibble (capillary whole blood) and location 1 in its high one.
     */
    private val capillary105 = bytes(
        0x03,
        0x29, 0x00,
        0xEA, 0x07, 0x0B, 0x0F, 0x08, 0x1E, 0x00,
        0x00, 0x00,
        0x69, 0xB0,
        0x11,
    )

    // -- a measurement ----------------------------------------------------

    @Test
    fun `a record in mg per dL is read whole`() {
        val record = requireNotNull(GlucoseMeasurement.parse(capillary105, zone))

        assertEquals(41, record.sequence)
        assertEquals(105.0, record.mgdl, 1e-9)
        assertEquals(at("2026-11-15T08:30:00"), record.wallClockMillis)
        assertEquals(1, record.sampleType)
        assertEquals(1, record.sampleLocation)
        assertFalse(record.hasContext)
        assertFalse(record.isControlSolution)
    }

    @Test
    fun `a record in mmol per L converts rather than being read as mg per dL`() {
        // 0xC03A: mantissa 58, exponent −4, so 0.0058 mol/L — 5.8 mmol/L.
        val mmol = capillary105.copyOf().also {
            it[0] = 0x07.toByte()
            it[12] = 0x3A.toByte()
            it[13] = 0xC0.toByte()
        }

        val record = requireNotNull(GlucoseMeasurement.parse(mmol, zone))
        assertEquals(5.8 * GlucoseUnit.MGDL_PER_MMOLL, record.mgdl, 1e-6)
        assertTrue(record.mgdl > 100, "5.8 mmol/L is about 104 mg/dL, not 5.8")
    }

    @Test
    fun `the time offset is added to the base time`() {
        val plusHour = capillary105.copyOf().also {
            it[10] = 0x3C.toByte() // +60 minutes
            it[11] = 0x00.toByte()
        }
        assertEquals(
            at("2026-11-15T09:30:00"),
            requireNotNull(GlucoseMeasurement.parse(plusHour, zone)).wallClockMillis,
        )

        val minusHalfHour = capillary105.copyOf().also {
            it[10] = 0xE2.toByte() // −30 minutes, as a signed 16-bit value
            it[11] = 0xFF.toByte()
        }
        assertEquals(
            at("2026-11-15T08:00:00"),
            requireNotNull(GlucoseMeasurement.parse(minusHalfHour, zone)).wallClockMillis,
        )
    }

    @Test
    fun `an offset the meter says it does not know is not an offset of minus 546 hours`() {
        val unknown = capillary105.copyOf().also {
            it[10] = 0x00.toByte()
            it[11] = 0x80.toByte() // 0x8000, the spec's "not known"
        }
        assertEquals(
            at("2026-11-15T08:30:00"),
            requireNotNull(GlucoseMeasurement.parse(unknown, zone)).wallClockMillis,
        )
    }

    @Test
    fun `a record with no offset field is still read`() {
        // Flags 0x02: concentration present, time offset absent, so the record is two
        // bytes shorter and every field after the base time moves.
        val noOffset = bytes(
            0x02,
            0x29, 0x00,
            0xEA, 0x07, 0x0B, 0x0F, 0x08, 0x1E, 0x00,
            0x69, 0xB0,
            0x11,
        )

        val record = requireNotNull(GlucoseMeasurement.parse(noOffset, zone))
        assertEquals(105.0, record.mgdl, 1e-9)
        assertEquals(at("2026-11-15T08:30:00"), record.wallClockMillis)
    }

    @Test
    fun `a control solution test is recognisable`() {
        val control = capillary105.copyOf().also { it[14] = 0x1A.toByte() }

        val record = requireNotNull(GlucoseMeasurement.parse(control, zone))
        assertEquals(MeterRecord.CONTROL_SOLUTION, record.sampleType)
        assertTrue(record.isControlSolution, "type 10 is a test of the strips, not of a person")
    }

    @Test
    fun `a record that says a context follows says so`() {
        val withContext = capillary105.copyOf().also { it[0] = 0x13.toByte() }
        assertTrue(requireNotNull(GlucoseMeasurement.parse(withContext, zone)).hasContext)
    }

    @Test
    fun `a sequence number near the top of its range is not negative`() {
        val high = capillary105.copyOf().also {
            it[1] = 0xFF.toByte()
            it[2] = 0xFF.toByte()
        }
        assertEquals(65535, requireNotNull(GlucoseMeasurement.parse(high, zone)).sequence)
    }

    // -- what is refused rather than guessed ------------------------------

    @Test
    fun `a concentration the meter will not state is not a reading`() {
        // 0x07FF is NaN in IEEE 11073's 16-bit float. Read as a plain number it would
        // be 2047, which is a storable-looking glucose.
        val nan = capillary105.copyOf().also {
            it[12] = 0xFF.toByte()
            it[13] = 0x07.toByte()
        }
        assertNull(GlucoseMeasurement.parse(nan, zone))

        val notAtThisResolution = capillary105.copyOf().also {
            it[12] = 0x00.toByte()
            it[13] = 0x08.toByte() // NRes
        }
        assertNull(GlucoseMeasurement.parse(notAtThisResolution, zone))
    }

    @Test
    fun `a record with no concentration at all is nothing to store`() {
        val noValue = bytes(0x01, 0x29, 0x00, 0xEA, 0x07, 0x0B, 0x0F, 0x08, 0x1E, 0x00, 0x00, 0x00)
        assertNull(GlucoseMeasurement.parse(noValue, zone))
    }

    @Test
    fun `a meter that has lost its clock is not filed under January`() {
        val noMonth = capillary105.copyOf().also { it[5] = 0x00.toByte() }
        assertNull(GlucoseMeasurement.parse(noMonth, zone))

        val noDay = capillary105.copyOf().also { it[6] = 0x00.toByte() }
        assertNull(GlucoseMeasurement.parse(noDay, zone))

        val noYear = capillary105.copyOf().also {
            it[3] = 0x00.toByte()
            it[4] = 0x00.toByte()
        }
        assertNull(GlucoseMeasurement.parse(noYear, zone))
    }

    @Test
    fun `a truncated notification is refused at every length`() {
        for (length in 0 until capillary105.size) {
            assertNull(
                GlucoseMeasurement.parse(capillary105.copyOf(length), zone),
                "$length bytes parsed as a record",
            )
        }
    }

    @Test
    fun `an impossible date is refused rather than rolled over`() {
        val thirtyFirstOfNovember = capillary105.copyOf().also { it[6] = 0x1F.toByte() }
        assertNull(GlucoseMeasurement.parse(thirtyFirstOfNovember, zone))
    }

    // -- the record access control point ----------------------------------

    @Test
    fun `the requests are the bytes the specification names`() {
        assertEquals(listOf(0x01, 0x01), RecordAccess.allRecords.map { it.toInt() and 0xFF })
        assertEquals(listOf(0x04, 0x01), RecordAccess.recordCount.map { it.toInt() and 0xFF })
        assertEquals(
            listOf(0x01, 0x03, 0x01, 0x2A, 0x00),
            RecordAccess.recordsFrom(42).map { it.toInt() and 0xFF },
        )
        assertEquals(
            listOf(0x01, 0x03, 0x01, 0x2C, 0x01),
            RecordAccess.recordsFrom(300).map { it.toInt() and 0xFF },
            "the sequence is little-endian, so 300 is 2C 01 and not 01 2C",
        )
    }

    @Test
    fun `a sequence outside a uint16 is refused rather than wrapped`() {
        // Wrapping would turn "everything after 70000" into "everything after 4464",
        // which silently re-downloads a meter's history.
        assertFailsWith<IllegalArgumentException> { RecordAccess.recordsFrom(70_000) }
        assertFailsWith<IllegalArgumentException> { RecordAccess.recordsFrom(-1) }
    }

    @Test
    fun `the responses are told apart`() {
        assertEquals(RacpResponse.Completed(0x01), RecordAccess.parse(bytes(0x06, 0x00, 0x01, 0x01)))
        assertEquals(RacpResponse.NoRecords(0x01), RecordAccess.parse(bytes(0x06, 0x00, 0x01, 0x06)))
        assertEquals(RacpResponse.Failed(0x01, 0x05), RecordAccess.parse(bytes(0x06, 0x00, 0x01, 0x05)))
        assertEquals(RacpResponse.Count(42), RecordAccess.parse(bytes(0x05, 0x00, 0x2A, 0x00)))
        assertEquals(RacpResponse.Unrecognised(1), RecordAccess.parse(bytes(0x99)))
    }

    @Test
    fun `an up to date meter is not a broken one`() {
        val response = RecordAccess.parse(bytes(0x06, 0x00, 0x01, 0x06))
        assertTrue(
            response is RacpResponse.NoRecords,
            "nothing newer than what we hold is the ordinary answer to every sync after the first",
        )
    }

    // -- the clock, and what it corrects ----------------------------------

    @Test
    fun `the meter's clock is read from the same seven bytes on either characteristic`() {
        val dateTime = bytes(0xEA, 0x07, 0x0B, 0x0F, 0x08, 0x1E, 0x00)
        assertEquals(at("2026-11-15T08:30:00"), MeterTime.parse(dateTime, zone))

        // Current Time carries three more bytes; the first seven are the same.
        val currentTime = dateTime + bytes(0x07, 0x00, 0x00)
        assertEquals(at("2026-11-15T08:30:00"), MeterTime.parse(currentTime, zone))

        assertNull(MeterTime.parse(bytes(0xEA, 0x07, 0x00), zone), "too short to be a time")
    }

    @Test
    fun `a slow meter has its records moved forward by the skew`() {
        val record = requireNotNull(GlucoseMeasurement.parse(capillary105, zone))
        // The phone says 10:30 while the meter says 08:30: two hours slow.
        val skew = MeterTime.skewMillis(
            phoneNowMillis = at("2026-11-15T10:30:00"),
            meterNowMillis = at("2026-11-15T08:30:00"),
        )
        assertEquals(2 * 60 * 60 * 1000L, skew)

        val stored = record.bloodTest(meterName = "Accu-Chek Guide", clockSkewMillis = skew)
        assertEquals(at("2026-11-15T10:30:00"), stored.takenAtMillis)
        assertEquals(105.0, stored.mgdl, 1e-9)
        assertEquals(BloodTestOrigin.METER, stored.origin)
        assertEquals(41, stored.meterSequence)
        assertEquals("Accu-Chek Guide", stored.meterName)
        assertTrue(stored.hasMeterRecord)
        assertTrue(stored.isPlausible)
    }

    @Test
    fun `a meter in step with the phone moves nothing`() {
        val record = requireNotNull(GlucoseMeasurement.parse(capillary105, zone))
        val stored = record.bloodTest("Accu-Chek Guide", clockSkewMillis = 0)
        assertEquals(record.wallClockMillis, stored.takenAtMillis)
    }
}
