package dev.cgm.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
                Surface {
                    val vm: CgmViewModel = viewModel(
                        factory = CgmViewModel.factory(app.repository, app.settings)
                    )
                    val state by vm.state.collectAsState()

                    if (state.configured) {
                        StatusScreen(
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
    }

    /**
     * Android 13+ hides the foreground-service notification without this, and
     * that notification is the app's primary display when it is not open.
     */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        if (granted != PackageManager.PERMISSION_GRANTED) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
