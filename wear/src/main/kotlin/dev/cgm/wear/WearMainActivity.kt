package dev.cgm.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.cgm.core.InsulinDose
import dev.cgm.core.InsulinKind
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import dev.cgm.core.Freshness
import dev.cgm.core.FreshnessPolicy
import dev.cgm.core.GlucoseSnapshot
import dev.cgm.core.Zone
import kotlinx.coroutines.delay

/**
 * The watch app itself.
 *
 * Deliberately the least important surface here: what people actually look at is
 * a complication or a tile, and opening an app to read a number defeats the point
 * of having it on a wrist. This exists to prove the link works and to give the
 * value somewhere to live while the other surfaces are built.
 */
class WearMainActivity : ComponentActivity() {

    companion object {
        /** Set by the silent notification, so tapping it lands on the doses. */
        const val EXTRA_SHOW_DOSES = "show_doses"
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = WatchStoreHolder.get(this)
        val openOnDoses = intent?.getBooleanExtra(EXTRA_SHOW_DOSES, false) == true

        setContent {
            MaterialTheme {
                val payload by store.payload.collectAsState()
                // Tapping the notification lands on the doses; tapping anywhere
                // goes back to the reading. Two screens do not need navigation.
                var showDoses by remember { mutableStateOf(openOnDoses) }

                // Ticks whether or not anything arrives. A frozen age over a dead
                // link is the failure this whole app is built to avoid, and it is
                // no less dangerous on a watch than on a phone.
                val now by produceState(initialValue = System.currentTimeMillis()) {
                    while (true) {
                        value = System.currentTimeMillis()
                        delay(1_000)
                    }
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                        .clickable { showDoses = !showDoses },
                    contentAlignment = Alignment.Center,
                ) {
                    val snapshot = payload?.snapshot
                    when {
                        showDoses -> RecentDoses(payload?.recentDoses.orEmpty(), now)
                        snapshot == null -> Waiting()
                        else -> Reading(snapshot, now)
                    }
                }
            }
        }
    }
}

@Composable
private fun Waiting() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(R.string.wear_waiting),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.wear_waiting_hint),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun Reading(snapshot: GlucoseSnapshot, now: Long) {
    // Freshness comes from :core, so the watch cannot decide a reading is current
    // when the phone has already given up on it.
    val freshness = FreshnessPolicy.Default.evaluate(snapshot.reading, now)
    val stale = freshness == Freshness.STALE
    val colour = if (stale) MaterialTheme.colorScheme.onSurfaceVariant else zoneColour(snapshot.zone())

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            snapshot.formattedValue(),
            fontSize = 56.sp,
            fontWeight = FontWeight.Bold,
            color = colour,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(snapshot.unit.suffix, style = MaterialTheme.typography.bodySmall)
            Text(snapshot.reading.trend.glyph, color = colour)
            snapshot.formattedDelta()?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Text(
            ageText(snapshot, freshness, now),
            style = MaterialTheme.typography.bodySmall,
            color = if (stale) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ageText(snapshot: GlucoseSnapshot, freshness: Freshness, now: Long): String {
    val seconds = snapshot.reading.ageMillis(now) / 1000
    val age = when {
        seconds < 60 -> stringResource(R.string.wear_age_seconds, seconds)
        seconds < 120 -> stringResource(R.string.wear_age_minute)
        else -> stringResource(R.string.wear_age_minutes, seconds / 60)
    }
    return when (freshness) {
        Freshness.FRESH -> age
        Freshness.AGING -> "$age · " + stringResource(R.string.wear_late)
        Freshness.STALE -> "$age · " + stringResource(R.string.wear_stale)
    }
}

/**
 * Zone colours, matching the phone's default palette.
 *
 * Duplicated rather than shared because the phone's live in a Compose theme that
 * depends on Android UI types :core must not carry. The values are the same, and
 * the colour-vision palettes follow when the watch gets its own settings.
 */
private fun zoneColour(zone: Zone): Color = when (zone) {
    Zone.URGENT_LOW -> Color(0xFFC62828)
    Zone.LOW -> Color(0xFFE53935)
    Zone.IN_RANGE -> Color(0xFF2E7D32)
    Zone.HIGH -> Color(0xFFF9A825)
    Zone.VERY_HIGH -> Color(0xFFEF6C00)
}

/**
 * Insulin logged in the last three hours.
 *
 * What the silent notification opens onto. The question it answers — "have I
 * already taken something for this" — gets asked while looking at a number that
 * is higher than expected, and having to reach for a phone to answer it is
 * exactly when the wrist stops being useful.
 *
 * The doses travel in the payload, so this works with the phone out of reach.
 */
@Composable
private fun RecentDoses(doses: List<InsulinDose>, now: Long) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.wear_doses_title),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
        )

        if (doses.isEmpty()) {
            Text(
                stringResource(R.string.wear_doses_empty),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            return@Column
        }

        // Newest first: the most recent dose is the one being asked about.
        doses.sortedByDescending { it.givenAtMillis }.forEach { dose ->
            val minutes = ((now - dose.givenAtMillis) / 60_000).coerceAtLeast(0)
            Column(
                Modifier.padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(
                        R.string.wear_dose_line,
                        stringResource(
                            if (dose.kind == InsulinKind.BASAL) R.string.wear_dose_basal
                            else R.string.wear_dose_bolus
                        ),
                        formatUnits(dose.units),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.wear_minutes_ago, minutes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatUnits(units: Double): String =
    if (units % 1.0 == 0.0) units.toInt().toString() else "%.1f".format(units)
