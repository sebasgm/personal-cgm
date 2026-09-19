package dev.cgm.core

import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.minutes

/**
 * The conditions worth interrupting someone for.
 *
 * [severity] exists so that a single reading cannot fire two alarms at once: at
 * 45 mg/dL both URGENT_LOW and LOW are technically true, but only the worse one
 * should make a sound.
 */
enum class AlarmKind(val severity: Int) {
    URGENT_LOW(severity = 4),
    LOW(severity = 2),
    HIGH(severity = 2),
    VERY_HIGH(severity = 3),

    /**
     * No fresh reading. Distinct from a glucose alarm because the failure is
     * ours, not the body's — and because it is the one alarm that is dangerous
     * to leave off: without it, a dead feed looks exactly like a flat line.
     */
    SIGNAL_LOSS(severity = 3);

    val isGlucose: Boolean get() = this != SIGNAL_LOSS
    val isLow: Boolean get() = this == URGENT_LOW || this == LOW
    val isHigh: Boolean get() = this == HIGH || this == VERY_HIGH

    /** The alarm this one silences when both conditions hold. */
    val supersedes: AlarmKind? get() = when (this) {
        URGENT_LOW -> LOW
        VERY_HIGH -> HIGH
        else -> null
    }
}

/**
 * One alarm's configuration.
 *
 * [thresholdMgdl] is deliberately independent of [GlucoseThresholds]. Where you
 * want the display to stop calling a value "in range" and where you want to be
 * woken up are different questions: a target band of 70-180 with alarms at 60
 * and 250 is an entirely reasonable setup.
 */
@Serializable
data class AlarmSetting(
    val enabled: Boolean = true,
    /** mg/dL. Meaningless for [AlarmKind.SIGNAL_LOSS]. */
    val thresholdMgdl: Double = 0.0,
    /** [AlarmKind.SIGNAL_LOSS] only: how old the newest reading may become. */
    val afterMillis: Long = 20.minutes.inWholeMilliseconds,
    /**
     * Whether this alarm should sound through Do Not Disturb.
     *
     * Android will not honour this unless the app holds Notification Policy
     * Access, and the channel carrying it must be created with the bypass
     * already set. The UI has to ask for that grant; this flag is only intent.
     */
    val overrideDnd: Boolean = false,
    /** How often to sound again while the condition persists. */
    val repeatEveryMillis: Long = 15.minutes.inWholeMilliseconds,
    /**
     * Issue #1: sound once, then stay quiet while the value is heading back
     * toward range. A low that is already recovering does not need to be
     * announced every fifteen minutes — that is how people learn to ignore
     * alarms.
     */
    val silenceWhileRecovering: Boolean = true,
)

@Serializable
data class AlarmSettings(
    val settings: Map<AlarmKind, AlarmSetting> = Defaults,
) {
    operator fun get(kind: AlarmKind): AlarmSetting =
        settings[kind] ?: Defaults.getValue(kind)

    fun with(kind: AlarmKind, setting: AlarmSetting): AlarmSettings =
        copy(settings = settings + (kind to setting))

    companion object {
        /**
         * Glucose defaults sit outside the default display band (70-180) on
         * purpose — an alarm at exactly the edge of target fires constantly.
         * The signal-loss default of 20 minutes is twice the point at which the
         * display gives up on a reading, because a brief gap is worth showing
         * but not worth waking someone for.
         */
        val Defaults: Map<AlarmKind, AlarmSetting> = mapOf(
            AlarmKind.URGENT_LOW to AlarmSetting(
                enabled = true,
                thresholdMgdl = 55.0,
                repeatEveryMillis = 5.minutes.inWholeMilliseconds,
                // An urgent low must keep sounding even while recovering.
                silenceWhileRecovering = false,
                overrideDnd = true,
            ),
            AlarmKind.LOW to AlarmSetting(enabled = true, thresholdMgdl = 70.0),
            AlarmKind.HIGH to AlarmSetting(enabled = true, thresholdMgdl = 220.0),
            AlarmKind.VERY_HIGH to AlarmSetting(enabled = false, thresholdMgdl = 300.0),
            AlarmKind.SIGNAL_LOSS to AlarmSetting(
                enabled = true,
                afterMillis = 20.minutes.inWholeMilliseconds,
            ),
        )

        val Default = AlarmSettings()
    }
}

@Serializable
data class AlarmEntry(
    val firingSinceMillis: Long,
    val lastSoundedAtMillis: Long,
    val snoozedUntilMillis: Long = 0,
)

/** Survives process death, so an alarm cannot re-sound just because we restarted. */
@Serializable
data class AlarmRuntimeState(
    val entries: Map<AlarmKind, AlarmEntry> = emptyMap(),
) {
    fun snooze(kind: AlarmKind, untilMillis: Long): AlarmRuntimeState {
        val entry = entries[kind] ?: return this
        return copy(entries = entries + (kind to entry.copy(snoozedUntilMillis = untilMillis)))
    }

    companion object {
        val Empty = AlarmRuntimeState()
    }
}

data class AlarmDecision(
    /** Conditions currently true, after severity suppression. */
    val firing: Set<AlarmKind> = emptySet(),
    /** Should make noise right now. A subset of [firing]. */
    val sound: List<AlarmKind> = emptyList(),
    /** Was firing, no longer is. The notifier should dismiss these. */
    val cleared: Set<AlarmKind> = emptySet(),
    val state: AlarmRuntimeState = AlarmRuntimeState.Empty,
)

/**
 * Decides what should be alarming, and whether it should make a sound.
 *
 * Pure: no Android, no clock of its own, no notification concepts. All the
 * fiddly behaviour — recovery silencing, repeat intervals, snoozes, suppression
 * of the lesser of two overlapping alarms — is decided here where it can be
 * tested exhaustively, and the Android layer only obeys.
 */
class AlarmEngine {

    fun evaluate(
        snapshot: GlucoseSnapshot?,
        freshness: Freshness,
        settings: AlarmSettings,
        previous: AlarmRuntimeState,
        nowMillis: Long,
    ): AlarmDecision {
        val conditions = activeConditions(snapshot, freshness, settings, nowMillis)
        val firing = suppressLesser(conditions)

        val sound = mutableListOf<AlarmKind>()
        val entries = mutableMapOf<AlarmKind, AlarmEntry>()

        for (kind in firing) {
            val setting = settings[kind]
            val existing = previous.entries[kind]

            if (existing == null) {
                // New condition: always announce it once.
                sound += kind
                entries[kind] = AlarmEntry(
                    firingSinceMillis = nowMillis,
                    lastSoundedAtMillis = nowMillis,
                )
                continue
            }

            val snoozed = nowMillis < existing.snoozedUntilMillis
            val due = nowMillis - existing.lastSoundedAtMillis >= setting.repeatEveryMillis
            val recovering = setting.silenceWhileRecovering &&
                isRecovering(kind, snapshot?.reading?.trend)

            if (!snoozed && due && !recovering) {
                sound += kind
                entries[kind] = existing.copy(lastSoundedAtMillis = nowMillis)
            } else {
                entries[kind] = existing
            }
        }

        val cleared = previous.entries.keys - firing

        return AlarmDecision(
            firing = firing,
            // Loudest first, so a caller that only honours one picks the right one.
            sound = sound.sortedByDescending { it.severity },
            cleared = cleared,
            state = AlarmRuntimeState(entries),
        )
    }

    private fun activeConditions(
        snapshot: GlucoseSnapshot?,
        freshness: Freshness,
        settings: AlarmSettings,
        nowMillis: Long,
    ): Set<AlarmKind> {
        val active = mutableSetOf<AlarmKind>()

        val signalLoss = settings[AlarmKind.SIGNAL_LOSS]
        if (signalLoss.enabled) {
            val age = snapshot?.reading?.ageMillis(nowMillis)
            // No reading at all counts as signal loss once we expected one.
            if (age == null || age >= signalLoss.afterMillis) active += AlarmKind.SIGNAL_LOSS
        }

        // A stale reading must not drive glucose alarms. Alarming on a number we
        // already know is out of date is how you get woken by a low that ended
        // an hour ago — and, worse, how a real low goes unannounced because the
        // last stale value happened to look fine.
        if (snapshot == null || freshness == Freshness.STALE) return active

        val mgdl = snapshot.reading.valueMgdl
        for (kind in AlarmKind.entries.filter { it.isGlucose }) {
            val setting = settings[kind]
            if (!setting.enabled) continue
            val hit = when {
                kind.isLow -> mgdl < setting.thresholdMgdl
                else -> mgdl > setting.thresholdMgdl
            }
            if (hit) active += kind
        }
        return active
    }

    /** Drop LOW when URGENT_LOW is firing, and HIGH when VERY_HIGH is. */
    private fun suppressLesser(active: Set<AlarmKind>): Set<AlarmKind> {
        val superseded = active.mapNotNull { it.supersedes }.toSet()
        return active - superseded
    }

    /** Moving back toward range, so the situation is improving on its own. */
    private fun isRecovering(kind: AlarmKind, trend: TrendArrow?): Boolean = when {
        trend == null || trend == TrendArrow.UNKNOWN -> false
        kind.isLow -> trend == TrendArrow.RISING || trend == TrendArrow.RISING_QUICKLY
        kind.isHigh -> trend == TrendArrow.FALLING || trend == TrendArrow.FALLING_QUICKLY
        else -> false
    }
}
