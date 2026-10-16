package com.hostu404.trilliontracker.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.hostu404.trilliontracker.ui.theme.TT

/** Photos are shown in black and white, like everything else. */
private val blackAndWhite = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/**
 * A person's photo in a bordered square, greyscale so a colour photo never
 * breaks the black-and-white page. Falls back to their initial.
 *
 * [photoUrl] is only ever set when Wikipedia's own thumbnail lives on
 * Commons (free-license by policy), so a missing photo means no free image
 * was available, not that we skipped checking.
 */
@Composable
fun PersonPhoto(
    name: String,
    photoUrl: String?,
    size: Dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .border(1.dp, TT.border, TT.panelShape)
            .padding(1.dp),
        contentAlignment = Alignment.Center
    ) {
        if (photoUrl != null) {
            AsyncImage(
                model = photoUrl,
                contentDescription = "Photo of $name",
                contentScale = ContentScale.Crop,
                // Biased toward the top so the square crop keeps the face,
                // whatever shape the original photo is.
                alignment = BiasAlignment(horizontalBias = 0f, verticalBias = -0.6f),
                placeholder = ColorPainter(TT.plane),
                colorFilter = blackAndWhite,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = name.firstOrNull()?.uppercase() ?: "?",
                color = TT.inkMuted,
                fontSize = (size.value * 0.38f).sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
