package dev.cgm.llu

import dev.cgm.core.GlucoseRange
import dev.cgm.core.GlucoseReading
import dev.cgm.core.GlucoseSnapshot
import dev.cgm.core.GlucoseSource
import dev.cgm.core.GlucoseSourceException
import dev.cgm.core.GlucoseUnit
import dev.cgm.core.SourceResult
import dev.cgm.core.TrendArrow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.security.MessageDigest

/**
 * Reads glucose from Abbott's LibreLinkUp follower service.
 *
 * Unofficial API — see docs/00-research.md §1A. Two things here exist purely
 * because of Abbott's behaviour and should not be "cleaned up":
 *
 *  - the `Account-Id` SHA-256 header, added by Abbott in 2024, without which
 *    every authenticated call 401s;
 *  - the region redirect, where logging in at the wrong regional host returns
 *    the correct region instead of a session.
 */
class LibreLinkUpSource(
    private val credentials: LibreLinkUpCredentials,
    private val sessionStore: SessionStore,
    httpClient: OkHttpClient = OkHttpClient(),
    /** Overridden in tests to point at a MockWebServer. */
    baseUrlOverride: HttpUrl? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : GlucoseSource {

    override val id = "librelinkup"

    @Volatile
    private var region: String = LibreLinkUpRegions.DEFAULT
    private val loginMutex = Mutex()
    private val fixedBaseUrl = baseUrlOverride

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val api: LibreLinkUpApi = Retrofit.Builder()
        .baseUrl(fixedBaseUrl ?: HttpUrl.Builder().scheme("https")
            .host(LibreLinkUpRegions.hostFor(LibreLinkUpRegions.DEFAULT)).build())
        .client(httpClient.newBuilder().addInterceptor(HeaderInterceptor()).build())
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(LibreLinkUpApi::class.java)

    /**
     * Applies Abbott's required headers, the bearer token, and the current region
     * host. Region is rewritten here rather than by rebuilding Retrofit because
     * it can change mid-session on a redirect.
     */
    private inner class HeaderInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val original = chain.request()
            val builder = original.newBuilder()
                .header("product", "llu.android")
                .header("version", CLIENT_VERSION)
                .header("accept", "application/json")

            currentToken?.let { builder.header("authorization", "Bearer $it") }
            currentAccountIdHash?.let { builder.header("account-id", it) }

            // Only rewrite the host when we are talking to the real service.
            val url = if (fixedBaseUrl == null) {
                original.url.newBuilder().host(LibreLinkUpRegions.hostFor(region)).build()
            } else {
                original.url
            }

            return chain.proceed(builder.url(url).build())
        }
    }

    @Volatile private var currentToken: String? = null
    @Volatile private var currentAccountIdHash: String? = null

    /**
     * Which patient we follow, cached for the life of the process.
     *
     * Deliberately separate from [SessionStore]: clearing the session on a 401
     * throws away the token, but the followed patient has not changed, and
     * re-resolving it would cost an extra round trip on every recovery.
     */
    @Volatile private var cachedPatientId: String? = null

    // -- GlucoseSource ----------------------------------------------------

    override suspend fun fetch(): SourceResult {
        val session = ensureSession()
        return try {
            read(session)
        } catch (e: GlucoseSourceException.AuthFailed) {
            // A six-month token can still be revoked early; one silent retry.
            sessionStore.clear()
            currentToken = null
            currentAccountIdHash = null
            read(ensureSession())
        }
    }

    private suspend fun read(session: LibreLinkUpSession): SourceResult {
        val patientId = session.patientId ?: cachedPatientId ?: resolvePatientId(session)
        cachedPatientId = patientId
        val body = call { api.graph(patientId) }

        val connection = body.connection
        val current = connection?.glucoseMeasurement
            ?: throw GlucoseSourceException.NoData(
                "no current measurement — sensor may be warming up or out of range"
            )

        val unit = if (current.glucoseUnits == 0) GlucoseUnit.MMOLL else GlucoseUnit.MGDL
        val history = body.graphData.mapNotNull { it.toReading() }.sortedBy { it.timestampMillis }
        val reading = current.toReading()
            ?: throw GlucoseSourceException.Unexpected(
                "unparseable timestamp: ${current.factoryTimestamp}"
            )

        // Delta against the most recent *earlier* reading, not just the last
        // element: the graph occasionally contains a point newer than `current`.
        val previous = history.lastOrNull { it.timestampMillis < reading.timestampMillis }

        return SourceResult(
            snapshot = GlucoseSnapshot(
                reading = reading,
                range = GlucoseRange(
                    lowMgdl = connection.targetLow ?: GlucoseRange().lowMgdl,
                    highMgdl = connection.targetHigh ?: GlucoseRange().highMgdl,
                ),
                unit = unit,
                deltaMgdl = previous?.let { reading.valueMgdl - it.valueMgdl },
            ),
            history = history,
        )
    }

    private suspend fun resolvePatientId(session: LibreLinkUpSession): String {
        val connections = call { api.connections() }
        val first = connections.firstOrNull()
            ?: throw GlucoseSourceException.NoData(
                "no followed patients — the sensor wearer must invite " +
                    "${credentials.email} as a follower and the invite must be accepted"
            )
        cachedPatientId = first.patientId
        sessionStore.save(session.copy(patientId = first.patientId))
        return first.patientId
    }

    // -- auth -------------------------------------------------------------

    private suspend fun ensureSession(): LibreLinkUpSession = loginMutex.withLock {
        val cached = sessionStore.load()
        if (cached != null && cached.isValidAt(clock() / 1000)) {
            region = cached.region
            currentToken = cached.token
            currentAccountIdHash = cached.accountIdHash
            return@withLock cached
        }
        login()
    }

    private suspend fun login(): LibreLinkUpSession {
        currentToken = null
        currentAccountIdHash = null

        // Which patient we follow does not change when a token expires. Carrying
        // it across saves a /connections round trip on every six-monthly refresh.
        val knownPatientId = sessionStore.load()?.patientId ?: cachedPatientId

        // Bounded: each redirect must name a new region, so this cannot spin.
        val tried = mutableSetOf<String>()
        while (tried.add(region)) {
            val data = call {
                api.login(LoginRequest(credentials.email, credentials.password))
            }

            if (data.redirect && data.region != null) {
                region = data.region
                continue
            }
            if (data.step != null) {
                throw GlucoseSourceException.AccountActionRequired(
                    "LibreLinkUp needs action in the app first (step: ${data.step.type}). " +
                        "Open LibreLinkUp, accept any pending terms, then retry."
                )
            }

            val ticket = data.authTicket
            val user = data.user
            if (ticket == null || user == null) {
                throw GlucoseSourceException.Unexpected("login returned no auth ticket")
            }

            val session = LibreLinkUpSession(
                region = region,
                token = ticket.token,
                accountIdHash = sha256Hex(user.id),
                expiresAtSeconds = ticket.expires,
                patientId = knownPatientId,
            )
            currentToken = session.token
            currentAccountIdHash = session.accountIdHash
            sessionStore.save(session)
            return session
        }
        throw GlucoseSourceException.Unexpected("region redirect did not settle")
    }

    // -- transport --------------------------------------------------------

    /** Unwraps the envelope and maps every failure onto [GlucoseSourceException]. */
    private suspend fun <T : Any> call(block: suspend () -> Response<Envelope<T>>): T {
        val response = try {
            block()
        } catch (e: IOException) {
            throw GlucoseSourceException.Unreachable(e.message ?: "network error", e)
        }

        if (!response.isSuccessful) {
            when (response.code()) {
                401, 403 -> throw GlucoseSourceException.AuthFailed("rejected (${response.code()})")
                429, 430 -> throw GlucoseSourceException.RateLimited(retryAfterMillis(response))
                else -> throw GlucoseSourceException.Unexpected("HTTP ${response.code()}")
            }
        }

        val envelope = response.body()
            ?: throw GlucoseSourceException.Unexpected("empty response body")

        when (envelope.status) {
            0 -> Unit
            2 -> throw GlucoseSourceException.AuthFailed("bad email or password")
            4 -> throw GlucoseSourceException.AccountActionRequired(
                envelope.error?.message ?: "account needs action in the LibreLinkUp app"
            )
            else -> throw GlucoseSourceException.Unexpected(
                "API status ${envelope.status}${envelope.error?.message?.let { ": $it" } ?: ""}"
            )
        }

        return envelope.data
            ?: throw GlucoseSourceException.Unexpected("status 0 but no data")
    }

    private fun retryAfterMillis(response: Response<*>): Long {
        val header = response.headers()["retry-after"]?.toLongOrNull()
        return (header ?: DEFAULT_RETRY_AFTER_SECONDS) * 1000
    }

    private companion object {
        /**
         * Abbott rejects clients reporting an ancient version. Bump this if login
         * starts failing with an "update required" status.
         */
        const val CLIENT_VERSION = "4.16.0"
        const val DEFAULT_RETRY_AFTER_SECONDS = 60L

        fun sha256Hex(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}

internal fun Measurement.toReading(): GlucoseReading? {
    val millis = Timestamps.parseUtcMillis(factoryTimestamp) ?: return null
    return GlucoseReading(
        valueMgdl = valueInMgPerDl,
        timestampMillis = millis,
        trend = TrendArrow.fromLibreLinkUp(trendArrow),
        isHigh = isHigh,
        isLow = isLow,
    )
}
