package dev.cgm.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.cgm.app.data.GlucoseRepository
import dev.cgm.app.data.ReadingDatabase
import dev.cgm.app.data.RollupWriter
import dev.cgm.app.data.SecureSettings
import kotlinx.coroutines.runBlocking

/**
 * Hand-rolled dependency wiring.
 *
 * A DI framework would earn its keep at maybe three times this size; right now it
 * would only add build time and indirection.
 */
class CgmApplication : Application() {

    val settings: SecureSettings by lazy { SecureSettings(this) }
    private val database: ReadingDatabase by lazy { ReadingDatabase.create(this) }
    val repository: GlucoseRepository by lazy {
        GlucoseRepository(
            settings,
            database.readings(),
            RollupWriter(database.readings(), database.rollups()),
            database.doses(),
        )
    }

    override fun onCreate() {
        super.onCreate()
        // One blocking read, before anything can render or post a notification.
        // Everything downstream then reads Locales.current synchronously.
        Locales.current = runBlocking { runCatching { settings.languageTagOnce() }.getOrNull() }
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        // Wrapped, or the channel keeps the device's language for ever: channel
        // names are fixed at creation and cannot be changed afterwards.
        val strings = Locales.wrap(this)
        val channel = NotificationChannel(
            CHANNEL_STATUS,
            strings.getString(R.string.channel_status),
            // LOW: the ongoing notification is a display, not an interruption.
            // Alarms in stage 6 get their own high-importance channel.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = strings.getString(R.string.channel_status_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_STATUS = "status"
    }
}
