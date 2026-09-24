package dev.cgm.web

import dev.cgm.core.GlucoseReading
import dev.cgm.core.GlucoseSnapshot
import dev.cgm.core.WatchPayload
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class RelayStoreTest {

    private var now = 1_800_000_000_000L
    private val store = RelayStore { now }

    private fun payload(sequence: Long, ageMinutes: Int = 1) = WatchPayload(
        snapshot = GlucoseSnapshot(GlucoseReading(160.0, now - ageMinutes * 60_000L)),
        sentAtMillis = now,
        sequence = sequence,
    )

    @Test
    fun `serves what was last pushed`() {
        assertTrue(store.accept(payload(1)))
        assertNotNull(store.current())
        assertEquals(1L, store.current()!!.sequence)
    }

    @Test
    fun `nothing pushed means nothing served`() {
        assertNull(store.current())
    }

    @Test
    fun `a newer push replaces an older one`() {
        store.accept(payload(1))
        assertTrue(store.accept(payload(2)))
        assertEquals(2L, store.current()!!.sequence)
    }

    @Test
    fun `an out-of-order push is refused`() {
        // A retry from a phone that reconnected can arrive behind a newer one,
        // and moving backwards in time is what a display must never do.
        store.accept(payload(5))
        assertFalse(store.accept(payload(4)))
        assertEquals(5L, store.current()!!.sequence)
    }

    @Test
    fun `the same sequence twice changes nothing`() {
        store.accept(payload(3))
        assertFalse(store.accept(payload(3)))
    }

    @Test
    fun `an old reading expires rather than being served forever`() {
        store.accept(payload(1, ageMinutes = 10))
        assertNotNull(store.current())

        now += RelayStore.MAX_AGE_MILLIS
        assertNull(store.current(), "a phone that stopped pushing must not leave a value behind")
    }

    @Test
    fun `expiry follows the reading's age, not when it arrived`() {
        // Pushing an old reading does not make it current.
        store.accept(payload(1, ageMinutes = (RelayStore.MAX_AGE_MILLIS / 60_000).toInt() + 1))
        assertNull(store.current())
    }

    @Test
    fun `clearing empties it`() {
        store.accept(payload(1))
        store.clear()
        assertNull(store.current())
    }
}

class RelaySecretTest {

    @Test
    fun `matches only the exact secret`() {
        assertTrue(RelaySecret.matches("hunter2hunter2", "hunter2hunter2"))
        assertFalse(RelaySecret.matches("hunter2hunter3", "hunter2hunter2"))
    }

    @Test
    fun `an absent or empty secret never matches`() {
        assertFalse(RelaySecret.matches(null, "secret"))
        assertFalse(RelaySecret.matches("", "secret"))
        // And an unconfigured relay does not accept everything.
        assertFalse(RelaySecret.matches("anything", ""))
    }

    @Test
    fun `reads a bearer token, case-insensitively`() {
        assertEquals("abc", RelaySecret.bearer("Bearer abc"))
        assertEquals("abc", RelaySecret.bearer("bearer abc"))
    }

    @Test
    fun `ignores anything that is not a bearer header`() {
        assertNull(RelaySecret.bearer(null))
        assertNull(RelaySecret.bearer("Basic abc"))
    }
}
