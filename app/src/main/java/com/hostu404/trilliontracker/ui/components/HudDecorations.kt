package com.hostu404.trilliontracker.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hostu404.trilliontracker.ui.theme.TT

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
 * still — an infinite [keyframes] loop throbs the split between a resting
 * and a peak width on an irregular beat (no easing, no clean sine), so the
 * fringing pulses like a bad signal rather than settling into a fixed,
 * ignorable frame. A small vertical creep on top of the usual horizontal
 * split (an eighth of the horizontal shift, opposite sign each side) breaks
 * the left/right symmetry a plain double-exposure would have, which is what
 * pushes it from "stylized" toward "off." Every other chromatic-aberration
 * use in the app stays on the calm, static default — this one is deliberate
 * main-character treatment for the person the app is needling.
 */
fun Modifier.sickeningChromaticAberration(
    baseShift: Dp = 2.4.dp,
    peakShift: Dp = 5.6.dp
): Modifier = composed {
    val basePx = with(LocalDensity.current) { baseShift.toPx() }
    val peakPx = with(LocalDensity.current) { peakShift.toPx() }
    val infinite = rememberInfiniteTransition(label = "sicklyShift")
    val shiftPx by infinite.animateFloat(
        initialValue = basePx,
        targetValue = basePx,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 2900
                basePx at 0
                peakPx at 260
                basePx * 0.55f at 620
                peakPx * 0.8f at 900
                basePx at 1250
                peakPx at 1650
                basePx * 0.4f at 1950
                peakPx * 0.65f at 2300
                basePx at 2900
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "shift"
    )

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
