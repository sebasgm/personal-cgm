package dev.cgm.app.ui

import androidx.annotation.StringRes
import dev.cgm.app.R

import dev.cgm.core.AlarmKind
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/** Trends windows. Long ones are aggregated in SQL, so a year stays cheap. */
enum class TrendPeriod(val label: String, val duration: Duration) {
    D1("24h", 1.days),
    D7("7d", 7.days),
    D14("14d", 14.days),
    D30("30d", 30.days),
    D90("90d", 90.days);

    val millis: Long get() = duration.inWholeMilliseconds
    val days: Int get() = duration.inWholeDays.toInt()

    companion object {
        val Default = D7
    }
}

/**
 * Report windows.
 *
 * Longer than the trend windows and coarser at the top end, because a report is
 * read once and kept, not watched. The longest options will read as mostly empty
 * until the history behind them exists — nothing backfills, so a period only
 * covers what was recorded while the app was running.
 */
enum class ReportPeriod(val label: String, val duration: Duration) {
    D7("7d", 7.days),
    D14("14d", 14.days),
    D30("30d", 30.days),
    D90("90d", 90.days),
    D180("6m", 180.days),
    D365("1y", 365.days);

    val millis: Long get() = duration.inWholeMilliseconds
    val days: Int get() = duration.inWholeDays.toInt()

    companion object {
        val Default = D30

        /**
         * The preset matching a stored length, or null for anything else.
         *
         * Null rather than a nearest match: the chips say what they say, and one
         * of them looking selected while the report covers a different span is
         * the kind of small lie that makes the rest untrustworthy.
         */
        fun ofDays(days: Int): ReportPeriod? = entries.firstOrNull { it.days == days }
    }
}

/**
 * Where the user is.
 *
 * A hand-rolled stack rather than navigation-compose: four tabs and two detail
 * screens do not justify the dependency or its argument plumbing.
 */
sealed interface Destination {
    data object Home : Destination
    data object Trends : Destination
    data object Logbook : Destination
    /**
     * Insulin and food, as the user records them.
     *
     * Named the diary rather than the log: "log" is already the Logbook of readings
     * the sensor produced, and would also read as a device log. This is the record of
     * what a person did, which is the opposite kind of fact.
     */
    data object Diary : Destination
    data object Settings : Destination
    data object Alarms : Destination
    data class AlarmDetail(val kind: AlarmKind) : Destination
    data object Ranges : Destination
    data object Accessibility : Destination
    data object Reminders : Destination
    data object Relay : Destination
    data object ReportSettings : Destination
    data object ReleaseNotes : Destination
    data object Disclaimer : Destination
}

/**
 * Labels are resource ids, not strings: an enum constant is built once per process,
 * long before a localised context exists, so a `String` here would freeze whatever
 * language happened to be active at class-load time.
 */
/**
 * How long away before returning lands on Home rather than where you left off.
 *
 * Five minutes is roughly when the reason for opening the app changes. Under
 * that you are still doing the thing you were doing — reading the logbook,
 * editing an alarm — and being thrown back to Home loses your place. Past it you
 * have almost certainly picked the phone up to check a number, and Settings is
 * where you *were*, not where you want to be.
 */
const val RETURN_TO_HOME_AFTER_MILLIS = 5L * 60 * 1000

enum class Tab(@StringRes val labelRes: Int, val root: Destination) {
    HOME(R.string.nav_now, Destination.Home),
    TRENDS(R.string.nav_trends, Destination.Trends),
    LOGBOOK(R.string.nav_logbook, Destination.Logbook),
    DIARY(R.string.nav_diary, Destination.Diary),
    SETTINGS(R.string.nav_settings, Destination.Settings);

    companion object {
        fun of(destination: Destination): Tab = when (destination) {
            Destination.Home -> HOME
            Destination.Trends -> TRENDS
            Destination.Logbook -> LOGBOOK
            Destination.Diary -> DIARY
            Destination.Settings,
            Destination.Alarms,
            Destination.Ranges,
            Destination.Accessibility,
            Destination.Reminders,
            Destination.Relay,
            Destination.ReportSettings,
            Destination.ReleaseNotes,
            Destination.Disclaimer,
            is Destination.AlarmDetail -> SETTINGS
        }
    }
}
