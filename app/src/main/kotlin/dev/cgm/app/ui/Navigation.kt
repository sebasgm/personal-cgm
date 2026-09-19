package dev.cgm.app.ui

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
 * Where the user is.
 *
 * A hand-rolled stack rather than navigation-compose: four tabs and two detail
 * screens do not justify the dependency or its argument plumbing.
 */
sealed interface Destination {
    data object Home : Destination
    data object Trends : Destination
    data object Logbook : Destination
    data object Settings : Destination
    data object Alarms : Destination
    data class AlarmDetail(val kind: AlarmKind) : Destination
    data object Ranges : Destination
}

enum class Tab(val label: String, val root: Destination) {
    HOME("Now", Destination.Home),
    TRENDS("Trends", Destination.Trends),
    LOGBOOK("Logbook", Destination.Logbook),
    SETTINGS("Settings", Destination.Settings);

    companion object {
        fun of(destination: Destination): Tab = when (destination) {
            Destination.Home -> HOME
            Destination.Trends -> TRENDS
            Destination.Logbook -> LOGBOOK
            Destination.Settings,
            Destination.Alarms,
            Destination.Ranges,
            is Destination.AlarmDetail -> SETTINGS
        }
    }
}
