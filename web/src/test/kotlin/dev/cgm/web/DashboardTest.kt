package dev.cgm.web

import dev.cgm.core.GlucoseReading
import dev.cgm.core.GlucoseSnapshot
import dev.cgm.core.GlucoseThresholds
import dev.cgm.core.GlucoseUnit
import dev.cgm.core.SensorInfo
import dev.cgm.core.SourceResult
import dev.cgm.core.TrendArrow
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class DashboardTest {

    private val now = 1_800_000_000_000L

    private fun result(
        valueMgdl: Double = 120.0,
        ageMinutes: Int = 1,
        history: List<GlucoseReading> = emptyList(),
        unit: GlucoseUnit = GlucoseUnit.MGDL,
        sensor: SensorInfo? = null,
    ) = SourceResult(
        snapshot = GlucoseSnapshot(
            reading = GlucoseReading(
                valueMgdl = valueMgdl,
                timestampMillis = now - ageMinutes.minutes.inWholeMilliseconds,
                trend = TrendArrow.RISING,
            ),
            thresholds = GlucoseThresholds(),
            unit = unit,
        ),
        history = history,
        sensor = sensor,
    )

    private fun series(count: Int, spacingMinutes: Int, value: Double = 120.0) =
        (0 until count).map {
            GlucoseReading(
                valueMgdl = value,
                timestampMillis = now - (count - it) * spacingMinutes * 60_000L,
            )
        }

    @Test
    fun `carries the reading the browser has to show`() {
        val dto = buildDashboard(result(valueMgdl = 174.0), now)
        assertEquals("174", dto.displayValue)
        assertEquals("mg/dL", dto.unit)
        assertEquals("RISING", dto.trend)
        assertEquals("IN_RANGE", dto.zone)
        assertEquals(60, dto.ageSeconds)
    }

    @Test
    fun `classifies zones on the server so the page cannot disagree with the phone`() {
        assertEquals("URGENT_LOW", buildDashboard(result(valueMgdl = 48.0), now).zone)
        assertEquals("LOW", buildDashboard(result(valueMgdl = 65.0), now).zone)
        assertEquals("HIGH", buildDashboard(result(valueMgdl = 200.0), now).zone)
        assertEquals("VERY_HIGH", buildDashboard(result(valueMgdl = 320.0), now).zone)
    }

    @Test
    fun `reports freshness from the reading's age`() {
        assertEquals("FRESH", buildDashboard(result(ageMinutes = 2), now).freshness)
        assertEquals("AGING", buildDashboard(result(ageMinutes = 7), now).freshness)
        assertEquals("STALE", buildDashboard(result(ageMinutes = 40), now).freshness)
    }

    @Test
    fun `includes the current reading in the history it sends`() {
        val dto = buildDashboard(result(history = series(10, 15)), now)
        // Ten graph points plus the current measurement, which graphData omits.
        assertEquals(11, dto.history.size)
        assertEquals(dto.timestampMillis, dto.history.last().t)
    }

    @Test
    fun `does not duplicate a reading present in both history and current`() {
        val base = result()
        val withDuplicate = base.copy(history = listOf(base.snapshot.reading))
        assertEquals(1, buildDashboard(withDuplicate, now).history.size)
    }

    @Test
    fun `sends history in time order whatever order it arrived in`() {
        val shuffled = series(20, 15).shuffled()
        val dto = buildDashboard(result(history = shuffled), now)
        assertEquals(dto.history.map { it.t }.sorted(), dto.history.map { it.t })
    }

    @Test
    fun `reports low coverage honestly for a sparse twelve-hour window`() {
        // What LibreLinkUp actually returns: about twelve hours at 15-minute spacing.
        val dto = buildDashboard(result(history = series(48, 15)), now)
        assertTrue(dto.stats.coverage < 0.7, "coverage was ${dto.stats.coverage}")
        assertTrue(!dto.stats.reliable, "a 15-minute-spaced window must not read as reliable")
    }

    @Test
    fun `surfaces the largest gap so a sparse chart explains itself`() {
        // An hour of readings, then nothing for three, then a recent hour — the
        // shape an overnight stall leaves behind. Both stretches sit in the past,
        // because anything after `now` falls outside the window entirely.
        val older = (0 until 12).map {
            GlucoseReading(120.0, now - 5.hours.inWholeMilliseconds + it * 5 * 60_000L)
        }
        val recent = (0 until 12).map {
            GlucoseReading(120.0, now - 1.hours.inWholeMilliseconds + it * 5 * 60_000L)
        }
        val dto = buildDashboard(result(history = older + recent), now)
        assertTrue(dto.largestGapMinutes >= 150, "largest gap was ${dto.largestGapMinutes}")
    }

    @Test
    fun `zone fractions cover every zone so the browser need not fill gaps`() {
        val dto = buildDashboard(result(history = series(20, 15)), now)
        assertEquals(5, dto.stats.zones.size)
    }

    @Test
    fun `formats mmol accounts in their own unit while keeping mgdl underneath`() {
        val dto = buildDashboard(result(valueMgdl = 112.0, unit = GlucoseUnit.MMOLL), now)
        assertEquals("6.2", dto.displayValue)
        assertEquals("mmol/L", dto.unit)
        assertEquals(112.0, dto.valueMgdl)
    }

    @Test
    fun `reports the sensor session day when the source knows it`() {
        val started = now - 3L * 24 * 60 * 60 * 1000
        val dto = buildDashboard(result(sensor = SensorInfo("ABC", started)), now)
        assertEquals(4, dto.sensorDay)
    }

    @Test
    fun `leaves the sensor day out rather than guessing it`() {
        assertNull(buildDashboard(result(), now).sensorDay)
    }

    @Test
    fun `sends its own clock so the page can render age against it`() {
        assertEquals(now, buildDashboard(result(), now).serverTimeMillis)
    }
}
