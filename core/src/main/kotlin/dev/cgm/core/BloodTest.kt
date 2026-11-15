package dev.cgm.core

import kotlin.math.abs
import kotlinx.serialization.Serializable

/** How a finger-prick result reached the app. */
enum class BloodTestOrigin {
    /** Read off a meter over Bluetooth. */
    METER,

    /** Typed in by hand. */
    MANUAL,
}

/**
 * What became of a finger-prick result handed to storage.
 *
 * Three outcomes rather than a boolean, because the three are acted on differently
 * and two of them were previously indistinguishable. A form that typed something
 * implausible must keep what was typed and say why; a collector that re-read a record
 * the database already holds has not failed at anything and must still advance past
 * it; and only [STORED] means there is a new row.
 *
 * The one that matters is [DUPLICATE]. A meter numbers its own records and hands them
 * out again on every connection, so re-reading one is the normal case, not an error —
 * and reporting it as a success would have a collector believe it had stored something
 * it had not.
 */
enum class BloodTestOutcome {
    /** A new row. */
    STORED,

    /** This meter record is already stored, and the stored one was kept. */
    DUPLICATE,

    /** Not a plausible result; nothing was written. */
    REFUSED,
}

/**
 * A finger-prick result: blood on a strip, read by a meter.
 *
 * Kept apart from [GlucoseReading] on purpose, and the separation is the whole point
 * of the feature. A reading is what a sensor estimated about interstitial fluid every
 * few minutes; this is what a meter measured in capillary blood at one instant. The
 * app's entire record is of the first kind, which means it has never had anything to
 * check itself against — and a few spot values filed into that record would not give
 * it one, they would quietly corrupt it: time in range, GMI, the daily pattern and the
 * coverage figure all mean "of the sensor series", and three capillary values a day
 * dropped among them would make every one of those figures describe something else.
 *
 * So this is a reference measurement, stored as its own fact, and the comparison
 * between the two is computed rather than baked in. See [SensorAgreement].
 *
 * The timestamp is when the blood was taken, like every other thing a person did in
 * this app: a meter's history can arrive hours later, and filing it under the moment
 * it was downloaded would pair it with the wrong sensor reading — which is the one
 * error that makes the comparison worse than having no comparison.
 */
@Serializable
data class BloodTest(
    val id: Long = 0,
    /** In mg/dL, like every stored glucose here, whatever it was typed or read in. */
    val mgdl: Double,
    val takenAtMillis: Long,
    val origin: BloodTestOrigin = BloodTestOrigin.MANUAL,
    /**
     * The meter's own record number, or null for a test typed in by hand.
     *
     * Carried so a meter can be re-read without duplicating what is already stored:
     * the standard Bluetooth profile numbers records and lets a collector ask for
     * everything above a number it names. It is the identity of the record on the
     * device, not of the row here, which is why it is kept beside the meter's name —
     * sequence 41 means nothing without knowing whose 41 it is.
     */
    val meterSequence: Int? = null,
    /** What the meter called itself, or null for a test typed in by hand. */
    val meterName: String? = null,
    val note: String? = null,
) {

    /**
     * Whether this is plausible enough to store.
     *
     * The same bounds a pushed reading gets, because the question is the same one: is
     * this a glucose value at all. A meter that reports outside them is reporting an
     * error code or a control solution, and a human who types a figure outside them
     * has mistyped it.
     */
    /**
     * Whether this result identifies itself as a particular record on a particular
     * meter, which is what makes it possible to recognise it arriving twice.
     *
     * Both halves or neither: a sequence number without the meter it came from cannot
     * be compared with anything, since every meter numbers from one.
     */
    val hasMeterRecord: Boolean get() = meterName != null && meterSequence != null

    val isPlausible: Boolean
        get() = takenAtMillis > 0 &&
            mgdl.isFinite() &&
            mgdl >= MIN_MGDL &&
            mgdl <= MAX_MGDL

    companion object {
        /** Deliberately [PushedReading]'s bounds, not a second opinion about them. */
        const val MIN_MGDL = PushedReading.MIN_MGDL
        const val MAX_MGDL = PushedReading.MAX_MGDL

        /**
         * A typed figure, in the unit the screen is showing, as mg/dL.
         *
         * Both decimal separators are accepted exactly as the dose and carbohydrate
         * fields accept them — a Spanish keyboard offers a comma, and "5,8" is five
         * point eight millimoles and must never read as fifty-eight.
         *
         * Nothing is rounded. A meter in mg/dL reports whole numbers and they survive
         * as such; 5.8 mmol/L converts to 104.5 mg/dL and stays there, because
         * rounding it to 104 or 105 would be this app editing someone else's
         * measurement for tidiness.
         */
        fun parse(text: String, unit: GlucoseUnit): Double? =
            TypedAmount.parse(text)?.let { unit.toMgdl(it) }

        fun cleanNote(note: String?): String? = TypedAmount.cleanNote(note)
    }
}

/**
 * One finger-prick result and the sensor reading that was nearest to it.
 *
 * [gapMillis] is kept rather than discarded because it qualifies everything else here:
 * a pair five seconds apart is a comparison, and a pair five minutes apart on a moving
 * trace is partly a measurement of those five minutes.
 */
data class PairedReading(
    val test: BloodTest,
    val sensorMgdl: Double,
    val gapMillis: Long,
) {
    /** Signed, sensor minus strip: positive means the sensor read higher. */
    val differenceMgdl: Double get() = sensorMgdl - test.mgdl

    /** |difference| as a fraction of the reference, which is what MARD averages. */
    val absoluteRelativeDifference: Double get() = abs(differenceMgdl) / test.mgdl

    /**
     * The Clarke zone of the sensor value against this strip.
     *
     * The strip is the reference and the sensor is the prediction, never the other way
     * round. Swapping them would silently answer a different question — how well the
     * fingerstick predicts the sensor — which is not a question anyone has.
     */
    val zone: ClarkeZone
        get() = ClarkeErrorGrid.zoneOf(referenceMgdl = test.mgdl, predictedMgdl = sensorMgdl)
}

/**
 * How far this sensor has been from this meter, measured rather than assumed.
 *
 * The figure that matters is [meanAbsoluteRelativeDifference] — MARD, the standard way
 * a CGM's accuracy is reported — and the reason to compute it here rather than quote a
 * manufacturer's is that a manufacturer's is a population figure. This one is n=1, on
 * the arm it is actually on, which is both much less reliable and much more relevant.
 *
 * [medianDifferenceMgdl] is the other half and is not interchangeable with it: MARD
 * says how far apart the two instruments are, and the median signed difference says
 * whether the sensor leans one way. A sensor 15% out in both directions and one
 * reading 15% low every time have the same MARD and call for different responses.
 * Median rather than mean, because a handful of pairs is exactly the sample size where
 * one bad strip decides a mean.
 *
 * Nothing here filters by how fast glucose was moving, and it should eventually: a
 * sensor reads interstitial fluid, which lags blood by five to fifteen minutes and
 * lags further the faster the trace moves, so a pair taken mid-rise reports a
 * disagreement that is partly just physiology. Until that is modelled, every figure
 * here is a slight overstatement of how wrong the sensor is, in a known direction.
 * See `docs/12-finger-prick-meter.md`.
 */
data class SensorAgreement(val pairs: List<PairedReading>) {

    val count: Int get() = pairs.size

    val hasData: Boolean get() = pairs.isNotEmpty()

    /**
     * Whether there are too few pairs to read the figures as anything but anecdote.
     *
     * Stated rather than hidden, exactly as the coverage notice on Trends is: the
     * figures are shown either way, and what changes is how confidently.
     */
    val isThin: Boolean get() = count < MIN_PAIRS

    /** MARD, as a fraction. Multiply by 100 for the percentage everyone quotes. */
    val meanAbsoluteRelativeDifference: Double?
        get() = if (pairs.isEmpty()) null else pairs.sumOf { it.absoluteRelativeDifference } / count

    /** Signed, sensor minus strip: positive means this sensor leans high. */
    val medianDifferenceMgdl: Double?
        get() = median(pairs.map { it.differenceMgdl })

    /** How close the pairs actually were in time, as the median gap. */
    val medianGapMillis: Long?
        get() = median(pairs.map { it.gapMillis.toDouble() })?.toLong()

    val zoneCounts: Map<ClarkeZone, Int>
        get() = pairs.groupingBy { it.zone }.eachCount()

    /**
     * The fraction in zones A and B — the clinical reading of the grid.
     *
     * A is "clinically accurate", B is "would not lead to inappropriate treatment",
     * and together they are the figure a paper reports. C, D and E are the ones worth
     * looking at individually, which is why they are left in [zoneCounts] rather than
     * summarised into a single number here.
     */
    val benignFraction: Double?
        get() = if (pairs.isEmpty()) null else {
            pairs.count { !it.zone.isUnsafe }.toDouble() / count
        }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2
        }
    }

    companion object {
        /**
         * How far apart a strip and a reading may be and still describe one moment.
         *
         * Five minutes — one sensor cadence — and deliberately not the fifteen the
         * diary uses to attach a glucose to a dose. That window answers "what was the
         * context when this was injected", where a value from twelve minutes earlier
         * is still context. This one asks whether two instruments measured the same
         * blood, and glucose can move sixty mg/dL in fifteen minutes: a pair that far
         * apart would be scoring the sensor against a different state of the body.
         */
        const val MATCH_WINDOW_MILLIS: Long = 5 * 60 * 1000L

        /**
         * Below this many pairs the figures are marked thin.
         *
         * Ten is not a defensible sample for an accuracy claim — the literature uses
         * hundreds — and it is not meant to be. It is the point at which a figure
         * stops being one bad strip and starts being a pattern, which is the only
         * claim this card ever makes.
         */
        const val MIN_PAIRS = 10

        /**
         * Pairs each test with its nearest reading, dropping the tests that have none.
         *
         * Dropped rather than paired with the closest reading however far away, for
         * the same reason a dose keeps no glucose when nothing is near it: a test
         * taken during a sensor warm-up, in a gap in the data, or on a day the phone
         * was not polling has nothing to be compared against, and inventing a
         * comparison is worse than reporting one fewer.
         *
         * Nearest in either direction, not the last reading before the test. Both are
         * minutes away, and preferring the earlier one only because it is earlier
         * would compare a strip against a value from before the moment it describes.
         */
        fun of(
            tests: List<BloodTest>,
            readings: List<GlucoseReading>,
            windowMillis: Long = MATCH_WINDOW_MILLIS,
        ): SensorAgreement {
            if (tests.isEmpty() || readings.isEmpty()) return SensorAgreement(emptyList())

            val ordered = readings.sortedBy { it.timestampMillis }
            val times = ordered.map { it.timestampMillis }

            return SensorAgreement(
                tests.mapNotNull { test ->
                    nearest(ordered, times, test.takenAtMillis)
                        ?.takeIf { abs(it.timestampMillis - test.takenAtMillis) <= windowMillis }
                        ?.let { reading ->
                            PairedReading(
                                test = test,
                                sensorMgdl = reading.valueMgdl,
                                gapMillis = abs(reading.timestampMillis - test.takenAtMillis),
                            )
                        }
                }
            )
        }

        /**
         * The reading closest to [atMillis], found by bisection.
         *
         * A linear scan would be simpler and is what this app does elsewhere, over
         * the handful of readings a single window query returns. Here the list can be
         * every reading in a ninety-day period, and a scan per test turns the card
         * into a visible pause on the Trends screen.
         */
        private fun nearest(
            readings: List<GlucoseReading>,
            times: List<Long>,
            atMillis: Long,
        ): GlucoseReading? {
            val found = times.binarySearch { it.compareTo(atMillis) }
            if (found >= 0) return readings[found]

            val after = -(found + 1)
            val before = after - 1
            return when {
                before < 0 -> readings.getOrNull(after)
                after > readings.lastIndex -> readings[before]
                atMillis - times[before] <= times[after] - atMillis -> readings[before]
                else -> readings[after]
            }
        }
    }
}
