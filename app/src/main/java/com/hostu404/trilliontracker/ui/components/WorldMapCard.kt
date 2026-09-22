package com.hostu404.trilliontracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hostu404.trilliontracker.data.CountryShape
import com.hostu404.trilliontracker.data.FlightStatus
import com.hostu404.trilliontracker.data.OCEAN_LABELS
import com.hostu404.trilliontracker.data.WorldGeo
import com.hostu404.trilliontracker.ui.theme.TT

/**
 * A pin on [WorldMapCard]. [isLive] distinguishes what kind of position
 * [lat]/[lon] actually is:
 *
 *  - `true`, aircraft: a genuine live ADS-B fix from OpenSky, updating
 *    roughly every 20s while the plane is airborne and its detail screen
 *    is open — see `rememberLiveFlightPosition` in `PersonDetailScreen.kt`.
 *    This is the one place in the app a raw in-transit coordinate exists at
 *    all, fetched only for the one person being looked at, right now, never
 *    stored or shown anywhere else.
 *  - `true`, vessel, or aircraft with no live fix yet: sitting at an airport
 *    or port, at that granularity — the same "known stop, not a live feed"
 *    position this app has always shown.
 *  - `false`: underway/airborne with no current position to show (vessels
 *    always fall here — there's no free live AIS source this app can call
 *    the way [com.hostu404.trilliontracker.data.OpenSkyClient] covers
 *    aircraft) or signal has gone quiet; the pin marks the last confirmed
 *    position instead, and [caption] says so.
 */
data class MapPin(
    val label: String,
    val lat: Double,
    val lon: Double,
    /** "✈" or "⚓" — which of the two this pin is. */
    val glyph: String,
    val isLive: Boolean,
    val caption: String
)

/**
 * "Jump the map to this pin." [token] is what actually re-triggers the
 * camera move (see [WorldMapCard]'s `LaunchedEffect`) — bumped only on an
 * explicit tap (a [MapPin] clicked from [com.hostu404.trilliontracker.ui.screens.PersonDetailScreen]'s
 * aircraft/vessel cards), never as a side effect of the pin's own fields
 * changing tick to tick (caption/speed update constantly while airborne,
 * and re-centering the camera on every one of those would fight the
 * person's own pan/zoom instead of responding to their tap).
 */
data class MapFocusRequest(val token: Int, val pin: MapPin)

private data class LonLat(val lon: Double, val lat: Double)

private data class CountryRender(
    val name: String,
    val rings: List<List<Double>>,
    val centroid: LonLat
)

/** Equirectangular projection: plain lon/lat -> pixel, no distortion correction — "pretty basic" by design. */
private fun projectLonLat(lon: Double, lat: Double, mapWidth: Float, mapHeight: Float): Offset {
    val x = ((lon + 180.0) / 360.0) * mapWidth
    val y = ((90.0 - lat) / 180.0) * mapHeight
    return Offset(x.toFloat(), y.toFloat())
}

/**
 * Natural Earth's raw rings don't split at the antimeridian — a country
 * that straddles ±180° (Fiji, Russia, Antarctica in this dataset) has
 * consecutive points that jump straight from ~179° to ~-179°. Plotted
 * naively through [projectLonLat], which never wraps, that jump draws as
 * a straight line clear across the entire map instead of the short hop
 * over the date line it actually is — that's the stray horizontal line
 * cutting across the map. This splits a ring into pieces wherever a jump
 * like that occurs, so each piece gets its own path instead of one bogus
 * line connecting them across the whole width.
 */
private fun splitAtAntimeridian(ring: List<Double>): List<List<Double>> {
    val pieces = mutableListOf(mutableListOf<Double>())
    var i = 0
    while (i + 1 < ring.size) {
        val lon = ring[i]
        val lat = ring[i + 1]
        val current = pieces.last()
        if (current.size >= 2 && kotlin.math.abs(lon - current[current.size - 2]) > 180.0) {
            pieces.add(mutableListOf())
        }
        pieces.last().add(lon)
        pieces.last().add(lat)
        i += 2
    }
    return pieces.filter { it.size >= 6 }
}

/**
 * Every country outline's screen-space [Path]s at the current [scale]/
 * [offset] — the actual per-vertex geometry work (project every point of
 * every ring of all ~177 countries, splitting each at the antimeridian).
 * Pulled out from [WorldMapCard]'s `Canvas` draw lambda into its own
 * function specifically so it can sit behind a [remember] keyed on the
 * handful of things that actually change it, instead of rebuilding a fresh
 * [Path] for every ring on every single recomposition: this card lives
 * inside `PersonDetailScreen`, which reads the app's live tick and
 * recomposes several times a second, but `scale`/`offset` only change on an
 * explicit pan-to-pin/reset tap — nothing here needs redoing on the ticks in
 * between, and a `Path`-per-ring rebuild across the whole country dataset on
 * every one of those ticks was real, continuous, pointless CPU/battery cost
 * for a map that was visually sitting still.
 */
private fun buildCountryPaths(
    rendered: List<CountryRender>,
    scale: Float,
    offset: Offset,
    widthPx: Float
): List<Path> {
    if (widthPx <= 0f) return emptyList()
    val mapWidth = widthPx * scale
    val mapHeight = mapWidth / 2f
    val paths = mutableListOf<Path>()
    for (country in rendered) {
        for (ring in country.rings) {
            if (ring.size < 6) continue
            for (piece in splitAtAntimeridian(ring)) {
                val path = Path()
                var i = 0
                var first = true
                while (i + 1 < piece.size) {
                    val p = projectLonLat(piece[i], piece[i + 1], mapWidth, mapHeight)
                    val x = p.x + offset.x
                    val y = p.y + offset.y
                    if (first) {
                        path.moveTo(x, y)
                        first = false
                    } else {
                        path.lineTo(x, y)
                    }
                    i += 2
                }
                path.close()
                paths.add(path)
            }
        }
    }
    return paths
}

/** Average of the largest ring's points — a coarse centroid, fine for placing a label, not a real centroid-of-area. */
private fun centroidOf(country: CountryShape): LonLat {
    val ring = country.rings.maxByOrNull { it.size } ?: return LonLat(0.0, 0.0)
    var sumLon = 0.0
    var sumLat = 0.0
    var count = 0
    var i = 0
    while (i + 1 < ring.size) {
        sumLon += ring[i]
        sumLat += ring[i + 1]
        count++
        i += 2
    }
    return if (count > 0) LonLat(sumLon / count, sumLat / count) else LonLat(0.0, 0.0)
}

/**
 * [drawText]'s implicit layout box runs from [topLeft] to this [DrawScope]'s
 * own edge — i.e. an available width/height of `size.width - topLeft.x` /
 * `size.height - topLeft.y`. Panning/zooming the map can easily push a
 * label's [topLeft] past the canvas edge, which makes that subtraction go
 * negative; Compose's own `Constraints(maxWidth = ...)` then throws
 * (`maxWidth(-14) must be >= than minWidth(0)`) instead of just clipping.
 * This wraps every label draw so an off-canvas position is skipped instead
 * of crashing.
 */
private fun DrawScope.drawTextSafely(
    textMeasurer: TextMeasurer,
    text: String,
    topLeft: Offset,
    style: TextStyle
) {
    val availableWidth = size.width - topLeft.x
    val availableHeight = size.height - topLeft.y
    if (availableWidth <= 0f || availableHeight <= 0f) return
    drawText(
        textMeasurer = textMeasurer,
        text = text,
        topLeft = topLeft,
        style = style,
        size = Size(availableWidth, availableHeight)
    )
}

/**
 * A self-drawn Canvas world map — no WebView, no maps SDK, no per-user API
 * calls, same architecture as every other screen in the app. Country
 * outlines come from a bundled Natural Earth dataset (see [WorldGeo]);
 * ocean/sea names are a short hand-picked list ([OCEAN_LABELS]) since there's
 * no equivalent boundary dataset for open water at this scale.
 *
 * No manual pan/pinch — this card sits inside a scrolling detail screen, and
 * a touch-drag gesture handler on the map itself used to fight that scroll
 * (grabbing a vertical drag that started over the map instead of letting it
 * reach the parent list, which stopped the page mid-scroll). Framing is
 * button-only now: the ⌖/⟲ pair, or a tap on an aircraft/vessel card
 * elsewhere on the screen (see [MapFocusRequest]) — both just recenter the
 * same scale+offset state a drag gesture used to. Ocean/sea labels are
 * always visible; country names only appear once zoomed in enough to read
 * them without turning into overlapping confetti — 177 country names at
 * world-view scale is not legible at any font size.
 *
 * Most pins are airport/port granularity, not a live in-transit position —
 * the one exception is a currently-airborne aircraft with a live OpenSky
 * fix. See [MapPin] for exactly which case is which.
 */
private const val DEFAULT_SCALE = 1.6f
private const val FOCUS_SCALE = 4.5f

@Composable
fun WorldMapCard(
    countries: List<CountryShape>,
    pins: List<MapPin>,
    modifier: Modifier = Modifier,
    /** Set from a tap on an aircraft/vessel card elsewhere on the screen — see [MapFocusRequest]. */
    focusRequest: MapFocusRequest? = null
) {
    if (countries.isEmpty()) return

    val textMeasurer = rememberTextMeasurer()
    var scale by remember { mutableFloatStateOf(DEFAULT_SCALE) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var centered by remember { mutableStateOf(false) }
    // Whichever pin the ⌖/⟲ buttons act on right now — the initial pin until
    // a card tap (see [MapFocusRequest]) hands them a different one, at
    // which point the buttons follow that new object instead.
    var focusedPin by remember { mutableStateOf<MapPin?>(null) }

    // Re-tinted for the Cyberpunk-inspired palette (cyan/teal on near-black),
    // rechecked with the same contrast math that caught the original
    // invisible-landmass bug: fill vs background clears 3:1 (3.31), the
    // border clears it by a wide margin (9.71), and land vs ocean is once
    // again unambiguous rather than a flat dark field with floating labels.
    val countryFill = Color(0xFF2E6B7D)
    val countryBorder = Color(0xFF5FC3DB)
    val countryLabelInk = Color(0xFFD8F5FC)
    val oceanInk = Color(0xFF3AA0B8)
    val mapBg = Color(0xFF060B0E)

    val rendered = remember(countries) {
        countries.map { CountryRender(it.name, it.rings, centroidOf(it)) }
    }

    Column(modifier = modifier) {
        Text(
            text = "[ MAP ]",
            color = TT.accentCyan,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp
        )
        Spacer(Modifier.height(10.dp))

        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(260.dp)
                .clip(TT.panelShape(14.dp))
                .background(mapBg)
        ) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { maxHeight.toPx() }

            // Shared by the initial auto-center, the "reset" button, and an
            // explicit focus request — one place that turns a lat/lon into
            // the scale+offset that puts it in the middle of the viewport.
            fun focusOn(pin: MapPin?, targetScale: Float) {
                if (pin == null || widthPx <= 0f) return
                scale = targetScale
                val mapWidth = widthPx * targetScale
                val mapHeight = mapWidth / 2f
                val p = projectLonLat(pin.lon, pin.lat, mapWidth, mapHeight)
                offset = Offset(widthPx / 2f - p.x, heightPx / 2f - p.y)
            }

            LaunchedEffect(pins, widthPx) {
                if (!centered && widthPx > 0f) {
                    centered = true
                    val initial = pins.firstOrNull()
                    focusedPin = initial
                    focusOn(initial, DEFAULT_SCALE)
                }
            }

            // Re-centers only on an actual tap (see [MapFocusRequest.token]),
            // never as a side effect of a pin's own fields ticking over —
            // and hands the buttons below this same new pin as their target.
            LaunchedEffect(focusRequest?.token, widthPx) {
                if (focusRequest != null && widthPx > 0f) {
                    focusedPin = focusRequest.pin
                    focusOn(focusRequest.pin, FOCUS_SCALE)
                }
            }

            // The expensive part — see [buildCountryPaths]'s own doc comment
            // for why this is remembered rather than rebuilt inside the draw
            // lambda below.
            val countryPaths = remember(rendered, scale, offset, widthPx) {
                buildCountryPaths(rendered, scale, offset, widthPx)
            }

            Canvas(Modifier.fillMaxWidth().height(260.dp)) {
                val mapWidth = size.width * scale
                val mapHeight = mapWidth / 2f
                val off = offset

                fun toCanvas(lon: Double, lat: Double): Offset {
                    val p = projectLonLat(lon, lat, mapWidth, mapHeight)
                    return Offset(p.x + off.x, p.y + off.y)
                }

                for (path in countryPaths) {
                    drawPath(path, color = countryFill, style = Fill)
                    drawPath(path, color = countryBorder, style = Stroke(width = 1.dp.toPx()))
                }

                // Ocean/sea labels: always on, sparse enough not to collide.
                for (ocean in OCEAN_LABELS) {
                    val p = toCanvas(ocean.lon, ocean.lat)
                    if (p.x < -60f || p.x > size.width + 60f || p.y < -20f || p.y > size.height + 20f) continue
                    drawTextSafely(
                        textMeasurer = textMeasurer,
                        text = ocean.name,
                        topLeft = Offset(p.x, p.y),
                        style = TextStyle(color = oceanInk, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    )
                }

                // Country labels only once zoomed in — see kdoc above for why.
                if (scale > 3f) {
                    for (country in rendered) {
                        val p = toCanvas(country.centroid.lon, country.centroid.lat)
                        if (p.x < 0f || p.x > size.width || p.y < 0f || p.y > size.height) continue
                        drawTextSafely(
                            textMeasurer = textMeasurer,
                            text = country.name,
                            topLeft = Offset(p.x, p.y),
                            style = TextStyle(color = countryLabelInk, fontSize = 9.sp)
                        )
                    }
                }

                for (pin in pins) {
                    val p = toCanvas(pin.lon, pin.lat)
                    val pinColor = if (pin.isLive) TT.good else TT.warning
                    if (pin.isLive) {
                        drawCircle(color = pinColor, radius = 5.dp.toPx(), center = p)
                        drawCircle(color = Color.White, radius = 5.dp.toPx(), center = p, style = Stroke(width = 1.dp.toPx()))
                    } else {
                        // Hollow ring instead of a filled dot — visually distinct
                        // "last known, not live" marker.
                        drawCircle(color = pinColor, radius = 5.dp.toPx(), center = p, style = Stroke(width = 1.5.dp.toPx()))
                    }
                    drawTextSafely(
                        textMeasurer = textMeasurer,
                        text = "${pin.glyph} ${pin.label}",
                        topLeft = Offset(p.x + 8.dp.toPx(), p.y - 8.dp.toPx()),
                        style = TextStyle(color = TT.inkPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    )
                }
            }

            // Zoom-to-location / reset — intentionally just two controls,
            // stacked where a thumb naturally rests on a phone held in one
            // hand, doing the one obvious thing each glyph suggests rather
            // than a fuller map-control cluster.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MapControlButton(
                    glyph = "⌖",
                    enabled = focusedPin != null,
                    onClick = { focusOn(focusedPin, FOCUS_SCALE) }
                )
                MapControlButton(
                    glyph = "⟲",
                    enabled = focusedPin != null,
                    onClick = { focusOn(focusedPin, DEFAULT_SCALE) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Column {
            pins.forEach { pin ->
                Text(
                    text = "${if (pin.isLive) "●" else "○"} ${pin.glyph} ${pin.label} — ${pin.caption}",
                    color = TT.inkMuted,
                    fontSize = 11.sp
                )
            }
            if (pins.isEmpty()) {
                Text(
                    text = "No known position to place yet.",
                    color = TT.inkMuted,
                    fontSize = 11.sp
                )
            }
        }
    }
}

/**
 * "⌖" (zoom to the current position) and "⟲" (back to the default framing)
 * — the same rounded-square chip language as every other small control in
 * this app (see `SocialButton` in `PersonDetailScreen.kt`), not the panel's
 * cut-corner shape, which is reserved for cards.
 */
@Composable
private fun MapControlButton(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .size(34.dp)
            .clip(shape)
            .background(TT.surfaceRaised.copy(alpha = if (enabled) 1f else 0.5f))
            .border(1.dp, TT.border, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = glyph,
            color = if (enabled) TT.accentCyan else TT.inkMuted,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
