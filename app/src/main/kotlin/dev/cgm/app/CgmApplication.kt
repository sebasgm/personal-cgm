package dev.cgm.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.cgm.app.data.GlucoseRepository
import dev.cgm.app.data.ReadingDatabase
import dev.cgm.app.data.SecureSettings

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
        GlucoseRepository(settings, database.readings())
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_STATUS,
            getString(R.string.channel_status),
            // LOW: the ongoing notification is a display, not an interruption.
            // Alarms in stage 6 get their own high-importance channel.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_status_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_STATUS = "status"
    }
}
