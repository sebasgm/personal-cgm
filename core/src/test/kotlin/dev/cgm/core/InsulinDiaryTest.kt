package dev.cgm.core

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The per-day insulin summary, and meals logged without a carbohydrate count.
 *
 * The two arrived together: once a meal can be recorded with no grams, every
 * total over meals has to say what it rests on, or a day with three uncounted
 * meals reports the same "0 g" as a day with none.
 */
class InsulinDiaryTest {

    private val zone: ZoneId = ZoneId.of("America/Argentina/Buenos_Aires")

    private fun at(date: String, hour: Int): Long =
        ZonedDateTime.of(LocalDate.parse(date).atTime(hour, 0), zone).toInstant().toEpochMilli()

    private fun dose(date: String, hour: Int, units: Double, kind: InsulinKind) =
        InsulinDose(kind = kind, units = units, givenAtMillis = at(date, hour))

    private fun meal(date: String, hour: Int, grams: Double?) =
        CarbEntry(grams = grams, eatenAtMillis = at(date, hour))

    // -- uncounted meals --------------------------------------------------

    @Test
    fun `a meal with no count is storable`() {
        assertTrue(meal("2026-10-22", 13, null).isPlausible)
        assertFalse(meal("2026-10-22", 13, null).isCounted)
    }

    @Test
    fun `a typo is still refused`() {
        assertFalse(meal("2026-10-22", 13, 900.0).isPlausible, "above the plausible bound")
        assertFalse(meal("2026-10-22", 13, 0.0).isPlausible, "zero grams is not uncounted")
    }

    @Test
    fun `an uncounted meal adds nothing to a total`() {
        val meals = listOf(
            meal("2026-10-22", 9, 30.0),
            meal("2026-10-22", 13, null),
            meal("2026-10-22", 20, 45.0),
        )
        assertEquals(75.0, CarbEntry.totalGrams(meals))
        assertEquals(2 to 1, CarbEntry.countedOf(meals))
    }

    // -- per-day grouping -------------------------------------------------

    @Test
    fun `doses are grouped by local day, most recent first`() {
        val days = InsulinDiary.byDay(
            doses = listOf(
                dose("2026-10-21", 8, 12.0, InsulinKind.BASAL),
                dose("2026-10-22", 8, 10.0, InsulinKind.BASAL),
                dose("2026-10-22", 13, 4.0, InsulinKind.BOLUS),
                dose("2026-10-22", 20, 6.0, InsulinKind.BOLUS),
            ),
            carbs = emptyList(),
            zone = zone,
        )

        assertEquals(listOf(LocalDate.parse("2026-10-22"), LocalDate.parse("2026-10-21")),
            days.map { it.date })

        val today = days.first()
        assertEquals(10.0, today.totals.basalUnits)
        assertEquals(10.0, today.totals.bolusUnits)
        assertEquals(20.0, today.totals.totalUnits)
        assertEquals(3, today.doseCount)
    }

    @Test
    fun `basal and bolus are never added together by accident`() {
        val day = InsulinDiary.byDay(
            doses = listOf(dose("2026-10-22", 8, 14.0, InsulinKind.BASAL)),
            carbs = emptyList(),
            zone = zone,
        ).single()

        assertEquals(14.0, day.totals.basalUnits)
        assertEquals(0.0, day.totals.bolusUnits)
    }

    @Test
    fun `a day of only meals still appears`() {
        // Eating without dosing is a real day and the diary has to show it.
        val days = InsulinDiary.byDay(
            doses = emptyList(),
            carbs = listOf(meal("2026-10-22", 13, 40.0)),
            zone = zone,
        )

        assertEquals(1, days.size)
        assertEquals(0.0, days.single().totals.totalUnits)
        assertEquals(40.0, days.single().carbGrams)
    }

    @Test
    fun `a day with nothing recorded is absent rather than zero`() {
        val days = InsulinDiary.byDay(
            doses = listOf(dose("2026-10-22", 8, 10.0, InsulinKind.BASAL)),
            carbs = emptyList(),
            zone = zone,
        )

        assertEquals(1, days.size, "the gap before today is not invented as a zero day")
    }

    @Test
    fun `the day limit keeps the most recent days`() {
        val doses = (1..20).map { dose("2026-10-%02d".format(it), 8, 10.0, InsulinKind.BASAL) }

        val days = InsulinDiary.byDay(doses, emptyList(), zone, limitDays = 14)

        assertEquals(14, days.size)
        assertEquals(LocalDate.parse("2026-10-20"), days.first().date)
        assertEquals(LocalDate.parse("2026-10-07"), days.last().date)
    }

    @Test
    fun `a dose late at night lands on its local day, not the UTC one`() {
        // 23:00 in Buenos Aires is already the next day in UTC. Grouping on UTC
        // would file an evening dose under tomorrow.
        val day = InsulinDiary.byDay(
            doses = listOf(dose("2026-10-22", 23, 8.0, InsulinKind.BOLUS)),
            carbs = emptyList(),
            zone = zone,
        ).single()

        assertEquals(LocalDate.parse("2026-10-22"), day.date)
    }

    @Test
    fun `a day's meals report how many were never counted`() {
        val day = InsulinDiary.byDay(
            doses = emptyList(),
            carbs = listOf(
                meal("2026-10-22", 9, null),
                meal("2026-10-22", 13, null),
                meal("2026-10-22", 20, 50.0),
            ),
            zone = zone,
        ).single()

        assertEquals(50.0, day.carbGrams)
        assertEquals(1, day.countedMeals)
        assertEquals(2, day.uncountedMeals)
        assertTrue(day.hasCarbs)
    }

    @Test
    fun `a day with no meals at all says so distinctly`() {
        val day = InsulinDiary.byDay(
            doses = listOf(dose("2026-10-22", 8, 10.0, InsulinKind.BASAL)),
            carbs = emptyList(),
            zone = zone,
        ).single()

        assertFalse(day.hasCarbs, "no meals is not the same as uncounted meals")
        assertEquals(0.0, day.carbGrams)
    }
}
