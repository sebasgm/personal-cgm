package dev.cgm.core

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Weighting a strip against the sensor beside it.
 *
 * Three properties carry the whole feature, and each of them is a way the blend could
 * quietly become dishonest:
 *
 *  - a pair is two measurements of one state, so the window is one sensor cadence and
 *    nothing outside it is fused at all;
 *  - a blend only means something on a flat trace, because the two instruments are
 *    measuring different fluids and the lag between them grows with the rate of change;
 *  - the alarm engine never sees a blend, in either direction.
 *
 * The third is the one with teeth. A blend of a low strip and a higher sensor reads
 * *above* the measurement it was supposed to be anchored to, which is the single way
 * this feature could hurt someone.
 */
class FusionTest {

    private val start: Long = 1_790_000_000_000L

    private fun minutes(n: Int): Long = n * 60_000L

    private fun strip(mgdl: Double, atMillis: Long = start) =
        BloodTest(mgdl = mgdl, takenAtMillis = atMillis)

    /**
     * A snapshot with a delta expressed as a rate, so a test can say "flat" or
     * "climbing at 3" without doing the arithmetic in its own head.
     */
    private fun snapshot(
        sensorMgdl: Double,
        ratePerMinute: Double? = 0.0,
        atMillis: Long = start,
        spanMillis: Long = minutes(5),
        thresholds: GlucoseThresholds = GlucoseThresholds.Default,
    ) = GlucoseSnapshot(
        reading = GlucoseReading(valueMgdl = sensorMgdl, timestampMillis = atMillis),
        thresholds = thresholds,
        delta = ratePerMinute?.let {
            GlucoseDelta(
                valueMgdl = it * (spanMillis / 60_000.0),
                spanMillis = spanMillis,
            )
        },
    )

    // -- pairing ------------------------------------------------------------

    @Test
    fun `with no strip nearby the sensor value passes through untouched`() {
        val fused = GlucoseFusion.of(snapshot(120.0), emptyList())

        assertEquals(FusionBasis.SENSOR, fused.basis)
        assertEquals(120.0, fused.mgdl)
        assertEquals(120.0, fused.alarmMgdl)
        assertNull(fused.strip, "nothing to pair with")
        assertNull(fused.gapMillis)
        assertTrue(!fused.isFused, "an unfused value must not claim to be one")
    }

    @Test
    fun `a strip outside the match window is not a pair`() {
        val justOutside = strip(150.0, start + GlucoseFusion.MATCH_WINDOW_MILLIS + 1)
        val fused = GlucoseFusion.of(snapshot(120.0), listOf(justOutside))

        assertEquals(FusionBasis.SENSOR, fused.basis)
        assertEquals(120.0, fused.mgdl, "the sensor stands alone")
    }

    @Test
    fun `a strip exactly on the window edge still pairs`() {
        val onEdge = strip(150.0, start + GlucoseFusion.MATCH_WINDOW_MILLIS)
        val fused = GlucoseFusion.of(snapshot(120.0), listOf(onEdge))

        assertEquals(FusionBasis.BLEND, fused.basis)
        assertEquals(GlucoseFusion.MATCH_WINDOW_MILLIS, fused.gapMillis)
    }

    @Test
    fun `the match window is the one the accuracy card uses, not a second opinion`() {
        assertEquals(SensorAgreement.MATCH_WINDOW_MILLIS, GlucoseFusion.MATCH_WINDOW_MILLIS)
    }

    @Test
    fun `the nearest strip wins, in either direction`() {
        val earlier = strip(200.0, start - minutes(4))
        val later = strip(150.0, start + minutes(1))

        val fused = GlucoseFusion.of(snapshot(120.0), listOf(earlier, later))

        assertEquals(150.0, fused.stripMgdl, "one minute after beats four minutes before")
        assertEquals(minutes(1), fused.gapMillis)
    }

    @Test
    fun `an implausible strip is never fused with anything`() {
        val nonsense = strip(BloodTest.MAX_MGDL + 1, start)
        val fused = GlucoseFusion.of(snapshot(120.0), listOf(nonsense))

        assertEquals(FusionBasis.SENSOR, fused.basis)
        assertEquals(120.0, fused.mgdl)
    }

    // -- blending ------------------------------------------------------------

    @Test
    fun `on a flat trace the two are weighted sixty forty toward the strip`() {
        val fused = GlucoseFusion.of(snapshot(100.0, ratePerMinute = 0.0), listOf(strip(150.0)))

        assertEquals(FusionBasis.BLEND, fused.basis)
        // 150*0.6 + 100*0.4
        assertEquals(130.0, fused.mgdl, 1e-9)
        assertEquals(0.6, GlucoseFusion.STRIP_WEIGHT)
        assertEquals(0.4, GlucoseFusion.SENSOR_WEIGHT, 1e-9)
    }

    @Test
    fun `the blend always lands nearer the strip than the sensor`() {
        val fused = GlucoseFusion.of(snapshot(100.0), listOf(strip(200.0)))
        val toStrip = kotlin.math.abs(fused.mgdl - 200.0)
        val toSensor = kotlin.math.abs(fused.mgdl - 100.0)

        assertTrue(toStrip < toSensor, "the strip is the reference, so it must pull harder")
    }

    @Test
    fun `a moving trace takes the strip alone`() {
        val fused = GlucoseFusion.of(snapshot(100.0, ratePerMinute = 3.0), listOf(strip(150.0)))

        assertEquals(FusionBasis.STRIP, fused.basis)
        assertEquals(150.0, fused.mgdl, "no average of two different moments")
    }

    @Test
    fun `falling counts as moving just as rising does`() {
        val fused = GlucoseFusion.of(snapshot(100.0, ratePerMinute = -3.0), listOf(strip(150.0)))

        assertEquals(FusionBasis.STRIP, fused.basis)
    }

    @Test
    fun `the flat threshold is inclusive at its edge`() {
        val onEdge = GlucoseFusion.of(
            snapshot(100.0, ratePerMinute = GlucoseFusion.FLAT_RATE_MGDL_PER_MINUTE),
            listOf(strip(150.0)),
        )
        val justPast = GlucoseFusion.of(
            snapshot(100.0, ratePerMinute = GlucoseFusion.FLAT_RATE_MGDL_PER_MINUTE + 0.01),
            listOf(strip(150.0)),
        )

        assertEquals(FusionBasis.BLEND, onEdge.basis)
        assertEquals(FusionBasis.STRIP, justPast.basis)
    }

    @Test
    fun `an unknown rate is not flat`() {
        val fused = GlucoseFusion.of(
            snapshot(100.0, ratePerMinute = null),
            listOf(strip(150.0)),
        )

        assertEquals(FusionBasis.STRIP, fused.basis, "no delta is no evidence of one moment")
        assertNull(fused.ratePerMinute)
    }

    @Test
    fun `a long delta is normalised before it is judged flat`() {
        // 12 mg/dL over fifteen minutes is 0.8/min — flat. Read as a five-minute
        // delta it would be 2.4/min, and the blend would be refused for no reason.
        val fused = GlucoseFusion.of(
            snapshot(100.0, ratePerMinute = 0.8, spanMillis = minutes(15)),
            listOf(strip(150.0)),
        )

        assertEquals(FusionBasis.BLEND, fused.basis)
        val rate = assertNotNull(fused.ratePerMinute)
        assertEquals(0.8, rate, 1e-9)
    }

    // -- what the alarm engine is handed -------------------------------------

    @Test
    fun `a blend never softens a hypo`() {
        // The owner's own example: strip 55, sensor 75, blend 63 — higher than the
        // measurement it is anchored to, in the one direction that is dangerous.
        val fused = GlucoseFusion.of(snapshot(75.0), listOf(strip(55.0)))

        assertEquals(63.0, fused.mgdl, 1e-9, "the blend is what it is")
        assertEquals(55.0, fused.alarmMgdl, "but the alarm sees the strip")
        assertTrue(
            fused.alarmMgdl <= fused.mgdl,
            "the alarm value must never be the gentler of the two",
        )
    }

    @Test
    fun `a comfortable strip does not silence a sensor that is reading low`() {
        val fused = GlucoseFusion.of(snapshot(50.0), listOf(strip(90.0)))

        assertEquals(50.0, fused.alarmMgdl, "the worse of the two wins, whichever it is")
    }

    @Test
    fun `within one band the more extreme value is judged`() {
        val low = GlucoseFusion.of(snapshot(68.0), listOf(strip(62.0)))
        assertEquals(62.0, low.alarmMgdl)

        val high = GlucoseFusion.of(snapshot(200.0), listOf(strip(230.0)))
        assertEquals(230.0, high.alarmMgdl)
    }

    @Test
    fun `when the two disagree about direction the strip is the reference`() {
        val thresholds = GlucoseThresholds.Default
        // A strip in the low band against a sensor in the high band: one instrument
        // has failed, and exactly one of the two alarms can be kept.
        assertEquals(
            65.0,
            GlucoseFusion.alarmValue(stripMgdl = 65.0, sensorMgdl = 200.0, thresholds = thresholds),
        )
    }

    @Test
    fun `an in-range pair hands the alarm engine an in-range number`() {
        val fused = GlucoseFusion.of(snapshot(110.0), listOf(strip(120.0)))

        assertTrue(
            GlucoseThresholds.Default.classify(fused.alarmMgdl) == Zone.IN_RANGE,
            "nothing here may invent an alarm",
        )
    }

    @Test
    fun `the alarm value ignores the trace entirely`() {
        // Moving or flat changes what is displayed, never what is judged.
        val flat = GlucoseFusion.of(snapshot(75.0, ratePerMinute = 0.0), listOf(strip(55.0)))
        val moving = GlucoseFusion.of(snapshot(75.0, ratePerMinute = 3.0), listOf(strip(55.0)))

        assertEquals(flat.alarmMgdl, moving.alarmMgdl)
        assertTrue(flat.mgdl != moving.mgdl, "but the displayed figure does differ")
    }

    @Test
    fun `custom thresholds are the ones the severity comparison uses`() {
        // Somebody whose low band starts at 80: a strip of 78 is low for them, and
        // the sensor's 95 must not be the number the engine judges.
        val tight = GlucoseThresholds(urgentLowMgdl = 60.0, lowMgdl = 80.0, highMgdl = 160.0, veryHighMgdl = 220.0)
        val fused = GlucoseFusion.of(snapshot(95.0, thresholds = tight), listOf(strip(78.0)))

        assertEquals(78.0, fused.alarmMgdl)
    }

    // -- what it reports about itself ----------------------------------------

    @Test
    fun `both halves stay reachable beside the fused figure`() {
        val fused = GlucoseFusion.of(snapshot(100.0), listOf(strip(150.0)))

        assertEquals(100.0, fused.sensorMgdl)
        assertEquals(150.0, fused.stripMgdl)
        assertEquals(-50.0, fused.disagreementMgdl, "signed sensor minus strip")
        assertTrue(fused.isFused)
    }

    @Test
    fun `fusion leaves the reading's own age alone`() {
        val old = snapshot(100.0, atMillis = start - minutes(20))
        val fused = GlucoseFusion.of(old, listOf(strip(150.0, start - minutes(20))))

        assertEquals(old.reading, fused.reading, "the timestamp on screen is still the sensor's")
    }
}
