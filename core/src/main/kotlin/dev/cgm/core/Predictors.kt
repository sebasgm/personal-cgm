package dev.cgm.core

import kotlin.math.abs
import kotlin.time.Duration.Companion.minutes

/**
 * A candidate for predicting where glucose goes next.
 *
 * Exists so that models can be *compared* rather than assumed. The GLYFE
 * benchmark's central point is that glucose prediction papers routinely report
 * impressive errors without saying what carrying the last value forward would
 * have scored — and that up to about fifteen minutes, nothing beats a straight
 * line. Any model here has to earn its place against [ZeroOrderHold].
 */
interface GlucosePredictor {

    val id: String

    /**
     * Predict [horizonMillis] past the last reading, or null when this model
     * cannot say anything useful from the history given.
     *
     * Returning null is a legitimate answer, not a failure: a predictor with four
     * readings and a two-hour horizon has nothing to offer, and saying so beats
     * inventing a line.
     */
    fun predict(history: List<GlucoseReading>, horizonMillis: Long): Double?
}

/**
 * Assume it stays where it is.
 *
 * The baseline every other model is measured against. Unglamorous and
 * surprisingly hard to beat at short horizons.
 */
object ZeroOrderHold : GlucosePredictor {
    override val id = "hold"

    override fun predict(history: List<GlucoseReading>, horizonMillis: Long): Double? =
        history.maxByOrNull { it.timestampMillis }?.valueMgdl
}

/**
 * Extend the recent slope in a straight line.
 *
 * The other GLYFE baseline. Competitive to about fifteen minutes and increasingly
 * optimistic past that, because glucose does not keep rising forever.
 */
class LinearExtrapolation(
    private val windowMillis: Long = 20.minutes.inWholeMilliseconds,
) : GlucosePredictor {
    override val id = "linear"

    override fun predict(history: List<GlucoseReading>, horizonMillis: Long): Double? {
        val last = history.maxByOrNull { it.timestampMillis } ?: return null
        val slope = ForecastModel.slopeMgdlPerMinute(history, last.timestampMillis) ?: return null
        return last.valueMgdl + slope * (horizonMillis / 60_000.0)
    }
}

/** The shipped model: robust slope, damped so it saturates instead of running away. */
class DampedTrend : GlucosePredictor {
    override val id = "damped"

    override fun predict(history: List<GlucoseReading>, horizonMillis: Long): Double? {
        val last = history.maxByOrNull { it.timestampMillis } ?: return null
        val slope = ForecastModel.slopeMgdlPerMinute(history, last.timestampMillis) ?: return null
        return last.valueMgdl + ForecastModel.displacement(slope, horizonMillis)
    }
}

/**
 * Autoregressive model of order [order], fitted to the history it is given.
 *
 * The first model here that *learns* anything: its coefficients come from this
 * person's own dynamics rather than from a constant chosen in advance. GLYFE uses
 * AR(3) as its third baseline, and it is the cheapest honest step past a straight
 * line — no training loop, no tensors, a small least-squares solve.
 *
 * Fitted on first differences rather than levels, because glucose is not
 * stationary and an AR on raw values spends its coefficients re-learning the
 * mean instead of the dynamics.
 */
class AutoRegressive(
    private val order: Int = 3,
    private val stepMillis: Long = 5.minutes.inWholeMilliseconds,
    /** Ridge term. Small, but keeps a near-singular fit from exploding. */
    private val regularisation: Double = 1e-3,
) : GlucosePredictor {

    override val id = "ar$order"

    override fun predict(history: List<GlucoseReading>, horizonMillis: Long): Double? {
        val grid = Resample.toGrid(history, stepMillis) ?: return null
        if (grid.size < order * 3 + 2) return null

        val diffs = DoubleArray(grid.size - 1) { grid[it + 1] - grid[it] }
        if (diffs.size <= order) return null

        val coefficients = fit(diffs) ?: return null

        // Roll forward one step at a time, feeding predictions back in.
        val steps = (horizonMillis / stepMillis).toInt()
        if (steps <= 0) return grid.last()

        val window = diffs.takeLast(order).toMutableList()
        var value = grid.last()
        repeat(steps) {
            var next = 0.0
            for (i in 0 until order) next += coefficients[i] * window[window.size - 1 - i]
            value += next
            window += next
        }
        return value
    }

    /** Ordinary least squares on lagged differences, solved by Gaussian elimination. */
    private fun fit(diffs: DoubleArray): DoubleArray? {
        val rows = diffs.size - order
        if (rows < order + 1) return null

        val xtx = Array(order) { DoubleArray(order) }
        val xty = DoubleArray(order)

        for (row in 0 until rows) {
            val target = diffs[row + order]
            for (i in 0 until order) {
                val xi = diffs[row + order - 1 - i]
                xty[i] += xi * target
                for (j in 0 until order) xtx[i][j] += xi * diffs[row + order - 1 - j]
            }
        }
        for (i in 0 until order) xtx[i][i] += regularisation * rows

        return solve(xtx, xty)
    }

    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { r -> DoubleArray(n + 1) { c -> if (c < n) a[r][c] else b[r] } }

        for (col in 0 until n) {
            val pivot = (col until n).maxByOrNull { abs(m[it][col]) } ?: return null
            if (abs(m[pivot][col]) < 1e-12) return null
            val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp

            for (row in 0 until n) {
                if (row == col) continue
                val factor = m[row][col] / m[col][col]
                for (c in col..n) m[row][c] -= factor * m[col][c]
            }
        }
        // Reduced to diagonal form above, so each coefficient reads straight off.
        return DoubleArray(n) { m[it][n] / m[it][it] }
    }
}

/**
 * Readings onto a regular grid.
 *
 * Models that assume even spacing need it, and real readings do not have it: the
 * sensor drops samples, and the graph backfill is fifteen minutes apart where our
 * own polling is one. Gaps wider than [MAX_GAP_STEPS] steps abort rather than
 * being interpolated across, because inventing readings to feed a model is how a
 * model learns from data that never existed.
 */
object Resample {

    const val MAX_GAP_STEPS = 3

    fun toGrid(history: List<GlucoseReading>, stepMillis: Long): DoubleArray? {
        if (history.size < 2) return null
        val sorted = history.sortedBy { it.timestampMillis }
        val start = sorted.first().timestampMillis
        val end = sorted.last().timestampMillis
        val count = ((end - start) / stepMillis).toInt() + 1
        if (count < 2) return null

        val out = DoubleArray(count)
        var cursor = 0
        for (i in 0 until count) {
            val at = start + i * stepMillis
            while (cursor + 1 < sorted.size &&
                abs(sorted[cursor + 1].timestampMillis - at) <= abs(sorted[cursor].timestampMillis - at)
            ) cursor++
            if (abs(sorted[cursor].timestampMillis - at) > stepMillis * MAX_GAP_STEPS) return null
            out[i] = sorted[cursor].valueMgdl
        }
        return out
    }
}
