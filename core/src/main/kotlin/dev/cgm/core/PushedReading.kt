package dev.cgm.core

/**
 * Validates a reading that arrived from somewhere we do not control.
 *
 * A polled source is asked a question and its answer has a shape we verified once.
 * A pushed reading is whatever another app decided to send, on its own schedule,
 * from a version we have never seen — so the checks here are not defensive
 * programming, they are the contract.
 *
 * Kept out of the Android layer so it can be tested without a device, and kept
 * generic rather than named after xDrip because a Nightscout source would need
 * exactly the same three judgements.
 */
object PushedReading {

    /**
     * The range a sensor can physically report.
     *
     * xDrip+ sends 0 to mean "no value", and in some configurations sends its raw
     * uncalibrated figure. Either one stored as a reading puts a spike in the
     * history that no later fix removes — the rollups have already absorbed it.
     */
    const val MIN_MGDL = 10.0
    const val MAX_MGDL = 600.0

    /**
     * Builds a reading, or null when what arrived is not one.
     *
     * Null rather than an exception: a malformed broadcast from another app is a
     * thing to ignore, not a failure of this one.
     */
    fun of(
        mgdl: Double?,
        timestampMillis: Long?,
        trend: TrendArrow,
        nowMillis: Long,
    ): GlucoseReading? {
        if (mgdl == null || mgdl.isNaN() || mgdl < MIN_MGDL || mgdl > MAX_MGDL) return null

        return GlucoseReading(
            valueMgdl = mgdl,
            timestampMillis = timestampOf(timestampMillis, nowMillis),
            trend = trend,
        )
    }

    /**
     * When the reading was taken, as far as we can tell.
     *
     * A missing or nonsensical timestamp becomes now. That is a small lie — the
     * reading is a few seconds older than that — and the alternative is a reading
     * at the epoch, which every age, freshness and staleness calculation in the app
     * would read as fifty years old and act on.
     *
     * A timestamp in the future is two apps disagreeing about the clock, not a
     * prediction, so it is pulled back to now rather than dropped: the value is
     * fine and a negative age is not survivable downstream.
     */
    internal fun timestampOf(timestampMillis: Long?, nowMillis: Long): Long = when {
        timestampMillis == null || timestampMillis <= 0 -> nowMillis
        timestampMillis > nowMillis -> nowMillis
        else -> timestampMillis
    }
}
