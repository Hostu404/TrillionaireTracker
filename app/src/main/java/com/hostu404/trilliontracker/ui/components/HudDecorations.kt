package com.hostu404.trilliontracker.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hostu404.trilliontracker.ui.theme.TT
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The corner-bracket framing from the reference HUD panels: four short "L"
 * marks sitting just outside a panel's own border, one per corner, rather
 * than a full second outline. Reserved for a handful of hero panels — used
 * on every card it would read as noise, which is exactly the "too
 * distracting" failure mode this whole treatment is meant to avoid.
 */
fun Modifier.hudCorners(
    color: Color = TT.accentCyan,
    length: Dp = 9.dp,
    inset: Dp = 3.dp,
    strokeWidth: Dp = 1.5.dp
): Modifier = composed {
    drawWithContent {
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
 * The portrait-specific escalation of [chromaticAberration] — reserved for
 * [com.hostu404.trilliontracker.ui.screens.PersonDetailScreen]'s
 * [PersonHeader] photo, deliberately meant to feel a bit wrong to look at
 * rather than merely "glassy." Two things make it read as sickly instead of
 * stylish: the shift is much wider than the base effect, and it never sits
 * still — it steps between a resting and a peak width on an irregular beat
 * (no easing, no clean sine), so the fringing pulses like a bad signal
 * rather than settling into a fixed, ignorable frame. A small vertical creep
 * on top of the usual horizontal split (an eighth of the horizontal shift,
 * opposite sign each side) breaks the left/right symmetry a plain
 * double-exposure would have, which is what pushes it from "stylized" toward
 * "off." Every other chromatic-aberration use in the app stays on the calm,
 * static default — this one is deliberate main-character treatment for the
 * person the app is needling.
 *
 * **Stepped, not smoothly interpolated.** [drawWithContent] here does three
 * full-photo [Canvas.saveLayer] passes (one per color channel) every time
 * this recomposes — real, non-trivial GPU/compositing cost, and continuous
 * for as long as this screen is open. The original version drove [shiftPx]
 * with [animateFloat], which recomposes on every animation frame (up to
 * 60/sec) to interpolate smoothly between keyframes — i.e. up to 180
 * full-photo redraws a second just for this one effect. This version instead
 * snaps directly between the same keyframe values on a plain timed loop
 * (~8 steps/sec), cutting the redraw rate roughly 7-8x for the same shift
 * range and beat pattern — and the harder jump-cut between values, if
 * anything, reads slightly more "glitchy" than a buttery interpolation
 * would, not less.
 */
fun Modifier.sickeningChromaticAberration(
    baseShift: Dp = 2.4.dp,
    peakShift: Dp = 5.6.dp
): Modifier = composed {
    val basePx = with(LocalDensity.current) { baseShift.toPx() }
    val peakPx = with(LocalDensity.current) { peakShift.toPx() }

    // The exact same (value, time) beat as before, just stepped through
    // directly instead of handed to animateFloat for smooth interpolation —
    // see the doc comment above for why.
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
