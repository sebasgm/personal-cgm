package dev.cgm.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.cgm.app.R
import dev.cgm.app.data.ZoneWatcher
import dev.cgm.app.meter.BondedDevice
import dev.cgm.app.meter.MeterState
import dev.cgm.app.meter.MeterSyncResult
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Reading a finger-prick meter.
 *
 * Deliberately not a scanner. Pairing happens in Android's own Bluetooth settings and
 * this screen lists what is already bonded, which is not a shortcut — it is what keeps
 * the app out of the location permission that a BLE scan needs on this `minSdk`. A
 * glucose monitor asking for location in order to read a meter is a permission nobody
 * should grant, and the way to never ask for it is to never scan.
 *
 * The screen reports rather than reassures. A sync says how many results were new, how
 * many were already stored, how many control solutions were dropped and how far the
 * meter's clock is from the phone's — and a device that turns out not to speak the
 * standard glucose service gets its service list printed, because that list is the
 * difference between "this meter cannot work" and "this meter needs a quirk written".
 */
@Composable
fun MeterScreen(viewModel: CgmViewModel) {
    val context = LocalContext.current
    val zone by ZoneWatcher.zone.collectAsState()
    val state by viewModel.meterState.collectAsState()

    // On API 31+ talking to a bonded device needs asking. Below it, the manifest's
    // legacy pair is granted at install and there is nothing to request.
    val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    var granted by remember {
        mutableStateOf(
            !needsPermission || ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }

    var devices by remember { mutableStateOf<List<BondedDevice>>(emptyList()) }
    // Re-read when the permission lands and when a read finishes: bonding a meter in
    // Android's settings and coming back should not need the screen reopening.
    LaunchedEffect(granted, state) {
        devices = if (granted) viewModel.bondedMeters(context) else emptyList()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text(
            stringResource(R.string.meter_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.meter_how),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!granted) {
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.meter_permission_why),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { ask.launch(Manifest.permission.BLUETOOTH_CONNECT) }) {
                Text(stringResource(R.string.meter_permission))
            }
            return@Column
        }

        Spacer(Modifier.height(20.dp))

        if (devices.isEmpty()) {
            Text(
                stringResource(R.string.meter_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            devices.forEach { device ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(device.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            device.address,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = { viewModel.syncMeter(context, device.address, zone) },
                        enabled = state !is MeterState.Reading,
                    ) {
                        Text(stringResource(R.string.meter_read))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }

        Spacer(Modifier.height(20.dp))
        when (val current = state) {
            MeterState.Idle -> Unit
            MeterState.Reading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.height(18.dp))
                Text(
                    stringResource(R.string.meter_working),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }

            is MeterState.Done -> Outcome(current.result)
        }
    }
}

@Composable
private fun Outcome(result: MeterSyncResult) {
    val failed = result !is MeterSyncResult.Synced

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (failed) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (result) {
                is MeterSyncResult.Synced -> {
                    val nothing = result.stored == 0 &&
                        result.duplicates == 0 &&
                        result.controlSolutions == 0
                    Text(
                        text = if (nothing) {
                            stringResource(R.string.meter_synced_nothing, result.meterName)
                        } else {
                            stringResource(
                                R.string.meter_synced,
                                result.meterName,
                                result.stored,
                                result.duplicates,
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )

                    if (result.controlSolutions > 0) {
                        Text(
                            stringResource(R.string.meter_controls, result.controlSolutions),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (result.refused > 0) {
                        Text(
                            stringResource(R.string.meter_refused, result.refused),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    // Said out loud, because every time this app stores came from it: a
                    // meter two hours out would otherwise file every result two hours
                    // early and nothing on screen would explain why.
                    val minutes = (abs(result.clockSkewMillis) / 60_000.0).roundToInt()
                    if (minutes >= 1) {
                        Text(
                            stringResource(
                                if (result.clockSkewMillis > 0) {
                                    R.string.meter_skew_behind
                                } else {
                                    R.string.meter_skew_ahead
                                },
                                minutes,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                is MeterSyncResult.NotCompatible -> {
                    Text(
                        stringResource(R.string.meter_incompatible),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    result.services.forEach {
                        Text(it, style = MaterialTheme.typography.labelSmall)
                    }
                }

                is MeterSyncResult.Failed -> Text(
                    stringResource(R.string.meter_failed, result.reason),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
