package dev.cgm.app

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * The app's own language, independent of the device's.
 *
 * Spanish is the default: it lives in `values/`, and English in `values-en/`. So
 * with no override at all a Spanish phone and a Japanese phone both get Spanish,
 * and only an English phone gets English. That is deliberate for an app with one
 * user, and it is why the override matters — it is the only way to ask for English
 * on a Spanish phone.
 *
 * The chosen tag is cached in memory rather than read from DataStore at each use.
 * Both places that need it are awkward for suspending code: an Activity's
 * `attachBaseContext` runs before anything can be collected, and the polling
 * service builds notification text on its own thread. Priming this once in
 * [CgmApplication.onCreate] costs a single blocking read at startup and makes every
 * later use synchronous.
 */
object Locales {

    /** BCP-47 tag, or null to follow the device. */
    @Volatile
    var current: String? = null

    val SUPPORTED = listOf("es", "en")

    /**
     * A context whose resources resolve in [current].
     *
     * Also sets the JVM default locale, because number and date formatting reach
     * for that rather than for a Context — a Spanish UI printing dates in English
     * would give the override away immediately.
     */
    fun wrap(context: Context): Context {
        val tag = current ?: return context
        val locale = runCatching { Locale.forLanguageTag(tag) }.getOrNull() ?: return context

        Locale.setDefault(locale)
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        return context.createConfigurationContext(configuration)
    }

    /** How a tag should read in a language picker, in its own language. */
    fun displayName(tag: String): String = when (tag) {
        "es" -> "Español"
        "en" -> "English"
        else -> Locale.forLanguageTag(tag).displayLanguage
    }
}
