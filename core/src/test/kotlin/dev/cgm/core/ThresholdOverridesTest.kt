package dev.cgm.core

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ThresholdOverridesTest {

    /** What the account reports for this user. */
    private val account = GlucoseThresholds(
        urgentLowMgdl = 55.0,
        lowMgdl = 70.0,
        highMgdl = 180.0,
        veryHighMgdl = 240.0,
    )

    @Test
    fun `no overrides leaves the account untouched`() {
        assertTrue(ThresholdOverrides.None.isEmpty)
        assertEquals(account, ThresholdOverrides.None.applyTo(account))
    }

    @Test
    fun `an override replaces only its own boundary`() {
        val mine = ThresholdOverrides(lowMgdl = 80.0)

        val effective = mine.applyTo(account)

        assertEquals(80.0, effective.lowMgdl)
        assertEquals(55.0, effective.urgentLowMgdl)
        assertEquals(180.0, effective.highMgdl)
        assertEquals(240.0, effective.veryHighMgdl)
    }

    /**
     * The reason overrides are stored per boundary rather than as a whole
     * [GlucoseThresholds]: editing the urgent low must not freeze the in-range
     * band, or the app stops agreeing with LibreLink the next time the account's
     * target moves.
     */
    @Test
    fun `boundaries left alone still follow the account when it changes`() {
        val mine = ThresholdOverrides(urgentLowMgdl = 60.0)

        val effective = mine.applyTo(account.withAccountTargets(targetLow = 75.0, targetHigh = 165.0))

        assertEquals(60.0, effective.urgentLowMgdl)
        assertEquals(75.0, effective.lowMgdl)
        assertEquals(165.0, effective.highMgdl)
    }

    @Test
    fun `a null hands the boundary back to the account`() {
        val mine = ThresholdOverrides(lowMgdl = 80.0).with(ThresholdBoundary.LOW, null)

        assertTrue(mine.isEmpty)
        assertNull(mine[ThresholdBoundary.LOW])
        assertEquals(70.0, mine.applyTo(account).lowMgdl)
    }

    @Test
    fun `tracks which boundaries are the user's`() {
        val mine = ThresholdOverrides(veryHighMgdl = 300.0)

        assertTrue(mine.overrides(ThresholdBoundary.VERY_HIGH))
        assertFalse(mine.overrides(ThresholdBoundary.HIGH))
        assertFalse(mine.isEmpty)
    }

    /**
     * The UI bounds each slider by its neighbours so this cannot be entered, but
     * settings written by an older build must not be able to make `classify`
     * nonsense either.
     */
    @Test
    fun `disordered stored overrides are repaired rather than trusted`() {
        val corrupt = ThresholdOverrides(urgentLowMgdl = 200.0)

        val effective = corrupt.applyTo(account)

        assertTrue(effective.urgentLowMgdl < effective.lowMgdl)
        assertTrue(effective.lowMgdl < effective.highMgdl)
        assertTrue(effective.highMgdl < effective.veryHighMgdl)
        assertEquals(Zone.IN_RANGE, effective.classify(effective.lowMgdl + 1))
    }

    @Test
    fun `an override actually moves the zone a reading lands in`() {
        assertEquals(Zone.IN_RANGE, account.classify(75.0))

        val stricter = ThresholdOverrides(lowMgdl = 80.0).applyTo(account)

        assertEquals(Zone.LOW, stricter.classify(75.0))
    }

    @Test
    fun `every boundary is addressable by name`() {
        ThresholdBoundary.entries.forEach { boundary ->
            val moved = account[boundary] + 1
            val effective = ThresholdOverrides.None.with(boundary, moved).applyTo(account)
            assertEquals(moved, effective[boundary], "$boundary did not take")
        }
    }

    @Test
    fun `only low and high have an account equivalent`() {
        assertTrue(ThresholdBoundary.LOW.comesFromAccount)
        assertTrue(ThresholdBoundary.HIGH.comesFromAccount)
        assertFalse(ThresholdBoundary.URGENT_LOW.comesFromAccount)
        assertFalse(ThresholdBoundary.VERY_HIGH.comesFromAccount)
    }
}
