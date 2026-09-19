package dev.cgm.core

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

class GlucoseUnitTest {
    @Test
    fun `mmol conversion and formatting`() {
        assertEquals("112", GlucoseUnit.MGDL.format(112.0))
        assertEquals("6.2", GlucoseUnit.MMOLL.format(112.0))
    }

    @Test
    fun `mgdl is the storage unit and round trips`() {
        val mmol = GlucoseUnit.MMOLL.from(180.0)
        assertEquals(180.0, mmol * GlucoseUnit.MMOL_PER_MGDL, 0.01)
    }
}

class GlucoseRangeTest {
    private val range = GlucoseRange(lowMgdl = 70.0, highMgdl = 180.0)

    @Test
    fun `classifies zones including urgent low`() {
        assertEquals(Zone.URGENT_LOW, range.classify(50.0))
        assertEquals(Zone.LOW, range.classify(65.0))
        assertEquals(Zone.IN_RANGE, range.classify(120.0))
        assertEquals(Zone.HIGH, range.classify(220.0))
    }

    @Test
    fun `fraction is clamped for the ranged-value complication`() {
        assertEquals(0f, range.fraction(40.0))
        assertEquals(1f, range.fraction(400.0))
        assertEquals(0.5f, range.fraction(125.0), 0.001f)
    }
}

class FreshnessTest {
    private val policy = FreshnessPolicy()
    private val now = 1_800_000_000_000L

    private fun readingAgedMinutes(m: Int) =
        GlucoseReading(valueMgdl = 100.0, timestampMillis = now - m.minutes.inWholeMilliseconds)

    @Test
    fun `freshness comes from the clock not from fetch success`() {
        assertEquals(Freshness.FRESH, policy.evaluate(readingAgedMinutes(2), now))
        assertEquals(Freshness.AGING, policy.evaluate(readingAgedMinutes(8), now))
        assertEquals(Freshness.STALE, policy.evaluate(readingAgedMinutes(30), now))
    }

    @Test
    fun `only fresh is trustworthy`() {
        assertTrue(Freshness.FRESH.isTrustworthy)
        assertTrue(!Freshness.AGING.isTrustworthy)
        assertTrue(!Freshness.STALE.isTrustworthy)
    }

    @Test
    fun `next transition lets callers schedule instead of poll`() {
        val r = readingAgedMinutes(2)
        assertEquals(4.minutes, policy.nextTransition(r, now))
        assertEquals(null, policy.nextTransition(readingAgedMinutes(60), now))
    }
}

class WatchPayloadTest {
    private val now = 1_800_000_000_000L

    private fun history(n: Int) = (0 until n).map {
        GlucoseReading(valueMgdl = 100.0 + it, timestampMillis = now - (n - it) * 60_000L)
    }

    @Test
    fun `round trips through the wire format`() {
        val payload = WatchPayload(
            snapshot = GlucoseSnapshot(
                reading = GlucoseReading(112.0, now, TrendArrow.RISING),
                unit = GlucoseUnit.MMOLL,
                deltaMgdl = 7.0,
            ),
            history = history(10),
            sentAtMillis = now,
            sequence = 3,
        )
        val decoded = WatchPayload.decode(payload.encode())
        assertEquals(payload, decoded)
        assertEquals(TrendArrow.RISING, decoded.snapshot.reading.trend)
    }

    @Test
    fun `payload stays small enough for the data layer`() {
        val payload = WatchPayload(
            snapshot = GlucoseSnapshot(GlucoseReading(112.0, now)),
            history = WatchPayload.downsample(history(500)),
            sentAtMillis = now,
        )
        // Well under the Data Layer's 100KB item cap, with room to grow.
        assertTrue(payload.encode().size < 8_000, "payload was ${payload.encode().size} bytes")
    }

    @Test
    fun `downsample keeps the newest reading`() {
        val full = history(500)
        val thinned = WatchPayload.downsample(full)
        assertTrue(thinned.size <= WatchPayload.MAX_HISTORY_POINTS)
        assertEquals(full.last(), thinned.last())
        assertNotEquals(full.size, thinned.size)
    }

    @Test
    fun `downsample leaves short history untouched`() {
        val short = history(5)
        assertEquals(short, WatchPayload.downsample(short))
    }
}

class SnapshotTest {
    @Test
    fun `formats delta in display unit with sign`() {
        val s = GlucoseSnapshot(
            reading = GlucoseReading(112.0, 0L),
            unit = GlucoseUnit.MGDL,
            deltaMgdl = -7.0,
        )
        assertEquals("-7", s.formattedDelta())
        assertEquals("112", s.formattedValue())
    }
}

class PollSchedulerTest {
    private val scheduler = PollScheduler()

    @Test
    fun `polls at the base interval while readings are current`() {
        assertEquals(60_000L, scheduler.nextDelayMillis(PollOutcome.Success(readingAgeMillis = 90_000)))
    }

    @Test
    fun `backs off when the sensor has clearly gone away`() {
        val outcome = PollOutcome.Success(readingAgeMillis = 40.minutes.inWholeMilliseconds)
        assertEquals(300_000L, scheduler.nextDelayMillis(outcome))
    }

    @Test
    fun `stops entirely on a fatal outcome`() {
        assertEquals(null, scheduler.nextDelayMillis(PollOutcome.Fatal))
    }

    @Test
    fun `obeys Retry-After instead of its own backoff curve`() {
        val outcome = PollOutcome.Transient(retryAfterMillis = 120_000)
        assertEquals(120_000L, scheduler.nextDelayMillis(outcome, consecutiveFailures = 1))
    }

    @Test
    fun `backs off exponentially and then caps`() {
        val t = PollOutcome.Transient()
        assertEquals(30_000L, scheduler.nextDelayMillis(t, consecutiveFailures = 1))
        assertEquals(60_000L, scheduler.nextDelayMillis(t, consecutiveFailures = 2))
        assertEquals(120_000L, scheduler.nextDelayMillis(t, consecutiveFailures = 3))
        assertEquals(300_000L, scheduler.nextDelayMillis(t, consecutiveFailures = 9))
        assertEquals(300_000L, scheduler.nextDelayMillis(t, consecutiveFailures = 99))
    }
}
