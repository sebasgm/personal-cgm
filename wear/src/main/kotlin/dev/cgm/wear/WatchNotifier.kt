package dev.cgm.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.cgm.core.Freshness
import dev.cgm.core.FreshnessPolicy
import dev.cgm.core.WatchPayload

/**
 * A silent notification on the watch carrying the current value.
 *
 * Posted by the watch itself rather than bridged from the phone, for one reason
 * that decides the whole design: a bridged notification's tap opens the phone
 * app, because the pending intent it carries belongs to the phone. Posting
 * locally is what lets tapping it open something *here* — in this case the recent
 * insulin, which is the question usually being asked when someone looks at a
 * number they did not expect.
 *
 * Silent and low importance. It is a display, not an interruption; alarms are
 * what interrupt, and those still come from the phone.
 */
class WatchNotifier(private val context: Context) {

    private val manager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    fun ensureChannel() {
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.wear_channel_reading),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.wear_channel_reading_desc)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
    }

    fun show(payload: WatchPayload, nowMillis: Long = System.currentTimeMillis()) {
        ensureChannel()
        val snapshot = payload.snapshot
        val freshness = FreshnessPolicy.Default.evaluate(snapshot.reading, nowMillis)

        val title = buildString {
            append(snapshot.formattedValue())
            append(' ')
            append(snapshot.unit.suffix)
            append("  ")
            append(snapshot.reading.trend.glyph)
            snapshot.formattedDelta()?.let { append("  ").append(it) }
        }

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, WearMainActivity::class.java)
                .putExtra(WearMainActivity.EXTRA_SHOW_DOSES, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setContentTitle(title)
                .setContentText(subtitle(payload, freshness, nowMillis))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(open)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                // Re-posted on every reading, so a swipe dismisses it until the
                // next one rather than for good.
                .setOngoing(false)
                // The age has to keep moving between readings. The platform renders
                // this from the timestamp with no further work from us, which is the
                // one thing that stays true when updates are late.
                .setWhen(snapshot.reading.timestampMillis)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .build()
        )
    }

    private fun subtitle(payload: WatchPayload, freshness: Freshness, nowMillis: Long): String {
        if (freshness == Freshness.STALE) return context.getString(R.string.wear_stale)

        val doses = payload.recentDoses
        return if (doses.isEmpty()) {
            context.getString(R.string.wear_no_recent_doses)
        } else {
            context.getString(
                R.string.wear_recent_units,
                formatUnits(doses.sumOf { it.units }),
            )
        }
    }

    private fun formatUnits(units: Double): String =
        if (units % 1.0 == 0.0) units.toInt().toString() else "%.1f".format(units)

    companion object {
        const val CHANNEL = "reading"
        const val NOTIFICATION_ID = 1
    }
}
