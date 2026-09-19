package dev.cgm.app.ui

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

    const val TITLE = "Before you use this"

    val PARAGRAPHS = listOf(
        "This app is not a medical device and it is not a source of truth. It reads " +
            "glucose values from your LibreLinkUp account and shows them. It does not " +
            "measure anything itself.",

        "Do not make treatment decisions on what you see here. If a number matters — " +
            "if you are deciding whether to eat, dose or drive — check the official " +
            "FreeStyle Libre app or scan your sensor. If this app and the official app " +
            "disagree, the official app is right and this one is wrong.",

        "Values can be wrong, late, or missing. They pass through Abbott's servers, your " +
            "network and Android's background limits before reaching this screen, and any " +
            "of those can delay or drop them. A number on screen with no warning beside " +
            "it still only means \"this is the most recent value we managed to fetch\".",

        "Alarms can fail to arrive. Android may delay or suppress notifications while the " +
            "phone is asleep, in Do Not Disturb, or saving battery, and this app cannot " +
            "override all of that. Never rely on it to wake you. Treat a silent night as " +
            "no evidence that nothing happened.",

        "If you feel unwell, believe what your body is telling you over what this app " +
            "shows, and seek medical advice. Nothing here replaces your clinician.",
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
            Disclaimer.TITLE,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        DisclaimerBody()
        Spacer(Modifier.height(8.dp))
        Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) {
            Text("I understand")
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
        SectionTitle(Disclaimer.TITLE)
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
        Text(it, style = MaterialTheme.typography.bodyMedium)
    }
}
