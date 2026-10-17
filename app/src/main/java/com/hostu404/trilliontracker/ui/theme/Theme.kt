package com.hostu404.trilliontracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.em

/**
 * Back to basics: pure (OLED) black, white text, thin white borders,
 * monospace everywhere, Unicode symbols instead of icons. No background
 * image, glows or effects.
 *
 * The one colour in the app is today's gain or loss ([up]/[down]). Every
 * other status (airborne, signal lost, unverified...) is white and carries
 * its own glyph and word, so nothing ever signals with colour alone.
 *
 * The world map keeps its own colours for now (see WorldMapCard).
 */
object TT {
    val plane = Color.Black

    /** Panels are black like the page: the white border is what marks them out. */
    val surface = Color.Black
    val surfaceRaised = Color.Black

    val inkPrimary = Color.White
    val inkSecondary = Color(0xFFBDBDBD)
    val inkMuted = Color(0xFF8A8A8A)

    val grid = Color(0xFF2A2A2A)

    /** The thin white line round every panel. */
    val border = Color.White

    /** Headings, links and other chrome. */
    val accent = Color.White

    /** The line in the 7-day chart and the $1T bar. */
    val series = Color.White

    /** Status roles, all white: each one carries a glyph and a word too. */
    val warning = Color.White
    val critical = Color.White

    /** Today's gain or loss: the only colours in the app. */
    val up = Color(0xFF2ECC71)
    val down = Color(0xFFFF3355)

    /**
     * Greys for the time-by-location strip's segments, assigned by place
     * (sorted airport/port code), never by rank. The strip's legend names
     * every segment with its duration and share, so the shade is only a
     * helper, never the only way to tell two places apart.
     */
    val categorical: List<Color> = listOf(
        Color.White,
        Color(0xFFC8C8C8),
        Color(0xFF969696),
        Color(0xFF6E6E6E),
        Color(0xFF4E4E4E),
        Color(0xFF353535)   // reserved for the "Other" fold slot
    )

    /** Square corners on every panel. */
    val panelShape: Shape = RectangleShape
}

private val scheme = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    background = TT.plane,
    onBackground = TT.inkPrimary,
    surface = TT.surface,
    onSurface = TT.inkPrimary,
    surfaceVariant = TT.surfaceRaised,
    onSurfaceVariant = TT.inkSecondary,
    outline = TT.border,
    error = TT.down
)

/** Every Material text style, in monospace. */
private val typography = Typography().run {
    // Monospace everywhere, and line height tied to the text's own size:
    // Material's fixed line heights (24sp for body text) left small text
    // sitting in tall, mostly empty rows.
    fun TextStyle.mono() = copy(fontFamily = FontFamily.Monospace, lineHeight = 1.3.em)
    Typography(
        displayLarge = displayLarge.mono(),
        displayMedium = displayMedium.mono(),
        displaySmall = displaySmall.mono(),
        headlineLarge = headlineLarge.mono(),
        headlineMedium = headlineMedium.mono(),
        headlineSmall = headlineSmall.mono(),
        titleLarge = titleLarge.mono(),
        titleMedium = titleMedium.mono(),
        titleSmall = titleSmall.mono(),
        bodyLarge = bodyLarge.mono(),
        bodyMedium = bodyMedium.mono(),
        bodySmall = bodySmall.mono(),
        labelLarge = labelLarge.mono(),
        labelMedium = labelMedium.mono(),
        labelSmall = labelSmall.mono()
    )
}

@Composable
fun TrillionaireTrackerTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // Black in both system modes, on purpose.
    MaterialTheme(
        colorScheme = scheme,
        typography = typography,
        content = content
    )
}
