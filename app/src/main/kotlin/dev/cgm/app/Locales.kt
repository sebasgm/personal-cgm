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
     * For anything that only needs strings: the application, the notifier, the
     * polling service. **Not** for an Activity's base context — see
     * [overrideConfiguration] for why.
     *
     * Also sets the JVM default locale, because number and date formatting reach
     * for that rather than for a Context — a Spanish UI printing dates in English
     * would give the override away immediately.
     */
    fun wrap(context: Context): Context {
        val configuration = overrideConfiguration(context) ?: return context
        return context.createConfigurationContext(configuration)
    }

    /**
     * The configuration [current] asks for, or null when there is no override.
     *
     * An Activity applies this through `applyOverrideConfiguration` rather than
     * replacing its base context with `createConfigurationContext`. The two look
     * equivalent and are not: a configuration context is a new context whose
     * *outer context is itself*, so an Activity whose base was replaced by one no
     * longer looks like an Activity to any system service fetched through it.
     *
     * That is not academic. `PrintManager.print` refuses outright — "Can print
     * only from an activity" — so with a language override set, printing could
     * never work. Applying the configuration instead keeps the real Activity
     * context underneath while resources still resolve in the chosen language.
     */
    fun overrideConfiguration(context: Context): Configuration? {
        val tag = current ?: return null
        val locale = runCatching { Locale.forLanguageTag(tag) }.getOrNull() ?: return null

        Locale.setDefault(locale)
        return Configuration(context.resources.configuration).apply { setLocale(locale) }
    }

    /** How a tag should read in a language picker, in its own language. */
    fun displayName(tag: String): String = when (tag) {
        "es" -> "Español"
        "en" -> "English"
        else -> Locale.forLanguageTag(tag).displayLanguage
    }
}
