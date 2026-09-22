package com.hostu404.trilliontracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * One series, so no legend — the row it sits in names it.
 * 2dp stroke, recessive, with a single end marker for "you are here".
 */
@Composable
fun Sparkline(
    values: List<Double>,
    color: Color,
    modifier: Modifier = Modifier
) {
    if (values.size < 2) return

    Canvas(modifier = modifier) {
        val min = values.min()
        val max = values.max()
        val span = (max - min).takeIf { it > 0.0 } ?: 1.0

        val stepX = size.width / (values.size - 1).toFloat()
        // Inset so the 2dp stroke and the end dot are never clipped.
        val pad = 4.dp.toPx()
        val usableH = (size.height - pad * 2).coerceAtLeast(1f)

        fun pointAt(index: Int): Offset {
            val norm = ((values[index] - min) / span).toFloat()
            return Offset(
                x = index * stepX,
                y = pad + (1f - norm) * usableH
            )
        }

        val path = Path().apply {
            val first = pointAt(0)
            moveTo(first.x, first.y)
            for (i in 1 until values.size) {
                val p = pointAt(i)
                lineTo(p.x, p.y)
            }
        }

        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = 2.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )

        val end = pointAt(values.size - 1)
        drawCircle(color = color, radius = 4.dp.toPx() / 2f, center = end)
    }
}
