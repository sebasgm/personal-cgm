package dev.cgm.app.data

import dev.cgm.core.DeltaCalculator
import dev.cgm.core.Freshness
import dev.cgm.core.FreshnessPolicy
import dev.cgm.core.GlucoseDelta
import dev.cgm.core.GlucoseReading
import dev.cgm.core.GlucoseSnapshot
import dev.cgm.core.GlucoseStatistics
import dev.cgm.core.GlucoseThresholds
import dev.cgm.core.GlucoseUnit
import dev.cgm.core.StatisticsCalculator
import dev.cgm.core.ThresholdOverrides
import dev.cgm.core.Zone
import dev.cgm.core.GlucoseSourceException
import dev.cgm.core.PollOutcome
import dev.cgm.core.SensorInfo
import dev.cgm.core.SourceResult
import dev.cgm.llu.LibreLinkUpCredentials
import dev.cgm.llu.LibreLinkUpSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Why the last poll failed, in terms the UI can act on. */
data class ErrorState(
    val message: String,
    /** True when retrying will not help: the user has to do something. */
    val needsUser: Boolean,
)

data class CgmState(
    val configured: Boolean = false,
    val snapshot: GlucoseSnapshot? = null,
    /** When we last *successfully* reached the API, distinct from reading age. */
    val lastSuccessMillis: Long? = null,
    val error: ErrorState? = null,
    val policy: FreshnessPolicy = FreshnessPolicy.Default,
    val sensor: SensorInfo? = null,
    /**
     * What the account said, before the user's overrides were laid on top.
     *
     * [snapshot]'s thresholds are the effective ones — everything colouring,
     * classifying or alarming reads those. This is kept only so the Ranges screen
     * can say "your account says 70" beside an overridden boundary, and offer to
     * hand it back.
     */
    val accountThresholds: GlucoseThresholds? = null,
    val overrides: ThresholdOverrides = ThresholdOverrides.None,
    /**
     * The unit everything displays in, available before the first reading so that
     * Settings can show and change it on a fresh install.
     */
    val unit: GlucoseUnit = GlucoseUnit.MGDL,
    /** What the account is set to, for "follow my account" to have something to follow. */
    val accountUnit: GlucoseUnit? = null,
    val unitOverride: GlucoseUnit? = null,
) {
    /**
     * Derived from the clock every time it is asked, never cached: a reading does
     * not become stale when a fetch fails, it becomes stale when it gets old.
     */
    fun freshness(nowMillis: Long): Freshness =
        snapshot?.let { policy.evaluate(it.reading, nowMillis) } ?: Freshness.STALE
}

/**
 * Owns the current reading and the history behind it.
 *
 * Deliberately has no scheduling in it — [pollOnce] does exactly one fetch and
 * reports what happened. Deciding when to call it belongs to PollScheduler, so
 * the same repository works under the phone's foreground service today and
 * whatever drives it on the watch later.
 */
class GlucoseRepository(
    private val settings: SecureSettings,
    private val dao: ReadingDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val _state = MutableStateFlow(CgmState())
    val state: StateFlow<CgmState> = _state.asStateFlow()

    /**
     * Every successful fetch, for downstream sinks. Stage 3's watch bridge
     * subscribes here; nothing else needs to change to start feeding the watch.
     */
    private val _results = MutableSharedFlow<SourceResult>(replay = 1, extraBufferCapacity = 8)
    val results: SharedFlow<SourceResult> = _results

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private var source: LibreLinkUpSource? = null
    private var sourceCredentials: LibreLinkUpCredentials? = null

    fun historySince(sinceMillis: Long): Flow<List<GlucoseReading>> =
        dao.observeSince(sinceMillis).asReadings()

    /** A window that has already passed, for browsing back through history. */
    fun historyBetween(startMillis: Long, endMillis: Long): Flow<List<GlucoseReading>> =
        dao.observeBetween(startMillis, endMillis).asReadings()

    fun recentReadings(limit: Int): Flow<List<GlucoseReading>> =
        dao.observeRecent(limit).asReadings()

    /** Window statistics, aggregated in SQL so a year-long window stays cheap. */
    suspend fun statistics(
        startMillis: Long,
        endMillis: Long,
        thresholds: GlucoseThresholds,
    ): GlucoseStatistics {
        val row = dao.aggregate(
            startMillis = startMillis,
            endMillis = endMillis,
            urgentLow = thresholds.urgentLowMgdl,
            low = thresholds.lowMgdl,
            high = thresholds.highMgdl,
            veryHigh = thresholds.veryHighMgdl,
        )
        if (row.total == 0) return GlucoseStatistics.Empty

        val total = row.total.toDouble()
        val expectedBuckets =
            ((endMillis - startMillis) / StatisticsCalculator.COVERAGE_BUCKET_MILLIS)
                .coerceAtLeast(1)

        return GlucoseStatistics(
            readingCount = row.total,
            meanMgdl = row.mean,
            zoneFractions = mapOf(
                Zone.URGENT_LOW to row.urgentLow / total,
                Zone.LOW to row.low / total,
                Zone.IN_RANGE to row.inRange / total,
                Zone.HIGH to row.high / total,
                Zone.VERY_HIGH to row.veryHigh / total,
            ),
            coverage = (row.buckets.toDouble() / expectedBuckets).coerceIn(0.0, 1.0),
        )
    }

    suspend fun refreshConfiguration() {
        val credentials = settings.credentials()
        val overrides = currentOverrides()
        val unitOverride = currentUnitOverride()
        _state.update {
            it.copy(
                configured = credentials != null,
                policy = currentPolicy(),
                overrides = overrides,
                unitOverride = unitOverride,
            ).withPreferencesApplied(overrides, unitOverride)
        }
    }

    private suspend fun currentPolicy(): FreshnessPolicy =
        runCatching { settings.freshnessPolicy.first() }.getOrDefault(FreshnessPolicy.Default)

    private suspend fun currentOverrides(): ThresholdOverrides =
        runCatching { settings.thresholdOverridesOnce() }.getOrDefault(ThresholdOverrides.None)

    private suspend fun currentUnitOverride(): GlucoseUnit? =
        runCatching { settings.unitOverrideOnce() }.getOrNull()

    /**
     * Take over a boundary, or hand it back to the account with a null value.
     *
     * Applies to the *current* state as well as saving, so the graph recolours
     * while the user's finger is still on the slider rather than at the next poll
     * up to a minute later.
     */
    suspend fun setThresholdOverrides(overrides: ThresholdOverrides) {
        settings.saveThresholdOverrides(overrides)
        _state.update {
            it.copy(overrides = overrides).withPreferencesApplied(overrides, it.unitOverride)
        }
    }

    /**
     * Choose a unit, or pass null to follow the account again.
     *
     * Applied to current state as well as saved, so the whole screen changes unit on
     * the tap rather than at the next poll.
     */
    suspend fun setUnitOverride(unit: GlucoseUnit?) {
        settings.saveUnitOverride(unit)
        _state.update {
            it.copy(unitOverride = unit).withPreferencesApplied(it.overrides, unit)
        }
    }

    /** One fetch. Never throws; failures come back as [PollOutcome]. */
    suspend fun pollOnce(): PollOutcome {
        val credentials = settings.credentials()
        if (credentials == null) {
            _state.update {
                it.copy(
                    configured = false,
                    error = ErrorState("Not signed in to LibreLinkUp", needsUser = true),
                )
            }
            return PollOutcome.Fatal
        }

        val client = sourceFor(credentials)

        return try {
            val fetched = client.fetch()
            persist(fetched)
            val refined = fetched.withRefinedDelta(refineDelta(fetched))

            // The account's band arrives on every fetch, so the user's overrides
            // have to be re-applied on every fetch or a poll would quietly undo
            // them. Downstream sinks — including the watch bridge — get the
            // effective thresholds, not the account's, so nothing colours a
            // reading differently to the phone.
            val account = refined.snapshot.thresholds
            val overrides = currentOverrides()
            val accountUnit = refined.snapshot.unit
            val unitOverride = currentUnitOverride()
            val result = refined
                .withThresholds(overrides.applyTo(account))
                .withUnit(unitOverride ?: accountUnit)

            _state.update {
                it.copy(
                    configured = true,
                    snapshot = result.snapshot,
                    lastSuccessMillis = clock(),
                    error = null,
                    policy = currentPolicy(),
                    sensor = result.sensor ?: it.sensor,
                    accountThresholds = account,
                    overrides = overrides,
                    unit = unitOverride ?: accountUnit,
                    accountUnit = accountUnit,
                    unitOverride = unitOverride,
                )
            }
            _results.emit(result)
            PollOutcome.Success(result.snapshot.reading.ageMillis(clock()))
        } catch (e: GlucoseSourceException) {
            _state.update { it.copy(error = e.toErrorState()) }
            when (e) {
                is GlucoseSourceException.RateLimited -> PollOutcome.Transient(e.retryAfterMillis)
                is GlucoseSourceException.AuthFailed,
                is GlucoseSourceException.AccountActionRequired -> PollOutcome.Fatal
                else -> PollOutcome.Transient()
            }
        }
    }

    /**
     * Recompute the delta against our own stored readings.
     *
     * LibreLinkUp's graph is ~15-minute aggregated, but we poll the current value
     * about once a minute and keep every sample, so after a few minutes of
     * running we can offer a proper ~5-minute delta where the API cannot.
     */
    private suspend fun refineDelta(result: SourceResult): GlucoseDelta? {
        val reading = result.snapshot.reading
        val window = dao.since(reading.timestampMillis - DELTA_LOOKBACK_MILLIS)
            .map { it.toReading() }
        return DeltaCalculator.compute(reading, window)
    }

    private suspend fun persist(result: SourceResult) {
        // The current reading is not always present in graphData, so add it
        // explicitly. REPLACE on the timestamp key makes the overlap harmless.
        val rows = (result.history + result.snapshot.reading).map(ReadingEntity::from)
        dao.insertAll(rows)
        dao.deleteBefore(clock() - HISTORY_RETENTION_MILLIS)
    }

    private fun sourceFor(credentials: LibreLinkUpCredentials): LibreLinkUpSource {
        val existing = source
        if (existing != null && sourceCredentials == credentials) return existing
        return LibreLinkUpSource(
            credentials = credentials,
            sessionStore = settings,
            httpClient = http,
            clock = clock,
        ).also {
            source = it
            sourceCredentials = credentials
        }
    }

    suspend fun signOut() {
        settings.clearCredentials()
        source = null
        sourceCredentials = null
        _state.value = CgmState(configured = false)
    }

    private companion object {
        /**
         * Two years. Issue #6 wants a one-year plot, and retention has to exceed
         * the longest window or the chart silently truncates. At roughly one
         * reading a minute that is ~1M rows, which SQLite handles fine because
         * every window is aggregated in SQL rather than loaded.
         */
        const val HISTORY_RETENTION_MILLIS = 730L * 24 * 60 * 60 * 1000

        /** Only need enough context to find a reading ~5 minutes back. */
        const val DELTA_LOOKBACK_MILLIS = 30L * 60 * 1000
    }
}

/** Keeps the API's coarse delta when our own history has nothing better yet. */
private fun SourceResult.withRefinedDelta(refined: GlucoseDelta?): SourceResult =
    if (refined == null) this
    else copy(snapshot = snapshot.copy(delta = refined))

private fun SourceResult.withThresholds(thresholds: GlucoseThresholds): SourceResult =
    copy(snapshot = snapshot.copy(thresholds = thresholds))

private fun SourceResult.withUnit(unit: GlucoseUnit): SourceResult =
    copy(snapshot = snapshot.copy(unit = unit))

/**
 * Re-derive the effective thresholds from the account's values and [overrides].
 *
 * Always rebuilds from [CgmState.accountThresholds] rather than from whatever the
 * snapshot currently carries, because removing an override has to restore the
 * account's number — and only the account copy still knows it.
 */
private fun CgmState.withPreferencesApplied(
    overrides: ThresholdOverrides,
    unitOverride: GlucoseUnit?,
): CgmState {
    val accountUnit = accountUnit ?: snapshot?.unit
    val unit = unitOverride ?: accountUnit ?: GlucoseUnit.MGDL
    val account = accountThresholds ?: snapshot?.thresholds
    val thresholds = account?.let(overrides::applyTo)

    return copy(
        unit = unit,
        accountUnit = accountUnit,
        accountThresholds = account,
        snapshot = snapshot?.let { snap ->
            snap.copy(thresholds = thresholds ?: snap.thresholds, unit = unit)
        },
    )
}

private fun GlucoseSourceException.toErrorState(): ErrorState = when (this) {
    is GlucoseSourceException.AuthFailed ->
        ErrorState(message ?: "Sign-in rejected", needsUser = true)
    is GlucoseSourceException.AccountActionRequired ->
        ErrorState(message ?: "LibreLinkUp needs attention", needsUser = true)
    is GlucoseSourceException.NoData ->
        ErrorState(message ?: "No reading available", needsUser = false)
    is GlucoseSourceException.RateLimited ->
        ErrorState("Rate limited by Abbott; backing off", needsUser = false)
    is GlucoseSourceException.Unreachable ->
        ErrorState("Offline", needsUser = false)
    is GlucoseSourceException.Unexpected ->
        ErrorState(message ?: "Unexpected API response", needsUser = false)
}
