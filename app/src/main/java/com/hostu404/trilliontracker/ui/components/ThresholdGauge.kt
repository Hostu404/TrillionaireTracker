package com.hostu404.trilliontracker.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hostu404.trilliontracker.ui.theme.TT

/**
 * Distance to $1,000,000,000,000, as one bar.
 *
 * The track carries a hairline tick at the threshold so the target is a place on
 * the bar rather than an implied edge.
 */
@Composable
fun ThresholdGauge(
    progress: Float,
    leftLabel: String,
    rightLabel: String,
    barColor: Color = TT.series,
    modifier: Modifier = Modifier
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(600),
        label = "threshold-progress"
    )

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
        ) {
            val r = CornerRadius(4.dp.toPx(), 4.dp.toPx())

            drawRoundRect(
                color = TT.grid,
                topLeft = Offset.Zero,
                size = Size(size.width, size.height),
                cornerRadius = r
            )

            val w = size.width * animated
            if (w > 0f) {
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset.Zero,
                    size = Size(w.coerceAtLeast(8.dp.toPx()), size.height),
                    cornerRadius = r
                )
            }

            // The line itself.
            drawRect(
                color = TT.inkMuted,
                topLeft = Offset(size.width - 1.5f, -2.dp.toPx()),
                size = Size(1.5f, size.height + 4.dp.toPx())
            )
        }

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = leftLabel,
                color = TT.inkSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = rightLabel,
                color = TT.inkMuted,
                fontSize = 12.sp
            )
        }
    }
}
