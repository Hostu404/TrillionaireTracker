package com.hostu404.trilliontracker.ui.components

import android.os.Build
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A left-edge swipe-to-go-back gesture, layered on top of Navigation
 * Compose's own system-back handling rather than replacing it — the
 * hardware back key, 3-button nav, and a real device's own OS-level edge
 * gesture all keep working exactly as before through NavHost's automatic
 * OnBackPressedDispatcher wiring; this only adds a second path to the same
 * [onBack] call. It exists because that system gesture isn't always
 * reachable in every place this app runs: the Android Studio emulator, in
 * particular, can run in gesture-nav mode with no on-screen back affordance
 * at all and no way to swipe in from outside its own window edge, which is
 * exactly the "no back button visible" case that came up testing this app
 * there. Applied per-screen at the [MainActivity] call sites for the two
 * pushed destinations ("person/…", "familyHistory/…") — never the root
 * "tracker" screen, which never had a back button either.
 *
 * Deliberately modeled on [rubberBandPhotoDrag]'s two safety rules, since
 * that's this codebase's own already-proven answer to "how do we detect a
 * deliberate gesture without stealing an ordinary one":
 *  - Only a touch that goes down within [edgeWidth] of the left edge is
 *    considered at all — a drag starting anywhere else on screen (the vast
 *    majority of it) is completely untouched by this modifier, so normal
 *    scrolling, tapping, and the profile-photo pull above are unaffected.
 *  - Nothing is consumed until the finger has moved at least [activationSlop],
 *    and — see the direction check below — isn't overwhelmingly vertical;
 *    every event before that is left unconsumed, so a genuine vertical
 *    scroll that happens to start in the edge strip is still free to be
 *    claimed by the [LazyColumn] underneath instead.
 *
 * **The direction check is deliberately biased toward "this is an edge-
 * swipe," not a neutral 45° split — a real flick on a real phone isn't
 * perfectly horizontal.** Confirmed on a signed release build on a real
 * device, not just the emulator this was originally built for: an ordinary
 * one-handed flick from the left edge reads as "down scroll" far more often
 * than as "back," because a thumb swiping in from the edge naturally arcs —
 * real net vertical travel is common even when the gesture is clearly meant
 * as a sideways flick. A strict `abs(x) > abs(y)` split (the original
 * version of this check) rejects exactly that as "predominantly vertical"
 * and hands it straight to the list's own scroll handling, which is what
 * "still not registering, it just scrolls" actually was. This only needs to
 * be lenient in one direction, though: the edge strip is narrow and
 * reserved (24dp of a whole screen's width), so a *genuine* vertical scroll
 * gesture essentially never needs to start precisely there, which is what
 * makes it safe to require much less horizontal travel, relative to
 * vertical, before still calling it an edge-swipe — see [DIRECTION_RATIO]
 * below.
 *
 * **Runs on [PointerEventPass.Initial], not the default Main — this is a
 * real bug fix, not a style choice.** [PersonDetailScreen] and
 * [FamilyHistoryScreen] are each, almost edge-to-edge, a [LazyColumn] of
 * full-width [hudTouchable] cards, so the edge strip this modifier watches
 * sits on top of whatever card happens to be there, not empty space. A
 * first version of this ran on the default Main pass (leaf-to-root: every
 * descendant gets to see and consume an event before this ancestor does)
 * and, on its own, correctly detected an edge swipe in isolation — but
 * never actually fired once wired up on a real screen, because a card's own
 * press/drag-cancel detection underneath it also watches for movement past
 * a touch-slop threshold (to know when to cancel its ripple rather than
 * register a tap), runs on that same Main pass, and — being the descendant —
 * always gets first look. It reliably crossed its own threshold and
 * consumed the drag before this modifier's later, Main-pass look at the
 * same gesture ever got an unconsumed event to measure. Initial pass
 * (root-to-leaf) flips that ordering: this modifier now sees every event
 * first, decides for itself whether a genuine horizontal edge-swipe is
 * underway, and only then consumes — at which point the card underneath
 * never sees that movement at all, so its press simply cancels the same way
 * it would for any other interrupted tap, instead of racing this modifier
 * for the same pointer.
 * Once committed, [onBack] fires at most once per gesture — guarded by
 * `fired` — the moment net rightward travel clears [activationSlop] plus
 * [triggerDistance], rather than waiting for the finger to lift; an edge
 * swipe should feel like it drives the transition, not like a delayed
 * on-release action.
 *
 * **Also excludes the edge strip from Android's own system gesture
 * navigation — without this, none of the above ever runs at all on a real
 * device.** Every fix so far (Initial pass, the lenient direction check)
 * addressed how this modifier's own Compose-level pointer input logic
 * behaves once it actually receives touch events — and still "wasn't
 * registering" after both, on a real signed build on a real phone. The
 * reason: on any device running Android's gesture navigation (the modern
 * default, not the 3-button nav this was implicitly assumed against), the
 * OS itself reserves a strip along the left/right screen edges system-wide
 * for its own back/home gesture, and swallows touches starting there before
 * they ever reach an app's window — this modifier's `pointerInput` simply
 * never saw them, no matter how its internal logic was tuned. This is a
 * documented Android platform behavior, not a bug in this app: an app opts
 * a specific region back out of that system reservation with
 * `View.setSystemGestureExclusionRects` (API 29+; a real device is
 * overwhelmingly likely to be past that floor even though this app's
 * `minSdk` is 26, so the call below is version-guarded rather than assumed
 * safe). The exclusion rect is set only while a screen using this modifier
 * is on-screen and enabled, matching [edgeWidth]'s own strip exactly, and
 * cleared on dispose — so navigating back to the root tracker screen (which
 * never applies this modifier) restores the system's own edge gesture there
 * instead of leaving a stale, unexplained dead zone behind.
 *
 * **Confirmed in practice (2026-09-25): all three fixes above are necessary
 * but were never sufficient on their own — this also needs the device's own
 * System navigation setting to actually be Gesture navigation.** On a real
 * Moto Edge 20 Lite running a signed release build, none of the Initial-pass
 * fix, the lenient direction check, nor `systemGestureExclusionRects`
 * produced a working edge-swipe while the phone was set to 2/3-button
 * navigation — switching the phone itself to gesture navigation (with no
 * further code change) is what finally made it register. The exact
 * mechanism by which button-nav mode keeps this modifier's `pointerInput`
 * from ever seeing the touch isn't confirmed (`systemGestureExclusionRects`
 * only un-reserves gesture-nav's own edge strip, so whatever intercepts the
 * touch in button-nav mode — possibly an OEM-specific edge affordance on
 * this device, possibly something else — is a different, unaddressed path);
 * what's confirmed is only the workaround, not the cause. This is treated as
 * an acceptable, undocumented-further gap rather than something to keep
 * chasing: a button-nav user already has a dedicated back button, so this
 * modifier was always a bonus for gesture-nav users specifically, mirroring
 * how the system's own predictive-back gesture is itself unavailable in
 * button-nav mode too.
 */
/**
 * How much further a drag is allowed to travel vertically than horizontally
 * and still count as an edge-swipe attempt, once [edgeSwipeBack] has decided
 * to commit to one direction or the other. `2f` means vertical travel can be
 * up to twice horizontal travel (roughly a 63° angle off the horizontal —
 * i.e. only within about 27° of straight up/down gets read as a genuine
 * scroll instead) before this backs off — see that function's own doc
 * comment for why a real flick needs this much more tolerance than a plain
 * `abs(x) > abs(y)` 45° split gives it, and why it's safe to be this
 * generous specifically because the edge strip this applies to is narrow
 * and reserved.
 */
private const val EDGE_SWIPE_VERTICAL_TOLERANCE = 2f

fun Modifier.edgeSwipeBack(
    enabled: Boolean = true,
    edgeWidth: Dp = 32.dp,
    activationSlop: Dp = 18.dp,
    triggerDistance: Dp = 40.dp,
    onBack: () -> Unit
): Modifier = composed {
    val edgeWidthPx = with(LocalDensity.current) { edgeWidth.toPx() }
    val slopPx = with(LocalDensity.current) { activationSlop.toPx() }
    val triggerPx = with(LocalDensity.current) { triggerDistance.toPx() }
    val latestOnBack = rememberUpdatedState(onBack)

    if (!enabled) {
        this
    } else {
        val view = LocalView.current
        var heightPx by remember { mutableIntStateOf(0) }

        // See this function's own doc comment for why this exists at all —
        // without it, Android's system gesture navigation claims this same
        // edge strip first and this modifier's pointerInput below never
        // sees the touch. Re-set whenever the measured height or edge width
        // actually changes, not on every recomposition; cleared on dispose
        // so this doesn't leave a dead zone on a screen that navigates away
        // from this modifier entirely.
        DisposableEffect(view, edgeWidthPx, heightPx) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && heightPx > 0) {
                view.systemGestureExclusionRects =
                    listOf(android.graphics.Rect(0, 0, edgeWidthPx.roundToInt(), heightPx))
            }
            onDispose {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    view.systemGestureExclusionRects = emptyList()
                }
            }
        }

        this
            .onGloballyPositioned { heightPx = it.size.height }
            .pointerInput(edgeWidthPx, slopPx, triggerPx) {
                awaitEachGesture {
                    // PointerEventPass.Initial — see the doc comment above
                    // for why this can't be the default Main pass: a full-
                    // width card underneath is a descendant, and Main runs
                    // descendants before ancestors, so this would only ever
                    // see a drag a card's own press-cancel detection had
                    // already consumed.
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (down.position.x > edgeWidthPx) {
                        return@awaitEachGesture
                    }
                    val pointerId = down.id
                    var sinceDown = Offset.Zero
                    var committed = false
                    var fired = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                        if (!change.pressed) {
                            if (committed) change.consume()
                            break
                        }
                        if (!committed && change.isConsumed) {
                            // On Initial pass this ancestor sees every event
                            // before any descendant card could consume it,
                            // so reaching here with isConsumed already true
                            // would mean another Initial-pass ancestor above
                            // this one claimed it first — not the cards
                            // below, which is the case this used to (and
                            // needed to) guard against back when this ran on
                            // Main.
                            break
                        }
                        val delta = change.positionChange()
                        if (!committed) {
                            sinceDown += delta
                            if (sinceDown.getDistance() > slopPx) {
                                // See EDGE_SWIPE_VERTICAL_TOLERANCE's own doc
                                // comment — this is deliberately far more
                                // lenient than a neutral 45° `abs(x) > abs(y)`
                                // split, because a real flick from the edge
                                // reads as steeper than that far more often
                                // than not.
                                if (abs(sinceDown.x) * EDGE_SWIPE_VERTICAL_TOLERANCE > abs(sinceDown.y)) {
                                    committed = true
                                    change.consume()
                                } else {
                                    // Still overwhelmingly vertical even at
                                    // this generous ratio — leave it alone
                                    // for whatever scrollable sits
                                    // underneath.
                                    break
                                }
                            }
                        } else {
                            change.consume()
                            if (!fired && sinceDown.x > slopPx + triggerPx) {
                                fired = true
                                latestOnBack.value()
                            }
                        }
                    }
                }
            }
    }
}
