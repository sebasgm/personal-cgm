package dev.cgm.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * A projection of where glucose is heading, with a band around it.
 *
 * **Never feeds an alarm and never counts as a reading.** Alarms fire on measured
 * values only (`docs/04-alarms.md` §5), and the chart draws this in a visual
 * language that cannot be mistaken for the trace. A forecast is a guess with an
 * error bar; the moment it is treated as data, every honesty rule in this app
 * stops meaning anything.
 */
data class ForecastPoint(
    /** Milliseconds after the forecast origin. */
    val horizonMillis: Long,
    val valueMgdl: Double,
    val lowMgdl: Double,
    val highMgdl: Double,
) {
    fun timestampMillis(originMillis: Long): Long = originMillis + horizonMillis
}

data class Forecast(
    val originMillis: Long,
    /** Last measured value the projection starts from. */
    val originValueMgdl: Double,
    val points: List<ForecastPoint>,
    val slopeMgdlPerMinute: Double,
    val calibration: ForecastCalibration?,
) {
    val horizonMillis: Long get() = points.lastOrNull()?.horizonMillis ?: 0

    /** True when the band came from this user's own measured error, not a prior. */
    val isPersonalised: Boolean get() = calibration?.isUsable == true
}

/**
 * How wrong this model has actually been, per horizon, for this person.
 *
 * The band is measured rather than assumed. A model that invents its own
 * confidence is claiming to know its error without ever having checked, and on a
 * glucose chart that is worse than no band at all.
 */
data class ForecastCalibration(
    /** Horizon (ms) to the residual range that [bandFraction] of errors fell inside. */
    val residualBands: Map<Long, ClosedFloatingPointRange<Double>>,
    /** Number of backtested forecasts the bands were fitted on. */
    val sampleCount: Int,
    /** Nominal width, e.g. 0.5 for a 50% band. */
    val bandFraction: Double,
    /**
     * Fraction of held-out cases whose actual value fell inside the band.
     *
     * The honest number. Fitted on one stretch of history and measured on a
     * different one, so it is not the tautology that evaluating on the fitting
     * data would give. If this sits near [bandFraction] the band means what it
     * says; far below and the model is overconfident.
     */
    val measuredCoverage: Double?,
    val coverageSampleCount: Int,
) {
    val isUsable: Boolean get() = sampleCount >= MIN_SAMPLES

    /** Whether the band is honest: measured coverage close to what it claims. */
    val isWellCalibrated: Boolean?
        get() = measuredCoverage?.let { abs(it - bandFraction) <= CALIBRATION_TOLERANCE }

    companion object {
        /** Below this the bands are noise fitted to a handful of cases. */
        const val MIN_SAMPLES = 200
        const val CALIBRATION_TOLERANCE = 0.10
    }
}

object ForecastModel {

    /** Matches the two-hour projection the Accu-Chek app popularised. */
    val DEFAULT_HORIZON_MILLIS = 2.hours.inWholeMilliseconds
    val STEP_MILLIS = 5.minutes.inWholeMilliseconds

    /** How far back the slope is estimated over. */
    val SLOPE_WINDOW_MILLIS = 20.minutes.inWholeMilliseconds

    /**
     * Timescale over which the current trend is assumed to persist.
     *
     * Glucose mean-reverts; a straight line drawn from a steep rise reaches
     * implausible numbers within the hour. Displacement saturates at
     * `slope × tau`, so a 2 mg/dL/min rise projects to about +90 rather than
     * +240 over two hours.
     */
    val DAMPING_TAU_MILLIS = 45.minutes.inWholeMilliseconds

    /** Fewer points than this and a slope is being read out of noise. */
    const val MIN_READINGS_FOR_SLOPE = 4

    /**
     * Robust slope in mg/dL per minute, by Theil–Sen.
     *
     * The median of pairwise slopes, so one bad reading at the end of the window
     * cannot swing the projection — which matters because the most recent reading
     * is both the most influential and the least corroborated.
     */
    fun slopeMgdlPerMinute(readings: List<GlucoseReading>, nowMillis: Long): Double? {
        val window = readings
            .filter { it.timestampMillis in (nowMillis - SLOPE_WINDOW_MILLIS)..nowMillis }
            .sortedBy { it.timestampMillis }
        if (window.size < MIN_READINGS_FOR_SLOPE) return null

        val slopes = ArrayList<Double>()
        for (i in window.indices) {
            for (j in i + 1 until window.size) {
                val dt = (window[j].timestampMillis - window[i].timestampMillis) / 60_000.0
                if (dt <= 0) continue
                slopes += (window[j].valueMgdl - window[i].valueMgdl) / dt
            }
        }
        if (slopes.isEmpty()) return null
        slopes.sort()
        return median(slopes)
    }

    /** Displacement from the origin after [horizonMillis], with damping applied. */
    fun displacement(slopeMgdlPerMinute: Double, horizonMillis: Long): Double {
        val tauMinutes = DAMPING_TAU_MILLIS / 60_000.0
        val horizonMinutes = horizonMillis / 60_000.0
        return slopeMgdlPerMinute * tauMinutes * (1 - exp(-horizonMinutes / tauMinutes))
    }

    fun forecast(
        readings: List<GlucoseReading>,
        nowMillis: Long,
        calibration: ForecastCalibration? = null,
        horizonMillis: Long = DEFAULT_HORIZON_MILLIS,
    ): Forecast? {
        val origin = readings.filter { it.timestampMillis <= nowMillis }
            .maxByOrNull { it.timestampMillis } ?: return null
        val slope = slopeMgdlPerMinute(readings, nowMillis) ?: return null

        val points = generateSequence(STEP_MILLIS) { it + STEP_MILLIS }
            .takeWhile { it <= horizonMillis }
            .map { horizon ->
                val centre = (origin.valueMgdl + displacement(slope, horizon))
                    .coerceIn(GlucoseThresholds.MIN_PLAUSIBLE_MGDL, GlucoseThresholds.MAX_PLAUSIBLE_MGDL)
                val band = bandFor(calibration, horizon)
                ForecastPoint(
                    horizonMillis = horizon,
                    valueMgdl = centre,
                    lowMgdl = (centre + band.start).coerceAtLeast(GlucoseThresholds.MIN_PLAUSIBLE_MGDL),
                    highMgdl = (centre + band.endInclusive)
                        .coerceAtMost(GlucoseThresholds.MAX_PLAUSIBLE_MGDL),
                )
            }
            .toList()

        return Forecast(
            originMillis = origin.timestampMillis,
            originValueMgdl = origin.valueMgdl,
            points = points,
            slopeMgdlPerMinute = slope,
            calibration = calibration,
        )
    }

    /**
     * Residual range to use at a horizon.
     *
     * With no usable calibration this falls back to a widening prior — deliberately
     * generous, because an uncalibrated band should look uncertain rather than
     * precise. It is replaced by measured error as soon as there is enough history.
     */
    private fun bandFor(
        calibration: ForecastCalibration?,
        horizonMillis: Long,
    ): ClosedFloatingPointRange<Double> {
        if (calibration != null && calibration.isUsable) {
            calibration.residualBands[horizonMillis]?.let { return it }
        }
        val minutes = horizonMillis / 60_000.0
        val spread = PRIOR_BASE_MGDL + PRIOR_PER_MINUTE * minutes
        return -spread..spread
    }

    private const val PRIOR_BASE_MGDL = 8.0
    private const val PRIOR_PER_MINUTE = 0.55

    internal fun median(sorted: List<Double>): Double {
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    internal fun quantile(sorted: List<Double>, fraction: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val index = (fraction.coerceIn(0.0, 1.0) * (sorted.size - 1))
        val lower = index.toInt()
        val upper = minOf(lower + 1, sorted.size - 1)
        val weight = index - lower
        return sorted[lower] * (1 - weight) + sorted[upper] * weight
    }
}

/**
 * Fits the band to measured error, and then checks it on history it did not see.
 *
 * The split is the point. Fitting quantiles and then reporting coverage on the
 * same data always returns the nominal figure, which tells the user nothing about
 * whether the model works.
 */
object ForecastCalibrator {

    /** Backtests are run this far apart, to keep the work bounded. */
    val BACKTEST_STRIDE_MILLIS = 15.minutes.inWholeMilliseconds

    /** A forecast step is only scored if a real reading landed this close to it. */
    val MATCH_TOLERANCE_MILLIS = 3.minutes.inWholeMilliseconds

    fun calibrate(
        history: List<GlucoseReading>,
        bandFraction: Double = 0.5,
        horizonMillis: Long = ForecastModel.DEFAULT_HORIZON_MILLIS,
        /** Newest slice held back from fitting and used to measure coverage. */
        holdoutMillis: Long = 7L * 24 * 60 * 60 * 1000,
    ): ForecastCalibration {
        val sorted = history.sortedBy { it.timestampMillis }
        if (sorted.size < 2) return empty(bandFraction)

        val newest = sorted.last().timestampMillis
        val splitAt = newest - holdoutMillis
        val fitting = sorted.filter { it.timestampMillis < splitAt }
        val holdout = sorted.filter { it.timestampMillis >= splitAt }

        // Not enough history to hold anything back: fit on everything and report
        // no coverage rather than a coverage figure that is really self-assessment.
        val (fitSet, testSet) =
            if (fitting.size < ForecastCalibration.MIN_SAMPLES) sorted to emptyList()
            else fitting to holdout

        val residuals = collectResiduals(fitSet, horizonMillis)
        val total = residuals.values.sumOf { it.size }

        val bands = residuals.mapValues { (_, errors) ->
            val sortedErrors = errors.sorted()
            val lower = (1 - bandFraction) / 2
            ForecastModel.quantile(sortedErrors, lower)..
                ForecastModel.quantile(sortedErrors, 1 - lower)
        }

        val fitted = ForecastCalibration(
            residualBands = bands,
            sampleCount = total,
            bandFraction = bandFraction,
            measuredCoverage = null,
            coverageSampleCount = 0,
        )

        if (testSet.isEmpty() || !fitted.isUsable) return fitted

        val (covered, scored) = measureCoverage(testSet, fitted, horizonMillis)
        return fitted.copy(
            measuredCoverage = if (scored > 0) covered.toDouble() / scored else null,
            coverageSampleCount = scored,
        )
    }

    /** Forecast error at each horizon, over every backtestable origin in [history]. */
    private fun collectResiduals(
        history: List<GlucoseReading>,
        horizonMillis: Long,
    ): Map<Long, MutableList<Double>> {
        val residuals = mutableMapOf<Long, MutableList<Double>>()
        forEachBacktest(history, horizonMillis) { forecast, actualAt ->
            forecast.points.forEach { point ->
                val actual = actualAt(point.timestampMillis(forecast.originMillis)) ?: return@forEach
                residuals.getOrPut(point.horizonMillis) { mutableListOf() }
                    .add(actual - point.valueMgdl)
            }
        }
        return residuals
    }

    private fun measureCoverage(
        history: List<GlucoseReading>,
        calibration: ForecastCalibration,
        horizonMillis: Long,
    ): Pair<Int, Int> {
        var covered = 0
        var scored = 0
        forEachBacktest(history, horizonMillis, calibration) { forecast, actualAt ->
            forecast.points.forEach { point ->
                val actual = actualAt(point.timestampMillis(forecast.originMillis)) ?: return@forEach
                scored++
                if (actual in point.lowMgdl..point.highMgdl) covered++
            }
        }
        return covered to scored
    }

    private inline fun forEachBacktest(
        history: List<GlucoseReading>,
        horizonMillis: Long,
        calibration: ForecastCalibration? = null,
        block: (Forecast, (Long) -> Double?) -> Unit,
    ) {
        if (history.isEmpty()) return
        val byTime = history.sortedBy { it.timestampMillis }
        val first = byTime.first().timestampMillis
        val last = byTime.last().timestampMillis

        // Nearest measured reading to an instant, or null if none is close enough.
        val actualAt: (Long) -> Double? = { at ->
            byTime.minByOrNull { abs(it.timestampMillis - at) }
                ?.takeIf { abs(it.timestampMillis - at) <= MATCH_TOLERANCE_MILLIS }
                ?.valueMgdl
        }

        var origin = first + ForecastModel.SLOPE_WINDOW_MILLIS
        while (origin + horizonMillis <= last) {
            val forecast = ForecastModel.forecast(
                readings = byTime.filter { it.timestampMillis <= origin },
                nowMillis = origin,
                calibration = calibration,
                horizonMillis = horizonMillis,
            )
            if (forecast != null) block(forecast, actualAt)
            origin += BACKTEST_STRIDE_MILLIS
        }
    }

    private fun empty(bandFraction: Double) = ForecastCalibration(
        residualBands = emptyMap(),
        sampleCount = 0,
        bandFraction = bandFraction,
        measuredCoverage = null,
        coverageSampleCount = 0,
    )
}
