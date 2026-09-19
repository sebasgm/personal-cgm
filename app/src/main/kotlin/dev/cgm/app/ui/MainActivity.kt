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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cgm.app.CgmApplication
import dev.cgm.app.service.PollingService

class MainActivity : ComponentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as CgmApplication
        ensureNotificationPermission()

        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) {
                val vm: CgmViewModel = viewModel(
                    factory = CgmViewModel.factory(app.repository, app.settings)
                )
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
                    MainScaffold(
                        viewModel = vm,
                        onStartService = { PollingService.start(this) },
                        onStopService = { PollingService.stop(this) },
                    )
                } else {
                    SetupScreen(viewModel = vm, onSignedIn = { PollingService.start(this) })
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

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { stack = listOf(t.root) },
                        icon = {},
                        label = { Text(t.label) },
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
                Destination.Settings -> SettingsScreen(
                    viewModel = viewModel,
                    onStartService = onStartService,
                    onStopService = onStopService,
                ) { stack = stack + it }
                Destination.Alarms -> AlarmsScreen(viewModel) { stack = stack + it }
                Destination.Ranges -> RangesScreen(viewModel)
                Destination.Disclaimer -> DisclaimerReadOnlyScreen()
                is Destination.AlarmDetail -> AlarmDetailScreen(viewModel, current.kind)
            }
        }
    }
}
