package dev.cgm.app.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.cgm.app.CgmApplication
import dev.cgm.app.R
import dev.cgm.core.InsulinDose
import dev.cgm.core.InsulinKind
import dev.cgm.core.InsulinReminder
import dev.cgm.core.ReminderSchedule
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Standing prompts to take insulin.
 *
 * Separate from alarms throughout: these fire on a clock, alarms fire on a
 * reading, and conflating them would mean a quiet evening silencing a low.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemindersScreen(viewModel: CgmViewModel) {
    val context = LocalContext.current
    val scheduler = remember { (context.applicationContext as CgmApplication).reminderScheduler }
    val reminders by viewModel.reminders.collectAsState()

    var editing by remember { mutableStateOf<InsulinReminder?>(null) }
    var adding by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text(
            stringResource(R.string.set_reminders),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        // Said before anything is created, not after a reminder has already been
        // late once.
        if (!scheduler.canScheduleExactly()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        stringResource(R.string.reminders_inexact),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = {
                        runCatching { context.startActivity(scheduler.exactAlarmSettingsIntent()) }
                    }) { Text(stringResource(R.string.reminders_grant_exact)) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (reminders.reminders.isEmpty()) {
            Text(
                stringResource(R.string.reminders_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        reminders.reminders.forEach { reminder ->
            ReminderRow(
                reminder = reminder,
                onToggle = { viewModel.setReminderEnabled(reminder, it) },
                onEdit = { editing = reminder },
            )
            HorizontalDivider()
        }

        Spacer(Modifier.height(16.dp))
        Button(onClick = { adding = true }) { Text(stringResource(R.string.reminders_add)) }
    }

    if (adding || editing != null) {
        ReminderEditor(
            existing = editing,
            onDismiss = { adding = false; editing = null },
            onSave = {
                viewModel.saveReminder(it)
                adding = false
                editing = null
            },
            onDelete = editing?.let { target ->
                {
                    viewModel.deleteReminder(target.id)
                    editing = null
                }
            },
        )
    }
}

@Composable
private fun ReminderRow(
    reminder: InsulinReminder,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    val next = remember(reminder) {
        ReminderSchedule.nextOccurrence(reminder, System.currentTimeMillis())
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "%02d:%02d".format(reminder.minuteOfDay / 60, reminder.minuteOfDay % 60),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                buildString {
                    append(
                        stringResource(
                            if (reminder.kind == InsulinKind.BASAL) R.string.dose_basal
                            else R.string.dose_bolus
                        )
                    )
                    reminder.units?.let { append(" · ").append(formatUnits(it)).append(" U") }
                    append(" · ").append(stringResource(R.string.reminders_daily))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The next firing, so an enabled reminder that will not fire for a
            // week cannot look like one firing tonight.
            next?.takeIf { reminder.enabled }?.let {
                Text(
                    stringResource(R.string.reminders_next, formatWhen(it)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = reminder.enabled, onCheckedChange = onToggle)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderEditor(
    existing: InsulinReminder?,
    onDismiss: () -> Unit,
    onSave: (InsulinReminder) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val state = rememberTimePickerState(
        initialHour = (existing?.minuteOfDay ?: 8 * 60) / 60,
        initialMinute = (existing?.minuteOfDay ?: 0) % 60,
        is24Hour = true,
    )
    var kind by remember { mutableStateOf(existing?.kind ?: InsulinKind.BASAL) }
    var units by remember { mutableStateOf(existing?.units?.let { formatUnits(it) } ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_reminders)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TimePicker(state = state)
                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InsulinKind.entries.forEach { option ->
                        FilterChip(
                            selected = kind == option,
                            onClick = { kind = option },
                            label = {
                                Text(
                                    stringResource(
                                        if (option == InsulinKind.BASAL) R.string.dose_basal
                                        else R.string.dose_bolus
                                    )
                                )
                            },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = units,
                    onValueChange = { units = it },
                    label = { Text("U") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    InsulinReminder(
                        id = existing?.id ?: UUID.randomUUID().toString(),
                        kind = kind,
                        minuteOfDay = InsulinReminder.minuteOfDay(state.hour, state.minute),
                        units = InsulinDose.parseUnits(units),
                        enabled = existing?.enabled ?: true,
                    )
                )
            }) { Text(stringResource(R.string.dose_save)) }
        },
        dismissButton = {
            Row {
                onDelete?.let {
                    TextButton(onClick = it) { Text(stringResource(R.string.reminders_delete)) }
                }
                OutlinedButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        },
    )
}

private fun formatUnits(units: Double): String =
    if (units % 1.0 == 0.0) units.toInt().toString() else "%.1f".format(units)

private val whenFormatter = DateTimeFormatter.ofPattern("EEE HH:mm")

private fun formatWhen(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(whenFormatter)
