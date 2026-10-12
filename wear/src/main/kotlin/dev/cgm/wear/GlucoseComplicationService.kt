package dev.cgm.wear

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import androidx.wear.watchface.complications.data.CountUpTimeReference
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import dev.cgm.core.GlucoseSnapshot
import dev.cgm.core.WatchPayload
import java.time.Instant

/**
 * Puts the current reading on any watch face that has a slot for it.
 *
 * A complication rather than a watch face, which is the better trade for this
 * app: it works on the faces the user already likes, including the ones that
 * shipped with the watch, and it leaves the face's own layout, battery behaviour
 * and styling alone. The cost is that the platform owns the refresh.
 *
 * **Push, not poll.** `UPDATE_PERIOD_SECONDS` is zero in the manifest, so the
 * platform never asks us on a timer. [WatchDataService] requests an update when a
 * reading lands, which is the only moment there is anything new to say. A timer
 * would wake the watch on a schedule unrelated to the sensor and still be late.
 *
 * **The age is rendered by the platform.** The number's staleness is the thing
 * most likely to mislead, and a complication cannot refresh itself often enough
 * to count minutes — update requests are averaged to roughly one every five. So
 * the age is a [TimeDifferenceComplicationText] counting up from the reading's own
 * timestamp: the face renders it live, correctly, for as long as nothing arrives,
 * even with the phone switched off.
 */
class GlucoseComplicationService : ComplicationDataSourceService() {

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener,
    ) {
        val payload = WatchStoreHolder.get(this).payload.value
        // Logged because in a push model there is otherwise no way to tell the two
        // failures apart from the wrist. A complication stuck on "--" can mean the
        // platform never asked us — the update request was dropped, the budget was
        // spent, the slot is on a face that is not on screen — or that it asked and
        // we had nothing to say. Those have opposite fixes and look identical.
        //
        // The reading's *age* rather than its value: this lands in the system log,
        // which is read by bug reports and is the wrong place for a glucose figure.
        // The age is what diagnoses the pipeline anyway — a value we are happy with
        // and a value an hour stale are the two different faults.
        Log.i(
            TAG,
            "asked for ${request.complicationType}; held=" + (
                payload?.snapshot?.reading
                    ?.let { "a reading ${it.ageMillis(System.currentTimeMillis()) / 1000}s old" }
                    ?: "nothing"
                ),
        )
        listener.onComplicationData(complicationFor(request.complicationType, payload))
    }

    /**
     * What the complication picker shows while choosing.
     *
     * Real-looking rather than the live value: the preview is rendered in a list
     * before anything is selected, and a blank or dashed entry reads as a broken
     * data source rather than as one with no reading yet.
     */
    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        previewFor(type)

    private fun complicationFor(
        type: ComplicationType,
        payload: WatchPayload?,
    ): ComplicationData {
        val snapshot = payload?.snapshot ?: return noData(type)
        val value = snapshot.formattedValue()
        val arrow = snapshot.reading.trend.glyph
        val age = ageText(snapshot)

        return when (type) {
            // The arrow travels with the number rather than in the title, because
            // the title is where the age goes and the age is the more important of
            // the two. A value with no age is the failure this app exists to avoid.
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = plain("$value$arrow"),
                contentDescription = plain(describe(snapshot)),
            )
                .setTitle(age)
                .setTapAction(openApp())
                .build()

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = plain(buildString {
                    append(value).append(' ').append(snapshot.unit.suffix)
                    append(' ').append(arrow)
                    snapshot.formattedDelta()?.let { append("  ").append(it) }
                }),
                contentDescription = plain(describe(snapshot)),
            )
                .setTitle(age)
                .setTapAction(openApp())
                .build()

            // The gauge spans the in-range band, not the sensor's whole scale: the
            // question a glanceable arc answers is "where am I in my target", and
            // a 40-to-400 arc leaves every ordinary reading in the same few
            // degrees. Out-of-range values therefore sit pinned at an end, which
            // the number beside the arc disambiguates.
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = snapshot.thresholds.fraction(snapshot.reading.valueMgdl),
                min = 0f,
                max = 1f,
                contentDescription = plain(describe(snapshot)),
            )
                .setText(plain("$value$arrow"))
                .setTitle(age)
                .setTapAction(openApp())
                .build()

            else -> noData(type)
        }
    }

    /**
     * How long ago the reading was taken, counted by the face.
     *
     * [CountUpTimeReference] from the reading's own timestamp, so this stays
     * truthful without us. A number we formatted once would freeze at whatever it
     * said when the update arrived, and then quietly understate the age for as
     * long as the feed was down — which is precisely when it matters.
     */
    private fun ageText(snapshot: GlucoseSnapshot): ComplicationText =
        TimeDifferenceComplicationText.Builder(
            style = TimeDifferenceStyle.SHORT_SINGLE_UNIT,
            countUpTimeReference = CountUpTimeReference(
                Instant.ofEpochMilli(snapshot.reading.timestampMillis)
            ),
        ).build()

    /** Read aloud by TalkBack, so it spells out what the glyphs compress. */
    private fun describe(snapshot: GlucoseSnapshot): String = buildString {
        append(snapshot.formattedValue()).append(' ').append(snapshot.unit.suffix)
        append(", ").append(snapshot.reading.trend.name.lowercase().replace('_', ' '))
    }

    private fun noData(type: ComplicationType): ComplicationData = when (type) {
        ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
            text = plain(NO_VALUE),
            contentDescription = plain(getString(R.string.comp_no_reading)),
        ).setTapAction(openApp()).build()

        ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
            text = plain(getString(R.string.comp_no_reading)),
            contentDescription = plain(getString(R.string.comp_no_reading)),
        ).setTapAction(openApp()).build()

        // Zero rather than an empty gauge: a ranged complication with no value is
        // drawn as a full arc by some faces, which would read as a maximum.
        ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
            value = 0f,
            min = 0f,
            max = 1f,
            contentDescription = plain(getString(R.string.comp_no_reading)),
        ).setText(plain(NO_VALUE)).setTapAction(openApp()).build()

        else -> ShortTextComplicationData.Builder(
            text = plain(NO_VALUE),
            contentDescription = plain(getString(R.string.comp_no_reading)),
        ).build()
    }

    private fun previewFor(type: ComplicationType): ComplicationData? = when (type) {
        ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
            text = plain("112→"),
            contentDescription = plain(getString(R.string.comp_name)),
        ).setTitle(plain("2m")).build()

        ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
            text = plain("112 mg/dL →  +4 / 5m"),
            contentDescription = plain(getString(R.string.comp_name)),
        ).setTitle(plain("2m")).build()

        ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
            value = 0.4f,
            min = 0f,
            max = 1f,
            contentDescription = plain(getString(R.string.comp_name)),
        ).setText(plain("112→")).setTitle(plain("2m")).build()

        else -> null
    }

    /** Tapping goes to the watch app, which has the chart and the recent doses. */
    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, WearMainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun plain(text: String): ComplicationText =
        PlainComplicationText.Builder(text).build()

    companion object {
        /** Two characters, so it does not reflow a slot sized for a number. */
        private const val NO_VALUE = "--"

        private const val TAG = "GlucoseComplication"

        /**
         * Asks the platform to come and fetch a new value.
         *
         * The requests are averaged — roughly one every five minutes is the budget
         * — so this is a request and not a guarantee. It is still the right call
         * on every reading rather than on a timer: the platform smooths the rate
         * better than we could guess at it, and asking only when there is
         * something new means the budget is never spent on a repeat.
         */
        fun requestUpdate(context: Context) {
            runCatching {
                ComplicationDataSourceUpdateRequester
                    .create(
                        context = context,
                        complicationDataSourceComponent = ComponentName(
                            context,
                            GlucoseComplicationService::class.java,
                        ),
                    )
                    .requestUpdateAll()
            }
        }
    }
}
