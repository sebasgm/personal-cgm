package dev.cgm.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.cgm.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The release notes, which are CHANGELOG.md itself.
 *
 * The file is copied into assets by a Gradle task rather than transcribed into
 * string resources, so the notes people read and the notes the repository keeps
 * cannot drift apart — and nobody has to remember to update two places.
 *
 * Rendered with a deliberately small Markdown subset: headings, bullets and
 * paragraphs are all Keep a Changelog uses, and pulling in a Markdown library to
 * render one file would cost more than it returns.
 */
@Composable
fun ReleaseNotesScreen() {
    val context = LocalContext.current
    val lines by produceState(initialValue = emptyList<ChangelogLine>(), context) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open("CHANGELOG.md").bufferedReader().use { it.readText() }
            }.map(::parseChangelog).getOrElse { emptyList() }
        }
    }

    if (lines.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Text(
                stringResource(R.string.release_notes_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        lines.forEach { line ->
            when (line) {
                is ChangelogLine.Version -> {
                    Spacer(Modifier.height(20.dp))
                    Text(
                        line.text,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                is ChangelogLine.Section -> {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        line.text,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                is ChangelogLine.Bullet -> Row(Modifier.padding(top = 6.dp)) {
                    Text("•  ", style = MaterialTheme.typography.bodyMedium)
                    Text(line.text, style = MaterialTheme.typography.bodyMedium)
                }
                is ChangelogLine.Paragraph -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        line.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

sealed interface ChangelogLine {
    data class Version(val text: String) : ChangelogLine
    data class Section(val text: String) : ChangelogLine
    data class Bullet(val text: String) : ChangelogLine
    data class Paragraph(val text: String) : ChangelogLine
}

/**
 * Keep a Changelog, reduced to four kinds of line.
 *
 * Bullets wrap across lines in the source, so a continuation - an indented line
 * that is not itself a bullet - is folded into the bullet above rather than
 * becoming a stray paragraph.
 */
internal fun parseChangelog(raw: String): List<ChangelogLine> {
    val out = mutableListOf<ChangelogLine>()
    val paragraph = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotBlank()) out += ChangelogLine.Paragraph(paragraph.toString().trim())
        paragraph.clear()
    }

    raw.lineSequence().forEach { rawLine ->
        val line = rawLine.trimEnd()
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> flushParagraph()

            // The document's own title adds nothing on a screen already titled.
            trimmed.startsWith("# ") -> flushParagraph()

            trimmed.startsWith("## ") -> {
                flushParagraph()
                out += ChangelogLine.Version(trimmed.removePrefix("## ").trim('[', ']', ' '))
            }

            trimmed.startsWith("### ") -> {
                flushParagraph()
                out += ChangelogLine.Section(trimmed.removePrefix("### "))
            }

            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                flushParagraph()
                out += ChangelogLine.Bullet(stripInline(trimmed.substring(2).trim()))
            }

            // Indented continuation of the bullet above.
            line.startsWith("  ") && out.lastOrNull() is ChangelogLine.Bullet -> {
                val last = out.removeAt(out.lastIndex) as ChangelogLine.Bullet
                out += ChangelogLine.Bullet(last.text + " " + stripInline(trimmed))
            }

            else -> paragraph.append(if (paragraph.isEmpty()) "" else " ").append(stripInline(trimmed))
        }
    }
    flushParagraph()
    return out
}

/** Drops the inline markers Keep a Changelog uses; there is no styled text here. */
private fun stripInline(text: String): String =
    text.replace("**", "").replace("`", "")
