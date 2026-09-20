package dev.cgm.app.ui

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cgm.app.R
import dev.cgm.core.InsulinDayTotals
import dev.cgm.core.InsulinDose
import dev.cgm.core.InsulinKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Logging insulin, and seeing what has been logged (issue #5).
 *
 * The units control is a stepper rather than a text field, for two reasons. Pens and
 * syringes deliver half units, so a free-text field offers a precision that does not
 * exist; and a decimal typed on a Spanish keyboard arrives with a comma, which a
 * naive parse reads as nothing at all. A stepper cannot be mistyped and needs no
 * parsing, which on a dose log is worth more than the flexibility it gives up.
 */
@Composable
fun DosesScreen(viewModel: CgmViewModel) {
    val doses by viewModel.doses.collectAsState()
    val zone = remember { ZoneId.systemDefault() }

    var kind by remember { mutableStateOf(InsulinKind.BOLUS) }
    var units by remember { mutableStateOf(DEFAULT_UNITS) }
    var minutesAgo by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text(
            stringResource(R.string.dose_log_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = kind == InsulinKind.BOLUS,
                onClick = { kind = InsulinKind.BOLUS },
                label = { Text(stringResource(R.string.dose_bolus)) },
            )
            FilterChip(
                selected = kind == InsulinKind.BASAL,
                onClick = { kind = InsulinKind.BASAL },
                label = { Text(stringResource(R.string.dose_basal)) },
            )
        }

        Spacer(Modifier.height(16.dp))
        UnitStepper(units = units, onChange = { units = it })

        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.dose_when), style = MaterialTheme.typography.bodyLarge)
        Row(
            modifier = Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Entering a forgotten injection is ordinary, and the dose has to land at
            // the time it was actually given or every chart built on it is wrong.
            listOf(
                0 to R.string.dose_now,
                15 to R.string.dose_minus_15,
                30 to R.string.dose_minus_30,
                60 to R.string.dose_minus_60,
            ).forEach { (offset, labelRes) ->
                FilterChip(
                    selected = minutesAgo == offset,
                    onClick = { minutesAgo = offset },
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text(stringResource(R.string.dose_note)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                viewModel.logDose(
                    kind = kind,
                    units = units,
                    givenAtMillis = System.currentTimeMillis() - minutesAgo * 60_000L,
                    note = note,
                )
                // Reset only what should not carry over. The kind usually repeats —
                // a bolus is followed by another bolus — so it stays.
                units = DEFAULT_UNITS
                minutesAgo = 0
                note = ""
            },
            enabled = units > 0 && units <= InsulinDose.MAX_PLAUSIBLE_UNITS,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.dose_save))
        }

        Spacer(Modifier.height(24.dp))
        TodayTotals(doses, zone)

        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.dose_recent),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(4.dp))

        if (doses.isEmpty()) {
            Text(
                stringResource(R.string.dose_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        doses.forEach { dose ->
            DoseRow(dose, zone, onDelete = { viewModel.deleteDose(dose) })
            HorizontalDivider()
        }
    }
}

/**
 * Half-unit steps, which is the finest a pen delivers.
 *
 * Long values are reached by holding neither button — there is no accelerator here,
 * because the realistic range is a handful of units and the guard against a runaway
 * repeat is not having one.
 */
@Composable
private fun UnitStepper(units: Double, onChange: (Double) -> Unit) {
    Column {
        Text(stringResource(R.string.dose_units), style = MaterialTheme.typography.bodyLarge)
        Row(
            modifier = Modifier.padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = { onChange((units - InsulinDose.STEP_UNITS).coerceAtLeast(0.0)) },
                enabled = units > 0,
            ) { Text("−", fontSize = 20.sp) }

            Text(
                "${formatUnits(units)} ${stringResource(R.string.dose_units_suffix)}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )

            OutlinedButton(
                onClick = {
                    onChange(
                        (units + InsulinDose.STEP_UNITS)
                            .coerceAtMost(InsulinDose.MAX_PLAUSIBLE_UNITS)
                    )
                },
                enabled = units < InsulinDose.MAX_PLAUSIBLE_UNITS,
            ) { Text("+", fontSize = 20.sp) }
        }
    }
}

@Composable
private fun TodayTotals(doses: List<InsulinDose>, zone: ZoneId) {
    val today = LocalDate.now(zone)
    val todays = doses.filter {
        Instant.ofEpochMilli(it.givenAtMillis).atZone(zone).toLocalDate() == today
    }
    val totals = InsulinDayTotals.of(todays)

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(
                R.string.dose_today,
                formatUnits(totals.basalUnits),
                formatUnits(totals.bolusUnits),
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun DoseRow(dose: InsulinDose, zone: ZoneId, onDelete: () -> Unit) {
    val time = remember(dose.givenAtMillis) {
        Instant.ofEpochMilli(dose.givenAtMillis).atZone(zone).format(DOSE_TIME_FORMAT)
    }
    val kindLabel = stringResource(
        if (dose.kind == InsulinKind.BASAL) R.string.dose_basal else R.string.dose_bolus
    )

    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${formatUnits(dose.units)} ${stringResource(R.string.dose_units_suffix)} " +
                    "· $kindLabel",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                dose.note?.let { stringResource(R.string.dose_given_at, time, it) } ?: time,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onDelete) { Text(stringResource(R.string.dose_delete)) }
    }
}

/**
 * Drops the decimal when there isn't one, so "6 U" rather than "6.0 U".
 *
 * Uses the default locale, so a Spanish UI shows "6,5" — the same separator the rest
 * of the screen uses.
 */
private fun formatUnits(units: Double): String =
    if (units % 1.0 == 0.0) units.toInt().toString() else String.format("%.1f", units)

private val DOSE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM HH:mm")

/** A common bolus, so the stepper starts somewhere useful rather than at zero. */
private const val DEFAULT_UNITS = 4.0
