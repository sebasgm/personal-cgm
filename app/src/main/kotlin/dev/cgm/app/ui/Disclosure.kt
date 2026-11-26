package dev.cgm.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.cgm.app.R

/**
 * A heading that hides a long list of logged rows until it is asked for.
 *
 * Three screens ended up with the same shape: a short answer, and then every row
 * the answer was computed from. A fortnight of daily totals, a month of diary
 * entries and a report's thirty ranges are each worth having, and none of them is
 * worth putting between someone and the control below it — which is exactly what
 * they did. The export buttons sat under the report's range rows and the insulin
 * entries under two weeks of day totals, so the two things most often wanted on
 * those screens were the two things furthest down them.
 *
 * Collapsed by default, deliberately. These rows are a record to consult, not a
 * figure to watch: the screens above them already carry the summaries, and
 * anything that has to be read on arrival does not belong behind a tap.
 *
 * The count goes in the header rather than being left to be discovered by
 * opening it. A closed section that says "14 days" is a statement about the data;
 * one that says nothing is a drawer, and a drawer is the thing people stop
 * opening.
 *
 * Plain `if` rather than `AnimatedVisibility`: the content can be thirty rows of
 * text, and animating that costs a frame drop on arrival to show a transition
 * nobody is waiting on.
 */
@Composable
fun CollapsibleRows(
    title: String,
    /** How many rows are inside, already written out — "14 days", "37 entries". */
    count: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                // The label is what a screen reader announces for the gesture, so it
                // says what the tap does rather than leaving "double tap to activate"
                // to stand in for it.
                .clickable(
                    onClickLabel = stringResource(
                        if (expanded) R.string.common_hide else R.string.common_show
                    ),
                ) { expanded = !expanded }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                note?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Text(
                count,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // A drawn glyph rather than a Material icon: this app carries no icon
            // dependency and draws its arrows as text everywhere else, and a text
            // glyph grows with the reader's font scale for free.
            Text(
                if (expanded) "▴" else "▾",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(2.dp))
        }

        if (expanded) content()
    }
}
