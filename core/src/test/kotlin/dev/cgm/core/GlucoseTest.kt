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

class GlucoseThresholdsTest {
    private val t = GlucoseThresholds()

    @Test
    fun `classifies all five zones`() {
        assertEquals(Zone.URGENT_LOW, t.classify(50.0))
        assertEquals(Zone.LOW, t.classify(65.0))
        assertEquals(Zone.IN_RANGE, t.classify(120.0))
        assertEquals(Zone.HIGH, t.classify(220.0))
        assertEquals(Zone.VERY_HIGH, t.classify(311.0))
    }

    @Test
    fun `defaults to the account in-range band over issue defaults`() {
        val fromAccount = t.withAccountTargets(targetLow = 70.0, targetHigh = 180.0)
        assertEquals(70.0, fromAccount.lowMgdl)
        assertEquals(180.0, fromAccount.highMgdl)
        // Urgent-low and very-high have no API equivalent and must survive.
        assertEquals(55.0, fromAccount.urgentLowMgdl)
        assertEquals(240.0, fromAccount.veryHighMgdl)
    }

    @Test
    fun `keeps its own defaults when the account supplies nothing`() {
        val unchanged = t.withAccountTargets(null, null)
        assertEquals(t, unchanged)
    }

    @Test
    fun `fraction is clamped for the ranged-value complication`() {
        assertEquals(0f, t.fraction(40.0))
        assertEquals(1f, t.fraction(400.0))
        assertEquals(0.5f, t.fraction(125.0), 0.001f)
    }

    @Test
    fun `repairs out-of-order boundaries instead of rejecting them`() {
        // What a half-finished edit in Settings looks like.
        val broken = GlucoseThresholds(
            urgentLowMgdl = 200.0,
            lowMgdl = 70.0,
            highMgdl = 180.0,
            veryHighMgdl = 240.0,
        ).sanitised()

        assertTrue(broken.urgentLowMgdl < broken.lowMgdl)
        assertTrue(broken.lowMgdl < broken.highMgdl)
        assertTrue(broken.highMgdl < broken.veryHighMgdl)
    }

    @Test
    fun `zone helpers group lows and highs`() {
        assertTrue(Zone.URGENT_LOW.isLow && Zone.LOW.isLow)
        assertTrue(Zone.HIGH.isHigh && Zone.VERY_HIGH.isHigh)
        assertTrue(!Zone.IN_RANGE.needsAttention)
        assertTrue(Zone.VERY_HIGH.needsAttention)
    }
}

class FreshnessTest {
    private val policy = FreshnessPolicy()
    private val now = 1_800_000_000_000L

    private fun readingAgedMinutes(m: Int) =
        GlucoseReading(valueMgdl = 100.0, timestampMillis = now - m.minutes.inWholeMilliseconds)

    @Test
    fun `freshness comes from the clock not from fetch success`() {
        // 2.9 min is the worst age observed on a healthy feed, so 3 must be FRESH.
        assertEquals(Freshness.FRESH, policy.evaluate(readingAgedMinutes(3), now))
        assertEquals(Freshness.AGING, policy.evaluate(readingAgedMinutes(7), now))
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
        assertEquals(3.minutes, policy.nextTransition(r, now))
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
                delta = GlucoseDelta(7.0, 5 * 60_000L),
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
            delta = GlucoseDelta(-7.0, 5 * 60_000L),
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

class DeltaCalculatorTest {
    private val now = 1_800_000_000_000L

    private fun reading(minutesAgo: Int, value: Double) =
        GlucoseReading(valueMgdl = value, timestampMillis = now - minutesAgo * 60_000L)

    private val current = reading(0, 120.0)

    @Test
    fun `prefers the reading closest to five minutes back`() {
        val history = listOf(
            reading(15, 80.0),
            reading(5, 110.0),
            reading(1, 118.0),
        )
        val delta = DeltaCalculator.compute(current, history)!!
        assertEquals(10.0, delta.valueMgdl)
        assertEquals(5 * 60_000L, delta.spanMillis)
        assertTrue(delta.isConventional)
    }

    @Test
    fun `falls back to a coarse span and flags it as unconventional`() {
        // What LibreLinkUp's ~15-minute graphData actually offers.
        val delta = DeltaCalculator.compute(current, listOf(reading(15, 80.0)))!!
        assertEquals(40.0, delta.valueMgdl)
        assertEquals(15 * 60_000L, delta.spanMillis)
        assertTrue(!delta.isConventional, "a 15-minute span must not pass as a 5-minute delta")
    }

    @Test
    fun `refuses to report a delta over a uselessly long span`() {
        assertEquals(null, DeltaCalculator.compute(current, listOf(reading(45, 80.0))))
    }

    @Test
    fun `ignores readings at or after the current one`() {
        val history = listOf(reading(-5, 200.0), reading(0, 120.0))
        assertEquals(null, DeltaCalculator.compute(current, history))
    }

    @Test
    fun `normalises a coarse delta onto the five-minute convention`() {
        val delta = GlucoseDelta(valueMgdl = 45.0, spanMillis = 15 * 60_000L)
        assertEquals(15.0, delta.normalisedPerFiveMinutes(), 0.001)
    }

    @Test
    fun `labels an unconventional span in the formatted output`() {
        val snapshot = GlucoseSnapshot(
            reading = current,
            delta = GlucoseDelta(40.0, 15 * 60_000L),
        )
        assertEquals("+40 / 15m", snapshot.formattedDelta())
    }

    @Test
    fun `returns null when there is no history at all`() {
        assertEquals(null, DeltaCalculator.compute(current, emptyList()))
    }
}

class StatisticsTest {
    private val now = 1_800_000_000_000L
    private val dayAgo = now - 24 * 60 * 60 * 1000L
    private val thresholds = GlucoseThresholds()

    private fun series(values: List<Double>, spacingMinutes: Int = 5): List<GlucoseReading> =
        values.mapIndexed { i, v ->
            GlucoseReading(
                valueMgdl = v,
                timestampMillis = dayAgo + i * spacingMinutes * 60_000L,
            )
        }

    @Test
    fun `splits time across zones`() {
        val stats = StatisticsCalculator.compute(
            series(listOf(50.0, 65.0, 120.0, 120.0, 220.0, 300.0)),
            thresholds, dayAgo, now,
        )
        assertEquals(6, stats.readingCount)
        assertEquals(2.0 / 6, stats.timeInRange!!, 0.001)
        assertEquals(1.0 / 6, stats.zoneFractions[Zone.URGENT_LOW]!!, 0.001)
        assertEquals(1.0 / 6, stats.zoneFractions[Zone.VERY_HIGH]!!, 0.001)
    }

    @Test
    fun `zone fractions sum to one`() {
        val stats = StatisticsCalculator.compute(
            series(listOf(50.0, 90.0, 120.0, 200.0, 300.0)), thresholds, dayAgo, now,
        )
        assertEquals(1.0, stats.zoneFractions.values.sum(), 0.001)
    }

    @Test
    fun `reports low coverage when the window is mostly empty`() {
        // One hour of readings inside a 24-hour window.
        val stats = StatisticsCalculator.compute(
            series(List(12) { 120.0 }), thresholds, dayAgo, now,
        )
        assertTrue(stats.coverage < 0.1, "coverage was ${stats.coverage}")
        assertTrue(!stats.isReliable, "a mostly-empty window must not read as reliable")
    }

    @Test
    fun `reports high coverage when the window is full`() {
        // 5-minute readings across the whole day.
        val stats = StatisticsCalculator.compute(
            series(List(288) { 120.0 }), thresholds, dayAgo, now,
        )
        assertTrue(stats.coverage > 0.95, "coverage was ${stats.coverage}")
        assertTrue(stats.isReliable)
    }

    @Test
    fun `coverage survives irregular cadence`() {
        // The 15-minute graph backfill must not look like missing data beyond
        // what it actually is.
        val stats = StatisticsCalculator.compute(
            series(List(96) { 120.0 }, spacingMinutes = 15), thresholds, dayAgo, now,
        )
        assertTrue(stats.coverage > 0.3, "coverage was ${stats.coverage}")
    }

    @Test
    fun `empty window yields empty stats not a crash`() {
        val stats = StatisticsCalculator.compute(emptyList(), thresholds, dayAgo, now)
        assertEquals(GlucoseStatistics.Empty, stats)
        assertEquals(null, stats.timeInRange)
    }

    @Test
    fun `ignores readings outside the window`() {
        val old = GlucoseReading(300.0, dayAgo - 60 * 60 * 1000L)
        val stats = StatisticsCalculator.compute(
            series(listOf(120.0)) + old, thresholds, dayAgo, now,
        )
        assertEquals(1, stats.readingCount)
    }
}

class SensorInfoTest {
    private val now = 1_800_000_000_000L
    private val day = 24 * 60 * 60 * 1000L

    @Test
    fun `counts the session day from one`() {
        assertEquals(1, SensorInfo(startedAtMillis = now - 3 * 60 * 60 * 1000L).dayOfSession(now))
        assertEquals(8, SensorInfo(startedAtMillis = now - 7 * day).dayOfSession(now))
    }

    @Test
    fun `flags an expired sensor`() {
        assertTrue(!SensorInfo(startedAtMillis = now - 10 * day).isExpired(now))
        assertTrue(SensorInfo(startedAtMillis = now - 15 * day).isExpired(now))
    }

    @Test
    fun `unknown start is not an expired sensor`() {
        assertEquals(null, SensorInfo().dayOfSession(now))
        assertTrue(!SensorInfo().isExpired(now))
    }
}

class EstimatedA1cTest {
    private fun statsWithMean(mean: Double) = GlucoseStatistics(
        readingCount = 100,
        meanMgdl = mean,
        zoneFractions = emptyMap(),
        coverage = 1.0,
    )

    @Test
    fun `GMI matches the published formula`() {
        // 154 mg/dL is the canonical mean for an A1C of about 7%.
        assertEquals(7.0, statsWithMean(154.0).gmiPercent!!, 0.02)
    }

    @Test
    fun `estimated A1C matches the ADAG relationship`() {
        assertEquals(7.0, statsWithMean(154.0).estimatedA1cPercent!!, 0.02)
    }

    @Test
    fun `both are null without a mean`() {
        assertEquals(null, GlucoseStatistics.Empty.gmiPercent)
        assertEquals(null, GlucoseStatistics.Empty.estimatedA1cPercent)
    }

    @Test
    fun `the two estimates diverge at high means, which is expected`() {
        val s = statsWithMean(300.0)
        assertTrue(s.gmiPercent!! != s.estimatedA1cPercent!!)
        assertTrue(s.gmiPercent!! > 10.0 && s.estimatedA1cPercent!! > 11.0)
    }
}
