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
 * The per-day insulin series the Trends chart is drawn from, and its axis.
 *
 * What is being guarded here is mostly the difference between zero and unknown. The
 * diary can drop a day nobody wrote anything down for; a chart cannot, because its
 * days are positions on an axis — and the obvious way to keep the position, a bar of
 * no height, is a claim that no insulin was taken.
 */
class InsulinTrendTest {

    private val zone: ZoneId = ZoneId.of("America/Argentina/Buenos_Aires")
    private val today: LocalDate = LocalDate.parse("2026-11-14")

    private fun at(date: String, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(LocalDate.parse(date).atTime(hour, minute), zone).toInstant().toEpochMilli()

    private fun dose(date: String, hour: Int, units: Double, kind: InsulinKind) =
        InsulinDose(kind = kind, units = units, givenAtMillis = at(date, hour))

    private fun basal(date: String, hour: Int, units: Double) =
        dose(date, hour, units, InsulinKind.BASAL)

    private fun bolus(date: String, hour: Int, units: Double) =
        dose(date, hour, units, InsulinKind.BOLUS)

    // -- the window -------------------------------------------------------

    @Test
    fun `the window is every calendar day in it, oldest first`() {
        val trend = InsulinTrend.of(emptyList(), zone, today, days = 7)

        assertEquals(7, trend.bars.size)
        assertEquals(LocalDate.parse("2026-11-08"), trend.bars.first().date)
        assertEquals(today, trend.bars.last().date)
        assertEquals(trend.bars.map { it.date }.sorted(), trend.bars.map { it.date })
    }

    @Test
    fun `the 24h chip is one day, which is today`() {
        val trend = InsulinTrend.of(
            doses = listOf(basal("2026-11-14", 8, 20.0)),
            zone = zone,
            today = today,
            days = 1,
        )

        assertEquals(1, trend.bars.size)
        assertEquals(today, trend.bars.single().date)
        assertTrue(trend.bars.single().isToday)
    }

    @Test
    fun `a day with nothing logged keeps its place and is not a zero`() {
        val trend = InsulinTrend.of(
            doses = listOf(basal("2026-11-12", 8, 20.0)),
            zone = zone,
            today = today,
            days = 3,
        )

        val quiet = trend.bars.single { it.date == LocalDate.parse("2026-11-13") }
        assertFalse(quiet.isLogged, "nothing written down is not a day of no insulin")
        assertEquals(0.0, quiet.totalUnits)
        assertEquals(1, trend.loggedDays)
        assertEquals(3, trend.bars.size, "the gap still occupies its day on the axis")
    }

    @Test
    fun `doses outside the window are left out rather than folded into the edge`() {
        val trend = InsulinTrend.of(
            doses = listOf(
                basal("2026-11-07", 8, 99.0),
                basal("2026-11-08", 8, 20.0),
            ),
            zone = zone,
            today = today,
            days = 7,
        )

        assertEquals(20.0, trend.bars.first().totalUnits, "the eighth day back is not piled on")
        assertEquals(20.0, trend.peakUnits)
    }

    @Test
    fun `days are cut in the display zone, not in UTC`() {
        // 23:30 in Buenos Aires is 02:30 the next day in UTC.
        val trend = InsulinTrend.of(
            doses = listOf(
                InsulinDose(
                    kind = InsulinKind.BOLUS,
                    units = 4.0,
                    givenAtMillis = at("2026-11-12", 23, 30),
                )
            ),
            zone = zone,
            today = today,
            days = 7,
        )

        assertEquals(
            4.0,
            trend.bars.single { it.date == LocalDate.parse("2026-11-12") }.totalUnits,
        )
        assertFalse(trend.bars.single { it.date == LocalDate.parse("2026-11-13") }.isLogged)
    }

    // -- the split and the totals -----------------------------------------

    @Test
    fun `basal and bolus are kept apart and their sum is the day`() {
        val trend = InsulinTrend.of(
            doses = listOf(
                basal("2026-11-12", 8, 22.0),
                bolus("2026-11-12", 13, 6.0),
                bolus("2026-11-12", 20, 4.5),
            ),
            zone = zone,
            today = today,
            days = 7,
        )

        val day = trend.bars.single { it.date == LocalDate.parse("2026-11-12") }
        assertEquals(22.0, day.basalUnits)
        assertEquals(10.5, day.bolusUnits)
        assertEquals(32.5, day.totalUnits)
        assertEquals(3, day.doseCount)
    }

    // -- the averages ------------------------------------------------------

    @Test
    fun `the averages divide by logged days, not by the window`() {
        val trend = InsulinTrend.of(
            doses = listOf(
                basal("2026-11-10", 8, 30.0),
                bolus("2026-11-10", 13, 10.0),
                basal("2026-11-12", 8, 20.0),
                bolus("2026-11-12", 13, 10.0),
            ),
            zone = zone,
            today = today,
            days = 30,
        )

        assertEquals(2, trend.averagedDays)
        assertEquals(35.0, trend.meanTotalUnits, "a day nobody logged is not a day of zero")
        assertEquals(25.0, trend.meanBasalUnits)
        assertEquals(10.0, trend.meanBolusUnits)
    }

    @Test
    fun `today is left out of the averages while it is still running`() {
        val trend = InsulinTrend.of(
            doses = listOf(
                basal("2026-11-13", 8, 40.0),
                basal("2026-11-14", 8, 10.0),
            ),
            zone = zone,
            today = today,
            days = 7,
        )

        assertEquals(1, trend.averagedDays)
        assertEquals(40.0, trend.meanTotalUnits, "half of today would drag the average down")
        assertEquals(2, trend.loggedDays, "and it is still a logged day on the chart")
        assertEquals(40.0, trend.peakUnits)
    }

    @Test
    fun `today is averaged when it is the only day there is`() {
        val trend = InsulinTrend.of(
            doses = listOf(basal("2026-11-14", 8, 18.0)),
            zone = zone,
            today = today,
            days = 7,
        )

        assertEquals(1, trend.averagedDays)
        assertEquals(18.0, trend.meanTotalUnits, "a dash on the first day would read as a bug")
    }

    @Test
    fun `an empty window has nothing to average rather than an average of nothing`() {
        val trend = InsulinTrend.of(emptyList(), zone, today, days = 7)

        assertFalse(trend.hasData)
        assertEquals(0, trend.loggedDays)
        assertNull(trend.meanTotalUnits)
        assertNull(trend.meanBasalUnits)
        assertNull(trend.meanBolusUnits)
        assertEquals(0.0, trend.peakUnits)
    }

    // -- the axis ----------------------------------------------------------

    @Test
    fun `the axis starts at zero and holds the peak`() {
        val axis = UnitsAxis.of(38.0)

        assertEquals(40.0, axis.topUnits)
        assertEquals(0f, axis.fraction(0.0))
        assertEquals(1f, axis.fraction(40.0))
        assertEquals(0.5f, axis.fraction(20.0), "half the units is half the height")
    }

    @Test
    fun `the axis keeps its lines readable at any size of day`() {
        assertEquals(listOf(2.0, 4.0, 6.0), UnitsAxis.of(6.0).gridLines())
        assertEquals(listOf(10.0, 20.0, 30.0, 40.0), UnitsAxis.of(38.0).gridLines())
        assertEquals(listOf(25.0, 50.0, 75.0, 100.0), UnitsAxis.of(95.0).gridLines())

        listOf(1.0, 7.5, 38.0, 95.0, 240.0, 600.0).forEach { peak ->
            val lines = UnitsAxis.of(peak).gridLines()
            assertTrue(lines.size in 1..5, "$peak gave ${lines.size} lines")
            assertTrue(lines.last() >= peak, "$peak is above its own axis")
        }
    }

    @Test
    fun `an empty window still gets a real axis`() {
        val axis = UnitsAxis.of(0.0)

        assertTrue(axis.topUnits > 0, "a zero span would divide by nothing")
        assertEquals(0f, axis.fraction(0.0))
    }

    @Test
    fun `nothing can escape the plot`() {
        val axis = UnitsAxis.of(40.0)

        assertEquals(1f, axis.fraction(400.0))
        assertEquals(0f, axis.fraction(-10.0))
    }
}
