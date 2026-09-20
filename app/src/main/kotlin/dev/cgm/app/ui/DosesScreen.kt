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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.ui.text.input.KeyboardType
import java.time.ZoneOffset
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
 * Units are typed on a numeric keypad. That puts the decimal separator back in play,
 * which is the one real hazard here: a Spanish keyboard produces "6,5" where an
 * English one produces "6.5", and `toDoubleOrNull` accepts only the period. So the
 * field keeps both separators while filtering everything else, and
 * [InsulinDose.parseUnits] normalises them — a comma must never be read as nothing,
 * and must certainly never turn 6,5 into 65.
 *
 * The time can be nudged with the relative chips or set exactly with a date and then
 * a time picker. Two dialogs rather than one because Material offers no combined
 * control, and a dose filed against the wrong day is a worse error than an extra tap.
 */
@Composable
fun DosesScreen(viewModel: CgmViewModel) {
    val doses by viewModel.doses.collectAsState()
    val zone = remember { ZoneId.systemDefault() }

    var kind by remember { mutableStateOf(InsulinKind.BOLUS) }
    var unitsText by remember { mutableStateOf("") }
    var minutesAgo by remember { mutableStateOf(0) }
    /** Set only when a date and time were chosen explicitly; otherwise the chips rule. */
    var pickedAtMillis by remember { mutableStateOf<Long?>(null) }
    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf<Long?>(null) }
    var note by remember { mutableStateOf("") }

    val units = InsulinDose.parseUnits(unitsText)
    val acceptable = units != null &&
        InsulinDose(kind = kind, units = units, givenAtMillis = 1).isPlausible
    // Resolved at save time when no explicit instant was picked, so a form left open
    // for ten minutes still records "now" as now.
    fun effectiveMillis(): Long =
        pickedAtMillis ?: (System.currentTimeMillis() - minutesAgo * 60_000L)

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
        OutlinedTextField(
            value = unitsText,
            // Filtered as it is typed rather than validated afterwards: a dose field
            // has no use for letters, and both separators are kept because which one
            // the keyboard offers depends on the language.
            onValueChange = { typed ->
                unitsText = typed.filter { it.isDigit() || it == '.' || it == ',' }.take(6)
            },
            label = { Text(stringResource(R.string.dose_units)) },
            suffix = { Text(stringResource(R.string.dose_units_suffix)) },
            singleLine = true,
            isError = unitsText.isNotEmpty() && !acceptable,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth(),
        )
        if (unitsText.isNotEmpty() && !acceptable) {
            Text(
                stringResource(
                    R.string.dose_invalid,
                    formatUnits(InsulinDose.MAX_PLAUSIBLE_UNITS),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

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
                    selected = pickedAtMillis == null && minutesAgo == offset,
                    onClick = {
                        minutesAgo = offset
                        pickedAtMillis = null
                    },
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }

        TextButton(onClick = { pickingDate = true }) {
            Text(stringResource(R.string.dose_pick_datetime))
        }
        Text(
            stringResource(
                R.string.dose_will_record_at,
                Instant.ofEpochMilli(effectiveMillis()).atZone(zone).format(DOSE_TIME_FORMAT),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

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
                val amount = units ?: return@Button
                viewModel.logDose(
                    kind = kind,
                    units = amount,
                    givenAtMillis = effectiveMillis(),
                    note = note,
                )
                // Reset only what should not carry over. The kind usually repeats —
                // a bolus is followed by another bolus — so it stays.
                unitsText = ""
                minutesAgo = 0
                pickedAtMillis = null
                note = ""
            },
            enabled = acceptable,
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

    // Date first, then time. Two steps rather than one combined control because
    // Material offers no combined picker, and a dose entered for the wrong day is a
    // worse error than one extra tap.
    if (pickingDate) {
        DoseDatePicker(
            initialMillis = effectiveMillis(),
            zone = zone,
            onPick = { dayStart ->
                pickingDate = false
                pickingTime = dayStart
            },
            onDismiss = { pickingDate = false },
        )
    }
    pickingTime?.let { dayStart ->
        DoseTimePicker(
            initialMillis = effectiveMillis(),
            zone = zone,
            onPick = { hour, minute ->
                pickedAtMillis = Instant.ofEpochMilli(dayStart)
                    .atZone(zone)
                    .withHour(hour)
                    .withMinute(minute)
                    .withSecond(0)
                    .toInstant()
                    .toEpochMilli()
                pickingTime = null
            },
            onDismiss = { pickingTime = null },
        )
    }
}

/** Picks the day. The picker speaks UTC midnight, so it is converted to local. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DoseDatePicker(
    initialMillis: Long,
    zone: ZoneId,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val picked = state.selectedDateMillis
                    if (picked == null) {
                        onDismiss()
                    } else {
                        val date = Instant.ofEpochMilli(picked).atZone(ZoneOffset.UTC).toLocalDate()
                        onPick(date.atStartOfDay(zone).toInstant().toEpochMilli())
                    }
                }
            ) { Text(stringResource(R.string.home_picker_show)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.home_picker_cancel)) } },
    ) {
        DatePicker(state = state)
    }
}

/** Picks the time of day, on the date already chosen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DoseTimePicker(
    initialMillis: Long,
    zone: ZoneId,
    onPick: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val start = remember(initialMillis) { Instant.ofEpochMilli(initialMillis).atZone(zone) }
    val state = rememberTimePickerState(
        initialHour = start.hour,
        initialMinute = start.minute,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onPick(state.hour, state.minute) }) {
                Text(stringResource(R.string.dose_time_set))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.home_picker_cancel)) } },
        text = { TimePicker(state = state) },
    )
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

