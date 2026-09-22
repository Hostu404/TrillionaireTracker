package com.hostu404.trilliontracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = glyph, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(4.dp))
        Text(text = label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun DeltaChip(
    deltaUsd: Double,
    formatted: String,
    modifier: Modifier = Modifier
) {
    val up = deltaUsd >= 0
    StatusChip(
        glyph = if (up) "▲" else "▼",
        label = formatted,
        color = if (up) TT.good else TT.critical,
        modifier = modifier
    )
}

@Composable
fun NoteChip(
    text: String,
    color: Color = TT.inkMuted,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        color = color,
        fontSize = 11.sp,
        modifier = modifier
            .border(1.dp, TT.border, RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}
