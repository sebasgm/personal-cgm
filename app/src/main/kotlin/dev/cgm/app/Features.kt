package dev.cgm.app

/**
 * Features switched off in the build.
 *
 * A constant rather than a deletion: the work stays in the tree, its tests keep
 * running, and turning it back on is one edit rather than an archaeology exercise
 * through the history.
 */
object Features {

    /**
     * The forecast: the dashed projection on the chart, its setting, its
     * acknowledgement, and the reliability card on Trends.
     *
     * Off at the owner's request until asked for again. Nothing about it was
     * wrong; it is simply not wanted in the next release.
     *
     * Flipping this to true restores all four surfaces at once — they all read
     * this flag rather than each carrying their own condition, so none of them
     * can come back on its own.
     */
    const val PROJECTION = false
}
