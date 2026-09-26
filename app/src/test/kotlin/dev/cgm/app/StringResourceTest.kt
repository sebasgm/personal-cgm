package dev.cgm.app

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guards the string table against the one mistake in it that crashes the app.
 *
 * `stringResource(id, arg)` resolves through `String.format`, so a literal percent
 * sign in a string that also takes an argument is read as the start of a conversion:
 * "Middle 50% %1$s" throws `UnknownFormatConversionException` the first time the
 * screen showing it appears. Nothing catches this earlier — the resource compiler is
 * happy, the Kotlin compiler cannot see inside a string, and a string that is only
 * reached by tapping something is not reached by opening the app.
 *
 * It crashed exactly that way once, in the daily pattern's tap callout, which is why
 * this exists. A percent inside a string with no arguments is harmless, because
 * `String.format` is never called on it — so the check applies only where it can bite.
 *
 * Reading the XML from disk rather than through Android resources keeps this a plain
 * JVM test: the hazard is in the text, and the text is a file.
 */
class StringResourceTest {

    /** One locale's table: the resource qualifier it lives under, and its strings. */
    private data class Locale(val name: String, val strings: Map<String, String>)

    private val locales: List<Locale> by lazy {
        File("src/main/res").listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values") }
            .map { it.name to File(it, "strings.xml") }
            .filter { (_, file) -> file.isFile }
            .sortedBy { (name, _) -> name }
            .map { (name, file) -> Locale(name, parse(file.readText())) }
    }

    /** The default locale, which every translation is checked against. */
    private val default: Locale get() = locales.single { it.name == "values" }

    private val stringPattern = Regex("""<string name="([a-z_0-9]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)

    /** A positional argument, as Android writes them: %1$s, %2$d. */
    private val argumentPattern = Regex("""%\d+\$[a-zA-Z]""")

    private fun parse(xml: String): Map<String, String> =
        stringPattern.findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }

    @Test
    fun `the locales exist and are not empty`() {
        assertTrue(locales.size >= 2, "expected at least a default and English locale")
        locales.forEach { assertTrue(it.strings.isNotEmpty(), "${it.name} has no strings") }
    }

    @Test
    fun `a percent sign in a formatted string is escaped`() {
        val offenders = mutableListOf<String>()

        locales.forEach { locale ->
            locale.strings.forEach { (name, body) ->
                // No arguments means no String.format, which means a bare percent is
                // simply a percent. Most of the explanatory text on Trends relies on
                // that, and demanding %% there would be noise.
                if (!argumentPattern.containsMatchIn(body)) return@forEach

                val leftover = argumentPattern.replace(body, "").replace("%%", "")
                if (leftover.contains('%')) {
                    offenders += "${locale.name}/$name: $body"
                }
            }
        }

        assertEquals(
            emptyList(),
            offenders,
            "a literal % in a string with arguments must be written %% or String.format throws",
        )
    }

    /**
     * Every locale carries every key.
     *
     * A missing key falls back to the default locale rather than crashing, so this is
     * not a crash guard — but the fallback is silent, and a screen that is half
     * Spanish is a bug nobody reports because it looks deliberate.
     */
    @Test
    fun `the locales define the same keys`() {
        val reference = default.strings.keys

        locales.filter { it.name != default.name }.forEach { locale ->
            val keys = locale.strings.keys
            assertEquals(emptySet(), reference - keys, "${locale.name} is missing keys")
            assertEquals(
                emptySet(),
                keys - reference,
                "${locale.name} has keys the default locale lacks",
            )
        }
    }

    /** And the same arguments in the same shape, or a translation formats wrongly. */
    @Test
    fun `a translated string takes the same arguments as the original`() {
        locales.filter { it.name != default.name }.forEach { locale ->
            locale.strings.forEach { (name, body) ->
                val expected = argumentPattern.findAll(default.strings[name].orEmpty())
                    .map { it.value }
                    .toSortedSet()
                val actual = argumentPattern.findAll(body).map { it.value }.toSortedSet()
                assertEquals(expected, actual, "${locale.name}/$name")
            }
        }
    }
}
