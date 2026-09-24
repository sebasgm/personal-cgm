package dev.cgm.app.ui

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.cgm.app.R
import dev.cgm.app.service.RelayConfig
import java.security.SecureRandom
import java.util.Base64

/**
 * Pushing readings to a relay so they can be seen from a browser.
 *
 * The only feature that sends health data off the device, so it is off until a
 * URL and a key are entered, and the screen says what it means rather than
 * burying it.
 */
@Composable
fun RelayScreen(viewModel: CgmViewModel) {
    val saved by viewModel.relayConfig.collectAsState()
    val clipboard = LocalClipboardManager.current

    var url by remember(saved.baseUrl) { mutableStateOf(saved.baseUrl) }
    var secret by remember(saved.secret) { mutableStateOf(saved.secret) }

    val draft = RelayConfig(url, secret)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text(stringResource(R.string.set_relay), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.relay_explain),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text(stringResource(R.string.relay_url)) },
            placeholder = { Text("https://…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // Plain HTTP would put the key and the readings on the wire in clear.
        if (url.isNotBlank() && !draft.isSecure) {
            Text(
                stringResource(R.string.relay_insecure),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = secret,
            onValueChange = { secret = it },
            label = { Text(stringResource(R.string.relay_key)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.relay_key_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(8.dp))
        Column {
            OutlinedButton(onClick = { secret = generateKey() }) {
                Text(stringResource(R.string.relay_generate))
            }
            if (secret.isNotBlank()) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(secret)) }) {
                    Text(stringResource(R.string.relay_copy))
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { viewModel.saveRelay(draft) },
            enabled = draft.isConfigured,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.dose_save)) }

        if (saved.isConfigured) {
            TextButton(onClick = {
                url = ""
                secret = ""
                viewModel.clearRelay()
            }) { Text(stringResource(R.string.relay_stop)) }
        }

        Spacer(Modifier.height(20.dp))
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(R.string.relay_privacy),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(14.dp),
            )
        }
    }
}

/**
 * A key long enough that guessing it is not a strategy.
 *
 * 32 random bytes, URL-safe. It is a password for your glucose, so it is
 * generated rather than chosen — a key someone invents is a key someone can
 * remember, and those are the ones that get reused.
 */
private fun generateKey(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
