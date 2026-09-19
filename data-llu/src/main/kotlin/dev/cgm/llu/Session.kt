package dev.cgm.llu

import kotlinx.serialization.Serializable

/** LibreLinkUp follower credentials. Not the LibreView account wearing the sensor. */
data class LibreLinkUpCredentials(
    val email: String,
    val password: String,
)

/**
 * What survives between runs after a successful login.
 *
 * The token lasts about six months, so persisting this is the difference between
 * one login per install and one login per app start — and Abbott rate-limits
 * logins far more aggressively than reads.
 */
@Serializable
data class LibreLinkUpSession(
    val region: String,
    val token: String,
    /** SHA-256 hex of the account id; required on every authenticated call. */
    val accountIdHash: String,
    /** Epoch seconds. */
    val expiresAtSeconds: Long,
    /** Which followed patient we read. Null until the first connection lookup. */
    val patientId: String? = null,
) {
    fun isValidAt(nowSeconds: Long): Boolean =
        token.isNotEmpty() && expiresAtSeconds > nowSeconds + EXPIRY_MARGIN_SECONDS

    companion object {
        /** Re-login a day early rather than discover expiry at 3am. */
        const val EXPIRY_MARGIN_SECONDS = 86_400L
    }
}

/**
 * Persistence for [LibreLinkUpSession], implemented per platform.
 *
 * This is an interface rather than a concrete class because the token is a
 * long-lived bearer credential for health data: on Android it belongs in
 * EncryptedSharedPreferences, and this module must not care.
 */
interface SessionStore {
    suspend fun load(): LibreLinkUpSession?
    suspend fun save(session: LibreLinkUpSession)
    suspend fun clear()
}

/** For tests and for the throwaway CLI. */
class InMemorySessionStore(initial: LibreLinkUpSession? = null) : SessionStore {
    @Volatile
    private var session: LibreLinkUpSession? = initial
    override suspend fun load(): LibreLinkUpSession? = session
    override suspend fun save(session: LibreLinkUpSession) { this.session = session }
    override suspend fun clear() { session = null }
}

object LibreLinkUpRegions {
    const val DEFAULT = "eu"

    val ALL = listOf("ae", "ap", "au", "ca", "de", "eu", "eu2", "fr", "jp", "la", "ru", "us", "cn")

    fun hostFor(region: String): String = "api-$region.libreview.io"
}
