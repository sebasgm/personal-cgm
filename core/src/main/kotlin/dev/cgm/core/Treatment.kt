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
