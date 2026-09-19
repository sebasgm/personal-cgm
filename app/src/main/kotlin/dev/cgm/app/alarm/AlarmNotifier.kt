package dev.cgm.app.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import dev.cgm.app.Locales
import dev.cgm.app.R
import dev.cgm.app.ui.MainActivity
import dev.cgm.core.AlarmKind
import dev.cgm.core.AlarmSetting
import dev.cgm.core.AlarmSettings
import dev.cgm.core.GlucoseSnapshot

/**
 * Turns alarm decisions into Android notifications.
 *
 * Three Android facts drive the shape of this class:
 *
 *  1. **Channel settings are immutable after creation.** Importance, sound and
 *     DND bypass belong to the user once the channel exists; later calls to
 *     change them are ignored. So each alarm gets *two* channels — one that
 *     bypasses Do Not Disturb and one that does not — and the toggle picks which
 *     one we post to, instead of trying to mutate a channel in place.
 *  2. **DND bypass requires Notification Policy Access**, a separate grant the
 *     user makes in system settings. Without it, `setBypassDnd(true)` is silently
 *     ignored, so the bypass channel is only created once the grant exists.
 *  3. **Alarm audio usage escapes the silent ringer** but still not DND. Using
 *     USAGE_ALARM means a low alarm is audible with the phone on silent, which is
 *     most of what people actually want overnight.
 */
class AlarmNotifier(private val context: Context) {

    private val manager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    /**
     * Resources in the app's chosen language rather than the device's.
     *
     * Note the limit this cannot escape: a channel's *name* is fixed when the
     * channel is created, so channels created before a language change keep their
     * old name in system settings. The notifications themselves follow immediately.
     */
    private val strings: Context = Locales.wrap(context)

    /** Whether the OS will honour a DND bypass for this app at all. */
    fun hasPolicyAccess(): Boolean = manager.isNotificationPolicyAccessGranted

    /**
     * Which channel an alarm posts to. Requesting bypass without the grant falls
     * back to the ordinary channel rather than creating one whose bypass flag the
     * system quietly dropped — a channel that lies about what it will do is worse
     * than one that is honest about not bypassing.
     */
    fun channelId(kind: AlarmKind, overrideDnd: Boolean): String =
        if (overrideDnd && hasPolicyAccess()) "alarm_${kind.name.lowercase()}_dnd"
        else "alarm_${kind.name.lowercase()}"

    fun ensureChannels(settings: AlarmSettings) {
        AlarmKind.entries.forEach { kind ->
            createChannel(kind, bypassDnd = false)
            if (settings[kind].overrideDnd && hasPolicyAccess()) {
                createChannel(kind, bypassDnd = true)
            }
        }
    }

    private fun createChannel(kind: AlarmKind, bypassDnd: Boolean) {
        val id = if (bypassDnd) "alarm_${kind.name.lowercase()}_dnd"
        else "alarm_${kind.name.lowercase()}"
        if (manager.getNotificationChannel(id) != null) return

        val channel = NotificationChannel(id, channelName(kind, bypassDnd), IMPORTANCE).apply {
            description = channelDescription(kind)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder()
                    // Alarm usage so it is audible on a silenced ringer.
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            setBypassDnd(bypassDnd)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * Drop a bypass channel so it can be recreated.
     *
     * Needed when the user grants policy access *after* we already created the
     * channel without bypass — the flag cannot be changed in place. Android
     * restores a deleted channel's user settings if the same id comes back, but
     * the bypass flag we set at creation does take effect, which is what matters.
     */
    fun resetChannels() {
        AlarmKind.entries.forEach { kind ->
            manager.deleteNotificationChannel("alarm_${kind.name.lowercase()}_dnd")
        }
    }

    fun notify(
        kind: AlarmKind,
        setting: AlarmSetting,
        snapshot: GlucoseSnapshot?,
        makeSound: Boolean,
    ) {
        val channel = channelId(kind, setting.overrideDnd)
        val open = PendingIntent.getActivity(
            context,
            kind.ordinal,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, channel)
            .setContentTitle(title(kind, snapshot))
            .setContentText(body(kind, setting, snapshot))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(open)
            .setAutoCancel(false)
            .setOngoing(kind == AlarmKind.URGENT_LOW)
            // Re-notifying without sound is how the engine keeps an alarm visible
            // while staying quiet during recovery or a snooze.
            .setOnlyAlertOnce(!makeSound)
            .addAction(
                0,
                strings.getString(R.string.alarm_snooze),
                PendingIntent.getBroadcast(
                    context,
                    1000 + kind.ordinal,
                    Intent(context, AlarmActionReceiver::class.java)
                        .setAction(AlarmActionReceiver.ACTION_SNOOZE)
                        .putExtra(AlarmActionReceiver.EXTRA_KIND, kind.name),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )

        if (!makeSound) builder.setSilent(true)

        manager.notify(notificationId(kind), builder.build())
    }

    fun clear(kind: AlarmKind) = manager.cancel(notificationId(kind))

    private fun notificationId(kind: AlarmKind) = 100 + kind.ordinal

    private fun title(kind: AlarmKind, snapshot: GlucoseSnapshot?): String {
        if (kind == AlarmKind.SIGNAL_LOSS) {
            return strings.getString(R.string.alarm_no_recent_reading)
        }
        val value = snapshot?.formattedValue() ?: "?"
        val template = when (kind) {
            AlarmKind.URGENT_LOW -> R.string.alarm_title_urgent_low
            AlarmKind.LOW -> R.string.alarm_title_low
            AlarmKind.HIGH -> R.string.alarm_title_high
            else -> R.string.alarm_title_very_high
        }
        return strings.getString(template, value)
    }

    private fun body(
        kind: AlarmKind,
        setting: AlarmSetting,
        snapshot: GlucoseSnapshot?,
    ): String = when (kind) {
        AlarmKind.SIGNAL_LOSS ->
            strings.getString(R.string.alarm_body_signal_loss, setting.afterMillis / 60_000)
        else -> buildString {
            snapshot?.reading?.trend?.glyph?.let { append(it).append("  ") }
            snapshot?.formattedDelta()?.let { append(it).append("  ") }
            append(strings.getString(R.string.alarm_body_threshold, setting.thresholdMgdl.toInt()))
        }
    }

    private fun channelName(kind: AlarmKind, bypassDnd: Boolean): String {
        val base = strings.getString(
            when (kind) {
                AlarmKind.URGENT_LOW -> R.string.zone_urgent_low
                AlarmKind.LOW -> R.string.zone_low
                AlarmKind.HIGH -> R.string.zone_high
                AlarmKind.VERY_HIGH -> R.string.zone_very_high
                AlarmKind.SIGNAL_LOSS -> R.string.zone_signal_loss
            }
        )
        return if (bypassDnd) strings.getString(R.string.alarm_through_dnd, base) else base
    }

    private fun channelDescription(kind: AlarmKind): String = when (kind) {
        AlarmKind.SIGNAL_LOSS -> strings.getString(R.string.alarm_channel_signal_loss)
        else -> strings.getString(R.string.alarm_channel_glucose)
    }

    companion object {
        private const val IMPORTANCE = NotificationManager.IMPORTANCE_HIGH

        /** System screen where the user grants DND bypass to this app. */
        fun policyAccessIntent(): Intent =
            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

        /** System screen for one channel's sound, vibration and DND behaviour. */
        fun channelSettingsIntent(context: Context, channelId: String): Intent =
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)

        fun appNotificationSettingsIntent(context: Context): Intent =
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
}
