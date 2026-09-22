package com.hostu404.trilliontracker.data

import kotlin.math.abs

/**
 * Makes the number live without making the network busy.
 *
 * The backend gives us a value and a drift rate once a minute. Between those
 * snapshots the client extrapolates locally, so the display ticks continuously
 * while upstream call volume stays flat. This is the same trick a debt clock
 * uses, and like a debt clock it is a projection — the UI labels it as one.
 *
 * There's a second, fresher anchor now too: for anyone [Holdings] has a
 * priced holding set for, `TrackerViewModel` re-anchors a [LiveWealthAnchor]
 * on every client-side Stooq quote poll (see [StooqClient],
 * `Holdings.updateAnchors`) instead of waiting for the next backend/seed
 * snapshot. Same clamped extrapolation either way — see the two [project]
 * overloads below — just a different, more frequently refreshed source for
 * the (value, drift, anchor-time) triple it extrapolates from.
 *
 * The projection is clamped so a stale snapshot (or a bad live quote) can
 * never run away with itself.
 */
object NetWorthEngine {

    /** Never project further than this past the snapshot. */
    private const val MAX_PROJECTION_SECONDS = 600.0

    /** Never let the projection move more than this fraction off the last real value. */
    private const val MAX_DRIFT_FRACTION = 0.02

    /**
     * The actual math, parameterized on a bare (value, drift, anchor-time)
     * triple instead of a [Person] — so the exact same clamped projection
     * backs both a snapshot-anchored figure and a [LiveWealthAnchor]-backed
     * one below, rather than two copies of this logic drifting apart.
     */
    fun project(netWorthUsd: Double, driftPerSecondUsd: Double, anchorEpoch: Long, nowEpochMillis: Long): Double {
        val elapsed = ((nowEpochMillis / 1000.0) - anchorEpoch)
            .coerceIn(0.0, MAX_PROJECTION_SECONDS)

        val raw = driftPerSecondUsd * elapsed
        val cap = abs(netWorthUsd) * MAX_DRIFT_FRACTION
        val bounded = raw.coerceIn(-cap, cap)

        return netWorthUsd + bounded
    }

    fun project(person: Person, snapshotEpoch: Long, nowEpochMillis: Long): Double =
        project(person.netWorthUsd, person.driftPerSecondUsd, snapshotEpoch, nowEpochMillis)

    /**
     * Same clamped projection, anchored on a [LiveWealthAnchor] (a live,
     * stock-price-computed value re-observed every quote poll) instead of
     * the backend/seed snapshot. See `TrackerUiState.projected` for which
     * anchor wins when both exist for a person.
     */
    fun project(anchor: LiveWealthAnchor, nowEpochMillis: Long): Double =
        project(anchor.netWorthUsd, anchor.driftPerSecondUsd, anchor.anchorEpochSeconds, nowEpochMillis)

    /** 0f..1f progress toward the threshold. */
    fun progressToThreshold(value: Double, thresholdUsd: Double): Float =
        (value / thresholdUsd).coerceIn(0.0, 1.0).toFloat()

    fun shortfall(value: Double, thresholdUsd: Double): Double =
        (thresholdUsd - value).coerceAtLeast(0.0)

    /**
     * A rough linear "at this rate" projection — seconds until [currentValue]
     * would reach [thresholdUsd] if [driftPerSecondUsd] held perfectly
     * steady, which it never will. This is the same honest-estimate spirit
     * as the backend's flight-destination guess: a straight-line
     * extrapolation from the latest snapshot, not a forecast, and the UI
     * must label it as one. Null when there's nothing meaningful to show —
     * already over the line, or the current drift never gets there
     * (flat or moving the wrong way).
     */
    fun secondsToThreshold(currentValue: Double, driftPerSecondUsd: Double, thresholdUsd: Double): Long? {
        if (currentValue >= thresholdUsd) return null
        if (driftPerSecondUsd <= 0.0) return null
        return ((thresholdUsd - currentValue) / driftPerSecondUsd).toLong()
    }
}
