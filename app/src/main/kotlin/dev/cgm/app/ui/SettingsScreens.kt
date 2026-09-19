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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.cgm.app.alarm.AlarmNotifier
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

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SectionHeader("Alerts")
        SettingsRow("Alarms", "Low, high and signal loss") { onOpen(Destination.Alarms) }

        Spacer(Modifier.height(12.dp))
        SectionHeader("Display")
        SettingsRow(
            title = "Ranges",
            subtitle = state.snapshot?.thresholds?.let {
                "${it.lowMgdl.roundToInt()}–${it.highMgdl.roundToInt()} mg/dL in range"
            } ?: "Target band and zone boundaries",
        ) { onOpen(Destination.Ranges) }
        SettingsRow("System notification settings", "Sounds, importance, badges") {
            context.safeStart(AlarmNotifier.appNotificationSettingsIntent(context))
        }

        Spacer(Modifier.height(12.dp))
        SectionHeader("Service")
        Text(
            "The reading is only current while the poller is running. It starts itself on " +
                "boot; these are for stopping or restarting it by hand.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onStartService) { Text("Start") }
            OutlinedButton(onClick = onStopService) { Text("Stop") }
        }

        Spacer(Modifier.height(12.dp))
        SectionHeader("Account")
        SettingsRow(
            title = "LibreLinkUp",
            subtitle = if (state.configured) "Signed in" else "Not signed in",
        ) {}
        TextButton(onClick = viewModel::signOut) { Text("Sign out") }
    }
}

/**
 * The alarm list. Every alarm is switchable from here without opening it,
 * because turning one off in a hurry is the common case.
 */
@Composable
fun AlarmsScreen(viewModel: CgmViewModel, onOpen: (Destination) -> Unit) {
    val settings by viewModel.alarmSettings.collectAsState()
    val context = LocalContext.current
    val notifier = remember { AlarmNotifier(context) }
    val hasPolicyAccess = notifier.hasPolicyAccess()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        SectionHeader("Alarms")

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
                    Text(kind.displayName(), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        kind.summary(setting),
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
                title = "Do Not Disturb override is unavailable",
                body = "Android will not let an app sound through Do Not Disturb until " +
                    "you grant it Notification Policy Access. Without it, the override " +
                    "switch inside each alarm has no effect.",
                actionLabel = "Grant access",
            ) { context.safeStart(AlarmNotifier.policyAccessIntent()) }
        }
    }
}

@Composable
fun AlarmDetailScreen(viewModel: CgmViewModel, kind: AlarmKind) {
    val settings by viewModel.alarmSettings.collectAsState()
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
        SectionHeader(kind.displayName())

        SwitchRow("Enabled", null, setting.enabled) { update { s -> s.copy(enabled = it) } }
        HorizontalDivider()

        if (kind.isGlucose) {
            SliderRow(
                label = "Alarm at",
                value = setting.thresholdMgdl,
                valueText = "${setting.thresholdMgdl.roundToInt()} mg/dL",
                range = if (kind.isLow) 40f..110f else 140f..350f,
                helper = "Independent of the display range — where you want to be " +
                    "warned and where the graph stops calling a value in range are " +
                    "different questions.",
            ) { update { s -> s.copy(thresholdMgdl = it.toDouble()) } }
        } else {
            SliderRow(
                label = "Warn after",
                value = (setting.afterMillis / 60_000).toDouble(),
                valueText = "${setting.afterMillis / 60_000} minutes without a reading",
                range = 5f..60f,
                helper = "On this connection a reading normally arrives within about " +
                    "3 minutes, so anything past 20 means something is wrong.",
            ) { update { s -> s.copy(afterMillis = (it.toLong() * 60_000)) } }
        }

        HorizontalDivider()
        SliderRow(
            label = "Repeat every",
            value = (setting.repeatEveryMillis / 60_000).toDouble(),
            valueText = "${setting.repeatEveryMillis / 60_000} minutes",
            range = 1f..60f,
        ) { update { s -> s.copy(repeatEveryMillis = it.toLong() * 60_000) } }

        if (kind.isGlucose) {
            HorizontalDivider()
            SwitchRow(
                title = "Stay quiet while recovering",
                subtitle = if (kind.isLow) {
                    "Sound once, then stay silent while the trend is rising."
                } else {
                    "Sound once, then stay silent while the trend is falling."
                },
                checked = setting.silenceWhileRecovering,
            ) { update { s -> s.copy(silenceWhileRecovering = it) } }
        }

        HorizontalDivider()
        SwitchRow(
            title = "Override Do Not Disturb",
            subtitle = if (hasPolicyAccess) {
                "This alarm will sound even when Do Not Disturb is on."
            } else {
                "Needs Notification Policy Access before Android will honour it."
            },
            checked = setting.overrideDnd,
            enabled = hasPolicyAccess,
        ) { update { s -> s.copy(overrideDnd = it) } }

        Spacer(Modifier.height(8.dp))

        if (!hasPolicyAccess) {
            InfoCard(
                title = "Grant Notification Policy Access",
                body = "Android does not let an app decide on its own to sound through " +
                    "Do Not Disturb. You grant it once, in system settings.",
                actionLabel = "Open system settings",
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
        ) { Text("Sound & vibration for this alarm") }

        Text(
            "Sound, vibration and importance belong to Android once an alarm exists, " +
                "so they are changed there rather than here.",
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
    val unit = state.snapshot?.unit ?: GlucoseUnit.MGDL

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SectionHeader("Ranges")

        if (effective == null) {
            Text("Waiting for the first reading.")
            Text(
                "Ranges start from your LibreLinkUp account, so they arrive with it.",
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
                Text("Follow my account again")
            }
        }

        Text(
            "Low and high start from your LibreLinkUp account, so the app agrees with " +
                "what LibreLink shows. Change one here and it stops following the " +
                "account until you hand it back. Urgent low and very high have no " +
                "equivalent in the account and are always yours.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "These decide colour, zones and time-in-range \u2014 not when you get woken. " +
                "Alarm levels are set per alarm under Alarms, deliberately separately: " +
                "an alarm at the edge of your target band would fire all day.",
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
            Text(boundary.label(), style = MaterialTheme.typography.bodyLarge)
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
                TextButton(onClick = onUseAccount) { Text("Use account") }
            }
        }
    }
}

private fun ThresholdBoundary.label(): String = when (this) {
    ThresholdBoundary.URGENT_LOW -> "Urgent low below"
    ThresholdBoundary.LOW -> "Low below"
    ThresholdBoundary.HIGH -> "High above"
    ThresholdBoundary.VERY_HIGH -> "Very high above"
}

/** Says where this number came from, so an edited band is never mistaken for the account's. */
private fun ThresholdBoundary.provenance(
    isOverridden: Boolean,
    accountValue: Double?,
    unit: GlucoseUnit,
): String = when {
    !comesFromAccount -> "Yours \u2014 your account has no equivalent"
    isOverridden && accountValue != null ->
        "Yours \u2014 account says ${unit.format(accountValue)}"
    isOverridden -> "Yours"
    else -> "From your LibreLinkUp account"
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
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    helper: String? = null,
    onChange: (Float) -> Unit,
) {
    var live by remember(value) { mutableStateOf(value.toFloat()) }
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(valueText, fontWeight = FontWeight.Medium)
        }
        Slider(
            value = live,
            onValueChange = { live = it },
            onValueChangeFinished = { onChange(live) },
            valueRange = range,
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

private fun AlarmKind.displayName(): String = when (this) {
    AlarmKind.URGENT_LOW -> "Urgent low"
    AlarmKind.LOW -> "Low"
    AlarmKind.HIGH -> "High"
    AlarmKind.VERY_HIGH -> "Very high"
    AlarmKind.SIGNAL_LOSS -> "Signal loss"
}

private fun AlarmKind.summary(setting: AlarmSetting): String = when {
    !setting.enabled -> "Off"
    this == AlarmKind.SIGNAL_LOSS -> "After ${setting.afterMillis / 60_000} min without data"
    isLow -> "Below ${setting.thresholdMgdl.roundToInt()} mg/dL"
    else -> "Above ${setting.thresholdMgdl.roundToInt()} mg/dL"
}

/** System screens can be missing on odd ROMs; never crash trying to open one. */
private fun Context.safeStart(intent: Intent) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
