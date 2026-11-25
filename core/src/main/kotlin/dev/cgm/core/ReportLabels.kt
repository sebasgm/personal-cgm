package dev.cgm.core

/**
 * Every word a report puts in front of a reader.
 *
 * The report's figures were always locale-correct — dates come out of the JVM
 * default locale and numbers deliberately do not — but the prose around them was
 * forty-odd English literals inlined in [ReportExport]. On a build whose default
 * language is Spanish that produced a document headed `Range / Readings /
 * Coverage` over rows reading `23 nov 2026`, which is worse than either language
 * alone: it reads as a half-finished translation, and the disclaimer — the one
 * paragraph that has to be understood — was in the language the reader may not
 * have.
 *
 * It lives here rather than in `strings.xml` alone because `:core` is plain
 * Kotlin/JVM and must stay that way; the module is shared with Wear OS and has no
 * resources to reach for. So the strings are *passed in*: the app fills this from
 * its own `strings.xml`, which keeps every translation in the one file a
 * translator already works in, and the defaults below keep `:core` and its tests
 * able to render a report on their own.
 *
 * Carried on the [Report] rather than handed to [ReportExport.export] separately.
 * A report is rebuilt whenever its preferences change and is never persisted, so
 * there is no stale-language case to worry about — and one bundle travelling with
 * the data it describes cannot disagree with the screen that drew the same report,
 * which two parameters eventually would.
 *
 * The parameterised strings take their numbers pre-formatted, as `%1$s` rather
 * than `%1$d`, so no template here or in any translation of it has to contain a
 * literal `%`. That is not fussiness: a percent sign in a formatted Android
 * string must be doubled, it is the single most common way a translated string
 * crashes at runtime, and the crash lands in whichever language nobody tested.
 */
data class ReportLabels(
    /** For `<html lang>`, so a screen reader does not read Spanish in English. */
    val languageTag: String = "en",
    val title: String = "Glucose report",
    /** The summary row that spans every range. */
    val overall: String = "All",
    val generated: String = "Generated",
    val valuesIn: String = "values in",
    val inRange: String = "in range",
    val summary: String = "Summary",
    val columnRange: String = "Range",
    val columnReadings: String = "Readings",
    val columnCoverage: String = "Coverage",
    val columnMean: String = "Mean",
    val columnMedian: String = "Median",
    val columnSd: String = "SD",
    val columnCv: String = "CV",
    val columnInRange: String = "In range",
    val medianByTimeOfDay: String = "Median by time of day",
    val disclaimer: String =
        "Not medical advice and not a medical device. These figures come from a " +
            "consumer sensor read through an unofficial interface, may be incomplete, " +
            "and have not been assessed by any regulator. Do not use them to make " +
            "treatment decisions.",
    /** `%1$s` ranges were dropped for having no readings. */
    val skippedRanges: String =
        "%1\$s range(s) in this period had no readings at all and are not listed. " +
            "The summary row below still covers the whole period, gaps included — " +
            "see its coverage figure.",
    /** `%1$s` is the coverage threshold, already written as a percentage. */
    val thinRanges: String =
        "Ranges shown in amber have under %1\$s coverage. Their figures describe " +
            "the hours recorded, not the whole range.",
    val bandsNote: String =
        "Each figure is the median of that part of the local day, over the days in " +
            "that range. Bands follow wall-clock hours, so they stay comparable " +
            "across a time zone change.",
    /** `%1$s` readings, `%2$s` coverage — both already written out. */
    val rangeHeadline: String = "%1\$s readings · %2\$s coverage",
) {
    /**
     * Substitutes the positional arguments of one of the templates above.
     *
     * Deliberately not `String.format`: the arguments arrive already written out,
     * so there is no number for a locale to reformat and no conversion for a
     * mistyped placeholder to fail on. A template that lost its placeholder in
     * translation comes out missing a figure rather than throwing, which is the
     * right failure for a document someone is trying to print.
     */
    fun fill(template: String, vararg args: String): String =
        args.foldIndexed(template) { index, text, value ->
            text.replace("%${index + 1}\$s", value)
        }

    companion object {
        /** The defaults, named so the fallback is explicit at every call site. */
        val English = ReportLabels()
    }
}
