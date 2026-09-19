package dev.cgm.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.cgm.core.AlarmKind
import dev.cgm.core.AlarmRuntimeState
import dev.cgm.core.AlarmSettings
import dev.cgm.core.FreshnessPolicy
import dev.cgm.core.GlucoseUnit
import dev.cgm.core.ThresholdOverrides
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

    // -- ranges -----------------------------------------------------------

    /**
     * Boundaries the user has taken over from the account. Only the ones they
     * actually edited are stored, so the rest keep following LibreLinkUp.
     */
    val thresholdOverrides: Flow<ThresholdOverrides> = context.dataStore.data.map { prefs ->
        prefs[KEY_RANGES]
            ?.let { runCatching { json.decodeFromString<ThresholdOverrides>(it) }.getOrNull() }
            ?: ThresholdOverrides.None
    }

    suspend fun thresholdOverridesOnce(): ThresholdOverrides = thresholdOverrides.first()

    suspend fun saveThresholdOverrides(overrides: ThresholdOverrides) {
        context.dataStore.edit { it[KEY_RANGES] = json.encodeToString(overrides) }
    }

    /**
     * The unit the user chose, or null to keep following the account.
     *
     * Null is a real state rather than a default: LibreLinkUp reports which unit the
     * account is set to, and following it means someone who switches their official
     * app does not have to switch this one too. An unrecognised stored value falls
     * back to following the account rather than guessing.
     */
    val unitOverride: Flow<GlucoseUnit?> = context.dataStore.data.map { prefs ->
        prefs[KEY_UNIT]?.let { name -> runCatching { GlucoseUnit.valueOf(name) }.getOrNull() }
    }

    suspend fun unitOverrideOnce(): GlucoseUnit? = unitOverride.first()

    suspend fun saveUnitOverride(unit: GlucoseUnit?) {
        context.dataStore.edit {
            if (unit == null) it.remove(KEY_UNIT) else it[KEY_UNIT] = unit.name
        }
    }

    // -- alarms -----------------------------------------------------------

    /**
     * Alarm configuration. Not secret, so stored as plain JSON — encrypting it
     * would only make it harder to inspect when an alarm misbehaves.
     */
    val alarmSettings: Flow<AlarmSettings> = context.dataStore.data.map { prefs ->
        prefs[KEY_ALARMS]
            ?.let { runCatching { json.decodeFromString<AlarmSettings>(it) }.getOrNull() }
            ?: AlarmSettings.Default
    }

    suspend fun alarmSettingsOnce(): AlarmSettings = alarmSettings.first()

    suspend fun saveAlarmSettings(settings: AlarmSettings) {
        context.dataStore.edit { it[KEY_ALARMS] = json.encodeToString(settings) }
    }

    /**
     * Alarm runtime state, persisted so that a restart does not re-announce an
     * alarm the user already heard and dismissed.
     */
    suspend fun alarmState(): AlarmRuntimeState {
        val raw = context.dataStore.data.first()[KEY_ALARM_STATE] ?: return AlarmRuntimeState.Empty
        return runCatching { json.decodeFromString<AlarmRuntimeState>(raw) }
            .getOrDefault(AlarmRuntimeState.Empty)
    }

    suspend fun saveAlarmState(state: AlarmRuntimeState) {
        context.dataStore.edit { it[KEY_ALARM_STATE] = json.encodeToString(state) }
    }

    suspend fun snoozeAlarm(kind: AlarmKind, forMillis: Long) {
        val snoozed = alarmState().snooze(kind, System.currentTimeMillis() + forMillis)
        saveAlarmState(snoozed)
    }

    private companion object {
        val KEY_ALARMS: Preferences.Key<String> = stringPreferencesKey("alarm_settings")
        val KEY_ALARM_STATE: Preferences.Key<String> = stringPreferencesKey("alarm_state")
        val KEY_EMAIL: Preferences.Key<String> = stringPreferencesKey("llu_email")
        val KEY_PASSWORD: Preferences.Key<String> = stringPreferencesKey("llu_password_enc")
        val KEY_SESSION: Preferences.Key<String> = stringPreferencesKey("llu_session_enc")
        val KEY_FRESHNESS: Preferences.Key<String> = stringPreferencesKey("freshness_policy")
        val KEY_RANGES: Preferences.Key<String> = stringPreferencesKey("threshold_overrides")
        val KEY_UNIT: Preferences.Key<String> = stringPreferencesKey("display_unit")
    }
}
