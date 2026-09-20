package dev.cgm.app.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Whether Android will leave our polling alone.
 *
 * Being exempt from battery optimisation does not by itself keep the CPU awake —
 * that is the wake lock's job — but without it the system is free to defer our
 * network access and throttle the service during Doze, which produces exactly
 * the same symptom: readings missing overnight and nowhere to recover them from,
 * because LibreLinkUp only serves about twelve hours of 15-minute history.
 */
object BatteryExemption {

    fun isExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)

    /**
     * The direct request dialog. Google restricts this intent on Play, which does
     * not apply to a sideloaded personal build; [settingsIntent] is the fallback
     * if an OEM has removed it.
     */
    fun requestIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))

    fun settingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}
