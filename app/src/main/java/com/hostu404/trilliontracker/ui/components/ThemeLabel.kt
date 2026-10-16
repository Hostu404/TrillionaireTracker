package com.hostu404.trilliontracker.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/** Themes that carry a glyph beside their name. */
private val THEME_GLYPHS = mapOf("Alien News" to "👽")

/** A news theme's name, with its glyph (greyscale, like everything else) when it has one. */
@Composable
fun ThemeLabel(theme: String, color: Color, fontSize: TextUnit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        THEME_GLYPHS[theme]?.let { glyph ->
            Text(text = glyph, fontSize = fontSize, modifier = Modifier.greyscale())
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = theme,
            color = color,
            fontSize = fontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private val greyscalePaint = Paint().apply {
    colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
}

/** Draws its content in greyscale — for colour emoji in a black-and-white app. */
private fun Modifier.greyscale(): Modifier = drawWithContent {
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(Offset.Zero, size), greyscalePaint)
        drawContent()
        canvas.restore()
    }
}
