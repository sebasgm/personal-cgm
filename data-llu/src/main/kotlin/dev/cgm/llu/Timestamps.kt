package dev.cgm.llu

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Abbott sends timestamps as strings in US format with no zone marker.
 * `FactoryTimestamp` is UTC; the sibling `Timestamp` is sensor-local. We only
 * ever read the former — local time silently breaks across DST and travel.
 *
 * Some regional endpoints return 24-hour time, so both shapes are accepted.
 */
internal object Timestamps {

    private val FORMATS = listOf(
        DateTimeFormatter.ofPattern("M/d/yyyy h:mm:ss a", Locale.US),
        DateTimeFormatter.ofPattern("M/d/yyyy H:mm:ss", Locale.US),
    )

    /** @return epoch millis, or null if no known format matches. */
    fun parseUtcMillis(raw: String): Long? {
        val trimmed = raw.trim()
        for (format in FORMATS) {
            try {
                return LocalDateTime.parse(trimmed, format).toInstant(ZoneOffset.UTC).toEpochMilli()
            } catch (_: DateTimeParseException) {
                // try the next shape
            }
        }
        return null
    }
}
