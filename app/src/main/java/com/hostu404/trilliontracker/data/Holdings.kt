package com.hostu404.trilliontracker.data

/**
 * One publicly-traded holding: a ticker and a share count. Mirrors
 * `backend/holdings.json`'s schema exactly — that file carries the actual
 * SEC-filing/proxy sourcing notes per person, re-check there before trusting
 * a figure below as current.
 */
data class Holding(val ticker: String, val shares: Double)

/**
 * The subset of `backend/holdings.json`'s per-person record this client
 * actually needs to compute a live total — just enough to reproduce
 * `backend/snapshot_worker.py`'s `net_worth()`, not the full record
 * (icaoHex/tail, true-tax-rate history, etc. stay backend/[SeedData]-only,
 * since nothing here needs them to price a stake).
 */
data class HoldingsInfo(
    val holdings: List<Holding>,
    val privateStakesUsd: Double = 0.0,
    val cashUsd: Double = 0.0,
    val liabilitiesUsd: Double = 0.0
) {
    val tickers: List<String> get() = holdings.map { it.ticker }
}

/**
 * A live-computed net worth, re-anchored on every client-side quote poll
 * (see `TrackerViewModel.startLiveWealthPolling`) instead of waiting for the
 * next backend/seed [Snapshot]. Feeds [NetWorthEngine.project] exactly the
 * way a snapshot's own (netWorthUsd, driftPerSecondUsd, generatedAt) trio
 * does — this is just a fresher version of that same anchor, computed from
 * real stock prices on-device rather than published once a minute (or, in
 * seed mode, never at all).
 */
data class LiveWealthAnchor(
    val netWorthUsd: Double,
    val driftPerSecondUsd: Double,
    val anchorEpochSeconds: Long
)

/**
 * A hand-ported copy of `backend/holdings.json`'s share-count data — the
 * same real, filing-sourced figures the Python backend uses, just read into
 * the Kotlin client directly since this app runs snapshot-free (see
 * [Config.SNAPSHOT_URL]) and has no backend process to fetch quotes on its
 * behalf. Re-check against `backend/holdings.json` (and, beyond that,
 * EDGAR/the filings it cites) before trusting these as current — share
 * counts drift with every 10b5-1 sale and every filing amendment, same
 * caveat that file's own sourcing notes carry.
 *
 * `dell` and `ortega` are deliberately absent: `backend/holdings.json` never
 * got a confidently-sourced share count for either of them, so there's
 * nothing honest to port. They simply never enter the live pool and keep
 * ticking off their static seed drift, exactly like every person here did
 * before this feature existed — the same "leave the gap visible, don't
 * guess" rule this project applies to bios, vessel AIS, and everything else
 * it can't back with a real source.
 */
object Holdings {
    private val byId: Map<String, HoldingsInfo> = mapOf(
        "musk" to HoldingsInfo(
            holdings = listOf(
                Holding("TSLA", 717_112_739.0),
                // SpaceX and xAI merged and the combined entity IPO'd on
                // Nasdaq as SPCX in June 2026 (see backend/holdings.json's
                // sourcing note) — a real quoted ticker now, not a
                // hand-marked private stake. If Yahoo Finance doesn't
                // actually carry it, this holding just won't price, and liveNetWorth()
                // below correctly returns null for Musk that pass rather
                // than publish a partial total — he falls back to his seed
                // drift exactly like anyone else with an unpriced holding.
                Holding("SPCX", 6_400_000_000.0)
            )
        ),
        "huang" to HoldingsInfo(holdings = listOf(Holding("NVDA", 851_983_603.0))),
        "bezos" to HoldingsInfo(holdings = listOf(Holding("AMZN", 950_400_000.0))),
        "zuckerberg" to HoldingsInfo(holdings = listOf(Holding("META", 334_000_000.0))),
        "ellison" to HoldingsInfo(holdings = listOf(Holding("ORCL", 1_160_000_000.0))),
        "page" to HoldingsInfo(holdings = listOf(Holding("GOOGL", 746_000_000.0))),
        "brin" to HoldingsInfo(holdings = listOf(Holding("GOOGL", 697_000_000.0))),
        "ballmer" to HoldingsInfo(holdings = listOf(Holding("MSFT", 297_000_000.0)))
    )

    fun forId(id: String): HoldingsInfo? = byId[id]

    /** Every person this client can ever compute a live figure for. */
    val trackedIds: Set<String> get() = byId.keys

    /** Every ticker any tracked person holds, deduped — one concurrent request per ticker per poll. */
    val allTickers: List<String> = byId.values.flatMap { it.tickers }.distinct()

    /**
     * Sum(shares × price) + private marks + cash - liabilities — mirrors
     * `backend/snapshot_worker.py`'s `net_worth()` exactly, including its
     * all-or-nothing rule: null the instant any one holding's ticker is
     * missing from [prices], because a partial total would silently
     * understate someone's wealth while still looking like a complete,
     * trustworthy live number. A person with no [HoldingsInfo] at all also
     * returns null here.
     */
    fun liveNetWorth(id: String, prices: Map<String, Double>): Double? {
        val info = byId[id] ?: return null
        var total = 0.0
        for (h in info.holdings) {
            val price = prices[h.ticker.uppercase()] ?: return null
            total += h.shares * price
        }
        return total + info.privateStakesUsd + info.cashUsd - info.liabilitiesUsd
    }

    /**
     * Folds a fresh batch of [prices] into [previous]'s anchors, producing
     * the next generation of [LiveWealthAnchor]s. A person whose holdings
     * don't fully price this pass just keeps whatever anchor they already
     * had (or none, if they've never priced) rather than being reset — one
     * missed poll (a transient rate limit, a network blip, that one
     * ticker's request failing) shouldn't undo someone's live tracking, it should
     * only skip that tick's update.
     *
     * The drift for a brand-new anchor (nothing for that id in [previous]
     * yet) borrows [seedDriftPerSecondUsd] rather than starting flat at
     * zero, so the number keeps moving immediately instead of sitting still
     * for a full poll interval before a real observed rate exists. Every
     * anchor after the first computes its own drift from the actual change
     * in live value over the actual elapsed time between polls — a real
     * observed market rate, not the seed/backend's own estimate.
     */
    fun updateAnchors(
        previous: Map<String, LiveWealthAnchor>,
        prices: Map<String, Double>,
        nowEpochSeconds: Long,
        seedDriftPerSecondUsd: (String) -> Double
    ): Map<String, LiveWealthAnchor> {
        if (prices.isEmpty()) return previous
        val updated = previous.toMutableMap()
        for (id in trackedIds) {
            val liveValue = liveNetWorth(id, prices) ?: continue
            val prior = updated[id]
            val elapsed = prior?.let { nowEpochSeconds - it.anchorEpochSeconds } ?: 0L
            val drift = if (prior != null && elapsed > 0) {
                (liveValue - prior.netWorthUsd) / elapsed
            } else {
                seedDriftPerSecondUsd(id)
            }
            updated[id] = LiveWealthAnchor(liveValue, drift, nowEpochSeconds)
        }
        return updated
    }
}
