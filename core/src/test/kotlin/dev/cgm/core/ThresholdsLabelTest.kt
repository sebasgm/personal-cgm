package dev.cgm.core

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class ThresholdsLabelTest {

    private val thresholds = GlucoseThresholds.Default

    @Test
    fun `the middle zones read as a span`() {
        assertEquals("55–70", thresholds.rangeLabel(Zone.LOW, GlucoseUnit.MGDL))
        assertEquals("70–180", thresholds.rangeLabel(Zone.IN_RANGE, GlucoseUnit.MGDL))
        assertEquals("180–240", thresholds.rangeLabel(Zone.HIGH, GlucoseUnit.MGDL))
    }

    @Test
    fun `the outer zones are open-ended rather than claiming an edge`() {
        // boundsOf runs these to 0 and to the largest plausible reading. Printing
        // "0–55" would state a lower limit the band does not have.
        assertEquals("<55", thresholds.rangeLabel(Zone.URGENT_LOW, GlucoseUnit.MGDL))
        assertEquals(">240", thresholds.rangeLabel(Zone.VERY_HIGH, GlucoseUnit.MGDL))
    }

    @Test
    fun `labels follow the display unit`() {
        assertEquals("3.9–10.0", thresholds.rangeLabel(Zone.IN_RANGE, GlucoseUnit.MMOLL))
        assertEquals("<3.1", thresholds.rangeLabel(Zone.URGENT_LOW, GlucoseUnit.MMOLL))
    }

    @Test
    fun `labels follow an override, so the card cannot disagree with the chart`() {
        val custom = GlucoseThresholds(urgentLowMgdl = 60.0, lowMgdl = 80.0, highMgdl = 160.0, veryHighMgdl = 250.0)
        assertEquals("80–160", custom.rangeLabel(Zone.IN_RANGE, GlucoseUnit.MGDL))
        assertEquals("<60", custom.rangeLabel(Zone.URGENT_LOW, GlucoseUnit.MGDL))
        assertEquals(">250", custom.rangeLabel(Zone.VERY_HIGH, GlucoseUnit.MGDL))
    }
}
