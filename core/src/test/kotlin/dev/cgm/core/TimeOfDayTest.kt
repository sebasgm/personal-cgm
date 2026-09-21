package dev.cgm.core

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class TimeOfDayProfilerTest {

    /** An hour whose readings all sit at [value], fully covered unless told otherwise. */
    private fun hour(
        date: String,
        localHour: Int,
        value: Double,
        count: Int = 12,
        coverageBuckets: Int = 12,
    ) = HourlyBin(
        localDate = date,
        localHour = localHour,
        bins = GlucoseHistogram.of(List(count) { value }),
        readingCount = count,
        coverageBuckets = coverageBuckets,
    )

    /** An hour spread evenly between two values, to give the percentiles something to find. */
    private fun spreadHour(date: String, localHour: Int, from: Double, to: Double) =
        HourlyBin(
            localDate = date,
            localHour = localHour,
            bins = GlucoseHistogram.of((0 until 60).map { from + (to - from) * it / 59.0 }),
            readingCount = 60,
            coverageBuckets = 12,
        )

    private fun fullWeek(valueAt: (hour: Int) -> Double): List<HourlyBin> =
        (1..7).flatMap { day ->
            (0 until 24).map { h -> hour("2026-09-%02d".format(day), h, valueAt(h)) }
        }

    @Test
    fun `divides the day into eight three-hour slices`() {
        val profile = TimeOfDayProfiler.of(fullWeek { 120.0 })
        assertEquals(8, profile.buckets.size)
        assertEquals(listOf(0, 3, 6, 9, 12, 15, 18, 21), profile.buckets.map { it.startHour })
        assertEquals("00–03", profile.buckets.first().label())
        assertEquals("21–00", profile.buckets.last().label())
    }

    @Test
    fun `puts each hour in the slice that contains it`() {
        val hours = listOf(
            hour("2026-09-01", 2, 90.0),   // 00-03
            hour("2026-09-01", 3, 200.0),  // 03-06
            hour("2026-09-01", 23, 150.0), // 21-00
        )
        val profile = TimeOfDayProfiler.of(hours)
        assertEquals(90.0, profile.buckets[0].median!!, 3.0)
        assertEquals(200.0, profile.buckets[1].median!!, 3.0)
        assertEquals(150.0, profile.buckets[7].median!!, 3.0)
    }

    @Test
    fun `finds an overnight pattern across a week`() {
        // High between 00:00 and 03:00, in range for the rest of the day.
        val profile = TimeOfDayProfiler.of(fullWeek { h -> if (h < 3) 240.0 else 120.0 })
        assertTrue(profile.buckets[0].median!! > 220, "overnight median ${profile.buckets[0].median}")
        assertTrue(profile.buckets[4].median!! < 140, "midday median ${profile.buckets[4].median}")
    }

    // -- the reason this exists at all ------------------------------------

    @Test
    fun `two slices with the same average can have very different spreads`() {
        val steady = TimeOfDayProfiler.of(
            (1..7).map { spreadHour("2026-09-0$it", 0, 130.0, 150.0) }
        ).buckets[0]

        val swinging = TimeOfDayProfiler.of(
            (1..7).map { spreadHour("2026-09-0$it", 0, 60.0, 220.0) }
        ).buckets[0]

        // Same middle, completely different days — which a bar chart of averages
        // would draw identically.
        assertEquals(steady.median!!, swinging.median!!, 15.0)
        val steadySpread = steady.p75!! - steady.p25!!
        val swingingSpread = swinging.p75!! - swinging.p25!!
        assertTrue(
            swingingSpread > steadySpread * 3,
            "spreads were $steadySpread and $swingingSpread",
        )
    }

    @Test
    fun `percentiles are ordered`() {
        val bucket = TimeOfDayProfiler.of(
            (1..7).map { spreadHour("2026-09-0$it", 6, 70.0, 250.0) }
        ).buckets[2]

        assertTrue(bucket.p10!! <= bucket.p25!!)
        assertTrue(bucket.p25!! <= bucket.median!!)
        assertTrue(bucket.median!! <= bucket.p75!!)
        assertTrue(bucket.p75!! <= bucket.p90!!)
    }

    @Test
    fun `names the least predictable part of the day`() {
        val hours = (1..7).flatMap { day ->
            listOf(
                spreadHour("2026-09-0$day", 0, 118.0, 122.0),
                spreadHour("2026-09-0$day", 12, 60.0, 280.0),
            )
        }
        val worst = TimeOfDayProfiler.of(hours).mostVariable()
        assertNotNull(worst)
        assertEquals(12, worst.startHour)
    }

    // -- honesty about thin data ------------------------------------------

    @Test
    fun `a slice with no data says so rather than reading as zero`() {
        val profile = TimeOfDayProfiler.of(listOf(hour("2026-09-01", 0, 120.0)))
        assertTrue(profile.buckets[0].hasData)
        assertTrue(!profile.buckets[4].hasData)
        assertNull(profile.buckets[4].median)
    }

    @Test
    fun `a few days is not a pattern`() {
        val threeDays = (1..3).flatMap { day ->
            (0 until 24).map { h -> hour("2026-09-0$day", h, 120.0) }
        }
        val profile = TimeOfDayProfiler.of(threeDays)
        assertEquals(3, profile.dayCount)
        assertTrue(!profile.isReliable, "three days must not read as reliable")
    }

    @Test
    fun `a full week of complete data is reliable`() {
        val profile = TimeOfDayProfiler.of(fullWeek { 120.0 })
        assertEquals(7, profile.dayCount)
        assertTrue(profile.coverage > 0.95, "coverage was ${profile.coverage}")
        assertTrue(profile.isReliable)
    }

    @Test
    fun `partial hours lower coverage without losing the readings`() {
        val sparse = (1..7).flatMap { day ->
            (0 until 24).map { h ->
                hour("2026-09-0$day", h, 120.0, count = 3, coverageBuckets = 3)
            }
        }
        val profile = TimeOfDayProfiler.of(sparse)
        assertTrue(profile.coverage < 0.3, "coverage was ${profile.coverage}")
        assertTrue(profile.hasData)
        assertTrue(!profile.isReliable)
    }

    @Test
    fun `counts the days behind each slice, not only overall`() {
        val hours = listOf(
            hour("2026-09-01", 1, 120.0),
            hour("2026-09-02", 1, 120.0),
            hour("2026-09-03", 13, 120.0),
        )
        val profile = TimeOfDayProfiler.of(hours)
        assertEquals(3, profile.dayCount)
        assertEquals(2, profile.buckets[0].dayCount)
        assertEquals(1, profile.buckets[4].dayCount)
    }

    @Test
    fun `no data at all yields an empty profile rather than eight empty slices`() {
        assertEquals(TimeOfDayProfile.Empty, TimeOfDayProfiler.of(emptyList()))
        assertTrue(!TimeOfDayProfile.Empty.hasData)
    }

    @Test
    fun `reports the range the bands occupy, for scaling an axis`() {
        val profile = TimeOfDayProfiler.of(
            (1..7).map { spreadHour("2026-09-0$it", 0, 80.0, 240.0) }
        )
        val range = profile.valueRange()
        assertNotNull(range)
        assertTrue(range.start < 110 && range.endInclusive > 200, "range was $range")
    }
}
