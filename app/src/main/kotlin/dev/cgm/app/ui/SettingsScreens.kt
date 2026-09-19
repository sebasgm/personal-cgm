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
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    viewModel: CgmViewModel,
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
    val t = state.snapshot?.thresholds

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader("Ranges")
        if (t == null) {
            Text("Waiting for the first reading.")
            return@Column
        }
        listOf(
            "Urgent low" to t.urgentLowMgdl,
            "Low below" to t.lowMgdl,
            "High above" to t.highMgdl,
            "Very high above" to t.veryHighMgdl,
        ).forEach { (label, value) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label)
                Text("${value.roundToInt()} mg/dL", fontWeight = FontWeight.Medium)
            }
        }
        Text(
            "The in-range band comes from your LibreLinkUp account, so the app agrees " +
                "with what LibreLink shows. Editing these here is not wired up yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
