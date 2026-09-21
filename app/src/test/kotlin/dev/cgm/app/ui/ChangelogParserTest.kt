package dev.cgm.app.ui

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ChangelogParserTest {

    private val sample = """
        # Changelog

        Nothing has been released yet.

        ## [Unreleased]

        ### Added

        - Alarms for urgent low, low, high, very high and signal loss, each
          independently switchable with its own threshold.
        - A foreground polling service.

        ### Fixed

        - The poller no longer sleeps through the night.
    """.trimIndent()

    private val parsed = parseChangelog(sample)

    @Test
    fun `drops the document title, which the screen already provides`() {
        assertTrue(parsed.none { it is ChangelogLine.Version && it.text == "Changelog" })
        assertTrue(parsed.none { it is ChangelogLine.Paragraph && it.text.contains("# ") })
    }

    @Test
    fun `reads version headings without their brackets`() {
        val versions = parsed.filterIsInstance<ChangelogLine.Version>()
        assertEquals(listOf("Unreleased"), versions.map { it.text })
    }

    @Test
    fun `reads section headings`() {
        assertEquals(
            listOf("Added", "Fixed"),
            parsed.filterIsInstance<ChangelogLine.Section>().map { it.text },
        )
    }

    @Test
    fun `folds a wrapped bullet back into one line`() {
        val bullets = parsed.filterIsInstance<ChangelogLine.Bullet>()
        assertEquals(3, bullets.size)
        assertTrue(
            bullets.first().text.endsWith("with its own threshold."),
            "wrapped continuation was lost: ${bullets.first().text}",
        )
        // And it must not have become a paragraph of its own.
        assertTrue(parsed.none { it is ChangelogLine.Paragraph && it.text.startsWith("independently") })
    }

    @Test
    fun `keeps prose as paragraphs`() {
        assertTrue(
            parsed.any { it is ChangelogLine.Paragraph && it.text.startsWith("Nothing has been") }
        )
    }

    @Test
    fun `an empty file yields nothing rather than throwing`() {
        assertEquals(emptyList(), parseChangelog(""))
    }

    @Test
    fun `strips inline markdown there is no styling for`() {
        val bold = parseChangelog("- A **bold** claim with `code`")
        assertEquals("A bold claim with code", (bold.single() as ChangelogLine.Bullet).text)
    }
}
