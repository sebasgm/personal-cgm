package dev.cgm.app.ui

import androidx.annotation.StringRes
import dev.cgm.app.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * What this app is not.
 *
 * Kept in one place so the first-run screen and the copy in Settings cannot drift
 * apart — a disclaimer that says two different things has told you nothing.
 *
 * [VERSION] is stored with the acceptance so that materially changing this text can
 * ask again. Bump it only for changes that alter the meaning; fixing a typo should
 * not interrogate everyone.
 */
object Disclaimer {

    const val VERSION = 1

    @StringRes
    val TITLE = R.string.disc_title

    /**
     * Ordered, because the argument runs in order: what this is not, then what not
     * to do with it, then why it can be wrong, then that the alarms can fail, then
     * what to trust instead.
     */
    val PARAGRAPHS = listOf(
        R.string.disc_p1,
        R.string.disc_p2,
        R.string.disc_p3,
        R.string.disc_p4,
        R.string.disc_p5,
    )
}

/**
 * The first-run gate.
 *
 * Deliberately blocking, and deliberately ahead of sign-in: it governs how every
 * number in the app should be read, so agreeing to it is not something to discover
 * later in Settings.
 */
@Composable
fun DisclaimerScreen(onAccept: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            stringResource(Disclaimer.TITLE),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        DisclaimerBody()
        Spacer(Modifier.height(8.dp))
        Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.disc_accept))
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** The same text, reachable for ever from Settings. */
@Composable
fun DisclaimerReadOnlyScreen() {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionTitle(stringResource(Disclaimer.TITLE))
        DisclaimerBody()
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun DisclaimerBody() {
    Disclaimer.PARAGRAPHS.forEach {
        Text(stringResource(it), style = MaterialTheme.typography.bodyMedium)
    }
}
