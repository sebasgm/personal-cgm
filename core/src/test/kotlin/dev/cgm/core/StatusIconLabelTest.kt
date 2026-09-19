package dev.cgm.core

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

class StatusIconLabelTest {

    private val now = 1_800_000_000_000L

    private fun snapshot(
        mgdl: Double,
        unit: GlucoseUnit = GlucoseUnit.MGDL,
        ageMinutes: Int = 1,
    ) = GlucoseSnapshot(
        reading = GlucoseReading(
            valueMgdl = mgdl,
            timestampMillis = now - ageMinutes.minutes.inWholeMilliseconds,
            trend = TrendArrow.STEADY,
        ),
        unit = unit,
    )

    @Test
    fun `shows the value while it is fresh`() {
        assertEquals("124", StatusIconLabel.of(snapshot(124.0), Freshness.FRESH))
    }

    /**
     * Late is not wrong. The shade says "later than expected" beside it, and
     * blanking the number every time a poll slips would make the status bar
     * useless on a flaky connection.
     */
    @Test
    fun `still shows the value while merely aging`() {
        assertEquals("124", StatusIconLabel.of(snapshot(124.0), Freshness.AGING))
    }

    /**
     * The status bar is glanced at, not read: there is no room beside it for "17
     * min ago", so a number there is taken as current. Same reasoning as the alarm
     * engine's refusal to fire glucose alarms on stale data.
     */
    @Test
    fun `withholds the value once stale`() {
        assertEquals(StatusIconLabel.UNKNOWN, StatusIconLabel.of(snapshot(124.0), Freshness.STALE))
    }

    @Test
    fun `says nothing before the first reading`() {
        assertEquals(StatusIconLabel.NO_DATA, StatusIconLabel.of(null, Freshness.STALE))
        assertEquals(StatusIconLabel.NO_DATA, StatusIconLabel.of(null, Freshness.FRESH))
    }

    @Test
    fun `keeps the decimal in mmol, where it is the interesting digit`() {
        assertEquals("6.9", StatusIconLabel.of(snapshot(124.0, GlucoseUnit.MMOLL), Freshness.FRESH))
    }

    /**
     * "10.0" is four glyphs and does not fit, so double-digit mmol drops the
     * decimal. The rounding edge is the reason this is measured rather than
     * thresholded: 9.96 formats as "10.0" while still being under ten.
     */
    @Test
    fun `drops the decimal in mmol once it no longer fits`() {
        // 9.99 mmol/L: the decimal form "10.0" will not fit, and the whole form
        // has to round to 10 rather than truncate to 9.
        assertEquals("10", StatusIconLabel.format(180.0, GlucoseUnit.MMOLL))
        assertEquals("10", StatusIconLabel.format(179.5, GlucoseUnit.MMOLL))
        assertEquals("9.9", StatusIconLabel.format(179.0, GlucoseUnit.MMOLL))
        assertEquals("28", StatusIconLabel.format(500.0, GlucoseUnit.MMOLL))
    }

    @Test
    fun `reports out-of-range rather than inventing a number the sensor cannot read`() {
        assertEquals(StatusIconLabel.ABOVE_RANGE, StatusIconLabel.format(505.0, GlucoseUnit.MGDL))
        assertEquals(StatusIconLabel.BELOW_RANGE, StatusIconLabel.format(38.0, GlucoseUnit.MGDL))
        // The sensor's own limits are reportable values, not errors.
        assertEquals("40", StatusIconLabel.format(40.0, GlucoseUnit.MGDL))
        assertEquals("500", StatusIconLabel.format(500.0, GlucoseUnit.MGDL))
    }

    /**
     * The whole point of the label: it has to fit in one status bar icon. Three
     * glyphs is the budget, so anything the renderer can be handed must be within
     * it, including the fallbacks.
     */
    @Test
    fun `never exceeds three glyphs`() {
        val labels = buildList {
            add(StatusIconLabel.NO_DATA)
            add(StatusIconLabel.UNKNOWN)
            add(StatusIconLabel.ABOVE_RANGE)
            add(StatusIconLabel.BELOW_RANGE)
            (40..500 step 1).forEach { mgdl ->
                add(StatusIconLabel.format(mgdl.toDouble(), GlucoseUnit.MGDL))
                add(StatusIconLabel.format(mgdl.toDouble(), GlucoseUnit.MMOLL))
            }
        }

        val tooLong = labels.filter { it.length > StatusIconLabel.MAX_GLYPHS }
        assertTrue(tooLong.isEmpty(), "labels wider than the icon: ${tooLong.distinct()}")
    }
}
