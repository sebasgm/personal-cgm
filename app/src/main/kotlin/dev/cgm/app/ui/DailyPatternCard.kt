package dev.cgm.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cgm.app.R
import dev.cgm.core.GlucoseThresholds
import dev.cgm.core.GlucoseUnit
import dev.cgm.core.TimeOfDayBucket
import dev.cgm.core.TimeOfDayProfile
import dev.cgm.core.ValueAxis

/**
 * The shape of a typical day, in three-hour slices.
 *
 * The view LibreView shows and issue #8 asked for, with one deliberate change:
 * the bars are **bands**. A slice averaging 140 with an interquartile range of
 * 60–260 and one averaging 140 with a range of 125–155 are completely different
 * situations, and a chart of averages draws them identically. The spread is where
 * the information is, so the spread is what gets drawn.
 *
 * Each slice is a box from the 25th to the 75th percentile with the median marked
 * inside it, and whiskers reaching the 10th and 90th — the same construction as a
 * clinical Ambulatory Glucose Profile, bucketed to the eight slices the Libre app
 * uses so it reads familiarly.
 */
@Composable
fun DailyPatternCard(
    profile: TimeOfDayProfile,
    thresholds: GlucoseThresholds,
    unit: GlucoseUnit,
) {
    Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.trends_daily_pattern),
                style = MaterialTheme.typography.titleSmall,
            )

            if (!profile.hasData) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.trends_daily_pattern_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.trends_daily_pattern_days, profile.dayCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            PatternChart(
                profile = profile,
                thresholds = thresholds,
                unit = unit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
            )

            Spacer(Modifier.height(10.dp))
            profile.mostVariable()?.takeIf { it.hasData }?.let {
                Text(
                    stringResource(R.string.trends_most_variable, it.label()),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                stringResource(R.string.trends_daily_pattern_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (profile.spansMultipleZones) {
                Text(
                    stringResource(
                        R.string.trends_daily_pattern_zones,
                        profile.zoneIds.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (profile.dayCount < TimeOfDayProfile.MIN_DAYS) {
                Text(
                    stringResource(
                        R.string.trends_daily_pattern_thin,
                        TimeOfDayProfile.MIN_DAYS,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun PatternChart(
    profile: TimeOfDayProfile,
    thresholds: GlucoseThresholds,
    unit: GlucoseUnit,
    modifier: Modifier = Modifier,
) {
    val band = ChartColors.band
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val vision = LocalColorVision.current
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)

    Canvas(modifier) {
        val gutter = 34.dp.toPx()
        val axisHeight = 16.dp.toPx()
        val plotWidth = (size.width - gutter).coerceAtLeast(1f)
        val plotHeight = (size.height - axisHeight).coerceAtLeast(1f)

        // Scale to the bands rather than to single values, so a wide slice is not
        // clipped at the top of the chart.
        val range = profile.valueRange()
        val axis = ValueAxis.of(
            listOfNotNull(range?.start, range?.endInclusive),
            thresholds,
        )
        fun y(value: Double) = (1f - axis.fraction(value)) * plotHeight

        drawRect(
            color = band,
            topLeft = Offset(gutter, y(thresholds.highMgdl)),
            size = Size(plotWidth, (y(thresholds.lowMgdl) - y(thresholds.highMgdl)).coerceAtLeast(0f)),
        )

        axis.gridLines().forEach { level ->
            drawLine(
                color = grid,
                start = Offset(gutter, y(level)),
                end = Offset(size.width, y(level)),
                strokeWidth = 1.dp.toPx(),
            )
            drawText(
                textMeasurer = measurer,
                text = unit.format(level),
                topLeft = Offset(0f, y(level) - 7.dp.toPx()),
                style = labelStyle,
            )
        }

        val slotWidth = plotWidth / profile.buckets.size
        val boxWidth = slotWidth * 0.52f

        profile.buckets.forEach { bucket ->
            val centre = gutter + slotWidth * (bucket.startHour / 3) + slotWidth / 2f

            drawText(
                textMeasurer = measurer,
                text = "%02d".format(bucket.startHour),
                topLeft = Offset(centre - 7.dp.toPx(), plotHeight + 2.dp.toPx()),
                style = labelStyle,
            )

            if (!bucket.hasData) return@forEach
            drawSlice(bucket, centre, boxWidth, vision, thresholds, ::y)
        }
    }
}

/**
 * One slice: whisker, box, median.
 *
 * Coloured by the zone its median falls in, so the chart keeps the app's rule
 * that colour always means the same thing — green here is the same green as a
 * reading in range.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSlice(
    bucket: TimeOfDayBucket,
    centre: Float,
    boxWidth: Float,
    vision: dev.cgm.core.ColorVision,
    thresholds: GlucoseThresholds,
    y: (Double) -> Float,
) {
    val median = bucket.median ?: return
    val colour: Color = ZoneColors.of(thresholds.classify(median), vision)

    // Whisker first, so the box sits over it.
    val low = bucket.p10
    val high = bucket.p90
    if (low != null && high != null) {
        drawLine(
            color = colour.copy(alpha = 0.55f),
            start = Offset(centre, y(high)),
            end = Offset(centre, y(low)),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }

    val top = bucket.p75
    val bottom = bucket.p25
    if (top != null && bottom != null) {
        drawRect(
            color = colour.copy(alpha = 0.35f),
            topLeft = Offset(centre - boxWidth / 2f, y(top)),
            size = Size(boxWidth, (y(bottom) - y(top)).coerceAtLeast(1f)),
        )
    }

    drawLine(
        color = colour,
        start = Offset(centre - boxWidth / 2f, y(median)),
        end = Offset(centre + boxWidth / 2f, y(median)),
        strokeWidth = 3.dp.toPx(),
        cap = StrokeCap.Round,
    )
}
