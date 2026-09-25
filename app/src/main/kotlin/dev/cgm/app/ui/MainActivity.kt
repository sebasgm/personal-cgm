package dev.cgm.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import dev.cgm.app.R
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.Context
import dev.cgm.app.CgmApplication
import dev.cgm.app.Locales
import dev.cgm.app.service.PollingService

class MainActivity : ComponentActivity() {

    /**
     * Applies the chosen language before any resource is resolved.
     *
     * Safe to read [Locales.current] synchronously here: Application.onCreate has
     * already primed it, and it runs before any activity is created.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Locales.wrap(newBase))
    }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as CgmApplication
        ensureNotificationPermission()

        val startService = { PollingService.start(this) }
        val stopService = { PollingService.stop(this) }

        setContent {
            // Hoisted above the theme: the typeface and the zone palette are
            // preferences, so they have to be known before the first frame is
            // composed rather than applied to one already on screen.
            val vm: CgmViewModel = viewModel(
                factory = CgmViewModel.factory(app.repository, app.settings, app.reminderScheduler)
            )
            val accessibility by vm.accessibility.collectAsState()

            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) CgmPalette.dark else CgmPalette.light,
                typography = cgmTypography(accessibility),
            ) {
              CompositionLocalProvider(LocalColorVision provides accessibility.colorVision) {
                val state by vm.state.collectAsState()
                val accepted by vm.disclaimerAccepted.collectAsState()

                // Ahead of sign-in on purpose: it governs how every number in the
                // app should be read, so it is not something to meet afterwards.
                if (accepted == false) {
                    DisclaimerScreen(onAccept = vm::acceptDisclaimer)
                } else if (accepted == null) {
                    // Still reading the stored acceptance. Blank rather than a flash
                    // of the disclaimer at someone who accepted it months ago.
                } else if (state.configured) {
                    // Nothing else brings the poller back. It started on sign-in and
                    // on boot, so installing a new build — which kills the service
                    // without a reboot — left it dead until someone pressed Start by
                    // hand, with no reading, no status bar value and no explanation.
                    // Starting it whenever a configured app opens is the difference
                    // between a gap in the record and no gap; the service's own loop
                    // guard makes a second start a no-op.
                    LaunchedEffect(Unit) { startService() }

                    MainScaffold(
                        viewModel = vm,
                        onStartService = startService,
                        onStopService = stopService,
                    )
                } else {
                    SetupScreen(viewModel = vm, onSignedIn = startService)
                }
              }
            }
        }
    }

    /**
     * Android 13+ hides the foreground-service notification without this, and
     * that notification is both the app's primary display when closed and the
     * carrier for every alarm.
     */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        if (granted != PackageManager.PERMISSION_GRANTED) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun MainScaffold(
    viewModel: CgmViewModel,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
) {
    // A plain stack. Switching tabs resets to that tab's root; back pops.
    var stack by remember { mutableStateOf(listOf<Destination>(Destination.Home)) }
    val current = stack.last()
    val tab = Tab.of(current)

    BackHandler(enabled = stack.size > 1) { stack = stack.dropLast(1) }

    // Coming back after a while away puts you on Home.
    //
    // Watched here rather than in the activity because this is where the stack
    // lives, and a reset that has to be signalled across that boundary is a reset
    // that can arrive at the wrong moment.
    val lifecycleOwner = LocalLifecycleOwner.current
    var leftAtMillis by remember { mutableLongStateOf(0L) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> leftAtMillis = System.currentTimeMillis()
                Lifecycle.Event.ON_START -> {
                    val away = System.currentTimeMillis() - leftAtMillis
                    // Zero means this is the first start, not a return from a long
                    // absence, so the app opens wherever it normally would.
                    if (leftAtMillis != 0L && away >= RETURN_TO_HOME_AFTER_MILLIS) {
                        stack = listOf(Destination.Home)
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val reminderDue by viewModel.reminderDue.collectAsState()
    if (reminderDue) {
        PeriodicReminder(onDismiss = viewModel::dismissReminder)
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { stack = listOf(t.root) },
                        icon = {},
                        label = { Text(stringResource(t.labelRes)) },
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (current) {
                Destination.Home -> HomeScreen(viewModel)
                Destination.Trends -> TrendsScreen(viewModel)
                Destination.Logbook -> LogbookScreen(viewModel)
                Destination.Diary -> DiaryScreen(viewModel)
                Destination.Settings -> SettingsScreen(
                    viewModel = viewModel,
                    onStartService = onStartService,
                    onStopService = onStopService,
                ) { stack = stack + it }
                Destination.Alarms -> AlarmsScreen(viewModel) { stack = stack + it }
                Destination.Ranges -> RangesScreen(viewModel)
                Destination.Accessibility -> AccessibilityScreen(viewModel)
                Destination.Reminders -> RemindersScreen(viewModel)
                Destination.Relay -> RelayScreen(viewModel)
                Destination.ReleaseNotes -> ReleaseNotesScreen()
                Destination.Disclaimer -> DisclaimerReadOnlyScreen()
                is Destination.AlarmDetail -> AlarmDetailScreen(viewModel, current.kind)
            }
        }
    }
}

/**
 * The short reminder, about once a month.
 *
 * A condensed version of what the full disclaimer says, on the two points that
 * actually bite in daily use: the data can be late or wrong, and the alarms can
 * fail to arrive. Both are properties of this app that no amount of care here
 * removes, so they are worth re-reading occasionally rather than once at install.
 *
 * It can be turned off, and the full notice stays in Settings either way —
 * dismissing a reminder is not withdrawing the agreement it reminds you of.
 */
@Composable
private fun PeriodicReminder(onDismiss: (Boolean) -> Unit) {
    var dontShowAgain by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { onDismiss(dontShowAgain) },
        title = { Text(stringResource(R.string.reminder_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.reminder_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.reminder_read_full),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { dontShowAgain = !dontShowAgain }
                        .padding(top = 12.dp),
                ) {
                    Checkbox(checked = dontShowAgain, onCheckedChange = { dontShowAgain = it })
                    Text(
                        stringResource(R.string.reminder_dont_show),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(dontShowAgain) }) {
                Text(stringResource(R.string.reminder_ok))
            }
        },
    )
}
