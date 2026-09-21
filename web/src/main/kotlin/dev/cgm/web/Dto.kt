package dev.cgm.web

import dev.cgm.core.ContinuityAnalyzer
import dev.cgm.core.Freshness
import dev.cgm.core.FreshnessPolicy
import dev.cgm.core.GlucoseReading
import dev.cgm.core.GlucoseStatistics
import dev.cgm.core.SourceResult
import dev.cgm.core.StatisticsCalculator
import dev.cgm.core.Zone
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class ErrorResponse(val error: String, val needsUser: Boolean = false)

@Serializable
data class PointDto(val t: Long, val v: Double)

@Serializable
data class ThresholdsDto(
    val urgentLow: Double,
    val low: Double,
    val high: Double,
    val veryHigh: Double,
)

@Serializable
data class StatsDto(
    val readingCount: Int,
    val mean: Double?,
    val coverage: Double,
    val reliable: Boolean,
    val gmi: Double?,
    val a1c: Double?,
    /** Zone name to fraction of time, so the browser does not re-derive zones. */
    val zones: Map<String, Double>,
)

/**
 * Everything one page render needs, in a single response.
 *
 * Assembled on the server rather than shipped raw because the classification,
 * freshness and statistics rules already exist in `:core` and are tested there.
 * Re-implementing them in JavaScript would create a second set of thresholds that
 * could disagree with the phone's — on a screen showing whether someone is low.
 */
@Serializable
data class DashboardDto(
    val valueMgdl: Double,
    val displayValue: String,
    val unit: String,
    val trend: String,
    val trendGlyph: String,
    val delta: String?,
    val timestampMillis: Long,
    val ageSeconds: Long,
    val freshness: String,
    val zone: String,
    val thresholds: ThresholdsDto,
    val history: List<PointDto>,
    val stats: StatsDto,
    /** Largest gap in the window, so a sparse chart explains itself. */
    val largestGapMinutes: Long,
    val sensorDay: Int?,
    val serverTimeMillis: Long,
)

fun buildDashboard(
    result: SourceResult,
    nowMillis: Long,
    policy: FreshnessPolicy = FreshnessPolicy.Default,
): DashboardDto {
    val snapshot = result.snapshot
    val thresholds = snapshot.thresholds
    val readings: List<GlucoseReading> =
        (result.history + snapshot.reading).distinctBy { it.timestampMillis }
            .sortedBy { it.timestampMillis }

    val windowStart = readings.firstOrNull()?.timestampMillis ?: nowMillis
    val stats: GlucoseStatistics = StatisticsCalculator.compute(
        readings = readings,
        thresholds = thresholds,
        windowStartMillis = windowStart,
        windowEndMillis = nowMillis,
    )
    val continuity = ContinuityAnalyzer.analyse(readings, windowStart, nowMillis)

    return DashboardDto(
        valueMgdl = snapshot.reading.valueMgdl,
        displayValue = snapshot.formattedValue(),
        unit = snapshot.unit.suffix,
        trend = snapshot.reading.trend.name,
        trendGlyph = snapshot.reading.trend.glyph,
        delta = snapshot.formattedDelta(),
        timestampMillis = snapshot.reading.timestampMillis,
        ageSeconds = snapshot.reading.ageMillis(nowMillis) / 1000,
        freshness = policy.evaluate(snapshot.reading, nowMillis).name,
        zone = snapshot.zone().name,
        thresholds = ThresholdsDto(
            urgentLow = thresholds.urgentLowMgdl,
            low = thresholds.lowMgdl,
            high = thresholds.highMgdl,
            veryHigh = thresholds.veryHighMgdl,
        ),
        history = readings.map { PointDto(it.timestampMillis, it.valueMgdl) },
        stats = StatsDto(
            readingCount = stats.readingCount,
            mean = stats.meanMgdl,
            coverage = stats.coverage,
            reliable = stats.isReliable,
            gmi = stats.gmiPercent,
            a1c = stats.estimatedA1cPercent,
            zones = Zone.entries.associate { zone ->
                zone.name to (stats.zoneFractions[zone] ?: 0.0)
            },
        ),
        largestGapMinutes = continuity.largestGapMillis / 60_000,
        sensorDay = result.sensor?.dayOfSession(nowMillis),
        serverTimeMillis = nowMillis,
    )
}

/** Freshness names the browser styles against, kept in one place. */
val FRESHNESS_NAMES = Freshness.entries.map { it.name }
