package dev.cgm.app.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.lifecycleScope
import dev.cgm.app.CgmApplication
import dev.cgm.app.R
import dev.cgm.app.data.CgmState
import dev.cgm.core.Freshness
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CgmSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = CarReadingScreen(carContext)
}

/**
 * One card: the value, how old it is, and recent insulin.
 *
 * The accessibility typeface does not reach here, and cannot. Car App Library
 * templates are rendered by the car's own host, which owns typography outright so
 * that every app on a dashboard looks and reads the same way under driver
 * distraction rules. There is no typeface API to call. What the host does honour
 * is the car's own display settings, which is the lever a driver actually has.
 *
 * The age is not decoration here. A driver glances at this for well under a
 * second and has no way to tell a current reading from a frozen one, so the app's
 * rule holds harder in a car than anywhere else: a stale value shows no number at
 * all rather than a number that looks fine.
 */
class CarReadingScreen(carContext: androidx.car.app.CarContext) : Screen(carContext) {

    private val repository =
        (carContext.applicationContext as CgmApplication).repository

    init {
        // Redraw when a reading lands. The car host caches the template until it
        // is told otherwise, so without this the card would show whatever was
        // current when the car was started.
        lifecycleScope.launch {
            repository.state.collectLatest { invalidate() }
        }
    }

    override fun onGetTemplate(): Template {
        val state = repository.state.value
        val now = System.currentTimeMillis()

        return PaneTemplate.Builder(buildPane(state, now))
            .setTitle(carContext.getString(R.string.app_name))
            .build()
    }

    private fun buildPane(state: CgmState, now: Long): Pane {
        val snapshot = state.snapshot
            ?: return Pane.Builder()
                .addRow(
                    Row.Builder()
                        .setTitle(carContext.getString(R.string.car_no_reading))
                        .build()
                )
                .build()

        val freshness = state.freshness(now)
        val stale = freshness == Freshness.STALE

        val value = if (stale) {
            carContext.getString(R.string.car_stale_value)
        } else {
            "${snapshot.formattedValue()} ${state.unit.suffix}  ${snapshot.reading.trend.glyph}"
        }

        val minutes = snapshot.reading.ageMillis(now) / 60_000
        val age = if (minutes < 1) {
            carContext.getString(R.string.car_age_now)
        } else {
            carContext.getString(R.string.car_age_minutes, minutes.toInt())
        }

        val pane = Pane.Builder()
            .addRow(
                Row.Builder()
                    .setTitle(value)
                    .addText(if (stale) carContext.getString(R.string.car_stale_note) else age)
                    .build()
            )

        // Only while there is something to say. An empty row in a car is a row
        // someone reads twice to check they have not missed anything.
        state.snapshot?.formattedDelta()?.takeIf { !stale }?.let {
            pane.addRow(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_change))
                    .addText(it)
                    .build()
            )
        }

        return pane.build()
    }
}
