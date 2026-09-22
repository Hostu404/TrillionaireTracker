package com.hostu404.trilliontracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Chart-surface palette, dark steps — a Cyberpunk 2077-inspired terminal/HUD
 * read on top of the same structure the app started with: near-black planes,
 * a cyan identity colour, and status colours that stay reserved.
 *
 * [accentCyan] and [accentYellow] are chrome, not data: brackets, glow,
 * section labels, the odd highlighted figure. They're deliberately brighter
 * than anything [categorical] or [series] is allowed to be — a data mark has
 * to stay inside the dark-mode OKLCH lightness band (~0.48–0.67) to hold its
 * contrast and CVD-separation guarantees (validated with dataviz's
 * validate_palette.js), but chrome answers only to a plain WCAG text-contrast
 * check, so it can run brighter without misleading anyone about what's data.
 *
 * Status colours are reserved and never reused as series or chrome colours.
 * Every status still carries a glyph and a label — nothing here signals with
 * colour alone.
 */
object TT {
    val plane = Color(0xFF050507)

    /**
     * Panel fills carry ~85% alpha rather than being fully opaque — every
     * HUD card is meant to read as a pane of glass over the background
     * image, not a solid tile. Not used via MaterialTheme.colorScheme
     * anywhere (checked: nothing in this app reads colorScheme.surface/
     * surfaceVariant directly), so the alpha only ever shows up where a
     * screen paints TT.surface / TT.surfaceRaised itself.
     */
    val surface = Color(0xD90C1416)
    val surfaceRaised = Color(0xD9141F22)

    val inkPrimary = Color(0xFFF2FBFF)
    val inkSecondary = Color(0xFFA9C4C9)
    val inkMuted = Color(0xFF5C6B6E)

    val grid = Color(0xFF17262A)
    val baseline = Color(0xFF1E3236)

    /** Faint neon-cyan panel edge — every card gets this HUD-line border. */
    val border = Color(0x4D00E5FF)

    /** A stronger edge for a focused/hero panel, or for a corner bracket's glow. */
    val borderBright = Color(0x9900E5FF)

    /** Chrome only — brackets, section-label text, the scanline tint. Not a data colour. */
    val accentCyan = Color(0xFF00E5FF)

    /** Chrome only, used sparingly — the one "important" highlight (a selected tab, a key stat). */
    val accentYellow = Color(0xFFFCEE0A)

    /** System monospace, for numeric/code-like HUD readouts — never for prose. */
    val monoNumeric = FontFamily.Monospace

    /** Single-series colour for sparklines and the threshold gauge. */
    val series = Color(0xFF00A2BE)

    val good = Color(0xFF2ECC71)
    val warning = Color(0xFFFAB219)
    val critical = Color(0xFFFF3355)

    /**
     * Fixed categorical order, dark steps — a "Night City" neon run (cyan →
     * blue → indigo → violet → magenta → orange) rather than the generic
     * business-chart hue family (teal/gold/etc.) the previous six steps used —
     * those read as a spreadsheet regardless of how dark the surface was.
     * Re-derived by searching OKLCH space directly for the widest adjacent
     * separation along that neon arc, then confirmed with the dataviz skill's
     * validator: `node scripts/validate_palette.js
     * "#00A2BE,#0067B2,#7183FF,#7145B5,#CB6AB2,#E05014" --mode dark --surface
     * "#0C1416"` — lightness band, chroma floor, CVD separation (worst
     * adjacent ΔE 14.4) and the normal-vision floor (worst ΔE 17.0, clears
     * the 15 gate) all pass; slot 4 sits under the 3:1 contrast floor (2.86),
     * which is the documented "relief" case — legal because every user of
     * this palette (currently [com.hostu404.trilliontracker.ui.components.TimelineStrip])
     * ships a legend (exact label + duration + percentage per entry) as the
     * visible-labels relief channel. Assigned by entity identity (sorted
     * airport code), never by rank, so a slot doesn't repaint when the
     * underlying data shifts. [series] (slot 1) is included
     * here so identity charts share the same hue as single-series ones.
     */
    val categorical: List<Color> = listOf(
        series,                 // 1 cyan
        Color(0xFF0067B2),      // 2 blue
        Color(0xFF7183FF),      // 3 indigo
        Color(0xFF7145B5),      // 4 violet
        Color(0xFFCB6AB2),      // 5 orchid magenta
        Color(0xFFE05014)       // 6 neon orange — reserved for the "Other" fold slot
    )

    /**
     * The panel shape every HUD card uses instead of a plain rounded rect —
     * top-start and bottom-end corners cut at 45°, the other two square. Same
     * asymmetric-bevel look as the reference panels, and a straight
     * find/replace target for the old `RoundedCornerShape(x)` calls.
     */
    fun panelShape(cut: Dp = 10.dp): Shape = CutCornerShape(topStart = cut, bottomEnd = cut)
}

private val scheme = darkColorScheme(
    primary = TT.series,
    onPrimary = Color.White,
    background = TT.plane,
    onBackground = TT.inkPrimary,
    surface = TT.surface,
    onSurface = TT.inkPrimary,
    surfaceVariant = TT.surfaceRaised,
    onSurfaceVariant = TT.inkSecondary,
    outline = TT.baseline,
    error = TT.critical
)

private val typography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Start
    )
)

@Composable
fun TrillionaireTrackerTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // Deliberately dark in both system modes: the palette's dark steps were
    // validated against the dark surface and an automatic flip would not hold.
    MaterialTheme(
        colorScheme = scheme,
        typography = typography,
        content = content
    )
}
