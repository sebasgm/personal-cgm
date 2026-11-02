package dev.cgm.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

import kotlinx.serialization.Serializable

/** The two things a dose can be. */
enum class InsulinKind {
    /** Background insulin, usually once or twice a day. */
    BASAL,

    /** A correction or a meal dose. */
    BOLUS,
}

/**
 * An insulin dose the user recorded.
 *
 * Kept separate from readings, and deliberately so: a reading is something the
 * sensor observed and this is something a person did. Different source, different
 * lifecycle, and this one is editable where a reading never is — a mistyped dose
 * has to be correctable, while a "corrected" reading would be a falsified record.
 *
 * The timestamp is when the dose was *given*, not when it was entered, because
 * entering yesterday's forgotten injection is a normal thing to do and putting it
 * on today's timeline would make the chart lie.
 */
@Serializable
data class InsulinDose(
    val id: Long = 0,
    val kind: InsulinKind,
    val units: Double,
    val givenAtMillis: Long,
    val note: String? = null,
    /**
     * Glucose at the moment of the dose, in mg/dL, or null when nothing is known.
     *
     * Recorded *with* the dose rather than looked up when the diary is read, and the
     * difference matters: readings are pruned after a year and a sensor's history is
     * not re-fetchable, so a dose from eighteen months ago would silently lose the
     * one number that says why it was taken. Stored as a copy, which is the only way
     * the pair survives.
     *
     * In mg/dL like every other reading in this app, converted for display at the
     * edge. Null is "no reading close enough to that time" — which is an ordinary
     * state, not a failure: a dose can be entered while the sensor is warming up,
     * during a gap in the data, or backdated to a day the phone was not polling.
     * Nothing here invents a value to fill it, because an invented glucose beside a
     * real dose is the kind of record that gets shown to a doctor.
     *
     * Left out of the payload sent to the watch, exactly as a reading's
     * `storedAtMillis` is. The watch carries recent doses only to answer "did I
     * already take something for this", and the glucose it would show beside them
     * is the number already filling its screen. Keeping it off the wire also keeps
     * the payload byte-identical to the one older watch builds parse, and they
     * parse it strictly — an unknown key there is a watch that stops showing
     * glucose at all, which is a steep price for a field it would not draw.
     */
    @kotlinx.serialization.Transient val glucoseMgdl: Double? = null,
) {

    /**
     * Whether this is plausible enough to store.
     *
     * Upper bound is not medical advice, it is a typo guard: a fat-fingered "100"
     * where "10" was meant is the realistic error, and a dose log that silently
     * accepts it is worse than one that asks again. The bound is deliberately far
     * above any normal dose so it never argues with a legitimate one.
     */
    val isPlausible: Boolean
        get() = units > 0 && units <= MAX_PLAUSIBLE_UNITS && givenAtMillis > 0

    companion object {
        const val MAX_PLAUSIBLE_UNITS = 100.0

        /** Doses are recorded to the half unit; pens and syringes do not do finer. */
        const val STEP_UNITS = 0.5

        /** Rounds to what a pen can actually deliver. */
        fun roundUnits(units: Double): Double =
            (Math.round(units / STEP_UNITS) * STEP_UNITS).coerceAtLeast(0.0)

        /**
         * Parses a typed dose, accepting either decimal separator.
         *
         * A Spanish keyboard produces "6,5" and an English one "6.5", and both mean
         * six and a half units. `toDoubleOrNull` accepts only the period, so without
         * this a comma would parse as nothing and a real dose would be silently
         * refused — or worse, read as 65.
         *
         * Returns null for anything that is not a single finite number, which the
         * form reports rather than guessing at.
         */
        fun parseUnits(text: String): Double? = TypedAmount.parse(text)

        /** Trims and drops an empty note, so blank and absent are the same thing. */
        fun cleanNote(note: String?): String? = TypedAmount.cleanNote(note)

        /**
         * How far from a dose a reading may sit and still be called its glucose.
         *
         * Fifteen minutes because that is roughly the span over which a value still
         * describes the moment you injected: a sensor reports every few minutes, so
         * a normal dose finds something within one or two of them, and anything
         * beyond this window is a different state of the body rather than a slightly
         * stale copy of the same one. Wider would quietly attach a breakfast reading
         * to a lunchtime bolus; narrower would leave the common case — a dose given
         * in a short gap between readings — with no glucose at all.
         */
        const val GLUCOSE_MATCH_WINDOW_MILLIS: Long = 15 * 60 * 1000L

        /**
         * The glucose to record beside a dose given at [atMillis], or null.
         *
         * Nearest in either direction rather than the last reading before the dose.
         * Both are a few minutes from the injection, and preferring the earlier one
         * only because it is earlier would hand back a value from before a correction
         * when one from after it sits closer in time.
         *
         * Returns null when nothing lands within [GLUCOSE_MATCH_WINDOW_MILLIS]. The
         * alternative — the closest reading however far away — is how a dose comes to
         * be filed with the glucose from six hours earlier, which reads as a fact and
         * is a fiction.
         */
        fun glucoseNear(readings: List<GlucoseReading>, atMillis: Long): Double? =
            readings
                .minByOrNull { Math.abs(it.timestampMillis - atMillis) }
                ?.takeIf { Math.abs(it.timestampMillis - atMillis) <= GLUCOSE_MATCH_WINDOW_MILLIS }
                ?.valueMgdl
    }
}

/**
 * A number a person typed into a form, and the note they typed beside it.
 *
 * One implementation for insulin and for carbohydrates rather than two: the
 * hazard is the decimal separator, it is identical in both fields, and a second
 * copy of the rule is a second chance to get "6,5" wrong in only one of them.
 */
internal object TypedAmount {

    fun parse(text: String): Double? {
        val normalised = text.trim().replace(',', '.')
        if (normalised.isEmpty()) return null
        val value = normalised.toDoubleOrNull() ?: return null
        return if (value.isFinite()) value else null
    }

    fun cleanNote(note: String?): String? = note?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * Carbohydrates the user ate.
 *
 * A sibling of [InsulinDose] rather than a variant of it. Both are things a person
 * did and both are editable, but they are not the same quantity and must never be
 * summed: a day's 48 units and a day's 180 grams are two facts, and a schema that
 * could add them together is a schema that eventually does.
 *
 * Recorded in grams of carbohydrate, not as a meal name or a portion. Grams are
 * the only part of a meal this app can do anything with — they are what lines up
 * against a rise on the chart — and asking for a description as well would make
 * logging lunch a form-filling exercise, which is how a food log stops being kept.
 *
 * The timestamp is when it was **eaten**, for the same reason a dose records when
 * it was given: entering the lunch you forgot to log is ordinary, and filing it
 * under the moment you remembered would put the carbs an hour after the rise they
 * caused.
 */
@Serializable
data class CarbEntry(
    val id: Long = 0,
    /**
     * Grams of carbohydrate, or null when the meal was logged without counting.
     *
     * Optional because the common case is honest ignorance: you ate, you know
     * roughly when, and you did not weigh it. The alternative was a required
     * field, which forces a number that is a guess and then lets every total
     * downstream treat that guess as measured. Null says "not counted", which is
     * the truth and which sums to nothing rather than to a fiction.
     */
    val grams: Double? = null,
    val eatenAtMillis: Long,
    val note: String? = null,
    /**
     * File name of a photo of the meal, within the app's own storage, or null.
     *
     * A name rather than the image: the picture lives as a file and only its name
     * is a column, so the database that holds two years of readings does not also
     * carry hundreds of megabytes of JPEG. A name rather than a path, so moving
     * the directory does not invalidate every row.
     *
     * It is a memory aid and nothing more. Nothing in this app reads the image, and
     * nothing estimates carbohydrates from it.
     */
    val photoName: String? = null,
) {

    /**
     * Whether this is plausible enough to store.
     *
     * A typo guard, not a dietary opinion: 400 where 40 was meant is the realistic
     * error, and the bound sits far enough above a large meal that it never argues
     * with a real one. An uncounted meal has nothing to check.
     */
    val isPlausible: Boolean
        get() = eatenAtMillis > 0 &&
            (grams == null || (grams > 0 && grams <= MAX_PLAUSIBLE_GRAMS))

    /** Whether a carbohydrate figure was recorded at all. */
    val isCounted: Boolean get() = grams != null

    companion object {
        const val MAX_PLAUSIBLE_GRAMS = 400.0

        /**
         * Carbohydrates are counted in whole grams.
         *
         * Not because a half gram is impossible but because it is a fiction: the
         * figure comes from a label, a guess or a portion size, and one decimal
         * place on an estimate claims precision that was never there. Insulin
         * rounds to the half unit because a pen delivers half units; nothing about
         * a plate of rice is measured that finely.
         */
        const val STEP_GRAMS = 1.0

        fun roundGrams(grams: Double): Double =
            (Math.round(grams / STEP_GRAMS) * STEP_GRAMS).coerceAtLeast(0.0)

        /** Accepts either decimal separator, exactly as the dose field does. */
        fun parseGrams(text: String): Double? = TypedAmount.parse(text)

        fun cleanNote(note: String?): String? = TypedAmount.cleanNote(note)

        /**
         * A day's carbohydrates, counting only the meals that were counted.
         *
         * Uncounted meals contribute nothing rather than zero, and the two are
         * not the same claim — which is why [countedOf] exists beside this, so a
         * total can say what it rests on instead of implying it covers every meal.
         */
        fun totalGrams(entries: List<CarbEntry>): Double =
            entries.mapNotNull { it.grams }.sum()

        /** How many of [entries] carried a figure, and how many did not. */
        fun countedOf(entries: List<CarbEntry>): Pair<Int, Int> =
            entries.count { it.isCounted } to entries.count { !it.isCounted }
    }
}

/** Totals for a day's doses, which is how insulin is usually reasoned about. */
data class InsulinDayTotals(val basalUnits: Double, val bolusUnits: Double) {
    val totalUnits: Double get() = basalUnits + bolusUnits

    companion object {
        fun of(doses: List<InsulinDose>) = InsulinDayTotals(
            basalUnits = doses.filter { it.kind == InsulinKind.BASAL }.sumOf { it.units },
            bolusUnits = doses.filter { it.kind == InsulinKind.BOLUS }.sumOf { it.units },
        )
    }
}

/**
 * One calendar day of insulin, with the carbohydrates recorded beside it.
 *
 * Beside, never combined: the grams are context for the units, and a single
 * figure mixing them would be meaningless. [uncountedMeals] is carried because a
 * day's grams means something different when three of its meals were never
 * counted, and a total with no such note invites reading it as the whole day.
 */
data class InsulinDay(
    val date: LocalDate,
    val totals: InsulinDayTotals,
    val doseCount: Int,
    val carbGrams: Double,
    val countedMeals: Int,
    val uncountedMeals: Int,
) {
    val hasCarbs: Boolean get() = countedMeals > 0 || uncountedMeals > 0
}

/**
 * A day-by-day summary, most recent first.
 *
 * Grouped in [zone] rather than by UTC day, because "how much did I take
 * yesterday" is a question about the calendar the person lives in. Days with
 * nothing recorded are left out rather than shown as zero: no insulin logged is
 * not the same statement as no insulin taken, and this is a diary, not a chart.
 */
object InsulinDiary {

    fun byDay(
        doses: List<InsulinDose>,
        carbs: List<CarbEntry>,
        zone: ZoneId,
        limitDays: Int = Int.MAX_VALUE,
    ): List<InsulinDay> {
        fun dayOf(millis: Long): LocalDate =
            Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

        val dosesByDay = doses.groupBy { dayOf(it.givenAtMillis) }
        val carbsByDay = carbs.groupBy { dayOf(it.eatenAtMillis) }

        return (dosesByDay.keys + carbsByDay.keys)
            .sortedDescending()
            .take(limitDays)
            .map { date ->
                val forDay = dosesByDay[date].orEmpty()
                val meals = carbsByDay[date].orEmpty()
                val (counted, uncounted) = CarbEntry.countedOf(meals)
                InsulinDay(
                    date = date,
                    totals = InsulinDayTotals.of(forDay),
                    doseCount = forDay.size,
                    carbGrams = CarbEntry.totalGrams(meals),
                    countedMeals = counted,
                    uncountedMeals = uncounted,
                )
            }
    }
}
