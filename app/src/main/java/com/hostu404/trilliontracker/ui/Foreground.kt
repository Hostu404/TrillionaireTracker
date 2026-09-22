package com.hostu404.trilliontracker.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Suspends until some part of the app is actually visible (`STARTED` or
 * above on the whole-process lifecycle, not just one Activity/Fragment).
 *
 * Every `while (true) { poll(); delay(...) }` loop in this app — the three
 * in `TrackerViewModel` (snapshot polling, the tick clock, live-wealth
 * polling) and the two `produceState` loops in `PersonDetailScreen` (live
 * flight position, live news) — calls this at the top of each pass. Without
 * it, `viewModelScope` and a composition's coroutine scope both keep running
 * exactly as before when the app is backgrounded or the screen locks:
 * nothing about them is tied to whether anyone can actually see the result,
 * so a phone left with this app merely open in the background would poll
 * Stooq/OpenSky/RSS and re-tick the clock forever, for no one, burning
 * battery and data. This makes every one of those loops park here instead
 * of doing real work while backgrounded, and resume the moment the app is
 * foregrounded again — immediately, not after a stale delay finishes.
 *
 * [ProcessLifecycleOwner] must be read on the main thread; the hop below is
 * a no-op if the caller is already there (every current caller is, via
 * `viewModelScope`'s default dispatcher or a composition's), but keeping it
 * explicit means this stays correct even called from somewhere that isn't.
 */
suspend fun awaitAppForeground() {
    while (true) {
        val visible = withContext(Dispatchers.Main.immediate) {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        if (visible) return
        delay(1_000L)
    }
}
