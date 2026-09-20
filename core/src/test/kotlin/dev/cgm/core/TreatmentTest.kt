package dev.cgm.core

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class InsulinDoseTest {

    private val now = 1_800_000_000_000L

    private fun dose(units: Double, at: Long = now) =
        InsulinDose(kind = InsulinKind.BOLUS, units = units, givenAtMillis = at)

    @Test
    fun `an ordinary dose is plausible`() {
        assertTrue(dose(6.0).isPlausible)
        assertTrue(dose(0.5).isPlausible)
    }

    @Test
    fun `zero and negative doses are not doses`() {
        assertFalse(dose(0.0).isPlausible)
        assertFalse(dose(-2.0).isPlausible)
    }

    /**
     * The guard is for the realistic typo — 100 where 10 was meant — and sits far
     * above any normal dose so it never argues with a legitimate one.
     */
    @Test
    fun `an implausibly large dose is rejected`() {
        assertTrue(dose(InsulinDose.MAX_PLAUSIBLE_UNITS).isPlausible)
        assertFalse(dose(InsulinDose.MAX_PLAUSIBLE_UNITS + 0.5).isPlausible)
    }

    @Test
    fun `a dose with no time is not plausible`() {
        assertFalse(dose(5.0, at = 0).isPlausible)
    }

    @Test
    fun `units round to what a pen can deliver`() {
        assertEquals(6.0, InsulinDose.roundUnits(6.1))
        assertEquals(6.5, InsulinDose.roundUnits(6.4))
        assertEquals(6.5, InsulinDose.roundUnits(6.6))
        assertEquals(0.0, InsulinDose.roundUnits(-1.0))
    }

    /**
     * The separator is the whole point: a Spanish keyboard types a comma, and
     * `toDoubleOrNull` refuses it. Silently refusing a real dose — or reading "6,5"
     * as 65 — is the failure this prevents.
     */
    @Test
    fun `units parse with either decimal separator`() {
        assertEquals(6.5, InsulinDose.parseUnits("6,5"))
        assertEquals(6.5, InsulinDose.parseUnits("6.5"))
        assertEquals(6.0, InsulinDose.parseUnits("6"))
        assertEquals(6.0, InsulinDose.parseUnits("  6 "))
        assertEquals(0.5, InsulinDose.parseUnits(",5"))
    }

    @Test
    fun `anything that is not a single number parses to nothing`() {
        assertNull(InsulinDose.parseUnits(""))
        assertNull(InsulinDose.parseUnits("   "))
        assertNull(InsulinDose.parseUnits("abc"))
        assertNull(InsulinDose.parseUnits("6,5,5"))
        assertNull(InsulinDose.parseUnits("6 5"))
        assertNull(InsulinDose.parseUnits("-"))
    }

    @Test
    fun `a blank note is the same as no note`() {
        assertNull(InsulinDose.cleanNote(null))
        assertNull(InsulinDose.cleanNote("   "))
        assertEquals("pizza", InsulinDose.cleanNote("  pizza "))
    }
}

class InsulinDayTotalsTest {

    private val now = 1_800_000_000_000L

    private fun dose(kind: InsulinKind, units: Double) =
        InsulinDose(kind = kind, units = units, givenAtMillis = now)

    @Test
    fun `totals separate basal from bolus`() {
        val totals = InsulinDayTotals.of(
            listOf(
                dose(InsulinKind.BASAL, 18.0),
                dose(InsulinKind.BOLUS, 6.0),
                dose(InsulinKind.BOLUS, 4.5),
            )
        )

        assertEquals(18.0, totals.basalUnits)
        assertEquals(10.5, totals.bolusUnits)
        assertEquals(28.5, totals.totalUnits)
    }

    @Test
    fun `no doses totals zero rather than failing`() {
        val totals = InsulinDayTotals.of(emptyList())

        assertEquals(0.0, totals.totalUnits)
    }
}
