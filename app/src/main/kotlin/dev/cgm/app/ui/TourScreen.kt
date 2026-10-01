package dev.cgm.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.cgm.app.R

/**
 * One stop on the tour: what a part of the app does, and a way to go and see it.
 *
 * [destination] is where "show me" goes. Pushed on top of the tour rather than
 * replacing it, so Back returns here — which is why the page index lives in the
 * view model and not in this composable, whose state does not survive being
 * covered by another screen.
 */
private data class TourStop(
    @StringRes val title: Int,
    @StringRes val body: Int,
    val destination: Destination? = null,
)

/**
 * The tour, in the order someone meets the app.
 *
 * Where the readings come from is second, before anything that displays them,
 * because every number on every later screen depends on it. The caveats are last
 * rather than hidden in the middle: by then there is enough context for "nothing
 * backfills" to mean something, and it is the sentence most worth remembering.
 */
private val STOPS = listOf(
    TourStop(R.string.tour_welcome_title, R.string.tour_welcome_body),
    TourStop(R.string.tour_source_title, R.string.tour_source_body, Destination.Settings),
    TourStop(R.string.tour_home_title, R.string.tour_home_body, Destination.Home),
    TourStop(R.string.tour_chart_title, R.string.tour_chart_body, Destination.Home),
    TourStop(R.string.tour_alarms_title, R.string.tour_alarms_body, Destination.Alarms),
    TourStop(R.string.tour_ranges_title, R.string.tour_ranges_body, Destination.Ranges),
    TourStop(R.string.tour_diary_title, R.string.tour_diary_body, Destination.Diary),
    TourStop(R.string.tour_logbook_title, R.string.tour_logbook_body, Destination.Logbook),
    TourStop(R.string.tour_trends_title, R.string.tour_trends_body, Destination.Trends),
    TourStop(R.string.tour_report_title, R.string.tour_report_body, Destination.Trends),
    TourStop(R.string.tour_access_title, R.string.tour_access_body, Destination.Accessibility),
    TourStop(R.string.tour_reminders_title, R.string.tour_reminders_body, Destination.Reminders),
    TourStop(R.string.tour_limits_title, R.string.tour_limits_body),
)

/** How many stops there are, for anything outside that needs to know. */
val TOUR_LENGTH = STOPS.size

/**
 * Renders `**like this**` in bold, and nothing else.
 *
 * These are paragraphs rather than labels, and the one phrase that carries each
 * one should be findable without reading the whole thing. A markdown library for
 * a single construct would be absurd; rendering the asterisks literally would be
 * worse. Unpaired markers are left as typed, because silently eating a stray
 * asterisk hides the typo instead of showing it.
 *
 * A screen reader reads the text and ignores the span, which is the correct
 * behaviour: bold is emphasis, not content.
 */
private fun emphasised(text: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    while (true) {
        val open = text.indexOf(MARKER, cursor)
        if (open < 0) break
        val close = text.indexOf(MARKER, open + MARKER.length)
        if (close < 0) break

        append(text, cursor, open)
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(text, open + MARKER.length, close)
        }
        cursor = close + MARKER.length
    }
    append(text, cursor, text.length)
}

private const val MARKER = "**"

/**
 * A guided walk through the app.
 *
 * Pages of plain text rather than spotlights drawn over the real screens. Partly
 * because an overlay has to know where things are on screen and breaks whenever
 * they move, and partly because this app is built to be read aloud — a screen
 * reader handles a paragraph and a button, and makes nothing at all of a hole cut
 * in a dimmed overlay.
 */
@Composable
fun TourScreen(
    viewModel: CgmViewModel,
    onOpen: (Destination) -> Unit,
    onFinish: () -> Unit,
) {
    val index by viewModel.tourStop.collectAsState()
    val stop = STOPS[index.coerceIn(0, STOPS.lastIndex)]
    val isLast = index >= STOPS.lastIndex

    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp),
    ) {
        LinearProgressIndicator(
            progress = { (index + 1).toFloat() / STOPS.size },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                stringResource(R.string.tour_step, index + 1, STOPS.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Always available. A tour you cannot leave is not a tour.
            TextButton(onClick = {
                viewModel.finishTour()
                onFinish()
            }) { Text(stringResource(R.string.tour_skip)) }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(stop.title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                emphasised(stringResource(stop.body)),
                style = MaterialTheme.typography.bodyLarge,
            )

            stop.destination?.let { destination ->
                Spacer(Modifier.height(20.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            stringResource(R.string.tour_go_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { onOpen(destination) }) {
                            Text(stringResource(R.string.tour_go))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { viewModel.moveTour(-1) },
                enabled = index > 0,
            ) { Text(stringResource(R.string.tour_back)) }

            Button(
                onClick = {
                    if (isLast) {
                        viewModel.finishTour()
                        onFinish()
                    } else {
                        viewModel.moveTour(1)
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    stringResource(if (isLast) R.string.tour_done else R.string.tour_next)
                )
            }
        }
    }
}
