package dev.cgm.web

import dev.cgm.llu.InMemorySessionStore
import dev.cgm.llu.LibreLinkUpCredentials
import dev.cgm.llu.LibreLinkUpSource
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * One signed-in browser.
 *
 * Holds a live [LibreLinkUpSource] so the six-month Abbott token is reused across
 * requests instead of logging in again on every page load — Abbott rate-limits
 * logins far harder than reads.
 */
class WebSession(
    val source: LibreLinkUpSource,
    val createdAtMillis: Long,
    @Volatile var lastUsedMillis: Long,
)

/**
 * Sessions live in memory and nowhere else.
 *
 * Deliberately not persisted. This server exists so a browser can reach an API
 * that refuses browsers; it is not a place for health credentials to accumulate.
 * Nothing survives a restart, there is no database to leak, and the password is
 * used once to obtain a token and then dropped.
 *
 * The browser only ever holds an opaque random id, in an HttpOnly cookie it
 * cannot read — so a script on the page cannot walk off with an Abbott token that
 * would otherwise be valid for six months.
 */
class SessionRegistry(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val sessions = ConcurrentHashMap<String, WebSession>()
    private val random = SecureRandom()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Log in and remember the result.
     *
     * The credentials are not stored: [LibreLinkUpSource] keeps them only for the
     * lifetime of this session object, to re-authenticate if Abbott revokes the
     * token mid-session.
     */
    suspend fun create(credentials: LibreLinkUpCredentials): String {
        val source = LibreLinkUpSource(
            credentials = credentials,
            sessionStore = InMemorySessionStore(),
            httpClient = http,
        )
        // Prove the credentials before handing back a session, so a typo fails at
        // the login screen rather than as an empty dashboard.
        source.fetch()

        val token = newToken()
        val now = clock()
        sessions[token] = WebSession(source, now, now)
        sweep()
        return token
    }

    fun get(token: String?): WebSession? {
        val session = sessions[token ?: return null] ?: return null
        if (isExpired(session)) {
            sessions.remove(token)
            return null
        }
        session.lastUsedMillis = clock()
        return session
    }

    fun remove(token: String?) {
        token?.let { sessions.remove(it) }
    }

    fun size(): Int = sessions.size

    private fun isExpired(session: WebSession): Boolean =
        clock() - session.lastUsedMillis > IDLE_TIMEOUT_MILLIS ||
            clock() - session.createdAtMillis > ABSOLUTE_TIMEOUT_MILLIS

    /** Expiry is checked lazily; nothing here warrants a background thread. */
    private fun sweep() {
        sessions.entries.removeIf { isExpired(it.value) }
    }

    private fun newToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        const val COOKIE = "cgm_session"

        /** Idle logout. Short, because the tab is likely left open on a desk. */
        val IDLE_TIMEOUT_MILLIS = TimeUnit.HOURS.toMillis(12)

        /** Hard ceiling regardless of activity. */
        val ABSOLUTE_TIMEOUT_MILLIS = TimeUnit.DAYS.toMillis(7)
    }
}
