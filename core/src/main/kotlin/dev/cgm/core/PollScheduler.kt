package dev.cgm.core

import kotlin.math.min
import kotlin.math.pow
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What happened on one poll, reduced to what scheduling actually depends on. */
sealed interface PollOutcome {

    /** Got a reading. [readingAgeMillis] is how old it already was. */
    data class Success(val readingAgeMillis: Long) : PollOutcome

    /** Worth retrying without user involvement. */
    data class Transient(val retryAfterMillis: Long? = null) : PollOutcome

    /** Needs the user: bad password, revoked access. Stop burning battery. */
    data object Fatal : PollOutcome
}

/**
 * Decides when to poll next.
 *
 * Pure and platform-free on purpose: the phone service drives it now, and the
 * stage 6 "watch polls for itself when the phone is away" fallback drives the
 * same logic on the watch.
 *
 * The shape of the problem: a Libre sensor produces a value a minute, but the
 * LibreLinkUp cloud adds a lag of its own, and Abbott rate-limits. Polling
 * faster than [baseIntervalMillis] buys nothing but 429s.
 */
class PollScheduler(
    private val baseIntervalMillis: Long = 60.seconds.inWholeMilliseconds,
    private val idleIntervalMillis: Long = 5.minutes.inWholeMilliseconds,
    private val firstBackoffMillis: Long = 30.seconds.inWholeMilliseconds,
    private val maxBackoffMillis: Long = 5.minutes.inWholeMilliseconds,
    /**
     * Above this reading age the sensor is presumed absent — out of range, or
     * between sessions. Polling hard at that point just drains the battery.
     */
    private val sensorIdleAfterMillis: Long = 15.minutes.inWholeMilliseconds,
) {

    /** @return delay until the next poll, or null to stop polling entirely. */
    fun nextDelayMillis(outcome: PollOutcome, consecutiveFailures: Int = 0): Long? =
        when (outcome) {
            is PollOutcome.Fatal -> null

            is PollOutcome.Success ->
                if (outcome.readingAgeMillis >= sensorIdleAfterMillis) idleIntervalMillis
                else baseIntervalMillis

            is PollOutcome.Transient -> outcome.retryAfterMillis
                // Honour Retry-After when the server sends one; it is not a hint.
                ?: exponentialBackoff(consecutiveFailures)
        }

    private fun exponentialBackoff(consecutiveFailures: Int): Long {
        val exponent = (consecutiveFailures - 1).coerceAtLeast(0)
        val scaled = firstBackoffMillis * 2.0.pow(exponent)
        return min(scaled, maxBackoffMillis.toDouble()).toLong()
    }
}
