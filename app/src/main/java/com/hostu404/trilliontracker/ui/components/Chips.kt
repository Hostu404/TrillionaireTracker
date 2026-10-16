package com.hostu404.trilliontracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hostu404.trilliontracker.ui.Format
import com.hostu404.trilliontracker.ui.theme.TT

/**
 * Every status in this app is a glyph plus a word.
 *
 * The good and critical steps sit about ΔE 4 apart under deuteranopia, so colour
 * alone would be unreadable for a chunk of users. The glyph carries the meaning;
 * the colour only reinforces it.
 */
@Composable
fun StatusChip(
    glyph: String,
    label: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .border(1.dp, color, RectangleShape)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = glyph, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(4.dp))
        Text(text = label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

/** Today's move as plain text: green up, red down, grey when nothing moved. */
@Composable
fun DayChange(deltaUsd: Double, suffix: String, fontSize: TextUnit = 12.sp) {
    Text(
        text = when {
            deltaUsd == 0.0 -> "– no change"
            deltaUsd > 0 -> "▲ ${Format.signedCompactUsd(deltaUsd)}"
            else -> "▼ ${Format.signedCompactUsd(deltaUsd)}"
        } + suffix,
        color = when {
            deltaUsd == 0.0 -> TT.inkMuted
            deltaUsd > 0 -> TT.up
            else -> TT.down
        },
        fontSize = fontSize,
        maxLines = 1
    )
}

/**
 * The 1dp line between sections of one bordered sheet: white edge to edge
 * between sections, or grey and inset ([subtle]) between parts of one.
 */
@Composable
fun SheetLine(subtle: Boolean = false) {
    Box(
        Modifier
            .then(if (subtle) Modifier.padding(horizontal = 12.dp) else Modifier)
            .fillMaxWidth()
            .height(1.dp)
            .background(if (subtle) TT.grid else TT.border)
    )
}
