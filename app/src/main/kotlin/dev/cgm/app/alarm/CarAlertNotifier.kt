package dev.cgm.app.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import dev.cgm.app.Locales
import dev.cgm.app.R
import dev.cgm.app.service.StatusBarIcon
import dev.cgm.core.AlarmKind
import dev.cgm.core.GlucoseSnapshot
import dev.cgm.core.ReadingFont
import dev.cgm.core.StatusIconLabel

/**
 * Gets a glucose alarm onto a car screen, by posting it as a message.
 *
 * **Why this shape.** Android Auto renders its own status bar and offers no
 * surface an app can put a number on — there is no equivalent of a Wear
 * complication, and the Car App Library only draws inside an app's own region.
 * So a value cannot sit beside the car's clock the way it sits beside the
 * phone's. What Android Auto *does* surface, over the map and without taking
 * the screen from anything, is a message.
 *
 * It filters hard on that. Gearhead runs every notification through its message
 * parser and drops whatever is not one; on the test phone it rejected both this
 * app and another CGM app with the same line:
 *
 *     GH.MsgNotifParser: No semantic reply action found for dev.cgm.app
 *     GH.MsgNotifParser: No semantic reply action found for com.gluroo.app
 *
 * The reply action it is looking for is [SEMANTIC_ACTION_REPLY]. A
 * [NotificationCompat.MessagingStyle] notification carrying one, plus a
 * mark-as-read action, is a shape it accepts, displays and reads aloud.
 *
 * **This is a deliberate misuse of the messaging surface**, in the same spirit
 * as declaring the car app IOT because no glucose category exists. It would not
 * pass Play review and it is not pretending to be a general solution; it is a
 * sideloaded personal build choosing to be told about a low while driving. The
 * actions exist to satisfy the parser, not to be used: a reply goes nowhere and
 * mark-as-read dismisses the car's copy only.
 *
 * **Deliberately not the alarm itself.** [AlarmNotifier] keeps posting the real
 * alarm, on its own channel, with alarm audio and Do Not Disturb bypass. This is
 * a second, silent notification whose only job is to reach the car. Reshaping
 * the alarm would have traded a working alarm for a car feature.
 */
class CarAlertNotifier(private val context: Context) {

    private val manager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    private val strings: Context = Locales.wrap(context)

    /**
     * Announce [kind] in the car.
     *
     * [title] and [body] are the alarm's own wording, passed in rather than
     * rebuilt, so the car says the same thing the phone shows. A driver hearing
     * a different sentence than the one on their phone would reasonably wonder
     * which to believe.
     */
    fun show(
        kind: AlarmKind,
        snapshot: GlucoseSnapshot?,
        title: String,
        body: String?,
        font: ReadingFont = ReadingFont.SYSTEM,
    ) {
        ensureChannel()

        // The sender is what the car announces before the text, so it has to read
        // as a source and not as a sentence: "message from CGM: glucose 55, low".
        val sender = Person.Builder()
            .setName(strings.getString(R.string.car_alert_sender))
            .setImportant(true)
            .build()

        val spoken = listOfNotNull(title, body).joinToString(". ")

        val builder = NotificationCompat.Builder(context, CHANNEL_CAR)
            .setSmallIcon(StatusBarIcon.of(context, iconLabel(kind, snapshot), font))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setStyle(
                NotificationCompat.MessagingStyle(sender)
                    .addMessage(spoken, System.currentTimeMillis(), sender)
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            // Must not be local-only: that is precisely the flag that stops a
            // notification leaving the phone, and the ongoing reading sets it on
            // purpose for the same reason in reverse.
            .setLocalOnly(false)
            .addInvisibleAction(replyAction(kind))
            .addInvisibleAction(markReadAction(kind))

        manager.notify(notificationId(kind), builder.build())
    }

    /** Takes the car's copy down. The phone's alarm is [AlarmNotifier]'s to clear. */
    fun clear(kind: AlarmKind) = manager.cancel(notificationId(kind))

    fun clearAll() = AlarmKind.entries.forEach(::clear)

    /**
     * Satisfies the parser and does nothing else.
     *
     * [PendingIntent.FLAG_MUTABLE] is required: a [RemoteInput] has to be able to
     * write the typed or dictated text into the intent, and an immutable one is
     * rejected outright.
     */
    private fun replyAction(kind: AlarmKind): NotificationCompat.Action =
        NotificationCompat.Action.Builder(
            0,
            strings.getString(R.string.car_alert_reply),
            broadcast(kind, AlarmActionReceiver.ACTION_CAR_REPLY, mutable = true),
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).build())
            .setShowsUserInterface(false)
            .build()

    private fun markReadAction(kind: AlarmKind): NotificationCompat.Action =
        NotificationCompat.Action.Builder(
            0,
            strings.getString(R.string.car_alert_mark_read),
            broadcast(kind, AlarmActionReceiver.ACTION_CAR_MARK_READ, mutable = false),
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()

    private fun broadcast(kind: AlarmKind, action: String, mutable: Boolean): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_BASE + kind.ordinal + (if (mutable) 0 else AlarmKind.entries.size),
            Intent(context, AlarmActionReceiver::class.java)
                .setAction(action)
                .putExtra(AlarmActionReceiver.EXTRA_KIND, kind.name),
            (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE) or
                PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** The same number the tray shows, so the two cannot disagree at a glance. */
    private fun iconLabel(kind: AlarmKind, snapshot: GlucoseSnapshot?): String = when {
        kind == AlarmKind.SIGNAL_LOSS || snapshot == null -> StatusIconLabel.UNKNOWN
        else -> StatusIconLabel.format(snapshot.reading.valueMgdl, snapshot.unit)
    }

    /**
     * High importance, no sound of its own.
     *
     * The importance is not decoration: Android Auto only surfaces and speaks
     * notifications the phone considers interruptive, so a quiet channel would
     * be silently dropped in the car — the failure this whole class exists to
     * work around. The sound is null because [AlarmNotifier] is already making
     * one through an alarm-usage channel, and two alarms for one low is how
     * people learn to ignore them.
     */
    private fun ensureChannel() {
        if (manager.getNotificationChannel(CHANNEL_CAR) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CAR,
                strings.getString(R.string.car_alert_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = strings.getString(R.string.car_alert_channel_desc)
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    private fun notificationId(kind: AlarmKind) = ID_BASE + kind.ordinal

    companion object {
        const val CHANNEL_CAR = "car_alert"
        const val KEY_REPLY = "car_reply"

        /** Clear of [AlarmNotifier]'s 100-block, so the two never overwrite. */
        private const val ID_BASE = 200
        private const val REQUEST_BASE = 3000
    }
}
