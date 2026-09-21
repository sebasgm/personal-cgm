package dev.cgm.core

import kotlin.math.abs

/**
 * Clarke error grid zones.
 *
 * RMSE treats a 40 mg/dL error at 250 the same as one at 60. Clinically they are
 * nothing alike: the first is a number being slightly wrong, the second is the
 * difference between acting and not. The error grid is the standard way of saying
 * so, and it is why a model can have better RMSE and still be worse.
 */
enum class ClarkeZone {
    /** Within 20%, or both sides agree it is low. Clinically accurate. */
    A,

    /** Outside 20% but would not lead to different treatment. Benign. */
    B,

    /** Would prompt treatment that was not needed. */
    C,

    /** Would miss a low or a high that was really there. Dangerous. */
    D,

    /** Confuses low for high or high for low. The worst kind of wrong. */
    E;

    /** C, D and E are the ones that would change what someone did. */
    val isUnsafe: Boolean get() = this == C || this == D || this == E
}

object ClarkeErrorGrid {

    /**
     * Zone for a prediction against what actually happened.
     *
     * The canonical rule set, in the canonical order — the zones overlap, so the
     * sequence of tests is part of the definition rather than an implementation
     * choice.
     */
    fun zoneOf(referenceMgdl: Double, predictedMgdl: Double): ClarkeZone {
        val r = referenceMgdl
        val p = predictedMgdl

        if ((r <= 70 && p <= 70) || (r > 0 && abs(p - r) <= 0.2 * r)) return ClarkeZone.A

        if ((r >= 180 && p <= 70) || (r <= 70 && p >= 180)) return ClarkeZone.E

        if ((r in 70.0..290.0 && p >= r + 110) ||
            (r in 130.0..180.0 && p <= (7.0 / 5.0) * r - 182)
        ) return ClarkeZone.C

        if ((r >= 240 && p in 70.0..180.0) ||
            (r <= 175.0 / 3.0 && p in 70.0..180.0) ||
            (r in (175.0 / 3.0)..70.0 && p >= (6.0 / 5.0) * r)
        ) return ClarkeZone.D

        return ClarkeZone.B
    }
}
