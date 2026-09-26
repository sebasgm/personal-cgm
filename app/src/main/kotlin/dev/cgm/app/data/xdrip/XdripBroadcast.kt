package dev.cgm.app.data.xdrip

import android.content.Intent
import dev.cgm.core.GlucoseReading
import dev.cgm.core.GlucoseSnapshot
import dev.cgm.core.GlucoseThresholds
import dev.cgm.core.GlucoseUnit
import dev.cgm.core.PushedReading
import dev.cgm.core.SourceResult
import dev.cgm.core.TrendArrow

/**
 * xDrip+'s local broadcast, as a reading.
 *
 * This is the whole of the xDrip+ integration: one action, a handful of extras,
 * no account, no network and no protocol to keep up with. What it buys is every
 * sensor xDrip+ can read — Dexcom, Medtronic, the Chinese sensors it supports —
 * for the price of parsing an Intent. See docs/09-sources.md.
 *
 * The user has to switch it on inside xDrip+: Settings → Inter-app settings →
 * "Broadcast locally", with "Compatible Broadcast" on so the extras below are
 * actually populated. Nothing here can detect that it is off; it simply looks
 * like a source that never sends, which is why the Settings screen says so.
 */
object XdripBroadcast {

    const val ACTION = "com.eveningoutpost.dexdrip.BgEstimate"

    /** xDrip+ says explicitly when it has nothing, rather than going quiet. */
    const val ACTION_NO_DATA = "com.eveningoutpost.dexdrip.BgEstimateNoData"

    private const val EXTRA_VALUE = "com.eveningoutpost.dexdrip.Extras.BgEstimate"
    private const val EXTRA_TIME = "com.eveningoutpost.dexdrip.Extras.Time"
    private const val EXTRA_SLOPE_NAME = "com.eveningoutpost.dexdrip.Extras.BgSlopeName"
    private const val EXTRA_SOURCE = "com.eveningoutpost.dexdrip.Extras.SourceDesc"

    /**
     * Reads an intent, or null when it does not carry a usable reading.
     *
     * Only the extras are pulled out here; judging them is [PushedReading]'s job,
     * which lives outside the Android layer so it can be tested without a device.
     */
    fun readingFrom(intent: Intent, nowMillis: Long): GlucoseReading? {
        if (intent.action != ACTION) return null

        // xDrip has sent this as a double, a float and an int over the years.
        val mgdl = when (val raw = intent.extras?.get(EXTRA_VALUE)) {
            is Double -> raw
            is Float -> raw.toDouble()
            is Int -> raw.toDouble()
            is Long -> raw.toDouble()
            else -> null
        }

        return PushedReading.of(
            mgdl = mgdl,
            timestampMillis = intent.getLongExtra(EXTRA_TIME, 0L),
            trend = TrendArrow.fromXdripSlopeName(intent.getStringExtra(EXTRA_SLOPE_NAME)),
            nowMillis = nowMillis,
        )
    }

    /**
     * What the source describes itself as, for the Settings screen.
     *
     * Worth showing: "G6 Native" or "Libre2" tells the user which sensor is
     * actually behind the numbers, which this app otherwise has no way to know.
     */
    fun sourceDescription(intent: Intent): String? =
        intent.getStringExtra(EXTRA_SOURCE)?.takeIf { it.isNotBlank() }

    /**
     * Wraps a reading as a source result.
     *
     * No thresholds and no unit come over the broadcast, so these are the app's
     * own defaults and the user's overrides are applied on top downstream, exactly
     * as they are to LibreLinkUp's account band. No history either: xDrip+ sends
     * one reading at a time, and the app's own database is the history.
     */
    fun resultFor(reading: GlucoseReading): SourceResult = SourceResult(
        snapshot = GlucoseSnapshot(
            reading = reading,
            thresholds = GlucoseThresholds.Default,
            unit = GlucoseUnit.MGDL,
        ),
    )
}
