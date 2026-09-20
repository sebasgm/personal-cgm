package dev.cgm.core

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
        fun parseUnits(text: String): Double? {
            val normalised = text.trim().replace(',', '.')
            if (normalised.isEmpty()) return null
            val value = normalised.toDoubleOrNull() ?: return null
            return if (value.isFinite()) value else null
        }

        /** Trims and drops an empty note, so blank and absent are the same thing. */
        fun cleanNote(note: String?): String? = note?.trim()?.takeIf { it.isNotEmpty() }
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
