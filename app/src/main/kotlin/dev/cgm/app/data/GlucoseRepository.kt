package dev.cgm.app.data

import androidx.annotation.StringRes
import dev.cgm.app.R

import dev.cgm.core.ContinuityAnalyzer
import dev.cgm.core.ContinuityReport
import dev.cgm.core.DeltaCalculator
import dev.cgm.core.Freshness
import dev.cgm.core.FreshnessPolicy
import dev.cgm.core.GlucoseDelta
import dev.cgm.core.GlucoseReading
import dev.cgm.core.GlucoseSnapshot
import dev.cgm.core.GlucoseStatistics
import dev.cgm.core.GlucoseThresholds
import dev.cgm.core.InsulinDose
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Why the last poll failed, in terms the UI can act on.
 *
 * A resource id rather than a sentence: this is built in the data layer, which has
 * no business holding display text and no localised context to resolve it with. The
 * screen or the notification resolves it, in the app's chosen language.
 */
data class ErrorState(
    @StringRes val messageRes: Int,
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
    private val rollups: RollupWriter? = null,
    private val doseDao: DoseDao,
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

    // -- insulin doses ------------------------------------------------------

    fun recentDoses(limit: Int): Flow<List<InsulinDose>> =
        doseDao.observeRecent(limit).map { rows -> rows.map(DoseEntity::toDose) }

    /** Doses in a window, for the chart's dose markers later. */
    fun dosesBetween(startMillis: Long, endMillis: Long): Flow<List<InsulinDose>> =
        doseDao.observeBetween(startMillis, endMillis).map { rows -> rows.map(DoseEntity::toDose) }

    /**
     * Stores a dose, or refuses it.
     *
     * Returns false rather than throwing on an implausible dose: this is called from a
     * form, and a form wants to keep what the user typed and say why, not lose it to
     * an exception.
     */
    suspend fun saveDose(dose: InsulinDose): Boolean {
        if (!dose.isPlausible) return false
        doseDao.upsert(DoseEntity.from(dose))
        return true
    }

    suspend fun deleteDose(dose: InsulinDose) = doseDao.delete(DoseEntity.from(dose))

    /**
     * How complete the last [windowMillis] of history actually is.
     *
     * Diagnostic rather than decorative: a gap here is permanent, so being able
     * to see one is the only way to tell whether the service is really keeping up
     * overnight or merely appearing to.
     */
    suspend fun continuity(windowMillis: Long): ContinuityReport {
        val now = clock()
        val readings = dao.since(now - windowMillis).map { it.toReading() }
        return ContinuityAnalyzer.analyse(readings, now - windowMillis, now)
    }

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

    /**
     * Put the newest stored reading on screen before any fetch has happened.
     *
     * Without this, a restart shows "--" in the status bar and "——" on Home until a
     * poll succeeds — which on a bad connection can be a long time, while a perfectly
     * good reading from two minutes ago sits in the database. Freshness is still
     * derived from the reading's own age, so a genuinely old one is reported as stale
     * rather than passed off as current.
     *
     * Deliberately does not set [CgmState.lastSuccessMillis]: nothing was fetched.
     * Thresholds come from the defaults plus the user's overrides, because the
     * account's own band is only known after a fetch; the first poll corrects it a
     * moment later.
     */
    suspend fun primeFromStorage() {
        if (_state.value.snapshot != null) return
        val latest = dao.latest()?.toReading() ?: return
        val overrides = currentOverrides()
        val unitOverride = currentUnitOverride()
        val unit = unitOverride ?: GlucoseUnit.MGDL

        _state.update {
            it.copy(
                snapshot = GlucoseSnapshot(
                    reading = latest,
                    thresholds = overrides.applyTo(GlucoseThresholds.Default),
                    unit = unit,
                ),
                overrides = overrides,
                unitOverride = unitOverride,
                unit = unit,
                policy = currentPolicy(),
            )
        }
    }

    /** One fetch. Never throws; failures come back as [PollOutcome]. */
    suspend fun pollOnce(): PollOutcome {
        val credentials = settings.credentials()
        if (credentials == null) {
            _state.update {
                it.copy(
                    configured = false,
                    error = ErrorState(R.string.err_not_signed_in, needsUser = true),
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
        val readings = result.history + result.snapshot.reading
        dao.insertAll(readings.map(ReadingEntity::from))
        dao.deleteBefore(clock() - HISTORY_RETENTION_MILLIS)

        // Refresh the summaries for the hours these readings touched. The writer
        // recomputes whole hours from raw rather than adding deltas, so the
        // repeated re-insertion of overlapping graph windows cannot double-count.
        rollups?.refreshFor(readings, result.sensor)
    }

    /**
     * Bring the rollup table in line with the readings table.
     *
     * Called once on startup. Cheap when they already agree, and the repair path
     * after the migration that introduced rollups, since that one creates the
     * table empty rather than trying to backfill inside a schema migration.
     */
    suspend fun ensureRollups() {
        rollups?.rebuildIfEmpty(state.value.sensor)
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
        ErrorState(R.string.err_sign_in_rejected, needsUser = true)
    is GlucoseSourceException.AccountActionRequired ->
        ErrorState(R.string.err_account_action, needsUser = true)
    is GlucoseSourceException.NoData ->
        ErrorState(R.string.err_no_reading, needsUser = false)
    is GlucoseSourceException.RateLimited ->
        ErrorState(R.string.err_rate_limited, needsUser = false)
    is GlucoseSourceException.Unreachable ->
        ErrorState(R.string.err_offline, needsUser = false)
    is GlucoseSourceException.Unexpected ->
        ErrorState(R.string.err_unexpected, needsUser = false)
}
