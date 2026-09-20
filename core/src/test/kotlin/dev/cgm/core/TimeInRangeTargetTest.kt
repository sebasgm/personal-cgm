package dev.cgm.core

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class TimeInRangeTargetTest {

    @Test
    fun `seventy percent is the target, and the boundary counts as meeting it`() {
        assertEquals(TimeInRangeTarget.AT_TARGET, TimeInRangeTarget.of(0.70))
        assertEquals(TimeInRangeTarget.AT_TARGET, TimeInRangeTarget.of(0.91))
    }

    @Test
    fun `between half and the target is below, not poor`() {
        assertEquals(TimeInRangeTarget.BELOW_TARGET, TimeInRangeTarget.of(0.50))
        assertEquals(TimeInRangeTarget.BELOW_TARGET, TimeInRangeTarget.of(0.69))
    }

    @Test
    fun `under half is well below`() {
        assertEquals(TimeInRangeTarget.WELL_BELOW_TARGET, TimeInRangeTarget.of(0.49))
        assertEquals(TimeInRangeTarget.WELL_BELOW_TARGET, TimeInRangeTarget.of(0.0))
    }
}
