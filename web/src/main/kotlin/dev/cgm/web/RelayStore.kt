package dev.cgm.web

import dev.cgm.core.WatchPayload
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The latest reading the phone pushed, and nothing else.
 *
 * Memory only, one entry, replaced on each push. No database is not an omission
 * here — the relay's whole job is to carry a current value, and anything it kept
 * would be a copy of history that already lives on the phone, in a place with
 * weaker custody.
 *
 * A restart loses the entry and the next push restores it, which is the correct
 * failure: a relay serving a value it cannot vouch for is worse than one serving
 * nothing.
 */
class RelayStore(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val held = AtomicReference<Entry?>(null)

    data class Entry(val payload: WatchPayload, val receivedAtMillis: Long)

    fun accept(payload: WatchPayload): Boolean {
        val current = held.get()
        // Out-of-order arrivals are dropped. A retried push from a phone that
        // reconnected can land behind a newer one, and moving backwards in time
        // is the one thing a display must never do.
        if (current != null && payload.sequence <= current.payload.sequence) return false

        held.set(Entry(payload, clock()))
        return true
    }

    /**
     * What the browser should see, or null.
     *
     * Expiry is on the *reading's* own timestamp rather than on when it arrived.
     * A phone that pushed an old reading has not made it current by pushing it,
     * and the browser is entitled to the same truth the phone shows.
     */
    fun current(): WatchPayload? {
        val entry = held.get() ?: return null
        val age = clock() - entry.payload.snapshot.reading.timestampMillis
        if (age > MAX_AGE_MILLIS) return null
        return entry.payload
    }

    fun clear() = held.set(null)

    companion object {
        /**
         * Past this, an entry is not stale data, it is no data.
         *
         * The browser already degrades a value long before this — losing its
         * colour, then refusing to show a number — so this is the outer bound
         * where the relay stops claiming to know anything at all.
         */
        val MAX_AGE_MILLIS: Long = TimeUnit.HOURS.toMillis(6)
    }
}

/**
 * The shared secret, compared without leaking how much of it matched.
 *
 * A byte-by-byte comparison returns sooner for a wrong first character than a
 * wrong last one, and that difference is enough to recover a secret one character
 * at a time. Comparing digests in constant time removes the signal.
 */
object RelaySecret {

    fun matches(provided: String?, expected: String): Boolean {
        if (provided.isNullOrEmpty() || expected.isEmpty()) return false
        return MessageDigest.isEqual(sha256(provided), sha256(expected))
    }

    private fun sha256(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())

    /** Pulls a bearer token out of an Authorization header. */
    fun bearer(header: String?): String? =
        header?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()
}
