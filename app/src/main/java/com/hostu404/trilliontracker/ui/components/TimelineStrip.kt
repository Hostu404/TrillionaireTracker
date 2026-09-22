package com.hostu404.trilliontracker.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hostu404.trilliontracker.ui.Format
import com.hostu404.trilliontracker.ui.theme.TT

/** One block on a [TimelineStrip] — a real span of wall-clock time, not a share of a total. */
data class TimelineSegment(
    val label: String,
    val startEpoch: Long,
    val endEpoch: Long,
    val color: Color
)

private data class LegendEntry(val label: String, val color: Color, val totalSeconds: Long)

/**
 * "Time by location" as an actual chronological log instead of a part-to-
 * whole shape — a single strip spanning [windowStartEpoch] to [windowEndEpoch]
 * ("now"), laid out left-to-right in the order things really happened, each
 * block sized by its own true duration rather than normalized into a shared
 * total. A busy week (three short hops) and a quiet one (one long stay) read
 * as visibly different strips instead of collapsing into the same ring —
 * something a proportional chart can't show and a systems-monitor readout
 * is built to. The legend below still sorts by total time (biggest first),
 * same as before — the strip answers "when," the legend answers "how much."
 *
 * Every block gets a matching soft-glow duplicate behind it (larger, low
 * alpha) rather than a real blur — the same plain-Canvas layering
 * [chromaticAberration] uses elsewhere, since minSdk 26 has no RenderEffect.
 * The bright cyan cap on the right edge marks "now" and
 * breathes gently, the one live element in an otherwise static readout, so
 * the strip reads as a monitor still watching rather than a printed report.
 */
@Composable
fun TimelineStrip(
    segments: List<TimelineSegment>,
    windowStartEpoch: Long,
    windowEndEpoch: Long,
    modifier: Modifier = Modifier
) {
    if (segments.isEmpty() || windowEndEpoch <= windowStartEpoch) return
    val spanSeconds = (windowEndEpoch - windowStartEpoch).toFloat().coerceAtLeast(1f)

    val infinite = rememberInfiniteTransition(label = "nowPulse")
    val nowAlpha by infinite.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "nowAlpha"
    )

    Column(modifier = modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(26.dp)
        ) {
            val barHeight = size.height
            val gapPx = 2.dp.toPx()
            val glowPad = 3.dp.toPx()

            for (seg in segments) {
                val x0 = ((seg.startEpoch - windowStartEpoch).toFloat() / spanSeconds) * size.width
                val x1 = ((seg.endEpoch - windowStartEpoch).toFloat() / spanSeconds) * size.width
                val left = x0.coerceIn(0f, size.width)
                val right = x1.coerceIn(0f, size.width)
                val w = (right - left - gapPx).coerceAtLeast(0f)
                if (w <= 0f) continue

                // Soft glow pass first — a taller, low-alpha duplicate.
                drawRect(
                    color = seg.color.copy(alpha = 0.22f),
                    topLeft = Offset(left, -glowPad),
                    size = Size(w, barHeight + glowPad * 2f)
                )
                // Crisp identity block on top.
                drawRect(
                    color = seg.color,
                    topLeft = Offset(left, 0f),
                    size = Size(w, barHeight)
                )
            }

            // "Now" cap — the live edge of the strip.
            val capX = size.width - 1f
            drawLine(
                color = TT.accentCyan.copy(alpha = nowAlpha),
                start = Offset(capX, -4.dp.toPx()),
                end = Offset(capX, barHeight + 4.dp.toPx()),
                strokeWidth = 2.dp.toPx()
            )
        }

        Spacer(Modifier.height(5.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = "${Format.duration(windowEndEpoch - windowStartEpoch)} ago",
                color = TT.inkMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "now",
                color = TT.accentCyan,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(Modifier.height(12.dp))

        val legend = remember(segments) {
            segments
                .groupBy { it.label to it.color }
                .map { (key, group) -> LegendEntry(key.first, key.second, group.sumOf { it.endEpoch - it.startEpoch }) }
                .sortedByDescending { it.totalSeconds }
        }
        val legendTotal = legend.sumOf { it.totalSeconds }.coerceAtLeast(1L)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            legend.forEach { entry ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(entry.color)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = entry.label,
                        color = TT.inkSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = Format.duration(entry.totalSeconds),
                        color = TT.inkPrimary,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = Format.percent(entry.totalSeconds.toDouble() / legendTotal.toDouble()),
                        color = TT.inkMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.width(36.dp)
                    )
                }
            }
        }
    }
}
