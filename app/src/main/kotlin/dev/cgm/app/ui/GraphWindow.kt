package dev.cgm.app.ui

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** The range chips under the Home graph. */
enum class GraphWindow(val label: String, val duration: Duration) {
    H3("3h", 3.hours),
    H6("6h", 6.hours),
    H12("12h", 12.hours),
    H24("24h", 24.hours);

    val millis: Long get() = duration.inWholeMilliseconds

    companion object {
        val Default = H3
    }
}
