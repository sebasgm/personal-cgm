package dev.cgm.core

import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The standard Bluetooth Glucose Service, as this app speaks it.
 *
 * Everything here is from the Bluetooth SIG's Glucose Profile and Glucose Service
 * specifications rather than from any meter's own protocol, which is the whole reason
 * a finger-prick meter is worth integrating at all: one implementation reads every
 * compliant device, and a device that is not compliant announces itself by not having
 * the service at all. See `docs/12-finger-prick-meter.md`.
 *
 * Parsing lives in `:core`, away from Android, because it is bit arithmetic over bytes
 * that arrive from someone else's firmware — the part most likely to be wrong, and the
 * part a phone is least needed to test.
 */
object GlucoseServiceUuids {
    /** The service a meter must expose. Its absence is the compatibility answer. */
    const val SERVICE = "00001808-0000-1000-8000-00805f9b34fb"

    /** One notification per stored record. */
    const val MEASUREMENT = "00002a18-0000-1000-8000-00805f9b34fb"

    /** Meal and health annotations, optional and currently unread. */
    const val CONTEXT = "00002a34-0000-1000-8000-00805f9b34fb"

    /** The Record Access Control Point: how history is asked for. Indicates, not notifies. */
    const val RECORD_ACCESS = "00002a52-0000-1000-8000-00805f9b34fb"

    /** The meter's clock, on a service of its own. */
    const val CURRENT_TIME_SERVICE = "00001805-0000-1000-8000-00805f9b34fb"
    const val CURRENT_TIME = "00002a2b-0000-1000-8000-00805f9b34fb"

    /**
     * The meter's clock again, inside the glucose service.
     *
     * Where Roche puts it — an Accu-Chek answers here and not on the Current Time
     * Service — so this is the Accu-Chek branch, and it is one characteristic wide.
     */
    const val DATE_TIME = "00002a08-0000-1000-8000-00805f9b34fb"

    const val DEVICE_INFORMATION = "0000180a-0000-1000-8000-00805f9b34fb"
    const val MANUFACTURER_NAME = "00002a29-0000-1000-8000-00805f9b34fb"
    const val MODEL_NUMBER = "00002a24-0000-1000-8000-00805f9b34fb"
    const val SERIAL_NUMBER = "00002a25-0000-1000-8000-00805f9b34fb"

    /** Client Characteristic Configuration: the descriptor every subscription writes. */
    const val CLIENT_CONFIG = "00002902-0000-1000-8000-00805f9b34fb"
}

/**
 * The Record Access Control Point: requests for stored records, and what comes back.
 *
 * The channel is request/response — indications, which are acknowledged, rather than
 * notifications — and the records themselves arrive separately as a burst on the
 * measurement characteristic. So a pull is: write a request here, collect measurements,
 * and wait for the response that says the burst is over.
 */
object RecordAccess {

    private const val OP_REPORT_RECORDS = 0x01
    private const val OP_REPORT_COUNT = 0x04
    private const val OP_COUNT_RESPONSE = 0x05
    private const val OP_RESPONSE_CODE = 0x06

    private const val OPERATOR_ALL = 0x01
    private const val OPERATOR_GREATER_OR_EQUAL = 0x03

    private const val FILTER_SEQUENCE = 0x01

    const val SUCCESS = 0x01
    const val NO_RECORDS_FOUND = 0x06

    /** Everything the meter holds. The first sync, and nothing else. */
    val allRecords: ByteArray = byteArrayOf(OP_REPORT_RECORDS.toByte(), OPERATOR_ALL.toByte())

    /** How many records are stored, without downloading any of them. */
    val recordCount: ByteArray = byteArrayOf(OP_REPORT_COUNT.toByte(), OPERATOR_ALL.toByte())

    /**
     * Records numbered [sequence] or above.
     *
     * Every sync after the first, asking from one past the highest already stored. The
     * standard spells "newer than" as "greater than or equal to", so the caller adds the
     * one; done here it would be a second place that knows whether the bound is
     * inclusive, and the two would eventually disagree.
     *
     * The sequence is a uint16 on the wire. A value outside that range is a bug
     * upstream, and clamping it quietly would turn "ask for everything after 70000"
     * into "ask for everything", which is a silent re-download of the meter's history.
     */
    fun recordsFrom(sequence: Int): ByteArray {
        require(sequence in 0..0xFFFF) { "sequence $sequence is not a uint16" }
        return byteArrayOf(
            OP_REPORT_RECORDS.toByte(),
            OPERATOR_GREATER_OR_EQUAL.toByte(),
            FILTER_SEQUENCE.toByte(),
            (sequence and 0xFF).toByte(),
            ((sequence shr 8) and 0xFF).toByte(),
        )
    }

    /** What an indication on the control point means. */
    fun parse(bytes: ByteArray): RacpResponse = when {
        bytes.size >= 4 && bytes[0].toInt() and 0xFF == OP_COUNT_RESPONSE ->
            RacpResponse.Count(u16(bytes, 2))

        bytes.size >= 4 && bytes[0].toInt() and 0xFF == OP_RESPONSE_CODE -> {
            val request = bytes[2].toInt() and 0xFF
            when (val code = bytes[3].toInt() and 0xFF) {
                SUCCESS -> RacpResponse.Completed(request)
                NO_RECORDS_FOUND -> RacpResponse.NoRecords(request)
                else -> RacpResponse.Failed(request, code)
            }
        }

        else -> RacpResponse.Unrecognised(bytes.size)
    }
}

/** What the meter said about a request. */
sealed interface RacpResponse {
    /** How many records it holds. */
    data class Count(val records: Int) : RacpResponse

    /** The burst is over and every record asked for has been sent. */
    data class Completed(val requestOpcode: Int) : RacpResponse

    /**
     * Nothing matched — which is the ordinary answer to "anything newer than the last
     * one I have", and not a failure. Separated from [Failed] so a caller cannot report
     * an up-to-date meter as a broken one.
     */
    data class NoRecords(val requestOpcode: Int) : RacpResponse

    data class Failed(val requestOpcode: Int, val code: Int) : RacpResponse

    /** Not a shape this knows. Kept rather than thrown: a meter may add opcodes. */
    data class Unrecognised(val length: Int) : RacpResponse
}

/**
 * One stored record, as the meter stated it.
 *
 * [wallClockMillis] is the meter's own clock read as a wall time, *not* a corrected
 * instant: a meter whose clock is an hour out reports an hour-out record, and the
 * correction needs the skew measured at connection, which this type does not have.
 * [bloodTest] applies it.
 */
data class MeterRecord(
    val sequence: Int,
    val mgdl: Double,
    val wallClockMillis: Long,
    /** The sample type nibble. 10 is a control solution; the rest are blood or plasma. */
    val sampleType: Int,
    val sampleLocation: Int,
    val hasContext: Boolean,
) {
    /**
     * Whether this is a test of the strips rather than of a person.
     *
     * Deliberately off-scale by design, so one of these stored among real results would
     * not merely be noise — it would be the worst point in every comparison the sensor
     * is scored by.
     */
    val isControlSolution: Boolean get() = sampleType == CONTROL_SOLUTION

    /**
     * This record as something the app stores.
     *
     * [clockSkewMillis] is the phone's clock minus the meter's, measured once when the
     * connection was made; it shifts the meter's wall time onto the phone's. Drift and
     * daylight saving between syncs are not corrected, because nothing here can know
     * about them — the skew is a measurement of one moment.
     */
    fun bloodTest(meterName: String, clockSkewMillis: Long): BloodTest = BloodTest(
        mgdl = mgdl,
        takenAtMillis = wallClockMillis + clockSkewMillis,
        origin = BloodTestOrigin.METER,
        meterSequence = sequence,
        meterName = meterName,
    )

    companion object {
        const val CONTROL_SOLUTION = 10
    }
}

/**
 * The Glucose Measurement characteristic.
 *
 * Little-endian throughout, with a flags byte that says which of the optional fields
 * are present — so the record's length is not fixed and the fields have to be walked
 * rather than indexed.
 */
object GlucoseMeasurement {

    private const val FLAG_TIME_OFFSET = 0x01
    private const val FLAG_CONCENTRATION = 0x02
    private const val FLAG_UNIT_MOL_PER_L = 0x04
    private const val FLAG_STATUS = 0x08
    private const val FLAG_CONTEXT_FOLLOWS = 0x10

    /** Milligrams per decilitre in one kilogram per litre. */
    private const val MGDL_PER_KGL = 100_000.0

    /** Millimoles per litre in one mole per litre. */
    private const val MMOLL_PER_MOLL = 1_000.0

    /** "The offset is not known", which is a real value a meter may send. */
    private const val OFFSET_UNKNOWN = -32768

    /**
     * A record, or null when the bytes do not describe one.
     *
     * Null rather than an exception, and null rather than a partial record: a malformed
     * notification is a record this app cannot file, and the alternative — filing it
     * with a guessed value or a guessed time — puts a fiction in the one table whose
     * whole purpose is to be the reference everything else is judged against.
     *
     * [zone] is the phone's, and it is right even though the clock being read is the
     * meter's: the meter states a wall time with no zone attached, so the only way to
     * turn it into an instant is to assume it meant the same wall clock the phone is
     * on. The skew measured at connection corrects whatever that assumption got wrong.
     */
    fun parse(bytes: ByteArray, zone: ZoneId): MeterRecord? {
        // Flags, sequence and base time: the fixed part, and the minimum worth reading.
        if (bytes.size < 10) return null

        val flags = bytes[0].toInt() and 0xFF
        val sequence = u16(bytes, 1)

        val wall = dateTimeAt(bytes, 3, zone) ?: return null

        var cursor = 10
        var offsetMinutes = 0
        if (flags and FLAG_TIME_OFFSET != 0) {
            if (bytes.size < cursor + 2) return null
            val offset = s16(bytes, cursor)
            // Not an offset of minus five hundred and forty-six hours.
            if (offset != OFFSET_UNKNOWN) offsetMinutes = offset
            cursor += 2
        }

        // A record with no concentration is a record of nothing.
        if (flags and FLAG_CONCENTRATION == 0) return null
        if (bytes.size < cursor + 3) return null

        val concentration = sfloat(u16(bytes, cursor)) ?: return null
        cursor += 2

        val typeLocation = bytes[cursor].toInt() and 0xFF
        cursor += 1

        val mgdl = if (flags and FLAG_UNIT_MOL_PER_L != 0) {
            concentration * MMOLL_PER_MOLL * GlucoseUnit.MGDL_PER_MMOLL
        } else {
            concentration * MGDL_PER_KGL
        }
        if (!mgdl.isFinite()) return null

        return MeterRecord(
            sequence = sequence,
            mgdl = mgdl,
            wallClockMillis = wall + offsetMinutes * 60_000L,
            // Type is the low nibble and location the high one, which is the opposite
            // way round from how the field is named.
            sampleType = typeLocation and 0x0F,
            sampleLocation = (typeLocation shr 4) and 0x0F,
            hasContext = flags and FLAG_CONTEXT_FOLLOWS != 0,
        )
    }

    /** Whether a record carries a sensor-status annunciation, for logging only. */
    fun hasStatus(flags: Int): Boolean = flags and FLAG_STATUS != 0
}

/**
 * The meter's clock, from either characteristic that can carry it.
 *
 * Current Time (`0x2A2B`) and Date Time (`0x2A08`) begin with the same seven bytes and
 * differ only in what follows, so one reader serves both — which is what makes the
 * Roche branch two lines rather than a parser of its own.
 */
object MeterTime {

    /** The meter's wall clock as an instant, or null when it will not state one. */
    fun parse(bytes: ByteArray, zone: ZoneId): Long? = dateTimeAt(bytes, 0, zone)

    /**
     * How far the phone is ahead of the meter, which is what corrects every record.
     *
     * Measured once per connection and applied uniformly. A meter that is slow by two
     * hours files every record two hours early, and this is the only thing that knows
     * otherwise.
     */
    fun skewMillis(phoneNowMillis: Long, meterNowMillis: Long): Long =
        phoneNowMillis - meterNowMillis
}

/**
 * Seven bytes of `org.bluetooth.characteristic.date_time`, as epoch millis in [zone].
 *
 * Returns null for the spec's own "unknown" encodings — a year, month or day of zero —
 * rather than coercing them to January. A meter that has lost its clock is saying so.
 */
private fun dateTimeAt(bytes: ByteArray, at: Int, zone: ZoneId): Long? {
    if (bytes.size < at + 7) return null
    val year = u16(bytes, at)
    val month = bytes[at + 2].toInt() and 0xFF
    val day = bytes[at + 3].toInt() and 0xFF
    val hour = bytes[at + 4].toInt() and 0xFF
    val minute = bytes[at + 5].toInt() and 0xFF
    val second = bytes[at + 6].toInt() and 0xFF
    if (year == 0 || month == 0 || day == 0) return null

    return runCatching {
        LocalDateTime.of(year, month, day, hour, minute, second)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
    }.getOrNull()
}

/**
 * The 16-bit float of IEEE 11073-20601: a 12-bit signed mantissa and a 4-bit signed
 * exponent, which is how a meter sends 5.8 without sending a double.
 *
 * The five reserved encodings — NaN, NRes, the two infinities and the reserved value —
 * come back as null. They are the meter saying it has no number, and a caller that
 * treated the raw bits as one would store 2047 × 10⁰ as a glucose.
 */
private fun sfloat(raw: Int): Double? {
    when (raw and 0xFFFF) {
        0x07FF, 0x0800, 0x07FE, 0x0802, 0x0801 -> return null
    }
    var mantissa = raw and 0x0FFF
    var exponent = (raw shr 12) and 0x0F
    if (mantissa >= 0x0800) mantissa -= 0x1000
    if (exponent >= 0x8) exponent -= 16
    return mantissa * Math.pow(10.0, exponent.toDouble())
}

private fun u16(bytes: ByteArray, at: Int): Int =
    (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

private fun s16(bytes: ByteArray, at: Int): Int = u16(bytes, at).let {
    if (it >= 0x8000) it - 0x10000 else it
}
