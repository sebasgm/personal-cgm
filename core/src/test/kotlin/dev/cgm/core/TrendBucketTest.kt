package dev.cgm.core

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Splitting the sensor-against-strip comparison by what the trace was doing.
 *
 * The breakdown exists to answer one question a pooled figure cannot: is this sensor
 * reading high, or is it reading late. Those produce the same median difference and
 * call for opposite responses — a sensor that leans high on a flat trace is wrong,
 * and a sensor that agrees when flat and reads high while glucose falls is accurate
 * and behind.
 *
 * So the test that matters most here is the last one, which builds exactly that
 * sensor — a perfectly accurate one with a five-minute lag — and asserts that the
 * breakdown reports it as accurate when flat and high when falling. A pooled MARD
 * over the same pairs reports a mediocre sensor and no reason.
 */
class TrendBucketTest {

    private val start: Long = 1_790_000_000_000L

    private fun minutes(n: Int): Long = n * 60_000L

    /** A sensor series at a fixed slope, one reading a minute. */
    private fun series(
        startMgdl: Double,
        ratePerMinute: Double,
        fromMinute: Int = -30,
        toMinute: Int = 10,
    ): List<GlucoseReading> = (fromMinute..toMinute).map { m ->
        GlucoseReading(
            valueMgdl = startMgdl + ratePerMinute * m,
            timestampMillis = start + minutes(m),
        )
    }

    // -- the boundary --------------------------------------------------------

    @Test
    fun `the bucket boundary is the line the blend is gated on`() {
        val flat = GlucoseFusion.FLAT_RATE_MGDL_PER_MINUTE

        assertEquals(TrendBucket.FLAT, TrendBucket.of(0.0))
        assertEquals(TrendBucket.FLAT, TrendBucket.of(flat), "the edge is flat")
        assertEquals(TrendBucket.FLAT, TrendBucket.of(-flat))
        assertEquals(TrendBucket.RISING, TrendBucket.of(flat + 0.01))
        assertEquals(TrendBucket.FALLING, TrendBucket.of(-flat - 0.01))
    }

    @Test
    fun `an unknown rate is not filed as flat`() {
        assertNull(TrendBucket.of(null), "missing data is not evidence of a flat trace")
    }

    @Test
    fun `direction is kept, not just magnitude`() {
        // Pooling both directions into "moving" would cancel the lag against itself.
        assertTrue(TrendBucket.of(3.0) != TrendBucket.of(-3.0))
    }

    // -- the slope comes from the readings -----------------------------------

    @Test
    fun `the slope is recovered from the sensor series, with no trend arrow anywhere`() {
        val readings = series(startMgdl = 120.0, ratePerMinute = -2.0)
        val test = BloodTest(mgdl = 120.0, takenAtMillis = start)

        val pair = SensorAgreement.of(listOf(test), readings).pairs.single()

        val rate = assertNotNull(pair.ratePerMinute)
        assertEquals(-2.0, rate, 1e-6)
        assertEquals(TrendBucket.FALLING, pair.trendBucket)
        assertEquals(TrendArrow.UNKNOWN, readings.first().trend, "no arrow was needed")
    }

    @Test
    fun `a pair with nothing behind it cannot be bucketed and says so`() {
        // Only the reading itself: no earlier reading, so no delta and no slope.
        val readings = listOf(GlucoseReading(valueMgdl = 120.0, timestampMillis = start))
        val test = BloodTest(mgdl = 118.0, takenAtMillis = start)

        val agreement = SensorAgreement.of(listOf(test), readings)

        assertEquals(1, agreement.count, "it is still a pair")
        assertNull(agreement.pairs.single().trendBucket)
        assertEquals(1, agreement.unknownTrendCount)
        assertTrue(agreement.byTrend.isEmpty(), "and it is in no bucket")
    }

    @Test
    fun `the lookback is deep enough for the delta the slope is measured from`() {
        assertTrue(
            SensorAgreement.SLOPE_LOOKBACK_MILLIS >= DeltaCalculator.TARGET_SPAN_MILLIS,
            "a five-minute delta needs at least five minutes of history",
        )
        assertEquals(DeltaCalculator.MAX_USABLE_SPAN_MILLIS, SensorAgreement.SLOPE_LOOKBACK_MILLIS)
    }

    // -- the breakdown --------------------------------------------------------

    @Test
    fun `each bucket is a full agreement over its own pairs`() {
        val falling = series(startMgdl = 150.0, ratePerMinute = -3.0)
        val agreement = SensorAgreement.of(
            listOf(BloodTest(mgdl = 130.0, takenAtMillis = start)),
            falling,
        )

        val bucket = assertNotNull(agreement.byTrend[TrendBucket.FALLING])
        assertEquals(1, bucket.count)
        assertEquals(20.0, assertNotNull(bucket.medianDifferenceMgdl), 1e-9)
        assertNotNull(bucket.meanAbsoluteRelativeDifference)
    }

    @Test
    fun `buckets nobody landed in are absent rather than empty`() {
        val flat = series(startMgdl = 100.0, ratePerMinute = 0.0)
        val agreement = SensorAgreement.of(
            listOf(BloodTest(mgdl = 100.0, takenAtMillis = start)),
            flat,
        )

        assertEquals(setOf(TrendBucket.FLAT), agreement.byTrend.keys)
    }

    @Test
    fun `the buckets and the unbucketed account for every pair`() {
        val readings = series(startMgdl = 100.0, ratePerMinute = 0.0) +
            listOf(GlucoseReading(valueMgdl = 200.0, timestampMillis = start + minutes(600)))
        val tests = listOf(
            BloodTest(mgdl = 98.0, takenAtMillis = start),
            // Far from everything else: paired, but with nothing behind it to slope on.
            BloodTest(mgdl = 190.0, takenAtMillis = start + minutes(600)),
        )

        val agreement = SensorAgreement.of(tests, readings)
        val bucketed = agreement.byTrend.values.sumOf { it.count }

        assertEquals(agreement.count, bucketed + agreement.unknownTrendCount)
    }

    // -- the question the whole breakdown exists to answer --------------------

    @Test
    fun `an accurate sensor that lags reads high while falling and agrees while flat`() {
        // A sensor that is exactly right about glucose five minutes ago. On a flat
        // trace that is indistinguishable from being right about now; on a falling
        // one it reads high by the fall of five minutes, every time.
        val lagMinutes = 5
        val falling = 3.0

        fun lagged(ratePerMinute: Double): List<GlucoseReading> = (-30..10).map { m ->
            GlucoseReading(
                // The sensor reports where blood was `lagMinutes` ago.
                valueMgdl = 150.0 + ratePerMinute * (m - lagMinutes),
                timestampMillis = start + minutes(m),
            )
        }

        // Strips measure blood, which is at 150 at the moment each is taken.
        val strip = listOf(BloodTest(mgdl = 150.0, takenAtMillis = start))

        val whileFlat = SensorAgreement.of(strip, lagged(0.0))
        val whileFalling = SensorAgreement.of(strip, lagged(-falling))

        val flatBias = assertNotNull(whileFlat.byTrend[TrendBucket.FLAT]?.medianDifferenceMgdl)
        val fallingBias =
            assertNotNull(whileFalling.byTrend[TrendBucket.FALLING]?.medianDifferenceMgdl)

        assertEquals(0.0, flatBias, 1e-9, "flat: the lag is invisible and the sensor is right")
        assertEquals(
            falling * lagMinutes,
            fallingBias,
            1e-9,
            "falling: the same accurate sensor reads high by exactly the fall it has not caught up with",
        )
    }

    @Test
    fun `and the pooled figure cannot tell that story`() {
        // The same accurate-but-late sensor, half its pairs flat and half falling. The
        // pooled median lands between the two and describes neither.
        fun lagged(rate: Double, at: Long) = (-30..10).map { m ->
            GlucoseReading(
                valueMgdl = 150.0 + rate * (m - 5),
                timestampMillis = at + minutes(m),
            )
        }

        val day = minutes(60 * 24)
        val readings = lagged(0.0, start) + lagged(-3.0, start + day)
        val tests = listOf(
            BloodTest(mgdl = 150.0, takenAtMillis = start),
            BloodTest(mgdl = 150.0, takenAtMillis = start + day),
        )

        val agreement = SensorAgreement.of(tests, readings)
        val pooled = assertNotNull(agreement.medianDifferenceMgdl)

        assertEquals(7.5, pooled, 1e-9, "a lean nobody's sensor actually has")
        assertEquals(
            0.0,
            assertNotNull(agreement.byTrend[TrendBucket.FLAT]?.medianDifferenceMgdl),
            1e-9,
        )
        assertEquals(
            15.0,
            assertNotNull(agreement.byTrend[TrendBucket.FALLING]?.medianDifferenceMgdl),
            1e-9,
        )
    }
}
