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
import com.hostu404.trilliontracker.data.StooqClient
import com.hostu404.trilliontracker.data.TrackerRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TrackerUiState(
    val snapshot: Snapshot? = null,
    val nowMillis: Long = System.currentTimeMillis(),
    val loading: Boolean = true,
    val usingSeed: Boolean = true,
    val error: String? = null,
    /**
     * One entry per person [Holdings] currently has a fully-priced live
     * total for — see `TrackerViewModel.startLiveWealthPolling`. Anyone
     * missing here (an unpriced holding this poll, or nobody-tracked at all,
     * like `dell`/`ortega`) just falls back to the snapshot/seed projection
     * in [projected], exactly as if this feature didn't exist for them.
     */
    val liveWealthAnchors: Map<String, LiveWealthAnchor> = emptyMap()
) {
    val people: List<Person> get() = snapshot?.people.orEmpty()

    /**
     * [people], live-sorted by [projected] net worth rather than however the
     * snapshot/seed happened to list them. The tracker screen's rank numbers
     * and row order both come from this, not [people] directly — a seed
     * list is authored in a fixed order (today, already-descending by
     * static net worth), but once live market drift moves people at
     * different rates that static order stops matching who's actually
     * ahead. Recomputed every tick alongside everything else this state
     * already ticks; an overtake anywhere in the field — including right at
     * the rank 10/11 boundary — shows up here as an ordinary reorder, which
     * the screen then animates (see `Modifier.animateItem()` on
     * `PersonRow`) rather than special-casing any particular rank.
     */
    val rankedPeople: List<Person> get() = people.sortedByDescending { projected(it) }

    val leader: Person? get() = people.maxByOrNull { projected(it) }

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
     * Live sum of every tracked person's [projected] net worth — the same
     * per-tick local projection each person's own card already uses,
     * just added together. Recomputes on every [nowMillis] tick, same as
     * an individual figure would.
     */
    val totalProjectedWealth: Double get() = people.sumOf { projected(it) }

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

    /** True only while someone is actually over the line right now. */
    val hasLiveTrillionaire: Boolean
        get() = people.any { projected(it) >= (snapshot?.thresholdUsd ?: Double.MAX_VALUE) }
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
     * whichever one person happens to be on screen. One batched Stooq
     * request per interval covers every tracked ticker at once
     * ([Holdings.allTickers]), so this stays a single small request no
     * matter how many people end up priceable. `StooqClient.fetchQuotes`
     * runs before the first [delay] here too, same "fetch immediately on
     * open" shape as the flight tracker, so wealth starts updating the
     * moment the app launches rather than after a full interval first.
     * Parks in [awaitAppForeground] while backgrounded, same as the other
     * two loops in this class.
     */
    private fun startLiveWealthPolling() = viewModelScope.launch {
        while (true) {
            awaitAppForeground()
            val prices = StooqClient.fetchQuotes(Holdings.allTickers)
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
