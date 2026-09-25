package com.hostu404.trilliontracker.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.hostu404.trilliontracker.ui.theme.TT
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The corner-bracket framing from the reference HUD panels: four short "L"
 * marks sitting just outside a panel's own border, one per corner, rather
 * than a full second outline. Reserved for a handful of hero panels — used
 * on every card it would read as noise, which is exactly the "too
 * distracting" failure mode this whole treatment is meant to avoid.
 */
// Lint's UnnecessaryComposedModifier check flagged the composed { } this
// used to be wrapped in: composed exists to let a Modifier factory call
// @Composable functions (remember, LocalContext.current, and the like) to
// build instance-specific state, but drawWithContent below never does
// that — every value it reads is a plain parameter, not composition
// state — so the wrapper bought nothing here except making this modifier
// non-skippable (composed forces Compose to re-evaluate and re-allocate
// the whole modifier chain on every recomposition, instead of reusing the
// same Modifier instance when nothing it depends on changed). Returning
// drawWithContent directly is the same visual result at strictly lower
// cost.
fun Modifier.hudCorners(
    color: Color = TT.accentCyan,
    length: Dp = 9.dp,
    inset: Dp = 3.dp,
    strokeWidth: Dp = 1.5.dp
): Modifier = drawWithContent {
    drawContent()
    val len = length.toPx()
    val gap = inset.toPx()
    val strokeW = strokeWidth.toPx()
    val w = size.width
    val h = size.height

    // top-left
    drawLine(color, Offset(gap, gap + len), Offset(gap, gap), strokeWidth = strokeW)
    drawLine(color, Offset(gap, gap), Offset(gap + len, gap), strokeWidth = strokeW)
    // top-right
    drawLine(color, Offset(w - gap - len, gap), Offset(w - gap, gap), strokeWidth = strokeW)
    drawLine(color, Offset(w - gap, gap), Offset(w - gap, gap + len), strokeWidth = strokeW)
    // bottom-left
    drawLine(color, Offset(gap, h - gap - len), Offset(gap, h - gap), strokeWidth = strokeW)
    drawLine(color, Offset(gap, h - gap), Offset(gap + len, h - gap), strokeWidth = strokeW)
    // bottom-right
    drawLine(color, Offset(w - gap - len, h - gap), Offset(w - gap, h - gap), strokeWidth = strokeW)
    drawLine(color, Offset(w - gap, h - gap - len), Offset(w - gap, h - gap), strokeWidth = strokeW)
}

/**
 * A near-invisible scan-line texture for the whole app — thin horizontal
 * lines at a very low alpha, the one purely decorative flourish that runs
 * behind every screen rather than on a specific panel. Kept static (no
 * animated flicker/roll): this app already redraws on every tick from live
 * data, and an animated overlay on top of that would fight for attention
 * with the numbers, not support them.
 */
@Composable
fun ScanlineOverlay(
    modifier: Modifier = Modifier,
    lineColor: Color = TT.accentCyan.copy(alpha = 0.028f),
    spacing: Dp = 3.dp
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val step = spacing.toPx()
        var y = 0f
        while (y < size.height) {
            drawLine(
                color = lineColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f
            )
            y += step
        }
    }
}

private fun channelMatrix(r: Float, g: Float, b: Float) = ColorMatrix(
    floatArrayOf(
        r, 0f, 0f, 0f, 0f,
        0f, g, 0f, 0f, 0f,
        0f, 0f, b, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )
)

private val redChannelFilter = ColorFilter.colorMatrix(channelMatrix(1f, 0f, 0f))
private val greenChannelFilter = ColorFilter.colorMatrix(channelMatrix(0f, 1f, 0f))
private val blueChannelFilter = ColorFilter.colorMatrix(channelMatrix(0f, 0f, 1f))

/**
 * A subtle red/blue fringe around whatever this is applied to — the classic
 * "poor man's" chromatic aberration: the same content drawn three times,
 * each pass isolated to one color channel via [ColorMatrix] and nudged a
 * couple of pixels apart, then composited back together additively
 * ([BlendMode.Plus]). Deliberately not [androidx.compose.ui.graphics.RenderEffect]/
 * RuntimeShader — those need API 31+ and this app's minSdk is 26, so a
 * shader-based version would need its own pre-31 fallback anyway, and this
 * plain-Canvas approach already works everywhere with no branch to maintain.
 *
 * Put this *after* a `.clip(...)` in the modifier chain (as on the portrait
 * in [com.hostu404.trilliontracker.ui.screens.PersonDetailScreen]) so the
 * fringing shows up on the photo's internal contrast edges and the outer
 * silhouette (the circle) stays a clean line, rather than the color split
 * spilling outside it.
 */
fun Modifier.chromaticAberration(shift: Dp = 0.9.dp): Modifier = composed {
    val shiftPx = with(LocalDensity.current) { shift.toPx() }
    val paint = remember { Paint() }
    drawWithContent {
        // `translate { ... }`'s lambda receiver is a plain DrawScope, which
        // doesn't expose drawContent() (that's ContentDrawScope-only) —
        // capturing `this` here as an explicit receiver sidesteps that,
        // while still drawing through the same underlying canvas/transform
        // translate set up, so the offset still applies.
        val contentScope = this
        val bounds = Rect(Offset.Zero, size)
        val canvas = drawContext.canvas
        paint.blendMode = BlendMode.Plus

        paint.colorFilter = redChannelFilter
        canvas.saveLayer(bounds, paint)
        translate(left = -shiftPx) { contentScope.drawContent() }
        canvas.restore()

        paint.colorFilter = greenChannelFilter
        canvas.saveLayer(bounds, paint)
        contentScope.drawContent()
        canvas.restore()

        paint.colorFilter = blueChannelFilter
        canvas.saveLayer(bounds, paint)
        translate(left = shiftPx) { contentScope.drawContent() }
        canvas.restore()
    }
}

/**
 * A photo grade for [com.hostu404.trilliontracker.ui.screens.PersonDetailScreen]'s
 * hero portrait — the "universal filter" that makes a real, naturally-lit
 * photograph read as part of this HUD's own palette instead of a plain
 * photo with an effect glued on top of it. Two passes folded into one
 * matrix: desaturate toward 40% (a real photo's natural hues otherwise
 * clash hard against the app's near-monochrome cyan-on-black system), then
 * push the remaining color toward that system specifically — red pulled
 * back, blue pushed up, everything darkened a touch and lifted slightly out
 * of true black on the green/blue channels only, landing close to
 * [TT.surface]'s own near-black teal rather than a neutral gray-black.
 *
 * Deliberately a plain affine [ColorMatrix], not a shader/[RenderEffect] —
 * same reasoning as [chromaticAberration]'s doc comment (minSdk 26, no
 * pre-31 fallback to maintain). A true duotone (mapping shadows and
 * highlights to two fixed colors) needs a lookup curve a 4x5 matrix can't
 * express; this is the matrix-only approximation of that idea — a
 * consistent color cast, not a literal two-color remap — which is enough to
 * make the photo feel like it belongs to the same system.
 *
 * Applied via [coil.compose.AsyncImage]'s own `colorFilter` parameter (baked
 * into the photo's own draw call), with [sickeningChromaticAberration]
 * layered on as a `Modifier` on top of that — so the aberration effect's
 * three re-draws pick up the already-graded pixels, not the raw photo.
 */
val hudPhotoGradeFilter: ColorFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            0.3248f, 0.3091f, 0.0311f, 0f, 0f,
            0.1315f, 0.7740f, 0.0445f, 0f, 4f,
            0.1644f, 0.5519f, 0.4713f, 0f, 8f,
            0f, 0f, 0f, 1f, 0f
        )
    )
)

/**
 * The portrait-specific escalation of [chromaticAberration] — reserved for
 * [com.hostu404.trilliontracker.ui.screens.PersonDetailScreen]'s
 * [PersonHeader] photo, deliberately meant to feel a bit wrong to look at
 * rather than merely "glassy." Calmed down 2026-09-24 (shift range halved,
 * vertical creep removed, beat slowed) alongside adding [hudPhotoGradeFilter],
 * in response to the combination reading as too jarring against the rest of
 * the HUD — then restored back to its full original intensity 2026-09-25,
 * on direct feedback that the calmer version had lost too much of the
 * intended queasiness. The wide shift, the vertical creep that breaks
 * left/right symmetry, and the faster, irregular 9-keyframe beat are all
 * back exactly as they were originally tuned. Every other chromatic-
 * aberration use in the app stays on the calm, fully static default
 * ([chromaticAberration]) — this one is deliberate main-character treatment
 * for whoever's profile is open, and is meant to stand out from that calm
 * baseline.
 *
 * **Restoring the intensity did not reintroduce the old performance cost —
 * those were always two separate things, not one dial.** [drawWithContent]
 * here does three full-photo [Canvas.saveLayer] passes (one per color
 * channel) every time this recomposes — real, non-trivial GPU/compositing
 * cost, continuous for as long as this screen is open. What actually made
 * an early version of this expensive was driving [shiftPx] with
 * [animateFloat], which recomposes on every animation frame (up to 60/sec)
 * to interpolate smoothly between keyframes — up to 180 full-photo redraws
 * a second just for this one effect. That was already replaced, independent
 * of the 2026-09-24 calm-down, with the stepped approach below: snapping
 * directly between keyframe values on a plain timed loop (~8 steps/sec),
 * cutting the redraw rate roughly 7-8x. The 2026-09-24 change only ever
 * touched the keyframe *values* (how far, how often) — never this stepped
 * mechanism — so restoring those values back to their original numbers
 * costs exactly the same per-second redraw rate as the calmed-down version
 * did. Full queasiness, same frame budget.
 */
fun Modifier.sickeningChromaticAberration(
    baseShift: Dp = 2.4.dp,
    peakShift: Dp = 5.6.dp
): Modifier = composed {
    val basePx = with(LocalDensity.current) { baseShift.toPx() }
    val peakPx = with(LocalDensity.current) { peakShift.toPx() }

    // The original, more erratic 9-keyframe beat (2.9s cycle) — restored
    // 2026-09-25 alongside the shift defaults above. Still driven through
    // the same stepped LaunchedEffect loop below, not animateFloat — see
    // the doc comment above for why that distinction is what keeps this
    // performance-neutral relative to the calmed-down version it replaces.
    val keyframeValues = remember(basePx, peakPx) {
        listOf(basePx, peakPx, basePx * 0.55f, peakPx * 0.8f, basePx, peakPx, basePx * 0.4f, peakPx * 0.65f, basePx)
    }
    val keyframeTimesMs = listOf(0, 260, 620, 900, 1250, 1650, 1950, 2300, 2900)
    val stepMs = 120L

    var shiftPx by remember { mutableFloatStateOf(basePx) }
    LaunchedEffect(keyframeValues) {
        val cycleMs = keyframeTimesMs.last()
        var elapsed = 0
        while (true) {
            val t = elapsed % cycleMs
            // Hold the most recently reached keyframe value rather than
            // interpolating — the "stepped" look described above.
            val idx = keyframeTimesMs.indexOfLast { it <= t }.coerceAtLeast(0)
            shiftPx = keyframeValues[idx]
            delay(stepMs)
            elapsed += stepMs.toInt()
        }
    }

    val paint = remember { Paint() }
    drawWithContent {
        val contentScope = this
        val bounds = Rect(Offset.Zero, size)
        val canvas = drawContext.canvas
        paint.blendMode = BlendMode.Plus

        // Restored 2026-09-25: a small vertical creep on top of the usual
        // horizontal split (an eighth of the horizontal shift, opposite sign
        // each side) breaks the left/right symmetry a plain double-exposure
        // would have — this asymmetry is exactly what pushes the effect from
        // "stylized" toward "off," per the doc comment above.
        paint.colorFilter = redChannelFilter
        canvas.saveLayer(bounds, paint)
        translate(left = -shiftPx, top = shiftPx * 0.14f) { contentScope.drawContent() }
        canvas.restore()

        paint.colorFilter = greenChannelFilter
        canvas.saveLayer(bounds, paint)
        contentScope.drawContent()
        canvas.restore()

        paint.colorFilter = blueChannelFilter
        canvas.saveLayer(bounds, paint)
        translate(left = shiftPx, top = -shiftPx * 0.14f) { contentScope.drawContent() }
        canvas.restore()
    }
}

/**
 * Measures whatever this wraps against a box [margin] bigger on every side
 * than what this node actually reports upward to its own parent — the
 * parent's layout is completely unaffected (this still occupies exactly the
 * space it always did), but the content drawn inside now genuinely extends
 * [margin] past that space in every direction, centred, with the overflow
 * just sitting outside this node's own reported bounds.
 *
 * Built specifically to pair with [rubberBandPhotoDrag] on the profile
 * photo, fixing a real reported bug: [ContentScale.Crop] alone sizes a
 * photo to *exactly* cover its frame with no spare pixels left over, so
 * panning a Crop-fit image with a plain offset just slides that whole
 * already-cropped rectangle sideways — which shows bare background on
 * whichever side it pulled away from, not more of the photo. Measuring the
 * photo against a quietly larger box instead means Crop scales up to cover
 * *that* larger box, so there are always genuine extra pixels of the
 * original photo sitting just past the visible frame — pulling now reveals
 * more of the actual image within [margin], never empty space (the frame's
 * own [Modifier.clip] still hides that extra margin at rest, exactly like
 * it hides anything else drawn outside the frame).
 *
 * The one real tradeoff, worth naming plainly: the photo reads very
 * slightly more zoomed-in at rest than an edge-to-edge Crop fit would,
 * since it's now scaled to cover a box bigger than the one it's actually
 * shown in. [rubberBandPhotoDrag] calls this with a margin a fixed 8dp
 * bigger than its own `maxPull` — comfortable buffer, not an exact
 * match — which keeps that cost close to invisible at rest; it's the
 * deliberate small compromise this pairing is built around, not a side
 * effect to hide. Private and only ever called from there for exactly that
 * reason: the margin only makes sense in terms of a `maxPull` it doesn't
 * have its own opinion about, so it's not exposed as a general knob.
 *
 * Assumes bounded incoming constraints (true at its one call site, a fixed-
 * size photo frame) — reporting [Constraints.Infinity] back up as this
 * node's own size would be invalid, so this isn't written as a
 * general-purpose "grow anything" utility.
 */
private fun Modifier.overscanBy(margin: Dp): Modifier = composed {
    val marginPx = with(LocalDensity.current) { margin.roundToPx() }
    layout { measurable, constraints ->
        val grown = Constraints(
            minWidth = (constraints.minWidth + marginPx * 2).coerceAtLeast(0),
            minHeight = (constraints.minHeight + marginPx * 2).coerceAtLeast(0),
            maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + marginPx * 2 else constraints.maxWidth,
            maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight + marginPx * 2 else constraints.maxHeight
        )
        val placeable = measurable.measure(grown)
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place(-marginPx, -marginPx)
        }
    }
}

/**
 * The profile photo's one physical-feeling interaction: drag it and it
 * pulls along with the finger inside its own frame; let go and it snaps
 * back to centre with an overshooting spring — a rubber band, not a real
 * repositioning tool. Nothing about a pull is ever kept — every gesture,
 * however it ends, resets to [Offset.Zero] the moment contact breaks.
 *
 * Two separate things keep this from firing during ordinary use of the rest
 * of the screen, per the brief this was built to ("restrained... shouldn't
 * accidentally activate"):
 *
 *  - Nothing about this — not even [dragging] — engages until the finger has
 *    travelled [activationSlop] from where it first touched down, tracked by
 *    hand in the gesture loop below rather than through
 *    `detectDragGestures`'s own built-in slop handling (used here in an
 *    earlier pass). That built-in slop is the platform's default touch-slop
 *    constant (~18dp), tuned to separate a deliberate drag from an
 *    incidental tap — a sound threshold in general, but a real reported
 *    problem here specifically: a normal
 *    scroll swipe that happens to start on top of this particular photo
 *    could cross that default distance before the scroll gesture above it
 *    ever got a chance to claim the touch, snatching an ordinary scroll into
 *    an accidental pull. [activationSlop] is deliberately well past that
 *    default for exactly that reason, and — critically — every event before
 *    it's crossed is left completely unconsumed, so a normal scroll starting
 *    on the photo is never even briefly intercepted; only a drag that
 *    commits to moving a real distance takes over from here.
 *  - Once a drag *does* commit, [rubberBandPull] compresses the raw finger
 *    travel into a small, hard-capped range ([maxPull]) on a
 *    diminishing-returns curve — pulling further and further makes less and
 *    less difference, the same "give" a real rubber band has near the end
 *    of its stretch, rather than the photo sliding wherever the finger
 *    actually goes. This is the visual half of "restrained"; the slop
 *    threshold above is the activation half.
 *
 * Live drag position is tracked as plain [mutableStateOf] — cheap,
 * synchronous, no coroutine per pointer-move event — and [springBack] (an
 * [Animatable]) only ever gets engaged once, at release, for the flick back
 * to zero. Reusing the drag [Animatable] for the live-drag phase too would
 * mean firing a `snapTo` coroutine on every single pointer-move callback for
 * as long as the finger is down, which is needless coroutine churn for a
 * value this modifier can just read directly instead.
 *
 * The resulting offset is applied with a plain lambda [Modifier.offset] —
 * this never has to reason about the photo's own frame edges itself,
 * whatever it produces just gets cropped by that frame's existing
 * [Modifier.clip] like any other overflow would be.
 *
 * Applies [overscanBy] to itself first, with a margin comfortably bigger
 * than [maxPull] — a real reported bug, fixed by pairing the two: without
 * spare pixels to pull into, dragging a plain [ContentScale.Crop] photo (no
 * slack left once it's scaled to exactly cover its frame) just slides the
 * whole already-cropped rectangle sideways, showing bare background on
 * whichever side it pulled away from instead of more of the photo. Folded
 * in here rather than left as a separate call a caller could forget to
 * chain alongside this one — see [overscanBy]'s own doc comment for the
 * (small, deliberate) tradeoff that fix costs.
 */
fun Modifier.rubberBandPhotoDrag(maxPull: Dp = 16.dp, activationSlop: Dp = 32.dp): Modifier = composed {
    val maxPullPx = with(LocalDensity.current) { maxPull.toPx() }
    val slopPx = with(LocalDensity.current) { activationSlop.toPx() }
    var rawDrag by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }
    val springBack = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val scope = rememberCoroutineScope()
    val overscanMargin = maxPull + 8.dp

    fun flickBack(from: Offset) {
        scope.launch {
            springBack.snapTo(from)
            springBack.animateTo(
                targetValue = Offset.Zero,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
        }
    }

    val liveOffset = if (dragging) rubberBandPull(rawDrag, maxPullPx) else springBack.value

    this
        .overscanBy(overscanMargin)
        .pointerInput(maxPullPx, slopPx) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val pointerId = down.id
                // Distance travelled since the finger went down, tallied
                // separately from [rawDrag] — this one only exists to decide
                // *whether* the gesture commits at all, and its own total
                // (including the slop distance itself) is deliberately
                // thrown away once it does, so the photo doesn't jump by
                // [activationSlop] the instant it engages — see rawDrag's
                // reset below.
                var sinceDown = Offset.Zero
                var committed = false
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                        if (!change.pressed) {
                            if (committed) change.consume()
                            break
                        }
                        val delta = change.positionChange()
                        if (!committed) {
                            sinceDown += delta
                            if (sinceDown.getDistance() > slopPx) {
                                committed = true
                                dragging = true
                                rawDrag = Offset.Zero
                                change.consume()
                            }
                            // Not committed yet: leave the event untouched.
                            // A normal scroll gesture that happens to have
                            // started on this photo is still free to claim
                            // it — see this function's own doc comment.
                        } else {
                            change.consume()
                            rawDrag += delta
                        }
                    }
                } finally {
                    // Covers a clean release AND any other way this gesture
                    // ends (the pointerInput coroutine getting cancelled by
                    // a recomposition, say) with the same one flick-back —
                    // no separate cancel-vs-end branch needed, since both
                    // mean the same thing here: whatever pull exists right
                    // now should spring back to zero.
                    if (committed) {
                        dragging = false
                        flickBack(rubberBandPull(rawDrag, maxPullPx))
                    }
                }
            }
        }
        .offset { IntOffset(liveOffset.x.roundToInt(), liveOffset.y.roundToInt()) }
}

/**
 * Diminishing-returns pull curve, applied per axis: approaches [maxPullPx]
 * asymptotically but never reaches or crosses it no matter how far [raw]
 * actually travels — the "restrained" half of [rubberBandPhotoDrag]'s
 * rubber-band feel; that function's spring-back on release is the other
 * half.
 */
private fun rubberBandPull(raw: Offset, maxPullPx: Float): Offset {
    fun axis(delta: Float): Float {
        if (maxPullPx <= 0f) return 0f
        val mag = abs(delta)
        val pulled = maxPullPx * (mag / (mag + maxPullPx))
        return if (delta < 0f) -pulled else pulled
    }
    return Offset(axis(raw.x), axis(raw.y))
}

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
 *  - Nothing is consumed until the finger has moved at least [activationSlop]
 *    AND that movement is predominantly horizontal — every event before
 *    that is left unconsumed, so a vertical scroll that happens to start in
 *    the edge strip is still free to be claimed by the [LazyColumn]
 *    underneath instead. If a descendant claims the gesture first (its
 *    change arrives already consumed, which happens here before this
 *    ancestor sees it — pointer events propagate leaf-to-root on the
 *    default [PointerEventPass.Main] this uses), this backs off rather than
 *    fighting over it.
 * Once committed, [onBack] fires at most once per gesture — guarded by
 * `fired` — the moment net rightward travel clears [activationSlop] plus
 * [triggerDistance], rather than waiting for the finger to lift; an edge
 * swipe should feel like it drives the transition, not like a delayed
 * on-release action.
 */
fun Modifier.edgeSwipeBack(
    enabled: Boolean = true,
    edgeWidth: Dp = 24.dp,
    activationSlop: Dp = 18.dp,
    triggerDistance: Dp = 56.dp,
    onBack: () -> Unit
): Modifier = composed {
    val edgeWidthPx = with(LocalDensity.current) { edgeWidth.toPx() }
    val slopPx = with(LocalDensity.current) { activationSlop.toPx() }
    val triggerPx = with(LocalDensity.current) { triggerDistance.toPx() }
    val latestOnBack = rememberUpdatedState(onBack)

    if (!enabled) {
        this
    } else {
        this.pointerInput(edgeWidthPx, slopPx, triggerPx) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (down.position.x > edgeWidthPx) {
                    return@awaitEachGesture
                }
                val pointerId = down.id
                var sinceDown = Offset.Zero
                var committed = false
                var fired = false
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                    if (!change.pressed) {
                        if (committed) change.consume()
                        break
                    }
                    if (!committed && change.isConsumed) {
                        // Some descendant (a scrollable, another gesture)
                        // already claimed this pointer — don't contest it.
                        break
                    }
                    val delta = change.positionChange()
                    if (!committed) {
                        sinceDown += delta
                        if (sinceDown.getDistance() > slopPx) {
                            if (abs(sinceDown.x) > abs(sinceDown.y)) {
                                committed = true
                                change.consume()
                            } else {
                                // Predominantly vertical — leave it alone for
                                // whatever scrollable sits underneath.
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

/**
 * The "this is an active computer, not a poster" layer that sits over the
 * background painting, between it and [ScanlineOverlay] — a faint drifting
 * coordinate grid plus a soft cyan scan-beam that sweeps top to bottom on a
 * loop. Distinct from [ScanlineOverlay] on purpose: that one is a static,
 * near-invisible print texture; this one visibly moves, which is what reads
 * as system chrome doing something rather than a fixed background pattern.
 * Kept slow (7s per sweep) and low-alpha (grid 0.05, beam 0.07) so it never
 * competes with the live numbers sitting on top of it — this is ambient
 * lighting, not another data layer.
 */
@Composable
fun DigitalFxOverlay(modifier: Modifier = Modifier) {
    val infinite = rememberInfiniteTransition(label = "digitalFx")
    val sweep by infinite.animateFloat(
        initialValue = -0.25f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweep"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val gridColor = TT.accentCyan.copy(alpha = 0.05f)
        val step = 46.dp.toPx()

        var x = 0f
        while (x < size.width) {
            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            x += step
        }
        var y = 0f
        while (y < size.height) {
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += step
        }

        val beamCenterY = size.height * sweep
        val beamHeight = size.height * 0.24f
        val top = beamCenterY - beamHeight / 2f
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, TT.accentCyan.copy(alpha = 0.07f), Color.Transparent),
                startY = top,
                endY = top + beamHeight
            ),
            topLeft = Offset(0f, top),
            size = Size(size.width, beamHeight)
        )
    }
}

/**
 * A static, low-alpha hex lattice etched behind a HUD card — the "circuit
 * board" backdrop from the HUD design pass (option A/D): ambient texture,
 * never brighter than needed to read at a glance, no animation of its own.
 * Draws in [drawBehind], so it always sits behind whatever this card's own
 * background/content paint — put this right after `.background(...)` in the
 * modifier chain, before `.border(...)`, so the border stays crisp on top
 * and any earlier `.clip(...)`/panel shape still clips the lattice to the
 * card's own chamfered corners.
 *
 * Tiled with the standard flat-top hex-grid formula: a tile is
 * `3 × hexRadius` wide and `sqrt(3) × hexRadius` tall, holding two hexagon
 * centres per tile — the minimal repeat unit that reconstructs a full
 * honeycomb once tiles wrap across each other's edges. Built as one [Path]
 * holding every hexagon rather than one `drawPath` call per hexagon, since a
 * typical card tiles into a couple hundred hexagons and — unlike
 * [honeycombGlowCell] below — nothing here reads animated state, so this
 * only actually runs once per real redraw, not once per frame.
 */
fun Modifier.honeycombBackdrop(
    color: Color = TT.accentCyan,
    alpha: Float = 0.10f,
    hexRadius: Dp = 16.dp,
    strokeWidth: Dp = 1.dp
): Modifier = composed {
    val radiusPx = with(LocalDensity.current) { hexRadius.toPx() }
    val strokePx = with(LocalDensity.current) { strokeWidth.toPx() }
    val lineColor = color.copy(alpha = alpha)
    drawBehind {
        if (radiusPx <= 0f) return@drawBehind
        val tileW = 3f * radiusPx
        val tileH = sqrt(3f) * radiusPx
        val cols = (size.width / tileW).toInt() + 2
        val rows = (size.height / tileH).toInt() + 2
        val path = Path()
        for (row in -1..rows) {
            for (col in -1..cols) {
                val baseX = col * tileW
                val baseY = row * tileH
                addHexagonTo(path, Offset(baseX, baseY + tileH / 2f), radiusPx)
                addHexagonTo(path, Offset(baseX + 1.5f * radiusPx, baseY), radiusPx)
            }
        }
        // The tiling above deliberately overshoots this element's own bounds
        // by a full tile on every side (the -1 start, the +2 col/row counts)
        // so a hexagon never gets cut off mid-edge at the tile boundary —
        // but with nothing clipping that overshoot, the pattern bled straight
        // through into whatever sits next to this card: the gap, a
        // neighbouring card, the row below it in a list. clipRect hard-stops
        // every stroke at this element's own rectangle so the lattice always
        // stays inside its own card, whatever shape its background/border use.
        clipRect {
            drawPath(path, color = lineColor, style = Stroke(width = strokePx))
        }
    }
}

private fun addHexagonTo(path: Path, center: Offset, radius: Float) {
    for (i in 0..5) {
        val angle = Math.toRadians((i * 60).toDouble())
        val x = center.x + radius * cos(angle).toFloat()
        val y = center.y + radius * sin(angle).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
}

/**
 * A soft, pulsing glow behind one spot on a card — from the same option-B/D
 * pass as [honeycombBackdrop] (no longer paired with it at call sites, but
 * built for the same "a status layer, not just texture" idea): flag a spot
 * near whatever data it's commenting on ("the no-signal chip", "the active
 * timeline segment") in that data's own status colour.
 *
 * [xFraction]/[yFraction] place the glow's centre as a fraction of this
 * element's own size (0f..1f each), so it stays pinned to roughly the same
 * visual spot regardless of exactly how tall the card ends up being.
 *
 * A plain radial-gradient wash rather than a blurred hexagon: real blur
 * ([androidx.compose.ui.graphics.RenderEffect]) needs API 31+ and this
 * app's minSdk is 26 — the same constraint already documented on
 * [chromaticAberration] above — and a soft circle reads as "glow" just as
 * well at this size.
 *
 * Stepped, not smoothly animated with `animateFloat`: same reasoning as
 * [sickeningChromaticAberration]'s own move away from it. An animated
 * `drawBehind` forces this whole node's entire draw phase — the honeycomb
 * lines included — to redraw every animation frame for as long as the card
 * is on screen, not just for a brief effect. A handful of held alpha steps
 * a few times a second reads as a believable pulse for a fraction of that
 * redraw cost.
 */
fun Modifier.honeycombGlowCell(
    xFraction: Float,
    yFraction: Float,
    color: Color,
    radius: Dp = 22.dp,
    stepMs: Long = 260L
): Modifier = composed {
    val radiusPx = with(LocalDensity.current) { radius.toPx() }
    val alphaSteps = remember { listOf(0.18f, 0.32f, 0.5f, 0.3f, 0.56f, 0.22f) }
    var alpha by remember { mutableFloatStateOf(alphaSteps.first()) }
    LaunchedEffect(alphaSteps, stepMs) {
        var i = 0
        while (true) {
            alpha = alphaSteps[i % alphaSteps.size]
            delay(stepMs)
            i++
        }
    }
    drawBehind {
        val center = Offset(size.width * xFraction, size.height * yFraction)
        // Placed near a corner/edge on purpose (see the doc comment above),
        // so the radial gradient's own radius routinely reaches past this
        // element's edge — clipRect keeps that bloom inside this card
        // instead of it washing over whatever sits next to it.
        clipRect {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)),
                    center = center,
                    radius = radiusPx
                ),
                radius = radiusPx,
                center = center
            )
        }
    }
}

/**
 * The touch affordance from the HUD design pass (option C/D): corner
 * ticks — the same "L"-bracket language as [hudCorners], just per-element
 * instead of per-panel — mark anything genuinely tappable at rest, and
 * pressing it stutters with the same hard-cut, unequal-step RGB-split
 * technique [sickeningChromaticAberration] uses, just looping only for as
 * long as the press lasts instead of running as an ambient multi-second
 * loop. "Twitchy" reserved for things you can actually touch, never applied
 * to static readouts — the whole point of the option-D pass. Brackets sit at
 * [cornerColorRest] well before any press, at an alpha bumped up from this
 * effect's first pass specifically so "this is a button" reads on its own,
 * not just as an afterthought once you're already pressing it.
 *
 * [elevation] adds a soft, static drop shadow — real elevation, not another
 * canvas trick, since (unlike the blur this file avoids elsewhere) shadow
 * elevation has worked since API 21 and costs nothing extra per frame.
 * Defaults to 0.dp (no shadow) on purpose: a bare inline text link (a
 * "Wikipedia ↗" caption sitting on its own, no fill or border) would just
 * show a stray rectangular shadow behind loose letters, which reads as a
 * rendering glitch, not depth. Pass a few dp of [elevation] and a matching
 * [shape] only where this decorates an actual filled/bordered panel — a
 * row, a card, an icon chip — so it visibly lifts off the flat panels around
 * it, the same way a real raised button would; everything else keeps the
 * corner ticks as its only affordance. The shadow is tinted with
 * [cornerColorRest] itself (a cyan-tinted lift reads as HUD chrome; a flat
 * grey Material shadow would not) — but that tint is only visible on API
 * 28+ (`Modifier.shadow`'s own floor for `ambientColor`/`spotColor`); below
 * it, this still draws a plain neutral shadow — never a hard failure, same
 * "degrade quietly on old API" rule as this file's other effects.
 *
 * Replaces a plain `Modifier.clickable { ... }` on the element it decorates
 * — don't chain both on the same node. [hudTouchable] already installs its
 * own `clickable` with the default ripple swapped out for the glitch, which
 * is this effect's own press feedback.
 */
fun Modifier.hudTouchable(
    cornerColorRest: Color = TT.accentCyan.copy(alpha = 0.55f),
    cornerColorPressed: Color = TT.accentCyan,
    cornerLength: Dp = 7.dp,
    cornerInset: Dp = 2.dp,
    cornerStrokeWidth: Dp = 1.4.dp,
    elevation: Dp = 0.dp,
    shape: Shape = RectangleShape,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    // Snaps between held keyframe values for as long as the element is
    // pressed; releasing resets to 0 immediately via the branch below, and
    // LaunchedEffect(pressed) cancels this loop's coroutine on that same
    // transition, so nothing keeps animating after the finger lifts.
    var shiftPx by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(pressed) {
        if (!pressed) {
            shiftPx = 0f
            return@LaunchedEffect
        }
        val keyframesPx = listOf(1f, -2.4f, 2f, -1.2f, 1.6f, 0f)
        var i = 0
        while (true) {
            shiftPx = keyframesPx[i % keyframesPx.size]
            delay(45L)
            i++
        }
    }

    val lenPx = with(LocalDensity.current) { cornerLength.toPx() }
    val gapPx = with(LocalDensity.current) { cornerInset.toPx() }
    val strokePx = with(LocalDensity.current) { cornerStrokeWidth.toPx() }
    val cornerColor = if (pressed) cornerColorPressed else cornerColorRest
    val paint = remember { Paint() }

    // Placed first so it sits behind everything chained after hudTouchable
    // too (background, border, honeycomb) — a shadow cast by the finished
    // panel's own silhouette, not just this node's bare content.
    val base = if (elevation > 0.dp) {
        Modifier.shadow(
            elevation = elevation,
            shape = shape,
            ambientColor = cornerColorRest,
            spotColor = cornerColorRest
        )
    } else {
        Modifier
    }

    base
        .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
        .drawWithContent {
            if (!pressed || shiftPx == 0f) {
                drawContent()
            } else {
                // Same additive per-channel offset technique as
                // [chromaticAberration], just gated to the press window.
                val contentScope = this
                val bounds = Rect(Offset.Zero, size)
                val canvas = drawContext.canvas
                paint.blendMode = BlendMode.Plus

                paint.colorFilter = redChannelFilter
                canvas.saveLayer(bounds, paint)
                translate(left = -shiftPx) { contentScope.drawContent() }
                canvas.restore()

                paint.colorFilter = greenChannelFilter
                canvas.saveLayer(bounds, paint)
                contentScope.drawContent()
                canvas.restore()

                paint.colorFilter = blueChannelFilter
                canvas.saveLayer(bounds, paint)
                translate(left = shiftPx) { contentScope.drawContent() }
                canvas.restore()
            }

            val w = size.width
            val h = size.height
            drawLine(cornerColor, Offset(gapPx, gapPx + lenPx), Offset(gapPx, gapPx), strokeWidth = strokePx)
            drawLine(cornerColor, Offset(gapPx, gapPx), Offset(gapPx + lenPx, gapPx), strokeWidth = strokePx)
            drawLine(cornerColor, Offset(w - gapPx - lenPx, gapPx), Offset(w - gapPx, gapPx), strokeWidth = strokePx)
            drawLine(cornerColor, Offset(w - gapPx, gapPx), Offset(w - gapPx, gapPx + lenPx), strokeWidth = strokePx)
            drawLine(cornerColor, Offset(gapPx, h - gapPx - lenPx), Offset(gapPx, h - gapPx), strokeWidth = strokePx)
            drawLine(cornerColor, Offset(gapPx, h - gapPx), Offset(gapPx + lenPx, h - gapPx), strokeWidth = strokePx)
            drawLine(cornerColor, Offset(w - gapPx - lenPx, h - gapPx), Offset(w - gapPx, h - gapPx), strokeWidth = strokePx)
            drawLine(cornerColor, Offset(w - gapPx, h - gapPx - lenPx), Offset(w - gapPx, h - gapPx), strokeWidth = strokePx)
        }
}
