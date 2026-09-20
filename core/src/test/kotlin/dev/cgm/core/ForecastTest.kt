package dev.cgm.core

import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class ForecastModelTest {

    private val now = 1_800_000_000_000L

    /** Readings every minute ending at [now], following [valueAt] in minutes-ago. */
    private fun series(minutes: Int, valueAt: (Int) -> Double): List<GlucoseReading> =
        (0 until minutes).map { ago ->
            GlucoseReading(
                valueMgdl = valueAt(ago),
                timestampMillis = now - ago * 60_000L,
            )
        }

    @Test
    fun `flat readings forecast flat`() {
        val forecast = ForecastModel.forecast(series(30) { 120.0 }, now)!!
        assertEquals(0.0, forecast.slopeMgdlPerMinute, 0.001)
        forecast.points.forEach {
            assertEquals(120.0, it.valueMgdl, 0.001)
        }
    }

    @Test
    fun `a steady rise is picked up`() {
        // +2 mg/dL per minute.
        val forecast = ForecastModel.forecast(series(30) { ago -> 120.0 - 2.0 * ago }, now)!!
        assertEquals(2.0, forecast.slopeMgdlPerMinute, 0.05)
        assertTrue(forecast.points.first().valueMgdl > 120.0)
    }

    @Test
    fun `damping keeps a steep rise plausible over two hours`() {
        val forecast = ForecastModel.forecast(series(30) { ago -> 120.0 - 2.0 * ago }, now)!!
        val twoHours = forecast.points.last()

        // Undamped, +2/min for 120 min would be +240, reaching 360.
        assertTrue(twoHours.valueMgdl < 240.0, "reached ${twoHours.valueMgdl} — damping failed")
        assertTrue(twoHours.valueMgdl > 180.0, "reached ${twoHours.valueMgdl} — over-damped")
    }

    @Test
    fun `displacement saturates rather than growing without bound`() {
        val oneHour = ForecastModel.displacement(2.0, 1.hours.inWholeMilliseconds)
        val tenHours = ForecastModel.displacement(2.0, 10.hours.inWholeMilliseconds)
        assertTrue(tenHours < oneHour * 2, "displacement is still growing linearly")
    }

    @Test
    fun `one bad reading does not swing the projection`() {
        val clean = series(30) { 120.0 }
        val withSpike = clean.toMutableList().also {
            it[0] = it[0].copy(valueMgdl = 260.0)
        }
        val forecast = ForecastModel.forecast(withSpike, now)!!
        // Theil-Sen takes the median of pairwise slopes, so a single outlier
        // cannot dominate the way least squares would let it.
        assertTrue(
            abs(forecast.slopeMgdlPerMinute) < 3.0,
            "slope was ${forecast.slopeMgdlPerMinute} — outlier dominated",
        )
    }

    @Test
    fun `refuses to forecast from too few readings`() {
        assertNull(ForecastModel.forecast(series(2) { 120.0 }, now))
    }

    @Test
    fun `refuses to forecast with no readings at all`() {
        assertNull(ForecastModel.forecast(emptyList(), now))
    }

    @Test
    fun `stays inside plausible sensor bounds`() {
        val crashing = ForecastModel.forecast(series(30) { ago -> 90.0 + 4.0 * ago }, now)!!
        crashing.points.forEach {
            assertTrue(it.valueMgdl >= GlucoseThresholds.MIN_PLAUSIBLE_MGDL)
            assertTrue(it.highMgdl <= GlucoseThresholds.MAX_PLAUSIBLE_MGDL)
        }
    }

    @Test
    fun `the band widens with horizon`() {
        val forecast = ForecastModel.forecast(series(30) { 120.0 }, now)!!
        val widths = forecast.points.map { it.highMgdl - it.lowMgdl }
        assertTrue(
            widths.zipWithNext().all { (a, b) -> b >= a },
            "band must not narrow further out: $widths",
        )
    }

    @Test
    fun `an uncalibrated forecast says so`() {
        val forecast = ForecastModel.forecast(series(30) { 120.0 }, now)!!
        assertTrue(!forecast.isPersonalised)
    }

    @Test
    fun `steps run to the requested horizon`() {
        val forecast = ForecastModel.forecast(
            series(30) { 120.0 },
            now,
            horizonMillis = 30.minutes.inWholeMilliseconds,
        )!!
        assertEquals(30.minutes.inWholeMilliseconds, forecast.horizonMillis)
        assertEquals(6, forecast.points.size)
    }
}

class ForecastCalibratorTest {

    private val now = 1_800_000_000_000L

    /** A fortnight of readings a minute, following a slow sine plus noise. */
    private fun history(days: Int, noise: Double = 0.0): List<GlucoseReading> {
        val minutes = days * 24 * 60
        var seed = 42L
        fun rand(): Double {
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            return ((seed ushr 11).toDouble() / (1L shl 53).toDouble()) - 0.5
        }
        return (0 until minutes).map { i ->
            val t = now - (minutes - i) * 60_000L
            val base = 140 + 50 * kotlin.math.sin(i / 180.0)
            GlucoseReading(valueMgdl = base + rand() * noise, timestampMillis = t)
        }
    }

    @Test
    fun `too little history yields an unusable calibration`() {
        val calibration = ForecastCalibrator.calibrate(history(days = 0).ifEmpty { emptyList() })
        assertTrue(!calibration.isUsable)
        assertNull(calibration.measuredCoverage)
    }

    @Test
    fun `fits residual bands from real error`() {
        val calibration = ForecastCalibrator.calibrate(
            history(days = 3, noise = 10.0),
            horizonMillis = 30.minutes.inWholeMilliseconds,
            holdoutMillis = 1L * 24 * 60 * 60 * 1000,
        )
        assertTrue(calibration.isUsable, "only ${calibration.sampleCount} samples")
        assertTrue(calibration.residualBands.isNotEmpty())

        // Error grows with horizon, so the band must too.
        val widths = calibration.residualBands.toSortedMap()
            .map { (_, range) -> range.endInclusive - range.start }
        assertTrue(widths.last() > widths.first(), "bands did not widen: $widths")
    }

    @Test
    fun `coverage is measured on history the bands were not fitted to`() {
        val calibration = ForecastCalibrator.calibrate(
            history(days = 4, noise = 10.0),
            bandFraction = 0.5,
            horizonMillis = 30.minutes.inWholeMilliseconds,
            holdoutMillis = 1L * 24 * 60 * 60 * 1000,
        )
        val coverage = calibration.measuredCoverage
        assertTrue(coverage != null, "no coverage was measured")
        assertTrue(calibration.coverageSampleCount > 0)
        // On a held-out slice of the same process, a 50% band should land in a
        // broad neighbourhood of 50%. Anything outside this means the split is
        // not doing what it claims.
        assertTrue(coverage!! in 0.15..0.95, "coverage was $coverage")
    }

    @Test
    fun `a well calibrated band reports itself as such`() {
        val calibration = ForecastCalibration(
            residualBands = mapOf(300_000L to -10.0..10.0),
            sampleCount = 1000,
            bandFraction = 0.5,
            measuredCoverage = 0.52,
            coverageSampleCount = 500,
        )
        assertEquals(true, calibration.isWellCalibrated)
    }

    @Test
    fun `an overconfident band is flagged`() {
        val calibration = ForecastCalibration(
            residualBands = mapOf(300_000L to -2.0..2.0),
            sampleCount = 1000,
            bandFraction = 0.5,
            measuredCoverage = 0.20,
            coverageSampleCount = 500,
        )
        assertEquals(false, calibration.isWellCalibrated)
    }

    @Test
    fun `calibrated forecasts use the measured band instead of the prior`() {
        val readings = history(days = 3, noise = 10.0)
        val calibration = ForecastCalibrator.calibrate(
            readings,
            horizonMillis = 30.minutes.inWholeMilliseconds,
            holdoutMillis = 1L * 24 * 60 * 60 * 1000,
        )
        val forecast = ForecastModel.forecast(
            readings, now, calibration, 30.minutes.inWholeMilliseconds,
        )!!
        assertTrue(forecast.isPersonalised)
    }
}
