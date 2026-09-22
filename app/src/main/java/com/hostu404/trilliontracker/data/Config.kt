package com.hostu404.trilliontracker.data

object Config {

    /**
     * Point this at the JSON your backend publishes (see /backend in this repo).
     *
     * Leave it blank and the app runs on the embedded seed snapshot, so it builds
     * and demos in the emulator with no server. Set it and nothing else changes —
     * that is the whole point of the snapshot design.
     *
     * `.github/workflows/snapshot.yml` is the ready-to-go free version of
     * "your backend": a GitHub Actions job that runs `snapshot_worker.py`
     * every 5 minutes and publishes its output to GitHub Pages, no server
     * of your own required. Once that workflow has actually run at least
     * once against a pushed repo with Pages turned on (Settings → Pages →
     * Deploy from a branch → main → /docs), this becomes:
     *
     *     "https://hostu404.github.io/TrillionaireTracker/snapshot.json"
     *
     * Left blank until then on purpose — pointing at a URL that isn't live
     * yet just means an error chip on every launch instead of the seed
     * data working quietly, which is a worse default than what this app
     * ships with today.
     */
    const val SNAPSHOT_URL: String = ""

    /** How often the client re-reads the CDN copy. */
    const val POLL_INTERVAL_SECONDS: Long = 60L

    /** How often the on-screen number re-renders between polls. */
    const val TICK_INTERVAL_MILLIS: Long = 80L

    /**
     * How often the client asks Stooq for fresh stock prices to compute
     * live net worth (see [Holdings], `StooqClient`, and
     * `TrackerViewModel.startLiveWealthPolling`). One batched request for
     * every tracked ticker at once, not one per person — matches the
     * cadence [com.hostu404.trilliontracker.data.OpenSkyClient]'s own live
     * position polling already uses for the same "actually feels live,
     * still a tiny, keyless, rate-limit-friendly request" balance.
     */
    const val LIVE_WEALTH_POLL_INTERVAL_MILLIS: Long = 20_000L

    /**
     * Global annual price tag for (approximately) ending extreme poverty
     * worldwide, in USD — $318B/year at the World Bank's $2.15-a-day
     * extreme-poverty line, from Sahoo, Blumenstock, Niehaus, Selker &
     * Wager, "What Would It Cost to End Extreme Poverty?" (NBER Working
     * Paper 34583, Dec 2025, rev. Sep 2026 — nber.org/papers/w34583), as
     * reported by UC Berkeley News (Feb 2026). The paper's own directly
     * studied figure is $211B/year across 34 countries representing 76%
     * of the world's poor; $318B is that same result extrapolated to the
     * whole world (~0.3% of global GDP) — the headline number used here.
     * Like the app's other social-comparison stats, this is a real, cited
     * estimate — a theoretical transfer cost, not a claim about how aid
     * would actually be delivered — used only as the divisor for a "what
     * this money could buy" comparison.
     */
    const val ANNUAL_POVERTY_ELIMINATION_COST_USD: Double = 318_000_000_000.0

    /** [ANNUAL_POVERTY_ELIMINATION_COST_USD] spread evenly across the year. */
    const val DAILY_POVERTY_ELIMINATION_COST_USD: Double = ANNUAL_POVERTY_ELIMINATION_COST_USD / 365.0

    /** Where the "open live map" button sends people. We don't host the pin. */
    fun liveMapFor(icaoHex: String): String =
        "https://globe.adsbexchange.com/?icao=$icaoHex"
}
