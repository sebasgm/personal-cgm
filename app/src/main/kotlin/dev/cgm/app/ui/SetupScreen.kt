package dev.cgm.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.cgm.app.R
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun SetupScreen(viewModel: CgmViewModel, onSignedIn: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    val busy by viewModel.signingIn.collectAsState()
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineSmall)

        Text(
            stringResource(R.string.setup_explanation),
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = email,
            onValueChange = { email = it; failed = false },
            label = { Text(stringResource(R.string.setup_email)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = password,
            onValueChange = { password = it; failed = false },
            label = { Text(stringResource(R.string.setup_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                viewModel.signIn(email, password) { ok ->
                    failed = !ok
                    if (ok) onSignedIn()
                }
            },
            enabled = !busy && email.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 8.dp),
                    strokeWidth = 2.dp,
                )
            }
            Text(if (busy) stringResource(R.string.setup_checking) else stringResource(R.string.setup_sign_in))
        }

        // Credentials are verified against the live API before being kept, so
        // the error here is the real reason rather than a generic failure.
        if (failed) {
            Text(
                state.error?.let { stringResource(it.messageRes) }
                    ?: stringResource(R.string.setup_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.Start),
            )
        }

        Text(
            stringResource(R.string.setup_key_note),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
