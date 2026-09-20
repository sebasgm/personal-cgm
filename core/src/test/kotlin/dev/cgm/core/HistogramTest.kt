package dev.cgm.core

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class GlucoseHistogramTest {

    @Test
    fun `bins by five mg per dL from forty`() {
        assertEquals(0, GlucoseHistogram.binOf(40.0))
        assertEquals(0, GlucoseHistogram.binOf(44.9))
        assertEquals(1, GlucoseHistogram.binOf(45.0))
        assertEquals(24, GlucoseHistogram.binOf(160.0))
    }

    @Test
    fun `clamps values outside the sensor range instead of losing them`() {
        assertEquals(0, GlucoseHistogram.binOf(10.0))
        assertEquals(GlucoseHistogram.BIN_COUNT - 1, GlucoseHistogram.binOf(900.0))
        val bins = GlucoseHistogram.of(listOf(10.0, 900.0))
        assertEquals(2, GlucoseHistogram.total(bins))
    }

    @Test
    fun `merging is what lets any window be built from hourly rows`() {
        val a = GlucoseHistogram.of(listOf(100.0, 110.0))
        val b = GlucoseHistogram.of(listOf(100.0, 300.0))
        val merged = GlucoseHistogram.merge(a.copyOf(), b)
        assertEquals(4, GlucoseHistogram.total(merged))
        assertEquals(2.0, GlucoseHistogram.countBetween(merged, 100.0, 105.0))
    }

    // -- the point of the whole design ------------------------------------

    @Test
    fun `time in range recomputes for different thresholds after the fact`() {
        // 10 readings: 4 below 70, 3 in 70-180, 3 above 180.
        val values = listOf(50.0, 60.0, 62.0, 68.0, 100.0, 120.0, 150.0, 200.0, 250.0, 300.0)
        val bins = GlucoseHistogram.of(values)

        val at70 = GlucoseHistogram.zoneFractions(bins, GlucoseThresholds(lowMgdl = 70.0))
        assertEquals(0.3, at70[Zone.IN_RANGE]!!, 0.001)

        // Raise the low bound to 80 and the same stored row answers again, with
        // no access to the original readings.
        val at80 = GlucoseHistogram.zoneFractions(bins, GlucoseThresholds(lowMgdl = 80.0))
        assertEquals(0.3, at80[Zone.IN_RANGE]!!, 0.001)

        // Only 150 survives a 130-180 band, and the row still knows that.
        val at130 = GlucoseHistogram.zoneFractions(bins, GlucoseThresholds(lowMgdl = 130.0))
        assertEquals(0.1, at130[Zone.IN_RANGE]!!, 0.001)
    }

    @Test
    fun `zone fractions sum to one`() {
        val bins = GlucoseHistogram.of(listOf(45.0, 65.0, 120.0, 200.0, 300.0))
        val fractions = GlucoseHistogram.zoneFractions(bins, GlucoseThresholds())
        assertEquals(1.0, fractions.values.sum(), 0.001)
    }

    @Test
    fun `empty histogram yields no fractions rather than a division by zero`() {
        assertTrue(GlucoseHistogram.zoneFractions(GlucoseHistogram.empty(), GlucoseThresholds()).isEmpty())
    }

    // -- percentiles, which mean and SD cannot give -----------------------

    @Test
    fun `median lands in the right bin`() {
        val bins = GlucoseHistogram.of((100..199).map { it.toDouble() })
        val median = GlucoseHistogram.median(bins)!!
        assertTrue(median in 145.0..155.0, "median was $median")
    }

    @Test
    fun `percentile bands bracket the distribution`() {
        val bins = GlucoseHistogram.of((60..260).map { it.toDouble() })
        val p10 = GlucoseHistogram.percentile(bins, 0.10)!!
        val p90 = GlucoseHistogram.percentile(bins, 0.90)!!
        assertTrue(p10 < p90)
        assertTrue(p10 in 75.0..90.0, "p10 was $p10")
        assertTrue(p90 in 230.0..250.0, "p90 was $p90")
    }

    @Test
    fun `percentile of an empty histogram is null`() {
        assertNull(GlucoseHistogram.percentile(GlucoseHistogram.empty(), 0.5))
    }

    // -- storage -----------------------------------------------------------

    @Test
    fun `round trips through its packed form`() {
        val bins = GlucoseHistogram.of(listOf(50.0, 50.0, 120.0, 300.0))
        val restored = GlucoseHistogram.fromBytes(GlucoseHistogram.toBytes(bins))
        assertTrue(bins.contentEquals(restored))
    }

    @Test
    fun `packed form is the advertised size`() {
        assertEquals(144, GlucoseHistogram.toBytes(GlucoseHistogram.empty()).size)
    }

    @Test
    fun `decodes a missing or truncated blob as empty rather than throwing`() {
        assertEquals(0, GlucoseHistogram.total(GlucoseHistogram.fromBytes(null)))
        assertEquals(0, GlucoseHistogram.total(GlucoseHistogram.fromBytes(ByteArray(10))))
    }
}

class MomentsTest {

    @Test
    fun `mean and sd match a direct calculation`() {
        val values = listOf(100.0, 120.0, 140.0, 160.0, 180.0)
        val m = Moments.of(values)
        assertEquals(140.0, m.mean!!, 0.001)
        // Population SD of the series above.
        assertEquals(28.284, m.standardDeviation!!, 0.01)
    }

    @Test
    fun `combining parts equals measuring the whole`() {
        val all = (1..100).map { it.toDouble() * 3 }
        val whole = Moments.of(all)
        val combined = Moments.sum(all.chunked(7).map { Moments.of(it) })

        assertEquals(whole.count, combined.count)
        assertEquals(whole.mean!!, combined.mean!!, 1e-9)
        assertEquals(whole.standardDeviation!!, combined.standardDeviation!!, 1e-9)
    }

    @Test
    fun `empty moments report nothing rather than zero`() {
        assertNull(Moments.Empty.mean)
        assertNull(Moments.Empty.standardDeviation)
        assertNull(Moments.Empty.coefficientOfVariationPercent)
    }

    @Test
    fun `coefficient of variation is a percentage of the mean`() {
        val m = Moments.of(listOf(100.0, 100.0, 100.0))
        assertEquals(0.0, m.coefficientOfVariationPercent!!, 0.001)

        val varied = Moments.of(listOf(50.0, 150.0))
        assertEquals(50.0, varied.coefficientOfVariationPercent!!, 0.001)
    }

    @Test
    fun `variance never goes negative through floating point drift`() {
        val m = Moments.of(List(1000) { 300.0 })
        assertTrue(m.variance!! >= 0.0)
    }
}
