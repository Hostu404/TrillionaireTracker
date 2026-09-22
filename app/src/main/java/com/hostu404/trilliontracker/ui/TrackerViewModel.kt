package com.hostu404.trilliontracker.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hostu404.trilliontracker.data.Config
import com.hostu404.trilliontracker.data.Holdings
import com.hostu404.trilliontracker.data.LiveWealthAnchor
import com.hostu404.trilliontracker.data.NetWorthEngine
import com.hostu404.trilliontracker.data.Person
import com.hostu404.trilliontracker.data.Snapshot
import com.hostu404.trilliontracker.data.LiveQuoteClient
import com.hostu404.trilliontracker.data.TrackerRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How many people the app actually presents itself as tracking — see [TrackerUiState.topTen]. */
private const val DISPLAYED_RANK_COUNT = 10

data class TrackerUiState(
    val snapshot: Snapshot? = null,
    val nowMillis: Long = System.currentTimeMillis(),
    val loading: Boolean = true,
    val usingSeed: Boolean = true,
    val error: String? = null,
    /**
     * One entry per person [Holdings] currently has a fully-priced live
     * total for — see `TrackerViewModel.startLiveWealthPolling`. Anyone
     * missing here (an unpriced holding this poll, an unconvertible FX rate
     * for a non-USD holding like Ortega's `ITX.MC`, or nobody-tracked at
     * all) just falls back to the snapshot/seed projection in [projected],
     * exactly as if this feature didn't exist for them.
     */
    val liveWealthAnchors: Map<String, LiveWealthAnchor> = emptyMap()
) {
    /** Every person the backend/seed currently prices — see [topTen] for who's actually shown. */
    val people: List<Person> get() = snapshot?.people.orEmpty()

    /**
     * [people], live-sorted by [projected] net worth rather than however the
     * snapshot/seed happened to list them. This is the full tracked bench,
     * not necessarily what's on screen — see [topTen] for that. Recomputed
     * every tick alongside everything else this state already ticks; an
     * overtake anywhere in the field shows up here as an ordinary reorder,
     * which the screen then animates (see `Modifier.animateItem()` on
     * `PersonRow`) rather than special-casing any particular rank.
     */
    val rankedPeople: List<Person> get() = people.sortedByDescending { projected(it) }

    /**
     * The top [DISPLAYED_RANK_COUNT] of [rankedPeople] — what the tracker
     * screen's list, ranks, "biggest mover", and the wealth/poverty totals
     * all actually use. [people] can be a wider bench than this (see
     * `backend/holdings.json` — tracking more than 10 people, all priced
     * every pass, lets someone near the boundary overtake the displayed
     * #10 and surface here automatically the moment they do, rather than
     * needing a manual add "just in time"). Everything user-facing reads
     * this instead of [people]/[rankedPeople] directly so the app's own
     * "top 10" premise stays accurate even as the tracked bench grows.
     */
    val topTen: List<Person> get() = rankedPeople.take(DISPLAYED_RANK_COUNT)

    val leader: Person? get() = topTen.firstOrNull()

    /**
     * A [LiveWealthAnchor] wins over the snapshot/seed figure whenever one
     * exists for this person — it's the fresher, real-stock-price-driven
     * source (re-anchored roughly every [Config.LIVE_WEALTH_POLL_INTERVAL_MILLIS],
     * vs. the snapshot's own [Snapshot.nextUpdateIn]/seed-static drift).
     * Same clamped [NetWorthEngine.project] math either way.
     */
    fun projected(person: Person): Double {
        liveWealthAnchors[person.id]?.let { anchor ->
            return NetWorthEngine.project(anchor, nowMillis)
        }
        val snap = snapshot ?: return person.netWorthUsd
        return NetWorthEngine.project(person, snap.generatedAt, nowMillis)
    }

    /** True while [person]'s figure is a live, market-price-anchored one rather than the seed/snapshot's. */
    fun isLive(personId: String): Boolean = liveWealthAnchors.containsKey(personId)

    /** The drift currently backing [projected]'s number for this person — live-observed when [isLive], seed/snapshot otherwise. */
    fun driftPerSecond(person: Person): Double =
        liveWealthAnchors[person.id]?.driftPerSecondUsd ?: person.driftPerSecondUsd

    fun personById(id: String): Person? = people.firstOrNull { it.id == id }

    /**
     * Live sum of [topTen]'s [projected] net worth — the same per-tick local
     * projection each person's own card already uses, just added together.
     * Deliberately [topTen], not [people]: this is presented everywhere in
     * the UI as "the top 10's combined wealth," so a wider tracked bench
     * (see [topTen]'s doc comment) shouldn't quietly inflate it with people
     * who aren't even shown. Recomputes on every [nowMillis] tick, same as
     * an individual figure would.
     */
    val totalProjectedWealth: Double get() = topTen.sumOf { projected(it) }

    /**
     * [totalProjectedWealth] expressed as a "days of a poverty-free world"
     * figure — how many days this much money would cover at
     * [Config.DAILY_POVERTY_ELIMINATION_COST_USD], the daily rate implied by
     * a real, cited annual estimate for ending extreme poverty worldwide.
     * A what-this-money-could-buy comparison against a published cost
     * estimate, not a literal claim that this exact plan would run for
     * exactly this many days — see [Config.ANNUAL_POVERTY_ELIMINATION_COST_USD]
     * for the source and caveats. The UI is responsible for labeling it as
     * a comparison.
     */
    val daysOfPovertyFreeWorld: Double
        get() = totalProjectedWealth / Config.DAILY_POVERTY_ELIMINATION_COST_USD

    /** True only while someone in [topTen] is actually over the line right now. */
    val hasLiveTrillionaire: Boolean
        get() = topTen.any { projected(it) >= (snapshot?.thresholdUsd ?: Double.MAX_VALUE) }
}

class TrackerViewModel(
    private val repository: TrackerRepository = TrackerRepository.default()
) : ViewModel() {

    private val _state = MutableStateFlow(TrackerUiState())
    val state: StateFlow<TrackerUiState> = _state.asStateFlow()

    init {
        startPolling()
        startTicking()
        startLiveWealthPolling()
    }

    /**
     * One network read per interval, shared by every element on screen.
     * Parks in [awaitAppForeground] while backgrounded instead of polling
     * for no one — see its own doc comment.
     */
    private fun startPolling() = viewModelScope.launch {
        while (true) {
            awaitAppForeground()
            val result = repository.load()
            _state.update {
                it.copy(
                    snapshot = result.snapshot,
                    loading = false,
                    usingSeed = result.fromSeed,
                    error = result.error
                )
            }
            val wait = result.snapshot.nextUpdateIn.takeIf { it > 0 }
                ?: Config.POLL_INTERVAL_SECONDS
            delay(wait * 1000L)
        }
    }

    /**
     * Local clock. Costs nothing upstream, but at a 80ms period it's still
     * a CPU wakeup 12.5 times a second — parks in [awaitAppForeground]
     * while backgrounded rather than ticking forever for a screen no one
     * can see.
     */
    private fun startTicking() = viewModelScope.launch {
        while (true) {
            awaitAppForeground()
            _state.update { it.copy(nowMillis = System.currentTimeMillis()) }
            delay(Config.TICK_INTERVAL_MILLIS)
        }
    }

    /**
     * Runs for the whole app session (not just while a detail screen is
     * open) so every billionaire's figure — the tracker list, the hero
     * card, any detail screen — updates off real stock prices, not just
     * whichever one person happens to be on screen. [LiveQuoteClient]
     * fires one concurrent request per tracked ticker
     * ([Holdings.allTickers]) each interval — Yahoo Finance's quote
     * endpoint has no batch form, unlike the Stooq endpoint this used to
     * call, but at around a dozen tracked symbols (stock tickers plus one
     * FX pair per non-USD currency in use) that's still cheap. `LiveQuoteClient.fetchQuotes`
     * runs before the first [delay] here too, same "fetch immediately on
     * open" shape as the flight tracker, so wealth starts updating the
     * moment the app launches rather than after a full interval first.
     * Parks in [awaitAppForeground] while backgrounded, same as the other
     * two loops in this class.
     */
    private fun startLiveWealthPolling() = viewModelScope.launch {
        while (true) {
            awaitAppForeground()
            val prices = LiveQuoteClient.fetchQuotes(Holdings.allTickers)
            if (prices.isNotEmpty()) {
                val nowEpochSeconds = System.currentTimeMillis() / 1000
                _state.update { current ->
                    current.copy(
                        liveWealthAnchors = Holdings.updateAnchors(
                            previous = current.liveWealthAnchors,
                            prices = prices,
                            nowEpochSeconds = nowEpochSeconds,
                            seedDriftPerSecondUsd = { id -> current.personById(id)?.driftPerSecondUsd ?: 0.0 }
                        )
                    )
                }
            }
            delay(Config.LIVE_WEALTH_POLL_INTERVAL_MILLIS)
        }
    }

    fun refreshNow() = viewModelScope.launch {
        val result = repository.load()
        _state.update {
            it.copy(
                snapshot = result.snapshot,
                loading = false,
                usingSeed = result.fromSeed,
                error = result.error
            )
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { TrackerViewModel() }
        }
    }
}
