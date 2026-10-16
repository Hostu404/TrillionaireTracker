package com.hostu404.trilliontracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hostu404.trilliontracker.ui.Format
import com.hostu404.trilliontracker.ui.theme.TT

/**
 * How a block is drawn — in black and white, the shape carries what colour
 * used to: a solid shade for time at a place, hatching for time on the
 * move (in flight / underway), an empty outline for time with no signal.
 */
enum class SegmentStyle { SOLID, HATCHED, OUTLINE }

/** One block on a [TimelineStrip] — a real span of wall-clock time, not a share of a total. */
data class TimelineSegment(
    val label: String,
    val startEpoch: Long,
    val endEpoch: Long,
    val color: Color,
    val style: SegmentStyle = SegmentStyle.SOLID
)

private data class LegendEntry(val label: String, val color: Color, val style: SegmentStyle, val totalSeconds: Long)

/**
 * "Time by location" as an actual chronological log instead of a part-to-
 * whole shape — a single strip spanning [windowStartEpoch] to [windowEndEpoch]
 * ("now"), laid out left-to-right in the order things really happened, each
 * block sized by its own true duration rather than normalized into a shared
 * total. A busy week (three short hops) and a quiet one (one long stay) read
 * as visibly different strips. The legend below sorts by total time
 * (biggest first) — the strip answers "when," the legend answers "how much,"
 * and names every block, so a shade is never the only way to tell two apart.
 */
@Composable
fun TimelineStrip(
    segments: List<TimelineSegment>,
    windowStartEpoch: Long,
    windowEndEpoch: Long,
    modifier: Modifier = Modifier,
    /** Off when something else on the page already names every block. */
    showLegend: Boolean = true
) {
    if (segments.isEmpty() || windowEndEpoch <= windowStartEpoch) return
    val spanSeconds = (windowEndEpoch - windowStartEpoch).toFloat().coerceAtLeast(1f)

    Column(modifier = modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(20.dp)
        ) {
            val gapPx = 2.dp.toPx()

            for (seg in segments) {
                val x0 = ((seg.startEpoch - windowStartEpoch).toFloat() / spanSeconds) * size.width
                val x1 = ((seg.endEpoch - windowStartEpoch).toFloat() / spanSeconds) * size.width
                val left = x0.coerceIn(0f, size.width)
                val right = x1.coerceIn(0f, size.width)
                val w = (right - left - gapPx).coerceAtLeast(0f)
                if (w <= 0f) continue
                drawSegment(seg.style, seg.color, Offset(left, 0f), Size(w, size.height))
            }

            // "Now" — the live edge of the strip.
            val capX = size.width - 1f
            drawLine(
                color = TT.accent,
                start = Offset(capX, -4.dp.toPx()),
                end = Offset(capX, size.height + 4.dp.toPx()),
                strokeWidth = 1.dp.toPx()
            )
        }

        Spacer(Modifier.height(3.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = "${Format.duration(windowEndEpoch - windowStartEpoch)} ago",
                color = TT.inkMuted,
                fontSize = 10.sp
            )
            Text(text = "now", color = TT.accent, fontSize = 10.sp)
        }

        if (!showLegend) return@Column

        Spacer(Modifier.height(6.dp))

        val legend = remember(segments) {
            segments
                .groupBy { Triple(it.label, it.color, it.style) }
                .map { (key, group) ->
                    LegendEntry(key.first, key.second, key.third, group.sumOf { it.endEpoch - it.startEpoch })
                }
                .sortedByDescending { it.totalSeconds }
        }
        val legendTotal = legend.sumOf { it.totalSeconds }.coerceAtLeast(1L)

        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            legend.forEach { entry ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SegmentSwatch(color = entry.color, style = entry.style)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = entry.label,
                        color = TT.inkSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = Format.duration(entry.totalSeconds),
                        color = TT.inkPrimary,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = Format.percent(entry.totalSeconds.toDouble() / legendTotal.toDouble()),
                        color = TT.inkMuted,
                        fontSize = 11.sp,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(40.dp)
                    )
                }
            }
        }
    }
}

/** A small square drawn the way a block of that [style] is drawn on the strip. */
@Composable
fun SegmentSwatch(color: Color, style: SegmentStyle, modifier: Modifier = Modifier) {
    Canvas(modifier.size(10.dp)) {
        drawSegment(style, color, Offset.Zero, size)
    }
}

private fun DrawScope.drawSegment(style: SegmentStyle, color: Color, topLeft: Offset, size: Size) {
    val edge = 1.dp.toPx()
    when (style) {
        SegmentStyle.SOLID -> drawRect(color = color, topLeft = topLeft, size = size)
        SegmentStyle.OUTLINE -> drawRect(
            color = color,
            topLeft = Offset(topLeft.x + edge / 2, topLeft.y + edge / 2),
            size = Size((size.width - edge).coerceAtLeast(0f), (size.height - edge).coerceAtLeast(0f)),
            style = Stroke(width = edge)
        )
        SegmentStyle.HATCHED -> {
            drawSegment(SegmentStyle.OUTLINE, color, topLeft, size)
            val step = 5.dp.toPx()
            clipRect(topLeft.x, topLeft.y, topLeft.x + size.width, topLeft.y + size.height) {
                var x = topLeft.x - size.height
                while (x < topLeft.x + size.width) {
                    drawLine(
                        color = color,
                        start = Offset(x, topLeft.y + size.height),
                        end = Offset(x + size.height, topLeft.y),
                        strokeWidth = edge
                    )
                    x += step
                }
            }
        }
    }
}
