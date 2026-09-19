package dev.cgm.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.cgm.core.FreshnessPolicy
import dev.cgm.llu.LibreLinkUpCredentials
import dev.cgm.llu.LibreLinkUpSession
import dev.cgm.llu.SessionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "cgm-settings")

/**
 * Credentials, session and preferences.
 *
 * Everything secret is stored as Keystore-encrypted ciphertext; DataStore only
 * ever sees an opaque Base64 string.
 */
class SecureSettings(private val context: Context) : SessionStore {

    private val json = Json { ignoreUnknownKeys = true }

    // -- credentials ------------------------------------------------------

    suspend fun saveCredentials(credentials: LibreLinkUpCredentials) {
        context.dataStore.edit {
            it[KEY_EMAIL] = credentials.email
            it[KEY_PASSWORD] = KeystoreCrypto.encrypt(credentials.password)
        }
    }

    suspend fun credentials(): LibreLinkUpCredentials? {
        val prefs = context.dataStore.data.first()
        val email = prefs[KEY_EMAIL] ?: return null
        val password = prefs[KEY_PASSWORD]?.let(KeystoreCrypto::decrypt) ?: return null
        return LibreLinkUpCredentials(email, password)
    }

    val isConfigured: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_EMAIL] != null && it[KEY_PASSWORD] != null }

    suspend fun clearCredentials() {
        context.dataStore.edit {
            it.remove(KEY_EMAIL)
            it.remove(KEY_PASSWORD)
            it.remove(KEY_SESSION)
        }
    }

    // -- SessionStore -----------------------------------------------------

    override suspend fun load(): LibreLinkUpSession? {
        val blob = context.dataStore.data.first()[KEY_SESSION] ?: return null
        val plain = KeystoreCrypto.decrypt(blob) ?: return null
        return runCatching { json.decodeFromString<LibreLinkUpSession>(plain) }.getOrNull()
    }

    override suspend fun save(session: LibreLinkUpSession) {
        val blob = KeystoreCrypto.encrypt(json.encodeToString(session))
        context.dataStore.edit { it[KEY_SESSION] = blob }
    }

    override suspend fun clear() {
        context.dataStore.edit { it.remove(KEY_SESSION) }
    }

    // -- preferences ------------------------------------------------------

    val freshnessPolicy: Flow<FreshnessPolicy> = context.dataStore.data.map { prefs ->
        prefs[KEY_FRESHNESS]
            ?.let { runCatching { json.decodeFromString<FreshnessPolicy>(it) }.getOrNull() }
            ?: FreshnessPolicy.Default
    }

    suspend fun saveFreshnessPolicy(policy: FreshnessPolicy) {
        context.dataStore.edit { it[KEY_FRESHNESS] = json.encodeToString(policy) }
    }

    private companion object {
        val KEY_EMAIL: Preferences.Key<String> = stringPreferencesKey("llu_email")
        val KEY_PASSWORD: Preferences.Key<String> = stringPreferencesKey("llu_password_enc")
        val KEY_SESSION: Preferences.Key<String> = stringPreferencesKey("llu_session_enc")
        val KEY_FRESHNESS: Preferences.Key<String> = stringPreferencesKey("freshness_policy")
    }
}
