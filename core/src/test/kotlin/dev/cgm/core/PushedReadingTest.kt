package dev.cgm.core

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class PushedReadingTest {

    private val now = 1_800_000_000_000L

    private fun reading(
        mgdl: Double?,
        timestampMillis: Long? = now - 30_000,
        trend: TrendArrow = TrendArrow.STEADY,
    ) = PushedReading.of(mgdl, timestampMillis, trend, now)

    @Test
    fun `a plausible reading comes through unchanged`() {
        val result = assertNotNull(reading(112.0))
        assertEquals(112.0, result.valueMgdl)
        assertEquals(now - 30_000, result.timestampMillis)
        assertEquals(TrendArrow.STEADY, result.trend)
    }

    @Test
    fun `zero is xDrip saying it has no value, not a reading of zero`() {
        assertNull(reading(0.0))
    }

    @Test
    fun `values outside what a sensor can report are refused`() {
        // A raw, uncalibrated figure or a unit mix-up. Stored once, it is in the
        // rollups, and no later fix takes it out again.
        assertNull(reading(PushedReading.MIN_MGDL - 0.1))
        assertNull(reading(PushedReading.MAX_MGDL + 0.1))
        assertNull(reading(Double.NaN))
        assertNull(reading(null))
        assertNotNull(reading(PushedReading.MIN_MGDL))
        assertNotNull(reading(PushedReading.MAX_MGDL))
    }

    @Test
    fun `a missing timestamp becomes now rather than the epoch`() {
        // The epoch would read as fifty years stale to every freshness check in
        // the app, and they would all act on it.
        assertEquals(now, assertNotNull(reading(100.0, timestampMillis = 0L)).timestampMillis)
        assertEquals(now, assertNotNull(reading(100.0, timestampMillis = null)).timestampMillis)
        assertEquals(now, assertNotNull(reading(100.0, timestampMillis = -5L)).timestampMillis)
    }

    @Test
    fun `a future timestamp is a clock disagreement, so it is pulled back`() {
        // Two apps on one phone can still disagree by seconds. A negative age is
        // not survivable downstream, and the value itself is fine.
        val result = assertNotNull(reading(100.0, timestampMillis = now + 90_000))
        assertEquals(now, result.timestampMillis)
        assertEquals(0L, result.ageMillis(now))
    }

    @Test
    fun `xDrip's seven slopes map onto the app's five arrows`() {
        assertEquals(TrendArrow.STEADY, TrendArrow.fromXdripSlopeName("Flat"))
        assertEquals(TrendArrow.RISING, TrendArrow.fromXdripSlopeName("FortyFiveUp"))
        assertEquals(TrendArrow.RISING_QUICKLY, TrendArrow.fromXdripSlopeName("SingleUp"))
        assertEquals(TrendArrow.RISING_QUICKLY, TrendArrow.fromXdripSlopeName("DoubleUp"))
        assertEquals(TrendArrow.FALLING, TrendArrow.fromXdripSlopeName("FortyFiveDown"))
        assertEquals(TrendArrow.FALLING_QUICKLY, TrendArrow.fromXdripSlopeName("SingleDown"))
        assertEquals(TrendArrow.FALLING_QUICKLY, TrendArrow.fromXdripSlopeName("DoubleDown"))
    }

    @Test
    fun `xDrip saying it cannot compute a slope is not a flat trend`() {
        // These are xDrip's own strings for having too few points to fit a line.
        // Reading them as "Flat" would draw a steady arrow over an unknown trend.
        assertEquals(TrendArrow.UNKNOWN, TrendArrow.fromXdripSlopeName("NOT COMPUTABLE"))
        assertEquals(TrendArrow.UNKNOWN, TrendArrow.fromXdripSlopeName("NON_COMPUTABLE"))
        assertEquals(TrendArrow.UNKNOWN, TrendArrow.fromXdripSlopeName("OUT OF RANGE"))
        assertEquals(TrendArrow.UNKNOWN, TrendArrow.fromXdripSlopeName(null))
        assertEquals(TrendArrow.UNKNOWN, TrendArrow.fromXdripSlopeName(""))
    }
}
