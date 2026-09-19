package dev.cgm.app.data

import dev.cgm.core.Freshness
import dev.cgm.core.FreshnessPolicy
import dev.cgm.core.GlucoseReading
import dev.cgm.core.GlucoseSnapshot
import dev.cgm.core.GlucoseSourceException
import dev.cgm.core.PollOutcome
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

    suspend fun refreshConfiguration() {
        val credentials = settings.credentials()
        _state.update { it.copy(configured = credentials != null, policy = currentPolicy()) }
    }

    private suspend fun currentPolicy(): FreshnessPolicy =
        runCatching { settings.freshnessPolicy.first() }.getOrDefault(FreshnessPolicy.Default)

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
            val result = client.fetch()
            persist(result)
            _state.update {
                it.copy(
                    configured = true,
                    snapshot = result.snapshot,
                    lastSuccessMillis = clock(),
                    error = null,
                    policy = currentPolicy(),
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
        /** Ninety days is plenty for the graph and keeps the table small. */
        const val HISTORY_RETENTION_MILLIS = 90L * 24 * 60 * 60 * 1000
    }
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
