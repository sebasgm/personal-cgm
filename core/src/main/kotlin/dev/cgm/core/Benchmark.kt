package dev.cgm.core

import kotlin.math.sqrt
import kotlin.time.Duration.Companion.minutes

/** How one model did at one horizon, on one person's history. */
data class HorizonScore(
    val horizonMillis: Long,
    val predictionCount: Int,
    val rmse: Double,
    val meanAbsoluteError: Double,
    /** Zone counts, so the clinical picture is not reduced to a single number. */
    val zones: Map<ClarkeZone, Int>,
) {
    /** Fraction of predictions that would have led to the wrong action. */
    val unsafeFraction: Double
        get() = if (predictionCount == 0) 0.0
        else zones.entries.filter { it.key.isUnsafe }.sumOf { it.value }.toDouble() / predictionCount

    /** Fraction in zone A: clinically accurate. */
    val accurateFraction: Double
        get() = if (predictionCount == 0) 0.0
        else (zones[ClarkeZone.A] ?: 0).toDouble() / predictionCount
}

data class ModelScore(
    val modelId: String,
    val byHorizon: Map<Long, HorizonScore>,
) {
    fun at(horizonMillis: Long): HorizonScore? = byHorizon[horizonMillis]
}

/**
 * The result of asking "is this model worth anything on my data".
 *
 * [baselineId] is not decoration. A model is only interesting relative to
 * carrying the last value forward, and reporting the comparison rather than the
 * raw error is the whole point of the exercise.
 */
data class BenchmarkResult(
    val models: List<ModelScore>,
    val baselineId: String,
    val horizonsMillis: List<Long>,
) {
    fun improvementOverBaseline(modelId: String, horizonMillis: Long): Double? {
        val baseline = models.firstOrNull { it.modelId == baselineId }?.at(horizonMillis) ?: return null
        val model = models.firstOrNull { it.modelId == modelId }?.at(horizonMillis) ?: return null
        return baseline.rmse - model.rmse
    }

    /** Best by RMSE, which is not always the one to ship — check the unsafe fraction. */
    fun bestAt(horizonMillis: Long): ModelScore? =
        models.filter { (it.at(horizonMillis)?.predictionCount ?: 0) > 0 }
            .minByOrNull { it.at(horizonMillis)!!.rmse }
}

/**
 * Walk-forward evaluation of predictors against real history.
 *
 * Stands at a past instant, shows a model only what was known then, and scores
 * what it said against what actually happened. Nothing is ever evaluated on data
 * it was fitted to — which is the difference between measuring a model and
 * flattering it.
 *
 * This exists because no CGM app can tell you whether its own prediction beats
 * assuming the current value persists. That question is answerable on one
 * person's data, and answering it is the precondition for any model work here.
 */
object ForecastBenchmark {

    val DEFAULT_HORIZONS = listOf(
        15.minutes.inWholeMilliseconds,
        30.minutes.inWholeMilliseconds,
        60.minutes.inWholeMilliseconds,
        120.minutes.inWholeMilliseconds,
    )

    /** History a model may look back over when predicting. */
    val CONTEXT_MILLIS = 3 * 60 * 60 * 1000L

    /** How far apart evaluation points are placed, to keep the work bounded. */
    val STRIDE_MILLIS = 15.minutes.inWholeMilliseconds

    /** A prediction is only scored against a reading this close to its target. */
    val MATCH_TOLERANCE_MILLIS = 3.minutes.inWholeMilliseconds

    fun defaultModels(): List<GlucosePredictor> = listOf(
        ZeroOrderHold,
        LinearExtrapolation(),
        DampedTrend(),
        AutoRegressive(order = 3),
    )

    fun run(
        history: List<GlucoseReading>,
        models: List<GlucosePredictor> = defaultModels(),
        horizonsMillis: List<Long> = DEFAULT_HORIZONS,
    ): BenchmarkResult {
        val sorted = history.sortedBy { it.timestampMillis }
        val scores = models.map { model ->
            ModelScore(model.id, scoreModel(model, sorted, horizonsMillis))
        }
        return BenchmarkResult(scores, ZeroOrderHold.id, horizonsMillis)
    }

    private fun scoreModel(
        model: GlucosePredictor,
        sorted: List<GlucoseReading>,
        horizons: List<Long>,
    ): Map<Long, HorizonScore> {
        if (sorted.size < 2) return emptyMap()

        val errors = horizons.associateWith { mutableListOf<Double>() }
        val zones = horizons.associateWith { mutableMapOf<ClarkeZone, Int>() }

        val first = sorted.first().timestampMillis
        val last = sorted.last().timestampMillis
        val longest = horizons.max()

        var origin = first + CONTEXT_MILLIS
        while (origin + longest <= last) {
            val context = sorted.filter {
                it.timestampMillis in (origin - CONTEXT_MILLIS)..origin
            }
            if (context.size >= 4) {
                horizons.forEach { horizon ->
                    val predicted = model.predict(context, horizon) ?: return@forEach
                    val actual = actualAt(sorted, origin + horizon) ?: return@forEach

                    errors.getValue(horizon) += (predicted - actual)
                    val zone = ClarkeErrorGrid.zoneOf(actual, predicted)
                    zones.getValue(horizon).merge(zone, 1, Int::plus)
                }
            }
            origin += STRIDE_MILLIS
        }

        return horizons.associateWith { horizon ->
            val e = errors.getValue(horizon)
            HorizonScore(
                horizonMillis = horizon,
                predictionCount = e.size,
                rmse = if (e.isEmpty()) 0.0 else sqrt(e.sumOf { it * it } / e.size),
                meanAbsoluteError = if (e.isEmpty()) 0.0 else e.sumOf { kotlin.math.abs(it) } / e.size,
                zones = zones.getValue(horizon).toMap(),
            )
        }
    }

    /**
     * The reading nearest an instant, or null when nothing landed close enough.
     *
     * Scoring against a reading twenty minutes off target would measure the gap,
     * not the model.
     */
    private fun actualAt(sorted: List<GlucoseReading>, at: Long): Double? =
        sorted.minByOrNull { kotlin.math.abs(it.timestampMillis - at) }
            ?.takeIf { kotlin.math.abs(it.timestampMillis - at) <= MATCH_TOLERANCE_MILLIS }
            ?.valueMgdl
}
