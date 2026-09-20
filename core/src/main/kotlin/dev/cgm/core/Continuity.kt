package dev.cgm.core

import kotlin.time.Duration.Companion.minutes

/**
 * How complete the record actually is over a window.
 *
 * Exists because a gap in this app's history is permanent. LibreLinkUp serves
 * about twelve hours of roughly 15-minute graph data and nothing older, so a
 * minute we failed to poll cannot be recovered at the resolution we poll at. That
 * makes "did we keep up overnight" a question worth being able to answer directly
 * rather than inferring from a chart that looks a bit sparse.
 */
data class ContinuityReport(
    val readingCount: Int,
    val largestGapMillis: Long,
    /** Gaps longer than the threshold — each one is data that no longer exists. */
    val gapCount: Int,
    /** Total time inside the window with no reading near it. */
    val missingMillis: Long,
    val windowMillis: Long,
) {
    val completeness: Double
        get() = if (windowMillis <= 0) 0.0
        else (1.0 - missingMillis.toDouble() / windowMillis).coerceIn(0.0, 1.0)

    val isHealthy: Boolean get() = gapCount == 0

    companion object {
        val Empty = ContinuityReport(0, 0, 0, 0, 0)
    }
}

object ContinuityAnalyzer {

    /**
     * A gap worth counting.
     *
     * Above the worst cadence a healthy feed shows. Measured on this connection:
     * readings arrive every 60s, worst observed spacing 122s. Five minutes means
     * several consecutive readings were missed, which is a real interruption
     * rather than one dropped sample.
     */
    val GAP_THRESHOLD_MILLIS = 5.minutes.inWholeMilliseconds

    fun analyse(
        readings: List<GlucoseReading>,
        windowStartMillis: Long,
        windowEndMillis: Long,
        gapThresholdMillis: Long = GAP_THRESHOLD_MILLIS,
    ): ContinuityReport {
        val window = readings
            .filter { it.timestampMillis in windowStartMillis..windowEndMillis }
            .sortedBy { it.timestampMillis }

        val windowMillis = (windowEndMillis - windowStartMillis).coerceAtLeast(0)
        if (window.isEmpty()) {
            return ContinuityReport(
                readingCount = 0,
                largestGapMillis = windowMillis,
                gapCount = if (windowMillis > gapThresholdMillis) 1 else 0,
                missingMillis = windowMillis,
                windowMillis = windowMillis,
            )
        }

        var largest = 0L
        var gaps = 0
        var missing = 0L

        // The edges count too: a window that starts an hour before the first
        // reading is missing that hour, however dense the rest of it is.
        fun consider(gap: Long) {
            if (gap > largest) largest = gap
            if (gap > gapThresholdMillis) {
                gaps++
                missing += gap
            }
        }

        consider(window.first().timestampMillis - windowStartMillis)
        window.zipWithNext { a, b -> consider(b.timestampMillis - a.timestampMillis) }
        consider(windowEndMillis - window.last().timestampMillis)

        return ContinuityReport(
            readingCount = window.size,
            largestGapMillis = largest,
            gapCount = gaps,
            missingMillis = missing.coerceAtMost(windowMillis),
            windowMillis = windowMillis,
        )
    }
}
