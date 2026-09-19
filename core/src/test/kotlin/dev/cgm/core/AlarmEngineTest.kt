package dev.cgm.core

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

class AlarmEngineTest {

    private val engine = AlarmEngine()
    private val now = 1_800_000_000_000L
    private val settings = AlarmSettings.Default

    /**
     * A reading aged relative to [atMillis], not to [now].
     *
     * Getting this wrong makes a reading look 20 minutes stale simply because
     * the test evaluated later, which then trips signal loss and masks whatever
     * the test was actually about.
     */
    private fun snapshotAt(
        atMillis: Long,
        mgdl: Double,
        trend: TrendArrow = TrendArrow.STEADY,
        ageMinutes: Int = 1,
    ) = GlucoseSnapshot(
        reading = GlucoseReading(
            valueMgdl = mgdl,
            timestampMillis = atMillis - ageMinutes.minutes.inWholeMilliseconds,
            trend = trend,
        ),
    )

    private fun snapshot(
        mgdl: Double,
        trend: TrendArrow = TrendArrow.STEADY,
        ageMinutes: Int = 1,
    ) = snapshotAt(now, mgdl, trend, ageMinutes)

    private fun evaluate(
        snapshot: GlucoseSnapshot?,
        freshness: Freshness = Freshness.FRESH,
        settings: AlarmSettings = this.settings,
        previous: AlarmRuntimeState = AlarmRuntimeState.Empty,
        atMillis: Long = now,
    ) = engine.evaluate(snapshot, freshness, settings, previous, atMillis)

    // -- firing conditions -------------------------------------------------

    @Test
    fun `quiet while in range`() {
        val d = evaluate(snapshot(110.0))
        assertTrue(d.firing.isEmpty())
        assertTrue(d.sound.isEmpty())
    }

    @Test
    fun `fires low below its own threshold`() {
        assertEquals(setOf(AlarmKind.LOW), evaluate(snapshot(65.0)).firing)
    }

    @Test
    fun `fires high above its own threshold not the display band`() {
        // 190 is outside the 70-180 display band but below the 220 alarm level.
        assertTrue(evaluate(snapshot(190.0)).firing.isEmpty())
        assertEquals(setOf(AlarmKind.HIGH), evaluate(snapshot(230.0)).firing)
    }

    @Test
    fun `urgent low suppresses the plain low alarm`() {
        val d = evaluate(snapshot(45.0))
        assertEquals(setOf(AlarmKind.URGENT_LOW), d.firing)
        assertTrue(AlarmKind.LOW !in d.firing, "45 must not fire two low alarms at once")
    }

    @Test
    fun `very high suppresses plain high when enabled`() {
        val s = settings.with(
            AlarmKind.VERY_HIGH,
            settings[AlarmKind.VERY_HIGH].copy(enabled = true),
        )
        assertEquals(setOf(AlarmKind.VERY_HIGH), evaluate(snapshot(320.0), settings = s).firing)
    }

    @Test
    fun `a disabled alarm never fires`() {
        val s = settings.with(AlarmKind.LOW, settings[AlarmKind.LOW].copy(enabled = false))
        assertTrue(evaluate(snapshot(65.0), settings = s).firing.isEmpty())
    }

    // -- signal loss -------------------------------------------------------

    @Test
    fun `fires signal loss once the reading is too old`() {
        val d = evaluate(snapshot(110.0, ageMinutes = 25), freshness = Freshness.STALE)
        assertEquals(setOf(AlarmKind.SIGNAL_LOSS), d.firing)
    }

    @Test
    fun `no reading at all counts as signal loss`() {
        assertEquals(setOf(AlarmKind.SIGNAL_LOSS), evaluate(null).firing)
    }

    @Test
    fun `stale data does not drive glucose alarms`() {
        // A 40 mg/dL reading from half an hour ago says nothing about now, and
        // alarming on it is how someone gets woken for a low that already ended.
        val d = evaluate(snapshot(40.0, ageMinutes = 30), freshness = Freshness.STALE)
        assertEquals(setOf(AlarmKind.SIGNAL_LOSS), d.firing)
        assertTrue(AlarmKind.URGENT_LOW !in d.firing)
    }

    @Test
    fun `signal loss clears when fresh data returns`() {
        val firing = evaluate(null)
        val recovered = evaluate(snapshot(110.0), previous = firing.state)
        assertTrue(recovered.firing.isEmpty())
        assertEquals(setOf(AlarmKind.SIGNAL_LOSS), recovered.cleared)
    }

    // -- sounding behaviour ------------------------------------------------

    @Test
    fun `announces a new condition immediately`() {
        assertEquals(listOf(AlarmKind.LOW), evaluate(snapshot(65.0)).sound)
    }

    @Test
    fun `stays quiet between repeats`() {
        val first = evaluate(snapshot(65.0))
        val soon = evaluate(
            snapshotAt(now + 5.minutes.inWholeMilliseconds, 64.0),
            previous = first.state,
            atMillis = now + 5.minutes.inWholeMilliseconds,
        )
        assertEquals(setOf(AlarmKind.LOW), soon.firing)
        assertTrue(soon.sound.isEmpty(), "should not re-sound before the repeat interval")
    }

    @Test
    fun `sounds again once the repeat interval passes`() {
        val first = evaluate(snapshot(65.0, trend = TrendArrow.FALLING))
        val later = evaluate(
            snapshotAt(now + 16.minutes.inWholeMilliseconds, 63.0, TrendArrow.FALLING),
            previous = first.state,
            atMillis = now + 16.minutes.inWholeMilliseconds,
        )
        assertEquals(listOf(AlarmKind.LOW), later.sound)
    }

    // -- issue #1: silence while recovering ---------------------------------

    @Test
    fun `sounds once then stays quiet while a low is recovering`() {
        val first = evaluate(snapshot(65.0, trend = TrendArrow.RISING))
        assertEquals(listOf(AlarmKind.LOW), first.sound)

        val later = evaluate(
            snapshotAt(now + 20.minutes.inWholeMilliseconds, 68.0, TrendArrow.RISING),
            previous = first.state,
            atMillis = now + 20.minutes.inWholeMilliseconds,
        )
        assertEquals(setOf(AlarmKind.LOW), later.firing, "still low, so still showing")
        assertTrue(later.sound.isEmpty(), "a recovering low must not keep sounding")
    }

    @Test
    fun `resumes sounding when a low stops recovering`() {
        val first = evaluate(snapshot(65.0, trend = TrendArrow.RISING))
        val stalled = evaluate(
            snapshotAt(now + 20.minutes.inWholeMilliseconds, 64.0, TrendArrow.FALLING),
            previous = first.state,
            atMillis = now + 20.minutes.inWholeMilliseconds,
        )
        assertEquals(listOf(AlarmKind.LOW), stalled.sound)
    }

    @Test
    fun `an urgent low keeps sounding even while recovering`() {
        val first = evaluate(snapshot(45.0, trend = TrendArrow.RISING_QUICKLY))
        val later = evaluate(
            snapshotAt(now + 6.minutes.inWholeMilliseconds, 50.0, TrendArrow.RISING_QUICKLY),
            previous = first.state,
            atMillis = now + 6.minutes.inWholeMilliseconds,
        )
        assertEquals(listOf(AlarmKind.URGENT_LOW), later.sound)
    }

    @Test
    fun `a falling high counts as recovering`() {
        val first = evaluate(snapshot(230.0, trend = TrendArrow.FALLING))
        val later = evaluate(
            snapshotAt(now + 20.minutes.inWholeMilliseconds, 225.0, TrendArrow.FALLING),
            previous = first.state,
            atMillis = now + 20.minutes.inWholeMilliseconds,
        )
        assertTrue(later.sound.isEmpty())
    }

    @Test
    fun `an unknown trend is not treated as recovering`() {
        val first = evaluate(snapshot(65.0, trend = TrendArrow.UNKNOWN))
        val later = evaluate(
            snapshotAt(now + 20.minutes.inWholeMilliseconds, 64.0, TrendArrow.UNKNOWN),
            previous = first.state,
            atMillis = now + 20.minutes.inWholeMilliseconds,
        )
        assertEquals(listOf(AlarmKind.LOW), later.sound)
    }

    // -- snooze -------------------------------------------------------------

    @Test
    fun `a snoozed alarm keeps firing but stays silent`() {
        val first = evaluate(snapshot(65.0, trend = TrendArrow.FALLING))
        val snoozed = first.state.snooze(
            AlarmKind.LOW,
            untilMillis = now + 30.minutes.inWholeMilliseconds,
        )
        val during = evaluate(
            snapshotAt(now + 20.minutes.inWholeMilliseconds, 64.0, TrendArrow.FALLING),
            previous = snoozed,
            atMillis = now + 20.minutes.inWholeMilliseconds,
        )
        assertEquals(setOf(AlarmKind.LOW), during.firing)
        assertTrue(during.sound.isEmpty())
    }

    @Test
    fun `sounds again after the snooze expires`() {
        val first = evaluate(snapshot(65.0, trend = TrendArrow.FALLING))
        val snoozed = first.state.snooze(
            AlarmKind.LOW,
            untilMillis = now + 10.minutes.inWholeMilliseconds,
        )
        val after = evaluate(
            snapshotAt(now + 20.minutes.inWholeMilliseconds, 64.0, TrendArrow.FALLING),
            previous = snoozed,
            atMillis = now + 20.minutes.inWholeMilliseconds,
        )
        assertEquals(listOf(AlarmKind.LOW), after.sound)
    }

    // -- ordering ------------------------------------------------------------

    @Test
    fun `sounds the most severe first`() {
        val s = AlarmSettings.Default
            .with(AlarmKind.SIGNAL_LOSS, AlarmSetting(enabled = true, afterMillis = 1))
        val d = evaluate(snapshot(45.0, ageMinutes = 2), settings = s)
        assertEquals(AlarmKind.URGENT_LOW, d.sound.first())
    }

    @Test
    fun `restarting does not re-announce an alarm already sounded`() {
        val first = evaluate(snapshot(65.0, trend = TrendArrow.FALLING))
        // State is persisted, so a fresh engine after process death sees it.
        val afterRestart = AlarmEngine().evaluate(
            snapshotAt(now + 1.minutes.inWholeMilliseconds, 64.0, TrendArrow.FALLING),
            Freshness.FRESH,
            settings,
            first.state,
            now + 1.minutes.inWholeMilliseconds,
        )
        assertTrue(afterRestart.sound.isEmpty())
    }
}
