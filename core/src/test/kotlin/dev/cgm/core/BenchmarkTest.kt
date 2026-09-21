package dev.cgm.core

import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class PredictorTest {

    private val now = 1_800_000_000_000L

    private fun series(minutes: Int, valueAt: (Int) -> Double) =
        (0 until minutes).map { ago ->
            GlucoseReading(valueMgdl = valueAt(ago), timestampMillis = now - ago * 60_000L)
        }

    private val flat = series(60) { 120.0 }
    private val rising = series(60) { ago -> 120.0 - 2.0 * ago }

    @Test
    fun `zero-order hold returns the last reading whatever the horizon`() {
        assertEquals(120.0, ZeroOrderHold.predict(rising, 30.minutes.inWholeMilliseconds))
        assertEquals(120.0, ZeroOrderHold.predict(rising, 2.hours.inWholeMilliseconds))
    }

    @Test
    fun `linear extrapolation keeps extending, which is its weakness`() {
        val thirty = LinearExtrapolation().predict(rising, 30.minutes.inWholeMilliseconds)!!
        val twoHours = LinearExtrapolation().predict(rising, 2.hours.inWholeMilliseconds)!!
        assertEquals(180.0, thirty, 3.0)
        // +2/min unchecked reaches an implausible number, which is the point of
        // having a damped alternative to compare it against.
        assertTrue(twoHours > 340.0, "linear reached $twoHours")
    }

    @Test
    fun `damped trend saturates where linear does not`() {
        val linear = LinearExtrapolation().predict(rising, 2.hours.inWholeMilliseconds)!!
        val damped = DampedTrend().predict(rising, 2.hours.inWholeMilliseconds)!!
        assertTrue(damped < linear, "damped $damped should be under linear $linear")
        assertTrue(damped > 120.0, "damped should still be rising")
    }

    @Test
    fun `every predictor agrees on a flat series`() {
        listOf(ZeroOrderHold, LinearExtrapolation(), DampedTrend(), AutoRegressive())
            .forEach { model ->
                val predicted = model.predict(flat, 30.minutes.inWholeMilliseconds)
                assertNotNull(predicted, "${model.id} gave up on a flat series")
                assertEquals(120.0, predicted, 2.0, "${model.id} drifted off a flat series")
            }
    }

    @Test
    fun `autoregressive learns a steady rise from the data`() {
        val predicted = AutoRegressive().predict(rising, 30.minutes.inWholeMilliseconds)
        assertNotNull(predicted)
        // Fitted, not assumed: it should land near the linear answer on a series
        // that genuinely is linear.
        assertTrue(abs(predicted - 180.0) < 25.0, "AR predicted $predicted")
    }

    @Test
    fun `predictors decline rather than guess from too little history`() {
        val scraps = series(2) { 120.0 }
        assertNull(LinearExtrapolation().predict(scraps, 30.minutes.inWholeMilliseconds))
        assertNull(AutoRegressive().predict(scraps, 30.minutes.inWholeMilliseconds))
    }
}

class ResampleTest {
    private val now = 1_800_000_000_000L

    @Test
    fun `puts readings onto an even grid`() {
        val readings = (0 until 24).map {
            GlucoseReading(100.0 + it, now - (24 - it) * 5 * 60_000L)
        }
        val grid = Resample.toGrid(readings, 5.minutes.inWholeMilliseconds)
        assertNotNull(grid)
        assertEquals(24, grid.size)
    }

    @Test
    fun `refuses to interpolate across a long gap`() {
        // Inventing readings to feed a model is how a model learns from data that
        // never existed.
        val readings = listOf(
            GlucoseReading(100.0, now - 3.hours.inWholeMilliseconds),
            GlucoseReading(180.0, now),
        )
        assertNull(Resample.toGrid(readings, 5.minutes.inWholeMilliseconds))
    }
}

class ClarkeErrorGridTest {

    @Test
    fun `close predictions are clinically accurate`() {
        assertEquals(ClarkeZone.A, ClarkeErrorGrid.zoneOf(referenceMgdl = 150.0, predictedMgdl = 160.0))
        assertEquals(ClarkeZone.A, ClarkeErrorGrid.zoneOf(referenceMgdl = 250.0, predictedMgdl = 220.0))
    }

    @Test
    fun `agreeing that it is low is accurate even when the numbers differ`() {
        assertEquals(ClarkeZone.A, ClarkeErrorGrid.zoneOf(referenceMgdl = 60.0, predictedMgdl = 45.0))
    }

    @Test
    fun `confusing a low for a high is the worst zone`() {
        assertEquals(ClarkeZone.E, ClarkeErrorGrid.zoneOf(referenceMgdl = 60.0, predictedMgdl = 200.0))
        assertEquals(ClarkeZone.E, ClarkeErrorGrid.zoneOf(referenceMgdl = 250.0, predictedMgdl = 50.0))
    }

    @Test
    fun `missing a real high is dangerous`() {
        assertEquals(ClarkeZone.D, ClarkeErrorGrid.zoneOf(referenceMgdl = 300.0, predictedMgdl = 150.0))
    }

    @Test
    fun `unsafe covers exactly C D and E`() {
        assertTrue(!ClarkeZone.A.isUnsafe && !ClarkeZone.B.isUnsafe)
        assertTrue(ClarkeZone.C.isUnsafe && ClarkeZone.D.isUnsafe && ClarkeZone.E.isUnsafe)
    }
}

class ForecastBenchmarkTest {

    private val now = 1_800_000_000_000L

    /** A day of readings a minute, following a slow wave. */
    private fun day(amplitude: Double = 50.0): List<GlucoseReading> =
        (0 until 24 * 60).map { i ->
            GlucoseReading(
                valueMgdl = 150 + amplitude * kotlin.math.sin(i / 120.0),
                timestampMillis = now - (24 * 60 - i) * 60_000L,
            )
        }

    private val result = ForecastBenchmark.run(day())

    @Test
    fun `scores every model at every horizon`() {
        assertEquals(4, result.models.size)
        result.models.forEach { model ->
            ForecastBenchmark.DEFAULT_HORIZONS.forEach { horizon ->
                assertNotNull(model.at(horizon), "${model.modelId} missing $horizon")
            }
        }
    }

    @Test
    fun `actually evaluates something`() {
        val hold = result.models.first { it.modelId == ZeroOrderHold.id }
        assertTrue(hold.at(30.minutes.inWholeMilliseconds)!!.predictionCount > 10)
    }

    @Test
    fun `error grows with horizon, as it must`() {
        val hold = result.models.first { it.modelId == ZeroOrderHold.id }
        val short = hold.at(15.minutes.inWholeMilliseconds)!!.rmse
        val long = hold.at(2.hours.inWholeMilliseconds)!!.rmse
        assertTrue(long > short, "RMSE did not grow: $short then $long")
    }

    @Test
    fun `a trending model beats carrying the value forward on a trending series`() {
        // The whole reason the baseline is there: if this does not hold, the
        // sophistication is not earning its place.
        val improvement = result.improvementOverBaseline(
            DampedTrend().id,
            30.minutes.inWholeMilliseconds,
        )
        assertNotNull(improvement)
        assertTrue(improvement > 0, "damped did not beat hold: $improvement mg/dL")
    }

    @Test
    fun `reports the clinical picture, not only the average error`() {
        val score = result.models.first { it.modelId == ZeroOrderHold.id }
            .at(30.minutes.inWholeMilliseconds)!!
        assertTrue(score.zones.isNotEmpty())
        assertTrue(score.accurateFraction > 0.5, "zone A was ${score.accurateFraction}")
        assertTrue(score.unsafeFraction <= 1.0)
    }

    @Test
    fun `names a best model per horizon`() {
        assertNotNull(result.bestAt(30.minutes.inWholeMilliseconds))
    }

    @Test
    fun `history too short to evaluate yields empty scores rather than nonsense`() {
        val thin = (0 until 5).map { GlucoseReading(120.0, now - it * 60_000L) }
        val scores = ForecastBenchmark.run(thin)
        scores.models.forEach { model ->
            model.byHorizon.values.forEach { assertEquals(0, it.predictionCount) }
        }
    }
}
