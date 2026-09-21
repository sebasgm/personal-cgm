package dev.cgm.app.ui

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.app.Activity
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import dev.cgm.app.Locales
import dev.cgm.app.R
import dev.cgm.app.alarm.AlarmNotifier
import dev.cgm.app.service.BatteryExemption
import dev.cgm.app.service.PollingService
import dev.cgm.core.AlarmKind
import dev.cgm.core.AlarmSetting
import dev.cgm.core.GlucoseThresholds
import dev.cgm.core.GlucoseUnit
import dev.cgm.core.ThresholdBoundary
import dev.cgm.core.get
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    viewModel: CgmViewModel,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onOpen: (Destination) -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val languageTag by viewModel.languageTag.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SectionHeader(stringResource(R.string.set_alerts))
        SettingsRow(stringResource(R.string.set_alarms), stringResource(R.string.set_alarms_subtitle)) { onOpen(Destination.Alarms) }

        Spacer(Modifier.height(12.dp))
        SectionHeader(stringResource(R.string.set_display))
        UnitPicker(
            selected = state.unitOverride,
            accountUnit = state.accountUnit,
            onSelect = viewModel::setUnit,
        )
        HorizontalDivider()
        LanguagePicker(
            selected = languageTag,
            onSelect = { tag ->
                viewModel.setLanguage(tag) { (context as? Activity)?.recreate() }
            },
        )
        HorizontalDivider()
        SettingsRow(
            title = stringResource(R.string.set_ranges),
            subtitle = state.snapshot?.thresholds?.let {
                stringResource(
                    R.string.set_ranges_subtitle_values,
                    state.unit.format(it.lowMgdl),
                    state.unit.format(it.highMgdl),
                    state.unit.suffix,
                )
            } ?: stringResource(R.string.set_ranges_subtitle_default),
        ) { onOpen(Destination.Ranges) }
        SettingsRow(stringResource(R.string.set_system_notifications), stringResource(R.string.set_system_notifications_subtitle)) {
            context.safeStart(AlarmNotifier.appNotificationSettingsIntent(context))
        }

        Spacer(Modifier.height(12.dp))
        SectionHeader(stringResource(R.string.set_safety))
        SettingsRow(
            title = stringResource(R.string.set_disclaimer),
            subtitle = stringResource(R.string.set_disclaimer_subtitle),
        ) { onOpen(Destination.Disclaimer) }

        Spacer(Modifier.height(12.dp))
        SectionHeader(stringResource(R.string.set_service))
        val polling by PollingService.running.collectAsState()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (polling) stringResource(R.string.set_polling) else stringResource(R.string.set_stopped),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (polling) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    ChartColors.signalLoss
                },
            )
        }
        Text(
            stringResource(
                if (polling) R.string.set_polling_note else R.string.set_stopped_note
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        var confirmStop by remember { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onStartService) { Text(stringResource(R.string.set_start)) }
            OutlinedButton(onClick = { confirmStop = true }) {
                Text(stringResource(R.string.set_stop))
            }
            OutlinedButton(onClick = viewModel::refreshNow) { Text(stringResource(R.string.set_refresh)) }
        }

        // Stopping is confirmed because the cost is invisible and permanent: no
        // readings are recorded while it is off, and that hole cannot be filled in
        // afterwards from an API that only serves twelve hours of coarse history.
        if (confirmStop) {
            ConfirmDialog(
                title = stringResource(R.string.confirm_stop_title),
                body = stringResource(R.string.confirm_stop_body),
                confirmLabel = stringResource(R.string.confirm_stop_action),
                onConfirm = { confirmStop = false; onStopService() },
                onDismiss = { confirmStop = false },
            )
        }

        SettingsRow(
            title = stringResource(R.string.set_accessibility),
            subtitle = stringResource(R.string.set_accessibility_desc),
        ) { onOpen(Destination.Accessibility) }

        Spacer(Modifier.height(12.dp))
        ForecastSetting(viewModel)

        Spacer(Modifier.height(12.dp))
        SectionHeader(stringResource(R.string.set_account))
        SettingsRow(
            title = stringResource(R.string.set_librelinkup),
            subtitle = if (state.configured) stringResource(R.string.set_signed_in) else stringResource(R.string.set_not_signed_in),
        ) {}
        Spacer(Modifier.height(12.dp))
        SettingsRow(
            title = stringResource(R.string.set_release_notes),
            subtitle = stringResource(R.string.set_release_notes_desc),
        ) { onOpen(Destination.ReleaseNotes) }

        Spacer(Modifier.height(12.dp))
        var confirmSignOut by remember { mutableStateOf(false) }
        TextButton(onClick = { confirmSignOut = true }) {
            Text(stringResource(R.string.set_sign_out))
        }
        if (confirmSignOut) {
            ConfirmDialog(
                title = stringResource(R.string.confirm_signout_title),
                body = stringResource(R.string.confirm_signout_body),
                confirmLabel = stringResource(R.string.confirm_signout_action),
                onConfirm = { confirmSignOut = false; viewModel.signOut() },
                onDismiss = { confirmSignOut = false },
            )
        }
    }
}

/**
 * The alarm list. Every alarm is switchable from here without opening it,
 * because turning one off in a hurry is the common case.
 */
@Composable
fun AlarmsScreen(viewModel: CgmViewModel, onOpen: (Destination) -> Unit) {
    val settings by viewModel.alarmSettings.collectAsState()
    val state by viewModel.state.collectAsState()
    val unit = state.unit
    val context = LocalContext.current
    val notifier = remember { AlarmNotifier(context) }
    val hasPolicyAccess = notifier.hasPolicyAccess()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        SectionHeader(stringResource(R.string.set_alarms))

        AlarmKind.entries.forEach { kind ->
            val setting = settings[kind]
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(Destination.AlarmDetail(kind)) }
                    .padding(vertical = 12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(kind.labelRes()), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        kind.summary(setting, unit),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = setting.enabled,
                    onCheckedChange = { viewModel.updateAlarm(kind, setting.copy(enabled = it)) },
                )
            }
            HorizontalDivider()
        }

        if (!hasPolicyAccess) {
            Spacer(Modifier.height(16.dp))
            InfoCard(
                title = stringResource(R.string.alarm_dnd_unavailable),
                body = stringResource(R.string.alarm_dnd_unavailable_body),
                actionLabel = stringResource(R.string.alarm_grant_access),
            ) { context.safeStart(AlarmNotifier.policyAccessIntent()) }
        }
    }
}

@Composable
fun AlarmDetailScreen(viewModel: CgmViewModel, kind: AlarmKind) {
    val settings by viewModel.alarmSettings.collectAsState()
    val state by viewModel.state.collectAsState()
    val unit = state.unit
    val setting = settings[kind]
    val context = LocalContext.current
    val notifier = remember { AlarmNotifier(context) }
    val hasPolicyAccess = notifier.hasPolicyAccess()

    fun update(block: (AlarmSetting) -> AlarmSetting) =
        viewModel.updateAlarm(kind, block(setting))

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionHeader(stringResource(kind.labelRes()))

        SwitchRow(stringResource(R.string.alarm_enabled), null, setting.enabled) { update { s -> s.copy(enabled = it) } }
        HorizontalDivider()

        if (kind.isGlucose) {
            SliderRow(
                label = stringResource(R.string.alarm_at),
                value = setting.thresholdMgdl,
                // Formatted from the live position, and in the display unit, so a
                // mmol/L reader picks a number they recognise while the stored
                // threshold stays mg/dL.
                format = { "${unit.format(it.toDouble())} ${unit.suffix}" },
                range = if (kind.isLow) 40f..110f else 140f..350f,
                helper = stringResource(R.string.alarm_at_helper),
            ) { update { s -> s.copy(thresholdMgdl = it.toDouble()) } }
        } else {
            SliderRow(
                label = stringResource(R.string.alarm_warn_after),
                value = (setting.afterMillis / 60_000).toDouble(),
                format = { minutes ->
                    context.getString(R.string.alarm_warn_after_value, minutes.toInt())
                },
                range = 5f..60f,
                helper = stringResource(R.string.alarm_warn_after_helper),
            ) { update { s -> s.copy(afterMillis = (it.toLong() * 60_000)) } }
        }

        HorizontalDivider()
        SliderRow(
            label = stringResource(R.string.alarm_repeat_every),
            value = (setting.repeatEveryMillis / 60_000).toDouble(),
            format = { minutes -> context.getString(R.string.alarm_minutes, minutes.toInt()) },
            range = 1f..60f,
        ) { update { s -> s.copy(repeatEveryMillis = it.toLong() * 60_000) } }

        if (kind.isGlucose) {
            HorizontalDivider()
            SwitchRow(
                title = stringResource(R.string.alarm_quiet_recovering),
                subtitle = if (kind.isLow) {
                    stringResource(R.string.alarm_quiet_rising)
                } else {
                    stringResource(R.string.alarm_quiet_falling)
                },
                checked = setting.silenceWhileRecovering,
            ) { update { s -> s.copy(silenceWhileRecovering = it) } }
        }

        HorizontalDivider()
        SwitchRow(
            title = stringResource(R.string.alarm_override_dnd),
            subtitle = if (hasPolicyAccess) {
                stringResource(R.string.alarm_override_dnd_on)
            } else {
                stringResource(R.string.alarm_override_dnd_needs_access)
            },
            checked = setting.overrideDnd,
            enabled = hasPolicyAccess,
        ) { update { s -> s.copy(overrideDnd = it) } }

        Spacer(Modifier.height(8.dp))

        if (!hasPolicyAccess) {
            InfoCard(
                title = stringResource(R.string.alarm_grant_policy_title),
                body = stringResource(R.string.alarm_grant_policy_body),
                actionLabel = stringResource(R.string.alarm_open_system_settings),
            ) { context.safeStart(AlarmNotifier.policyAccessIntent()) }
        }

        OutlinedButton(
            onClick = {
                context.safeStart(
                    AlarmNotifier.channelSettingsIntent(
                        context,
                        notifier.channelId(kind, setting.overrideDnd),
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.alarm_sound_vibration)) }

        Text(
            stringResource(R.string.alarm_sound_vibration_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun RangesScreen(viewModel: CgmViewModel) {
    val state by viewModel.state.collectAsState()
    val effective = state.snapshot?.thresholds
    val account = state.accountThresholds
    val overrides = state.overrides
    val unit = state.unit

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SectionHeader(stringResource(R.string.set_ranges))

        if (effective == null) {
            Text(stringResource(R.string.range_waiting))
            Text(
                stringResource(R.string.range_waiting_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        ThresholdBoundary.entries.forEach { boundary ->
            RangeRow(
                boundary = boundary,
                value = effective[boundary],
                accountValue = account?.get(boundary),
                isOverridden = overrides.overrides(boundary),
                bounds = boundary.editableRange(effective),
                unit = unit,
                onChange = { viewModel.setThreshold(boundary, it) },
                onUseAccount = { viewModel.setThreshold(boundary, null) },
            )
            HorizontalDivider()
        }

        Spacer(Modifier.height(8.dp))

        if (!overrides.isEmpty) {
            OutlinedButton(onClick = { viewModel.resetThresholds() }) {
                Text(stringResource(R.string.range_follow_account_again))
            }
        }

        Text(
            stringResource(R.string.range_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.range_alarm_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * How far a boundary may be dragged: up to its neighbours, never past them.
 *
 * Constraining the input is why [GlucoseThresholds.sanitised] never has to do
 * anything here. Repairing afterwards would be worse than preventing: pushing the
 * urgent low above the low would silently drag the low, high and very high up with
 * it, and the user would watch three numbers they did not touch change themselves.
 */
private fun ThresholdBoundary.editableRange(
    t: GlucoseThresholds,
): ClosedFloatingPointRange<Float> {
    val floor = GlucoseThresholds.MIN_PLAUSIBLE_MGDL.toFloat()
    val ceiling = GlucoseThresholds.MAX_PLAUSIBLE_MGDL.toFloat()
    return when (this) {
        ThresholdBoundary.URGENT_LOW -> floor..(t.lowMgdl - 1).toFloat()
        ThresholdBoundary.LOW -> (t.urgentLowMgdl + 1).toFloat()..(t.highMgdl - 1).toFloat()
        ThresholdBoundary.HIGH -> (t.lowMgdl + 1).toFloat()..(t.veryHighMgdl - 1).toFloat()
        ThresholdBoundary.VERY_HIGH -> (t.highMgdl + 1).toFloat()..ceiling
    }
}

@Composable
private fun RangeRow(
    boundary: ThresholdBoundary,
    value: Double,
    accountValue: Double?,
    isOverridden: Boolean,
    bounds: ClosedFloatingPointRange<Float>,
    unit: GlucoseUnit,
    onChange: (Double) -> Unit,
    onUseAccount: () -> Unit,
) {
    // Keyed on value so an edit from elsewhere (a poll changing the account band)
    // moves the thumb, while a drag in progress is not fought over.
    var live by remember(value) { mutableStateOf(value.toFloat()) }

    Column(Modifier.padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(boundary.labelRes()), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            Text(
                "${unit.format(live.toDouble())} ${unit.suffix}",
                fontWeight = FontWeight.Medium,
            )
        }
        Slider(
            value = live.coerceIn(bounds.start, bounds.endInclusive),
            onValueChange = { live = it },
            onValueChangeFinished = { onChange(live.roundToInt().toDouble()) },
            valueRange = bounds,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                boundary.provenance(isOverridden, accountValue, unit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isOverridden && boundary.comesFromAccount && accountValue != null) {
                TextButton(onClick = onUseAccount) { Text(stringResource(R.string.range_use_account)) }
            }
        }
    }
}

@StringRes
private fun ThresholdBoundary.labelRes(): Int = when (this) {
    ThresholdBoundary.URGENT_LOW -> R.string.range_urgent_low_below
    ThresholdBoundary.LOW -> R.string.range_low_below
    ThresholdBoundary.HIGH -> R.string.range_high_above
    ThresholdBoundary.VERY_HIGH -> R.string.range_very_high_above
}

/** Says where this number came from, so an edited band is never mistaken for the account's. */
@Composable
private fun ThresholdBoundary.provenance(
    isOverridden: Boolean,
    accountValue: Double?,
    unit: GlucoseUnit,
): String = when {
    !comesFromAccount -> stringResource(R.string.range_yours_no_equivalent)
    isOverridden && accountValue != null ->
        stringResource(R.string.range_yours_account_says, unit.format(accountValue))
    isOverridden -> stringResource(R.string.range_yours)
    else -> stringResource(R.string.range_from_account)
}

/**
 * Whether the app is actually keeping up, and the two system settings that decide
 * whether it can.
 *
 * A missed reading cannot be recovered - LibreLinkUp serves about twelve hours of
 * 15-minute history and nothing older - so this is the screen that says whether
 * the record being built is worth anything.
 */
@Composable
private fun ContinuityCard(viewModel: CgmViewModel) {
    val context = LocalContext.current
    val report by viewModel.continuity.collectAsState()
    var exempt by remember { mutableStateOf(BatteryExemption.isExempt(context)) }

    // Re-read on recomposition: the user may have just come back from granting it.
    LaunchedEffect(Unit) {
        exempt = BatteryExemption.isExempt(context)
        viewModel.refreshContinuity()
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (report.isHealthy && exempt) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "${(report.completeness * 100).roundToInt()}% of the last 24 hours recorded",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                if (report.isHealthy) {
                    "${report.readingCount} readings, no gaps."
                } else {
                    "${report.readingCount} readings, ${report.gapCount} gap(s), " +
                        "longest ${report.largestGapMillis / 60_000} min."
                },
                style = MaterialTheme.typography.bodySmall,
            )

            if (!exempt) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Android is allowed to put this app to sleep. That is the usual " +
                        "cause of readings missing overnight, and they cannot be " +
                        "recovered afterwards.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(
                    onClick = {
                        context.safeStart(BatteryExemption.requestIntent(context))
                    }
                ) { Text("Allow it to keep running") }
            }
        }
    }
}

/**
 * The projection toggle, and the only number that says whether it works.
 *
 * A forecast that reports its own confidence without ever checking it is
 * guessing twice. The coverage figure below is measured on history the model was
 * not fitted to, so it can genuinely disagree with the band's nominal width - and
 * when it does, that is the answer to "does this work".
 */
@Composable
private fun ForecastSetting(viewModel: CgmViewModel) {
    val enabled by viewModel.forecastEnabled.collectAsState()
    val calibration by viewModel.calibration.collectAsState()

    SectionHeader(stringResource(R.string.set_forecast))
    SwitchRow(
        title = stringResource(R.string.set_forecast),
        subtitle = stringResource(R.string.set_forecast_desc),
        checked = enabled,
    ) { viewModel.setForecastEnabled(it) }

    if (!enabled) return

    val current = calibration
    val coverage = current?.measuredCoverage
    Text(
        text = when {
            current == null || !current.isUsable || coverage == null ->
                stringResource(R.string.forecast_uncalibrated)
            else -> stringResource(
                R.string.forecast_coverage,
                (current.bandFraction * 100).roundToInt(),
                (coverage * 100).roundToInt(),
                current.coverageSampleCount,
            )
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (current?.isWellCalibrated == false) {
        Text(
            stringResource(R.string.forecast_overconfident),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

// -- small shared pieces ---------------------------------------------------

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun SettingsRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Double,
    /**
     * Formats whatever the slider is currently on, not what is saved.
     *
     * The number has to track the thumb. Showing the stored value while dragging
     * means picking a threshold blind and only learning what was chosen after
     * letting go, which on an alarm level is the one place that is not acceptable.
     */
    format: (Float) -> String,
    range: ClosedFloatingPointRange<Float>,
    helper: String? = null,
    onChange: (Float) -> Unit,
) {
    var live by remember(value) { mutableStateOf(value.toFloat()) }
    val dragging = remember { mutableStateOf(false) }
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                format(live),
                fontWeight = FontWeight.Medium,
                // Emphasised while moving, so the number being chosen is obvious.
                color = if (dragging.value) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
        Slider(
            value = live,
            onValueChange = { dragging.value = true; live = it.roundToInt().toFloat() },
            onValueChangeFinished = { dragging.value = false; onChange(live) },
            valueRange = range,
            // Whole units only: nobody sets an alarm at 71.4 mg/dL, and snapping
            // makes the displayed number reachable rather than approximate.
            steps = (range.endInclusive - range.start).toInt() - 1,
        )
        helper?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(body, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@StringRes
internal fun AlarmKind.labelRes(): Int = when (this) {
    AlarmKind.URGENT_LOW -> R.string.zone_urgent_low
    AlarmKind.LOW -> R.string.zone_low
    AlarmKind.HIGH -> R.string.zone_high
    AlarmKind.VERY_HIGH -> R.string.zone_very_high
    AlarmKind.SIGNAL_LOSS -> R.string.zone_signal_loss
}

@Composable
private fun AlarmKind.summary(setting: AlarmSetting, unit: GlucoseUnit): String = when {
    !setting.enabled -> stringResource(R.string.alarm_off)
    this == AlarmKind.SIGNAL_LOSS ->
        stringResource(R.string.alarm_after_minutes, setting.afterMillis / 60_000)
    isLow -> stringResource(R.string.alarm_below, unit.format(setting.thresholdMgdl), unit.suffix)
    else -> stringResource(R.string.alarm_above, unit.format(setting.thresholdMgdl), unit.suffix)
}

/**
 * The app's language.
 *
 * Changing it recreates the activity, because Android resolves resources when a
 * context is attached — strings already composed cannot re-resolve themselves.
 * "System" is not the same as Spanish: Spanish is what the default resources hold,
 * so following the system yields Spanish on everything except an English device.
 */
@Composable
private fun LanguagePicker(selected: String?, onSelect: (String?) -> Unit) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(stringResource(R.string.set_language), style = MaterialTheme.typography.bodyLarge)
        Row(
            modifier = Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text(stringResource(R.string.set_language_system)) },
            )
            Locales.SUPPORTED.forEach { tag ->
                FilterChip(
                    selected = selected == tag,
                    onClick = { onSelect(tag) },
                    label = { Text(Locales.displayName(tag)) },
                )
            }
        }
    }
}

/**
 * The unit everything is shown in.
 *
 * Three choices rather than two: following the account is the default, so someone
 * who switches units in the official LibreLink app does not have to remember to
 * switch them here as well. Values are always stored in mg/dL whatever is picked —
 * a unit is a way of reading a number, not a different number.
 */
@Composable
private fun UnitPicker(
    selected: GlucoseUnit?,
    accountUnit: GlucoseUnit?,
    onSelect: (GlucoseUnit?) -> Unit,
) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(stringResource(R.string.set_units), style = MaterialTheme.typography.bodyLarge)
        Row(
            modifier = Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = {
                    Text(
                        accountUnit?.let { stringResource(R.string.set_units_account, it.suffix) }
                            ?: stringResource(R.string.set_units_account_plain)
                    )
                },
            )
            GlucoseUnit.entries.forEach { candidate ->
                FilterChip(
                    selected = selected == candidate,
                    onClick = { onSelect(candidate) },
                    label = { Text(candidate.suffix) },
                )
            }
        }
    }
}

/** System screens can be missing on odd ROMs; never crash trying to open one. */
private fun Context.safeStart(intent: Intent) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
