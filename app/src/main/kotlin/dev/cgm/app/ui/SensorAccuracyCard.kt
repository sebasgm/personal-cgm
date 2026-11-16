package dev.cgm.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.cgm.app.R
import dev.cgm.core.ClarkeZone
import dev.cgm.core.GlucoseFusion
import dev.cgm.core.GlucoseUnit
import dev.cgm.core.SensorAgreement
import dev.cgm.core.TrendBucket
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How far the sensor has been from the meter.
 *
 * The one card in this app that scores the app's own data. Everything else on Trends
 * describes what the sensor said; this says how much the sensor should be believed,
 * and it can only exist because finger-prick results are now recorded — which is the
 * whole reason they are.
 *
 * Three figures and a grid. The figures are the headline: how many comparisons there
 * are, how far apart the two instruments were on average, and how many of those
 * comparisons fall in the two Clarke zones that would not have led anyone astray. The
 * grid underneath is the clinical reading, and the zones that matter are spelled out
 * in words rather than left as letters — "D" means nothing to the person holding the
 * phone, and "would miss what needed detecting" means everything.
 *
 * The unsafe zones are drawn in the error colour, which in this app means "what you
 * are looking at may not be true". Here it means exactly that about the sensor.
 */
@Composable
fun SensorAccuracyCard(agreement: SensorAgreement, unit: GlucoseUnit) {
    Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.trends_accuracy),
                style = MaterialTheme.typography.titleSmall,
            )

            if (!agreement.hasData) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.trends_accuracy_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Metric(
                    stringResource(R.string.trends_accuracy_pairs),
                    agreement.count.toString(),
                )
                Metric(
                    stringResource(R.string.trends_accuracy_mard),
                    agreement.meanAbsoluteRelativeDifference
                        ?.let { "%.1f%%".format(it * 100) } ?: "—",
                )
                Metric(
                    stringResource(R.string.trends_accuracy_benign),
                    agreement.benignFraction
                        ?.let { "${(it * 100).roundToInt()}%" } ?: "—",
                )
            }

            Spacer(Modifier.height(14.dp))
            // Which way it leans, in a sentence. A signed median in a metric tile would
            // be a number with a minus sign in front of it and no statement of what the
            // sign means; "the sensor read 8 above the strip" cannot be misread.
            agreement.medianDifferenceMgdl?.let { bias ->
                Text(
                    text = when {
                        // Under half a unit of the display's own resolution is not a
                        // lean, it is rounding, and claiming a direction for it would be
                        // reading noise as a finding.
                        abs(unit.from(bias)) < leanFloor(unit) ->
                            stringResource(R.string.trends_accuracy_bias_none)
                        bias > 0 -> stringResource(
                            R.string.trends_accuracy_bias_high,
                            unit.format(abs(bias)),
                            unit.suffix,
                        )
                        else -> stringResource(
                            R.string.trends_accuracy_bias_low,
                            unit.format(abs(bias)),
                            unit.suffix,
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            agreement.medianGapMillis?.let { gap ->
                Text(
                    stringResource(
                        R.string.trends_accuracy_gap,
                        (gap / 60_000.0).roundToInt(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(14.dp))
            CLARKE_ORDER.forEach { (zone, labelRes) ->
                val count = agreement.zoneCounts[zone] ?: 0
                // A zone nobody landed in is left out rather than shown as zero. Five
                // rows of which three say "0" reads as a list of hazards; two rows that
                // name what happened reads as a result.
                if (count == 0) return@forEach
                ZoneRow(
                    label = stringResource(labelRes),
                    count = count,
                    fraction = count.toDouble() / agreement.count,
                    unsafe = zone.isUnsafe,
                )
            }

            TrendBreakdown(agreement, unit)

            if (agreement.isThin) {
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.trends_accuracy_thin, SensorAgreement.MIN_PAIRS),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.trends_accuracy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The same comparison again, grouped by what the trace was doing.
 *
 * This is the part of the card that can tell a sensor reading high from a sensor
 * reading late. Both produce the same pooled median and call for opposite responses:
 * a lean that survives in the flat row is the instrument; a lean that appears only in
 * the falling row is physiology, and the honest conclusion is that the sensor is
 * accurate and behind.
 *
 * Signed figures here, where the headline bias is a sentence — three rows of prose
 * would be unreadable, so the note above them carries what the sign means and the
 * rows carry the numbers. That note is not optional decoration: a bare "+18" with no
 * statement of which instrument is which is the kind of number this card exists to
 * avoid printing.
 */
@Composable
private fun TrendBreakdown(agreement: SensorAgreement, unit: GlucoseUnit) {
    val buckets = agreement.byTrend
    // Nothing to compare against nothing. One bucket is not a breakdown either: it
    // would restate the headline figure under a second heading and imply a contrast
    // that the data has not got.
    if (buckets.size < 2) return

    Spacer(Modifier.height(14.dp))
    Text(
        stringResource(R.string.trends_accuracy_by_trend),
        style = MaterialTheme.typography.titleSmall,
    )
    Text(
        stringResource(
            R.string.trends_accuracy_by_trend_note,
            unit.format(GlucoseFusion.FLAT_RATE_MGDL_PER_MINUTE),
            unit.suffix,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
    )

    TREND_ORDER.forEach { (bucket, labelRes) ->
        val slice = buckets[bucket] ?: return@forEach
        TrendRow(
            label = stringResource(labelRes),
            value = stringResource(
                R.string.trends_accuracy_trend_row,
                slice.count,
                signed(slice.medianDifferenceMgdl, unit),
                slice.meanAbsoluteRelativeDifference
                    ?.let { "%.0f%%".format(it * 100) } ?: "—",
            ),
        )
    }

    if (agreement.unknownTrendCount > 0) {
        Text(
            stringResource(
                R.string.trends_accuracy_trend_unknown,
                agreement.unknownTrendCount,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun TrendRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(0.4f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A signed difference, with the plus kept.
 *
 * [GlucoseUnit.format] drops a leading plus, which is right for a reading and wrong
 * for a difference: "8" in a column of differences reads as a magnitude, and the whole
 * point of this column is the direction.
 */
private fun signed(mgdl: Double?, unit: GlucoseUnit): String {
    if (mgdl == null) return "—"
    val magnitude = unit.format(abs(mgdl))
    // Below the display's own resolution there is no direction to report, and "+0"
    // would claim one. Same floor the headline sentence uses.
    if (abs(unit.from(mgdl)) < leanFloor(unit)) return magnitude
    return if (mgdl > 0) "+$magnitude" else "\u2212$magnitude"
}

/** Falling first: it is the row where an accurate sensor looks worst. */
private val TREND_ORDER = listOf(
    TrendBucket.FALLING to R.string.trends_accuracy_trend_falling,
    TrendBucket.FLAT to R.string.trends_accuracy_trend_flat,
    TrendBucket.RISING to R.string.trends_accuracy_trend_rising,
)

@Composable
private fun ZoneRow(label: String, count: Int, fraction: Double, unsafe: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = if (unsafe) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.fillMaxWidth(0.72f),
        )
        Text(
            "$count · ${(fraction * 100).roundToInt()}%",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = if (unsafe) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * Below this, a lean is rounding rather than a direction.
 *
 * Half of what the display can show: a tenth of a millimole, or a whole mg/dL. A
 * median difference of 0.4 mg/dL would otherwise print as "the sensor reads 0 above
 * the strip", which is a sentence that says a thing and also unsays it.
 */
private fun leanFloor(unit: GlucoseUnit): Double =
    if (unit == GlucoseUnit.MMOLL) 0.05 else 0.5

/** Best first, and the hazardous three last, where they are read as the exception. */
private val CLARKE_ORDER = listOf(
    ClarkeZone.A to R.string.clarke_a,
    ClarkeZone.B to R.string.clarke_b,
    ClarkeZone.C to R.string.clarke_c,
    ClarkeZone.D to R.string.clarke_d,
    ClarkeZone.E to R.string.clarke_e,
)
