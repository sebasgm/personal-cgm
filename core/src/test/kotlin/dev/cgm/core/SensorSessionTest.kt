package dev.cgm.core

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The sensor session: how long it runs, and when it is a different sensor.
 *
 * Both halves were wrong in the same release. The lifetime was a single 14-day
 * constant, which reported a 15-day sensor as "day 15/14" and muted it as expired
 * a day early; and identity was taken from the activation stamp, which
 * LibreLinkUp moves on a re-scan, so a sensor halfway through its life restarted
 * its day count at one and looked like a new one had been fitted.
 */
class SensorSessionTest {

    private val day = 24L * 60 * 60 * 1000
    private val started = 1_800_000_000_000L

    private fun sensor(
        serial: String? = "SN-A",
        startedAt: Long? = started,
        lifetime: Int = 15,
        productType: Int? = null,
    ) = SensorInfo(
        serial = serial,
        startedAtMillis = startedAt,
        lifetimeDays = lifetime,
        productType = productType,
    )

    // -- lifetime ---------------------------------------------------------

    @Test
    fun `a 15-day sensor is on day 15 and not expired on its final day`() {
        val s = sensor(lifetime = 15)
        val lastDay = started + 14 * day + 1_000

        assertEquals(15, s.dayOfSession(lastDay))
        assertFalse(s.isExpired(lastDay), "a 15-day sensor still runs on day 15")
        assertTrue(s.isFinalDay(lastDay))
    }

    @Test
    fun `the same instant expires a 14-day sensor`() {
        val lastDay = started + 14 * day + 1_000
        assertTrue(sensor(lifetime = 14).isExpired(lastDay))
        assertFalse(sensor(lifetime = 15).isExpired(lastDay))
    }

    @Test
    fun `expiry is exactly the end of the designed life`() {
        val s = sensor(lifetime = 15)
        assertFalse(s.isExpired(started + 15 * day - 1))
        assertTrue(s.isExpired(started + 15 * day))
        assertEquals(0, s.remainingMillis(started + 15 * day))
    }

    @Test
    fun `the final day starts a day before the end, not earlier`() {
        val s = sensor(lifetime = 15)
        assertFalse(s.isFinalDay(started + 14 * day - 1), "still two days of life left")
        assertTrue(s.isFinalDay(started + 14 * day))
        assertFalse(s.isFinalDay(started + 15 * day), "expired is not a final day")
    }

    @Test
    fun `the warning window opens three days before the end`() {
        val s = sensor(lifetime = 15)

        assertFalse(s.isEndingSoon(started + 12 * day - 1), "four days left is not soon")
        assertTrue(s.isEndingSoon(started + 12 * day), "exactly three days left")
        assertTrue(s.isEndingSoon(started + 14 * day + 23 * 60 * 60 * 1000))
    }

    @Test
    fun `the warning window closes at expiry rather than staying open`() {
        // Past the end the figure reads "expired", which says more than a colour.
        val s = sensor(lifetime = 15)

        assertFalse(s.isEndingSoon(started + 15 * day))
        assertFalse(s.isEndingSoon(started + 20 * day))
    }

    @Test
    fun `the warning window follows the chosen lifetime`() {
        // The whole point of the per-sensor lifetime: the same instant is the
        // fourth-to-last day of a 15-day sensor and the third of a 14-day one.
        val instant = started + 11 * day + 1_000

        assertTrue(sensor(lifetime = 14).isEndingSoon(instant))
        assertFalse(sensor(lifetime = 15).isEndingSoon(instant))
    }

    @Test
    fun `the last day is inside the warning window`() {
        // Two thresholds, one ordering: nothing may be in its final day without
        // also being about to end, or the colour would clear as the notice fires.
        val s = sensor(lifetime = 15)
        val finalDay = started + 14 * day + 1_000

        assertTrue(s.isFinalDay(finalDay))
        assertTrue(s.isEndingSoon(finalDay))
    }

    @Test
    fun `a sensor with no activation is never about to end`() {
        assertFalse(sensor(startedAt = null).isEndingSoon(started))
    }

    @Test
    fun `remaining time is negative once past the end`() {
        val s = sensor(lifetime = 15)
        assertEquals(-day, s.remainingMillis(started + 16 * day))
    }

    @Test
    fun `nothing is known without an activation instant`() {
        val s = sensor(startedAt = null)
        assertNull(s.dayOfSession(started))
        assertNull(s.remainingMillis(started))
        assertNull(s.endsAtMillis())
        assertFalse(s.isExpired(started), "unknown is not expired")
    }

    // -- identity ---------------------------------------------------------

    @Test
    fun `a re-scan does not restart the day count`() {
        // Abbott moves the activation stamp to the moment of the scan. Believing
        // it put a sensor on day 1 on day 8, which is what read as a new sensor.
        val onRecord = sensor(startedAt = started)
        val rescanned = sensor(startedAt = started + 7 * day)

        val resolved = SensorIdentity.reconcile(onRecord, rescanned, lifetimeDays = 15)

        assertEquals(started, resolved.sensor?.startedAtMillis)
        assertFalse(resolved.isNewSensor)
        assertEquals(8, resolved.sensor?.dayOfSession(started + 7 * day))
    }

    @Test
    fun `an earlier activation for a known serial is taken`() {
        // The correction direction that is real: the first sighting was late.
        val onRecord = sensor(startedAt = started + 2 * day)
        val earlier = sensor(startedAt = started)

        val resolved = SensorIdentity.reconcile(onRecord, earlier, lifetimeDays = 15)

        assertEquals(started, resolved.sensor?.startedAtMillis)
        assertFalse(resolved.isNewSensor)
    }

    @Test
    fun `a different serial is a new sensor`() {
        val resolved = SensorIdentity.reconcile(
            stored = sensor(serial = "SN-A", startedAt = started),
            fetched = sensor(serial = "SN-B", startedAt = started + 15 * day),
            lifetimeDays = 15,
        )

        assertTrue(resolved.isNewSensor)
        assertEquals("SN-B", resolved.sensor?.serial)
        assertEquals(started + 15 * day, resolved.sensor?.startedAtMillis)
    }

    @Test
    fun `the first sensor ever seen is not announced as new`() {
        // Otherwise every fresh install claims a sensor was just fitted.
        val resolved = SensorIdentity.reconcile(
            stored = null,
            fetched = sensor(),
            lifetimeDays = 15,
        )

        assertFalse(resolved.isNewSensor)
        assertEquals("SN-A", resolved.sensor?.serial)
    }

    @Test
    fun `an unknown serial is never evidence of a change`() {
        val resolved = SensorIdentity.reconcile(
            stored = sensor(serial = "SN-A"),
            fetched = sensor(serial = null, startedAt = started + 3 * day),
            lifetimeDays = 15,
        )

        assertFalse(resolved.isNewSensor, "a nameless sensor proves nothing")
    }

    @Test
    fun `two unknown serials are not the same sensor`() {
        assertFalse(sensor(serial = null).isSameSensorAs(sensor(serial = null)))
    }

    @Test
    fun `a fetch that says nothing leaves the record alone`() {
        val onRecord = sensor(startedAt = started, lifetime = 14)

        val resolved = SensorIdentity.reconcile(onRecord, fetched = null, lifetimeDays = 15)

        assertEquals(started, resolved.sensor?.startedAtMillis)
        assertFalse(resolved.isNewSensor)
        assertEquals(15, resolved.sensor?.lifetimeDays, "the current setting still applies")
    }

    @Test
    fun `the chosen lifetime overrides whatever the source carried`() {
        val resolved = SensorIdentity.reconcile(
            stored = null,
            fetched = sensor(lifetime = SensorInfo.DEFAULT_LIFETIME_DAYS),
            lifetimeDays = 15,
        )

        assertEquals(15, resolved.sensor?.lifetimeDays)
    }

    @Test
    fun `a product code already on record survives a fetch without one`() {
        val resolved = SensorIdentity.reconcile(
            stored = sensor(productType = 4),
            fetched = sensor(productType = null),
            lifetimeDays = 15,
        )

        assertEquals(4, resolved.sensor?.productType)
    }
}
