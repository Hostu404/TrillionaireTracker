package com.hostu404.trilliontracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.hostu404.trilliontracker.data.AirportInfo
import com.hostu404.trilliontracker.data.AirportStop
import com.hostu404.trilliontracker.data.FamilyHistoryRepository
import com.hostu404.trilliontracker.data.FlightState
import com.hostu404.trilliontracker.data.FlightStatus
import com.hostu404.trilliontracker.data.GoogleNewsClient
import com.hostu404.trilliontracker.data.LivePosition
import com.hostu404.trilliontracker.data.NetWorthEngine
import com.hostu404.trilliontracker.data.NewsItem
import com.hostu404.trilliontracker.data.LiveFlightTracker
import com.hostu404.trilliontracker.data.Person
import com.hostu404.trilliontracker.data.PortInfo
import com.hostu404.trilliontracker.data.PortStop
import com.hostu404.trilliontracker.data.VesselState
import com.hostu404.trilliontracker.data.VesselStatus
import com.hostu404.trilliontracker.data.WorldGeo
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.asin
import kotlin.math.sqrt
import com.hostu404.trilliontracker.ui.Format
import com.hostu404.trilliontracker.ui.TrackerUiState
import com.hostu404.trilliontracker.ui.awaitAppForeground
import com.hostu404.trilliontracker.ui.components.DeltaChip
import com.hostu404.trilliontracker.ui.components.MapFocusRequest
import com.hostu404.trilliontracker.ui.components.MapPin
import com.hostu404.trilliontracker.ui.components.NoteChip
import com.hostu404.trilliontracker.ui.components.TimelineSegment
import com.hostu404.trilliontracker.ui.components.TimelineStrip
import com.hostu404.trilliontracker.ui.components.honeycombGlowCell
import com.hostu404.trilliontracker.ui.components.hudCorners
import com.hostu404.trilliontracker.ui.components.hudTouchable
import com.hostu404.trilliontracker.ui.components.rubberBandPhotoDrag
import com.hostu404.trilliontracker.ui.components.sickeningChromaticAberration
import com.hostu404.trilliontracker.ui.components.hudPhotoGradeFilter
import com.hostu404.trilliontracker.ui.components.RollingNumber
import com.hostu404.trilliontracker.ui.components.Sparkline
import com.hostu404.trilliontracker.ui.components.StatusChip
import com.hostu404.trilliontracker.ui.components.isSparklineFlat
import com.hostu404.trilliontracker.ui.components.ThresholdGauge
import com.hostu404.trilliontracker.ui.components.WorldMapCard
import com.hostu404.trilliontracker.ui.theme.TT

@Composable
fun PersonDetailScreen(
    personId: String,
    state: TrackerUiState,
    onOpenFamilyHistory: (String) -> Unit
) {
    val person = state.personById(personId)
    val threshold = state.snapshot?.thresholdUsd ?: 1_000_000_000_000.0
    val nowSeconds = state.nowMillis / 1000

    // No background here — App() already paints the shared Saturn
    // image + scrim + scanlines behind the NavHost; an opaque fill on
    // this screen's root would hide all of it.
    Box(
        Modifier
            .fillMaxSize()
    ) {
        if (person == null) {
            Text(
                text = "Not in this snapshot.",
                color = TT.inkMuted,
                modifier = Modifier.padding(24.dp)
            )
            return@Box
        }

        val projected = state.projected(person)
        val airports = state.snapshot?.airports ?: emptyMap()
        val ports = state.snapshot?.ports ?: emptyMap()

        // Hoisted above the LazyColumn deliberately: its content lambda is
        // plain LazyListScope.() -> Unit, not @Composable, so LocalContext.current
        // and remember can only be called out here, not directly inside it.
        val mapContext = LocalContext.current
        val countries = remember { WorldGeo.countries(mapContext) }
        val liveFlightPosition = rememberLiveFlightPosition(person.flight)
        val flightPin = remember(person.flight, airports, liveFlightPosition) {
            flightMapPin(person.flight, airports, liveFlightPosition)
        }
        val vesselPin = remember(person.vessel, ports) {
            vesselMapPin(person.vessel, ports)
        }
        val pins = remember(flightPin, vesselPin) { listOfNotNull(flightPin, vesselPin) }

        // "Click the aircraft/vessel card, the map jumps to it." A token
        // rather than the pin itself is what actually re-triggers the map's
        // LaunchedEffect (see WorldMapCard) — the pin's own fields (caption,
        // live speed) can change every tick while airborne, and keying off
        // the pin directly would re-center the map on every one of those
        // ticks instead of only on an actual tap.
        // mutableIntStateOf, not mutableStateOf<Int> — lint's own
        // AutoboxingStateCreation check flags the generic version here: it
        // boxes every write to this counter, which mutableIntStateOf avoids
        // since it's backed by a primitive-int snapshot state instead.
        var mapFocusToken by remember { mutableIntStateOf(0) }
        var mapFocusPin by remember { mutableStateOf<MapPin?>(null) }
        val mapFocusRequest = mapFocusPin?.let { MapFocusRequest(mapFocusToken, it) }

        val liveNews = rememberLiveNews(query = person.name, seedNews = person.news)

        // Only true for whoever FamilyHistoryRepository actually has a
        // sourced entry for (just the `brin` proof-of-concept for now) — see
        // FamilyHistoryScreen's own doc comment. Nobody sees an empty or
        // "nothing here yet" card for the other nine; the entry point simply
        // doesn't render until there's real, cited content behind it.
        val hasFamilyHistory = remember(person.id) {
            FamilyHistoryRepository.entryFor(person.id, mapContext) != null
        }

        // Layout order below is deliberate, most-asked-about first: who they
        // are (bio, with the link-out social button folded into its end) ->
        // where they came from, generations back (family history, when
        // sourced — grouped with bio since both are "who they are," not the
        // live numbers below) -> the number this whole app is about ->
        // where they physically are right now (map) -> the raw
        // aircraft/vessel status feeding that map -> how that time actually
        // breaks down -> the raw stop history behind the breakdown ->
        // what's being written about them.
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                PersonHeader(
                    name = person.name,
                    company = person.company,
                    photoUrl = person.photoUrl,
                    wikipediaUrl = person.wikipediaUrl,
                    birthDate = person.birthDate,
                    residence = person.residence,
                    socialUrl = person.socialUrl,
                    // Strictly "moving right now," matching TrackerScreen's
                    // own PersonRow definition (state == AIRBORNE / UNDERWAY,
                    // not merely a confirmed-but-parked fix) — see that
                    // file's 2026-09-24 comment for why the two need to
                    // agree: this header is the thing the list's own
                    // transit indicator sends someone to, so if the list
                    // lights up, this is where they land expecting to see it
                    // confirmed.
                    isAirborneNow = person.flight?.state == FlightState.AIRBORNE,
                    isUnderwayNow = person.vessel?.state == VesselState.UNDERWAY
                )
            }

            person.bio?.let { bio ->
                item { BiographyCard(bio) }
            }

            if (hasFamilyHistory) {
                item {
                    FamilyHistoryEntryCard(onClick = { onOpenFamilyHistory(person.id) })
                }
            }

            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(TT.surface, TT.panelShape(14.dp))
                        .then(
                            if (projected >= threshold) {
                                Modifier.honeycombGlowCell(xFraction = 0.5f, yFraction = 0.12f, color = TT.good)
                            } else {
                                Modifier
                            }
                        )
                        .border(1.dp, TT.border, TT.panelShape(14.dp))
                        .padding(14.dp)
                ) {
                    RollingNumber(
                        text = Format.exactUsd(projected),
                        style = TextStyle(
                            fontSize = 24.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace
                        ),
                        color = if (projected >= threshold) TT.good else TT.inkPrimary
                    )

                    Spacer(Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DeltaChip(
                            deltaUsd = person.dayChangeUsd,
                            formatted = Format.signedCompactUsd(person.dayChangeUsd) + " today"
                        )
                        if (state.isLive(person.id)) {
                            Spacer(Modifier.width(8.dp))
                            StatusChip(glyph = "●", label = "TRACKING · market price", color = TT.good)
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    ThresholdGauge(
                        progress = NetWorthEngine.progressToThreshold(projected, threshold),
                        leftLabel = "${Format.percentOf(projected, threshold)} of \$1T",
                        rightLabel = if (projected >= threshold) "over the line" else
                            "${Format.compactUsd(NetWorthEngine.shortfall(projected, threshold))} to go",
                        barColor = if (projected >= threshold) TT.good else TT.series
                    )

                    NetWorthEngine.secondsToThreshold(projected, state.driftPerSecond(person), threshold)?.let { secs ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "~${Format.roughDuration(secs)} to \$1T at current rate",
                            color = TT.inkMuted,
                            fontSize = 11.sp
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    val historyFlat = isSparklineFlat(person.history)
                    Sparkline(
                        values = person.history,
                        // Muted instead of the usual bright cyan the moment
                        // the tail stops moving — see [isSparklineFlat]'s own
                        // doc for why that reads as "the market's closed,"
                        // not a stuck/broken feed. The chip above still says
                        // "TRACKING" — a real anchor exists, it's just not
                        // moving right now.
                        color = if (historyFlat) TT.inkMuted else TT.series,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (historyFlat) {
                            "flat · market's closed right now"
                        } else {
                            "last ${person.history.size} closes"
                        },
                        color = TT.inkMuted,
                        fontSize = 11.sp
                    )
                }
            }

            if (person.flight != null || person.vessel != null) {
                item {
                    WorldMapCard(
                        countries = countries,
                        pins = pins,
                        focusRequest = mapFocusRequest
                    )
                }
            }

            person.flight?.let { flight ->
                // Same gate as BoatCard below: nobody with no plane assigned
                // at all needs a card that exists only to say "No aircraft
                // mapped." — that's a card for nobody, not useful information.
                item {
                    FlightCard(
                        flight = flight,
                        nowSeconds = nowSeconds,
                        airports = airports,
                        onFocusMap = flightPin?.let { pin ->
                            { mapFocusPin = pin; mapFocusToken++ }
                        }
                    )
                }
            }

            person.vessel?.let { vessel ->
                item {
                    BoatCard(
                        vessel = vessel,
                        nowSeconds = nowSeconds,
                        ports = ports,
                        onFocusMap = vesselPin?.let { pin ->
                            { mapFocusPin = pin; mapFocusToken++ }
                        }
                    )
                }
            }

            run {
                // trackedSeconds alone is the right gate for each strip —
                // buildTimelineSegments/buildVesselTimelineSegments already
                // render a correct, honest strip with zero stops (the whole
                // window as "No signal") for a plane/boat that's been
                // tracked but never yet caught parked/in port. Requiring
                // recentStops too used to hide a strip for that case
                // entirely, even though there was real tracked time to show.
                // A strip only appears at all once something has actually
                // been confirmed — see snapshot_worker.py's flight_status/
                // vessel_status, which no longer start the tracking clock
                // on an aircraft or vessel that's never once been located.
                val flight = person.flight?.takeIf { it.trackedSeconds > 0 }
                val vessel = person.vessel?.takeIf { it.trackedSeconds > 0 }
                if (flight != null || vessel != null) {
                    item { TimeByLocationCard(flight, vessel, nowSeconds, airports, ports) }
                }
            }

            person.flight?.recentStops?.takeIf { it.isNotEmpty() }?.let { stops ->
                item { LocationHistoryCard(stops, nowSeconds, airports) }
            }

            person.vessel?.recentStops?.takeIf { it.isNotEmpty() }?.let { stops ->
                item { PortHistoryCard(stops, nowSeconds, ports) }
            }

            if (liveNews.isNotEmpty()) {
                item { SectionLabel("IN THE NEWS") }
                // Keyed on url (a real headline's one stable, unique field)
                // instead of the default index — a live RSS poll can come
                // back with headlines in a different order from the last
                // one, and an index key would misattribute recomposition/
                // animation state to whatever headline now happens to sit
                // at that same position rather than following the story it
                // actually belongs to.
                items(liveNews, key = { it.url }) { newsItem ->
                    NewsRow(newsItem, nowSeconds)
                }
            }
        }
    }
}

/**
 * Photo + name + age/birthdate/residence + a link to the source of truth.
 *
 * Age is computed from [birthDate] at render time (see [Format.ageFrom]),
 * not stored anywhere — open the app again after a birthday and it's just
 * right, no new snapshot needed. [residence] is city/region only, same
 * granularity rule as everywhere else location shows up in this app (see
 * [FlightStatus.currentAirportIcao]) — never a street address, and left
 * blank rather than guessed when public reporting doesn't clearly agree on
 * where someone currently lives.
 *
 * The photo is optional — [Person.photoUrl] is only ever set when
 * Wikipedia's own thumbnail lives on Commons (free-license by policy), so a
 * missing photo here means no free image was available, not that we
 * skipped checking. Falls back to a plain initial disc so the layout never
 * has a hole where the photo would be.
 */
/**
 * Opens a map centered on the town/region name itself — never a specific
 * address or a coordinate. [residence] is already never more precise than
 * "City, ST" (see [Person.residence]'s own doc comment), and this passes
 * exactly that string through as a place-name search query, nothing more:
 * Maps resolves "Palo Alto, CA" to roughly the town, the same way it would
 * for anyone typing that name in themselves. There is deliberately no path
 * from this app to a pin on anyone's actual front door.
 */
private fun townMapUrl(residence: String): String {
    val query = java.net.URLEncoder.encode(residence, "UTF-8")
    return "https://www.google.com/maps/search/?api=1&query=$query"
}

/**
 * Full-width hero banner, not a small circular avatar — the photo itself is
 * the header. Identity text (name/company/age/residence/wikipedia) is laid
 * directly over the image inside a bottom scrim gradient rather than inside
 * any solid/opaque panel: a hard-edged background box would read as "text in
 * a box sitting on a photo," where the point here is text that belongs to
 * the photo. The gradient exists purely so light text stays legible against
 * whatever the photo's own bottom edge happens to look like — it is not a
 * design element in its own right, so it is never drawn without a photo
 * underneath needing it. Lines that used to be their own row (age/birth,
 * residence, Wikipedia) are compacted onto shared rows so the banner's own
 * height buys back space rather than spending it. [socialUrl] lives inside
 * this banner too — tucked into its own top-right corner using the same
 * [TT.panelShape] chamfer the banner itself is clipped to, rather than
 * sitting above it as a separate row that only added empty space — moved
 * off the end of [BiographyCard] so a profile's link-out sits with the rest
 * of its identity (photo, name) instead of several cards further down the
 * screen. It uses a translucent-black chip (the same style this banner's
 * old top-left back button used, before that button was removed 2026-09-24
 * as redundant against the phone's own system back), not [BiographyCard]'s
 * old bare-glyph treatment, because it has to stay legible over an
 * arbitrary photo instead of this app's own surface color.
 */
/**
 * Renders [text] with a thin black outline behind the normal fill —
 * [PersonHeader]'s identity text (name/company/age) sits directly on top of
 * an arbitrary photo rather than this app's own surface color, and the
 * bottom scrim gradient alone doesn't guarantee contrast against every
 * photo's own bright/high-key areas.
 *
 * Deliberately NOT built on Compose's text `drawStyle = Stroke(...)` — that
 * traces the glyph outline through Skia's font stroker, which follows every
 * curve of the letterforms and comes out blotchy and uneven at these small
 * sizes (tried it; it looked like a smudge, not an outline). Instead this
 * draws the same text several times in solid black, each nudged a hair in a
 * different direction, with the real white text on top — the classic "faux
 * outline" trick from games/overlays, and a much cleaner, crisper result
 * than stroking the glyphs themselves.
 */
@Composable
private fun OutlinedWhiteText(
    text: String,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified
) {
    val nudge = 0.6.dp
    val outlineColor = Color.Black.copy(alpha = 0.75f)
    Box(modifier) {
        listOf(-nudge to -nudge, 0.dp to -nudge, nudge to -nudge,
               -nudge to 0.dp,                    nudge to 0.dp,
               -nudge to nudge,  0.dp to nudge,   nudge to nudge
        ).forEach { (dx, dy) ->
            Text(
                text = text,
                fontSize = fontSize,
                fontWeight = fontWeight,
                fontFamily = fontFamily,
                letterSpacing = letterSpacing,
                color = outlineColor,
                modifier = Modifier.offset(x = dx, y = dy)
            )
        }
        Text(
            text = text,
            fontSize = fontSize,
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            letterSpacing = letterSpacing,
            color = color
        )
    }
}

@Composable
private fun PersonHeader(
    name: String,
    company: String,
    photoUrl: String?,
    wikipediaUrl: String?,
    birthDate: String?,
    residence: String?,
    socialUrl: String?,
    isAirborneNow: Boolean,
    isUnderwayNow: Boolean
) {
    val uriHandler = LocalUriHandler.current
    val age = birthDate?.let { Format.ageFrom(it) }
    val birthLabel = birthDate?.let { Format.birthDateLabel(it) }
    val shape = TT.panelShape(14.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(196.dp)
            .clip(shape)
            .border(1.5.dp, TT.border, shape)
    ) {
        if (photoUrl != null) {
            // A same-tone placeholder keeps sickeningChromaticAberration()
            // drawing *something* through its three-pass filter from the
            // very first frame, so the effect never visibly "switches on"
            // once the network image lands — the photo underneath simply
            // resolves into an already-fringed, already-throbbing frame
            // instead of popping in plain and gaining the effect a beat
            // later.
            AsyncImage(
                model = photoUrl,
                contentDescription = "Photo of $name",
                contentScale = ContentScale.Crop,
                // Default Crop alignment is dead center, which crops evenly
                // top and bottom — fine for a landscape photo, but this
                // banner is much wider than a typical portrait headshot, so
                // filling it vertically crops a lot, and centering that crop
                // reliably cuts into foreheads. Biased toward the top (with
                // a little headroom rather than a hard top edge) instead,
                // so the crop comes off the bottom — shoulders/chest, never
                // anyone's head — no matter which photo loads here.
                alignment = BiasAlignment(horizontalBias = 0f, verticalBias = -0.6f),
                placeholder = ColorPainter(TT.surface),
                // The "universal filter" — desaturates and tints every photo
                // toward this app's own palette before anything else touches
                // it, so a raw, naturally-lit photo doesn't clash with the
                // near-monochrome cyan-on-black HUD around it. Applied here
                // (baked into the image's own draw call) rather than as a
                // Modifier, so sickeningChromaticAberration below draws the
                // already-graded pixels three times, not the raw photo.
                colorFilter = hudPhotoGradeFilter,
                // Pull-and-release "reflex" gesture — drag the photo around
                // inside its own frame, let go and it snaps back with a
                // rubber-band spring. Placed before sickeningChromaticAberration
                // so the glitch effect's three re-draws pick up wherever the
                // photo currently sits, riding along with a pull instead of
                // staying pinned in place under it. The frame's own .clip(shape)
                // on the outer Box (not this Modifier chain) is what actually
                // keeps a pull from spilling outside the header — see
                // [rubberBandPhotoDrag]'s own doc comment.
                modifier = Modifier
                    .fillMaxSize()
                    .rubberBandPhotoDrag()
                    .sickeningChromaticAberration()
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(TT.surface),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = name.firstOrNull()?.uppercase() ?: "?",
                    color = TT.inkMuted,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(114.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .then(
                    // The header's own "in transit" light — added
                    // 2026-09-24 alongside the strict AIRBORNE/UNDERWAY
                    // check above, so the same glow that flags a row on the
                    // list (TrackerScreen's PersonRow) is what someone
                    // actually finds when they tap through to confirm it.
                    // Placed on this Column (drawn after the photo and its
                    // scrim, not before) so the wash shows up over the dark
                    // gradient behind the readout text instead of getting
                    // buried under the photo itself.
                    if (isAirborneNow || isUnderwayNow) {
                        Modifier.honeycombGlowCell(xFraction = 0.22f, yFraction = 0.06f, color = TT.warning)
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            // Readout treatment added 2026-09-24, once the photo itself got
            // graded toward the app's own palette (see hudPhotoGradeFilter):
            // the caption block underneath it was still set in a plain
            // human caption style (proportional font, sentence-case "Lives
            // in X"/"Born Y" prose), which read as a normal photo caption
            // sitting on top of an otherwise fully HUD photo. Everything
            // below is now built from the same vocabulary the rest of the
            // screen already uses — TT.monoNumeric for anything that reads
            // as a data field (exactly what the net worth ticker and tail
            // numbers already do), uppercase+letter-spaced labels the way
            // SectionLabel's "[ BIOGRAPHY ]" brackets do, TT.accentCyan for
            // chrome/labels only, never for the subject's own name (that
            // stays plain white — a label isn't data about the person, it's
            // UI chrome, same rule the header/border-color split follows
            // everywhere else).
            // "[ SUBJECT ]" eyebrow removed 2026-09-24 (same pass as the
            // in-transit indicator it used to sit next to) — the transit
            // chip below is the only thing that still belongs on this line,
            // and it now only renders — with its own Spacer(4.dp) — when
            // there's actually something to say, rather than leaving a
            // near-empty Row + gap sitting above the name on every profile
            // that isn't currently AIRBORNE/UNDERWAY.
            if (isAirborneNow || isUnderwayNow) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isAirborneNow) {
                        Text(
                            text = "[ ✈ AIRBORNE ]",
                            color = TT.warning,
                            fontSize = 10.sp,
                            fontFamily = TT.monoNumeric,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.sp
                        )
                    }
                    if (isUnderwayNow) {
                        if (isAirborneNow) {
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            text = "[ ⚓ UNDERWAY ]",
                            color = TT.warning,
                            fontSize = 10.sp,
                            fontFamily = TT.monoNumeric,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.sp
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            OutlinedWhiteText(
                text = name.uppercase(),
                color = Color.White,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = TT.monoNumeric,
                letterSpacing = 0.5.sp
            )
            OutlinedWhiteText(
                text = "// ${company.uppercase()}",
                color = TT.accentCyan,
                fontSize = 12.sp,
                fontFamily = TT.monoNumeric,
                letterSpacing = 0.5.sp
            )

            if (age != null && birthLabel != null) {
                Spacer(Modifier.height(4.dp))
                OutlinedWhiteText(
                    text = "AGE $age  ·  BORN ${birthLabel.uppercase()}",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    fontFamily = TT.monoNumeric,
                    letterSpacing = 0.3.sp
                )
            }

            if (residence != null || wikipediaUrl != null) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (residence != null) {
                        Text(
                            text = "[ ${residence.uppercase()} ↗ ]",
                            color = TT.accentCyan,
                            fontSize = 11.sp,
                            fontFamily = TT.monoNumeric,
                            letterSpacing = 0.5.sp,
                            modifier = Modifier
                                .padding(2.dp)
                                .clickable { uriHandler.openUri(townMapUrl(residence)) }
                        )
                    }
                    if (residence != null && wikipediaUrl != null) {
                        Spacer(Modifier.width(6.dp))
                    }
                    if (wikipediaUrl != null) {
                        Text(
                            text = "[ WIKIPEDIA ↗ ]",
                            color = TT.accentCyan,
                            fontSize = 11.sp,
                            fontFamily = TT.monoNumeric,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.5.sp,
                            modifier = Modifier
                                .padding(2.dp)
                                .clickable { uriHandler.openUri(wikipediaUrl) }
                        )
                    }
                }
            }
        }

        // The in-app back button that used to sit in the opposite (TopStart)
        // corner was removed 2026-09-24 — the phone's own system back does
        // the exact same thing everywhere in Android, so a second, on-screen
        // control doing the same job was pure redundancy, not a real
        // affordance (see FamilyHistoryScreen's matching note, which had the
        // same button). This social button was originally styled to mirror
        // that one for symmetry; kept the same 10dp-cut/translucent-black
        // chip now that it's the only corner control left, rather than
        // restyling it along with removing its former partner. Link-out
        // only, never embedded (see [socialPlatformGlyph]'s own doc comment
        // for why).
        if (socialUrl != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(TT.panelShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .border(1.dp, TT.border, TT.panelShape(10.dp))
                    .hudTouchable(cornerLength = 6.dp, cornerInset = 2.dp) { uriHandler.openUri(socialUrl) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    text = socialPlatformGlyph(socialUrl),
                    color = TT.accentCyan,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/** A HUD-bracket section header — "[ AIRCRAFT ]" in the chrome accent, not the data one. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = "[ $text ]",
        color = TT.accentCyan,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(top = 8.dp)
    )
}

/**
 * The publishing rule, rendered.
 *
 * Departure and in-air status are shown live, and while the aircraft is
 * airborne the map above genuinely tracks a live ADS-B position (see
 * [rememberLiveFlightPosition]) — the "open live map" link below is an
 * additional external option (ADS-B Exchange's own full tracker), not the
 * only place a live fix exists anymore. Everything about position shown as
 * *text* on this card — current location, the 7-day history below — stays
 * airport granularity only, never a raw coordinate.
 */
@Composable
private fun FlightCard(
    flight: FlightStatus,
    nowSeconds: Long,
    airports: Map<String, AirportInfo>,
    onFocusMap: (() -> Unit)? = null
) {
    val uriHandler = LocalUriHandler.current
    fun label(icao: String) = airports[icao]?.label ?: icao
    // A plain UNKNOWN reading covers two very different situations — see
    // snapshot_worker.py's flight_status() `was == "AIRBORNE"` branch — and
    // only currentBucket, not state, tells them apart.
    val signalLost = flight.state == FlightState.UNKNOWN && flight.currentBucket == "SIGNAL_LOST"
    // Option D's "the grid is a status layer, not just texture": flag this
    // card with a status-coloured glow only when there's actually something
    // worth flagging — a confirmed dropped signal (critical/red). A plain
    // UNKNOWN with no bucket set yet is the everyday "haven't heard from it
    // in a bit" gap — see currentBucket's own doc comment — not a real
    // aircraft-in-use event, so it gets no glow at all, same as the calm
    // AIRBORNE/ON_GROUND states.
    val glowColor = when {
        signalLost -> TT.critical
        else -> null
    }

    Column(
        Modifier
            .fillMaxWidth()
            // Whole card is the tap target when a map to focus exists, so
            // hudTouchable goes first — see PersonRow's identical reasoning
            // in TrackerScreen.kt — replacing the plain `clickable` this
            // used to have.
            .then(
                if (onFocusMap != null) {
                    Modifier.hudTouchable(
                        cornerLength = 9.dp,
                        cornerInset = 3.dp,
                        elevation = 2.dp,
                        shape = TT.panelShape(14.dp),
                        onClick = onFocusMap
                    )
                } else {
                    Modifier
                }
            )
            .background(TT.surface, TT.panelShape(14.dp))
            .then(
                if (glowColor != null) {
                    Modifier.honeycombGlowCell(xFraction = 0.16f, yFraction = 0.14f, color = glowColor)
                } else {
                    Modifier
                }
            )
            .border(1.dp, TT.border, TT.panelShape(14.dp))
            .padding(14.dp)
    ) {
        SectionLabel(text = "AIRCRAFT")

        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                flight.state == FlightState.AIRBORNE ->
                    StatusChip(glyph = "✈", label = "AIRBORNE NOW", color = TT.warning)
                flight.state == FlightState.ON_GROUND ->
                    StatusChip(glyph = "●", label = "ON GROUND", color = TT.inkSecondary)
                signalLost ->
                    StatusChip(glyph = "!", label = "SIGNAL LOST", color = TT.critical)
                else ->
                    StatusChip(glyph = "?", label = "NO SIGNAL", color = TT.inkMuted)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = flight.tail,
                color = TT.inkSecondary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
            if (!flight.verified) {
                Spacer(Modifier.width(8.dp))
                NoteChip(text = "unverified", color = TT.warning)
            }
        }

        Spacer(Modifier.height(10.dp))

        DetailRow(
            label = "Current",
            value = when {
                flight.state == FlightState.ON_GROUND && flight.currentAirportIcao != null ->
                    label(flight.currentAirportIcao)
                // Added 2026-09-23: a real fix landed, but no known airport
                // nearby — see FlightStatus.currentLat's doc comment. Shown
                // with the same "approximate" caveat the map pin carries,
                // rather than silently falling through to "No signal" below
                // for a position that was, in fact, caught.
                flight.state == FlightState.ON_GROUND && flight.generalLocation != null ->
                    "${flight.generalLocation} (approximate — no known airport nearby)"
                flight.state == FlightState.AIRBORNE -> "In the air — no fixed location"
                signalLost && flight.probableIcao != null ->
                    "Possibly landed near ${label(flight.probableIcao)} (unconfirmed)"
                signalLost -> "Possibly landed — location unclear (unconfirmed)"
                else -> "No signal"
            }
        )

        // The raw fix behind the "(approximate — no known airport nearby)"
        // line above, spelled out as text. The number was already being
        // used to place the dashed "approximate" pin on the map (see
        // flightMapPin) — this doesn't surface it anywhere new, just also
        // renders as text where a pin already showed it. Same lifetime as
        // the fields it reads: current-pass only, never persisted.
        if (flight.state == FlightState.ON_GROUND && flight.currentLat != null && flight.currentLon != null) {
            DetailRow(
                label = "Coordinates",
                value = Format.coordinate(flight.currentLat, flight.currentLon)
            )
        }

        Spacer(Modifier.height(8.dp))

        flight.departedIcao?.let { dep ->
            DetailRow(
                label = "Departed",
                value = label(dep) + (flight.departedAtEpoch?.let {
                    "  ·  ${Format.elapsedSince(it, nowSeconds)} ago"
                } ?: "")
            )
        }

        when (flight.state) {
            FlightState.AIRBORNE -> {
                Spacer(Modifier.height(8.dp))
                if (flight.estimatedDestinationIcao != null) {
                    DetailRow(label = "Heading toward", value = label(flight.estimatedDestinationIcao) + " (est.)")
                } else {
                    Text(
                        text = "In flight — course doesn't point clearly at a major airport yet.",
                        color = TT.inkMuted,
                        fontSize = 13.sp
                    )
                }
            }

            FlightState.ON_GROUND -> {
                flight.arrivedIcao?.let { arr ->
                    DetailRow(
                        label = "Arrived",
                        value = label(arr) + (flight.arrivedAtEpoch?.let {
                            "  ·  ${Format.agoShort(it, nowSeconds)}"
                        } ?: "")
                    )
                }
            }

            FlightState.UNKNOWN -> Unit
        }

        // Straight-line total across confirmed airport-to-airport legs on
        // record (see flightTripDistanceNm's doc comment) — an honest, if
        // approximate, answer to "how far has this actually flown lately,"
        // built entirely from history already collected. Null (nothing
        // shown) until at least two stops in the trailing window resolve to
        // a known airport with coordinates.
        flightTripDistanceNm(flight.recentStops, airports)?.let { nm ->
            Spacer(Modifier.height(8.dp))
            DetailRow(label = "Distance (7d)", value = Format.nauticalMiles(nm))
        }

        // lastSeenEpoch == 0 means "never actually seen" (an identity with no
        // position data at all), not a real 1970 timestamp — showing it as
        // an elapsed time would read as real data when it isn't any.
        if (flight.lastSeenEpoch > 0) {
            Spacer(Modifier.height(8.dp))
            DetailRow(
                label = "Last seen",
                value = Format.agoShort(flight.lastSeenEpoch, nowSeconds)
            )
        }

        flight.liveMapUrl?.let { url ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Open live map ↗",
                color = TT.accentCyan,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .padding(2.dp)
                    .clickable { uriHandler.openUri(url) }
            )
        }
    }
}

/**
 * Airport-level, 7 days, newest first. Deliberately not a coordinate trail —
 * see [AirportStop] and the README's Flights section for why.
 */
@Composable
private fun LocationHistoryCard(stops: List<AirportStop>, nowSeconds: Long, airports: Map<String, AirportInfo>) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(TT.surface, TT.panelShape(14.dp))
            .border(1.dp, TT.border, TT.panelShape(14.dp))
            .padding(14.dp)
    ) {
        SectionLabel(text = "LAST 7 DAYS")

        Spacer(Modifier.height(8.dp))

        stops.forEachIndexed { index, stop ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = airports[stop.icao]?.label ?: stop.icao,
                    color = TT.inkPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.width(108.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "arrived ${Format.agoShort(stop.arrivedAtEpoch, nowSeconds)}",
                        color = TT.inkSecondary,
                        fontSize = 12.sp
                    )
                    Text(
                        text = stop.departedAtEpoch?.let {
                            "left ${Format.agoShort(it, nowSeconds)}"
                        } ?: "still there",
                        color = if (stop.departedAtEpoch == null) TT.good else TT.inkMuted,
                        fontSize = 12.sp
                    )
                }
            }
            if (index != stops.lastIndex) {
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/**
 * The maritime mirror of [FlightCard] — same publishing rule, same
 * granularity limits. The one thing planes don't have: a self-reported
 * destination (and, added 2026-09-24, an ETA) straight from the vessel's
 * own AIS broadcast. It's shown, but clearly labeled as crew-entered and
 * unverified — it's routinely blank, stale, or informal shorthand, nothing
 * like a filed flight plan.
 */
@Composable
private fun BoatCard(
    vessel: VesselStatus,
    nowSeconds: Long,
    ports: Map<String, PortInfo>,
    onFocusMap: (() -> Unit)? = null
) {
    val uriHandler = LocalUriHandler.current
    fun label(unlocode: String) = ports[unlocode]?.label ?: unlocode
    // Added 2026-09-24: vessels now have the same "confirmed dropped signal"
    // distinction flights do — see FlightCard's signalLost and
    // VesselStatus.currentBucket's doc comment. Same rule as FlightCard's
    // glowColor: flag this card only when there's actually something worth
    // flagging (a confirmed SIGNAL_LOST), not for the everyday "haven't
    // heard from it in a bit" gap — AIS coverage here is shore-based, so an
    // ordinary ocean crossing can go days unheard with nothing wrong at
    // all, and that's an even worse reason to glow amber than a plane's
    // equivalent short gap already was. A previous version of this card
    // glowed amber for any plain UNKNOWN reading; that's the one asymmetry
    // between the two cards this removes — quiet unless it's actually lost.
    val signalLost = vessel.state == VesselState.UNKNOWN && vessel.currentBucket == "SIGNAL_LOST"
    val glowColor = when {
        signalLost -> TT.critical
        else -> null
    }

    Column(
        Modifier
            .fillMaxWidth()
            .then(
                if (onFocusMap != null) {
                    Modifier.hudTouchable(
                        cornerLength = 9.dp,
                        cornerInset = 3.dp,
                        elevation = 2.dp,
                        shape = TT.panelShape(14.dp),
                        onClick = onFocusMap
                    )
                } else {
                    Modifier
                }
            )
            .background(TT.surface, TT.panelShape(14.dp))
            .then(
                if (glowColor != null) {
                    Modifier.honeycombGlowCell(xFraction = 0.16f, yFraction = 0.14f, color = glowColor)
                } else {
                    Modifier
                }
            )
            .border(1.dp, TT.border, TT.panelShape(14.dp))
            .padding(14.dp)
    ) {
        SectionLabel(text = "VESSEL")

        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                vessel.state == VesselState.UNDERWAY ->
                    StatusChip(glyph = "⚓", label = "UNDERWAY NOW", color = TT.warning)
                vessel.state == VesselState.IN_PORT ->
                    StatusChip(glyph = "●", label = "IN PORT", color = TT.inkSecondary)
                signalLost ->
                    StatusChip(glyph = "!", label = "SIGNAL LOST", color = TT.critical)
                else ->
                    StatusChip(glyph = "?", label = "NO SIGNAL", color = TT.inkMuted)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = vessel.name,
                color = TT.inkSecondary,
                fontSize = 13.sp
            )
            if (!vessel.verified) {
                Spacer(Modifier.width(8.dp))
                NoteChip(text = "unverified", color = TT.warning)
            }
        }

        Spacer(Modifier.height(10.dp))

        DetailRow(
            label = "Current",
            value = when {
                vessel.state == VesselState.IN_PORT && vessel.currentPortUnlocode != null ->
                    label(vessel.currentPortUnlocode)
                // Added 2026-09-23: a real AIS fix (moored or underway) that
                // didn't resolve to a known port — see VesselStatus.currentLat's
                // doc comment. Checked before the plain UNDERWAY branch below
                // so a real underway position is shown when we have one,
                // rather than always reading "no fixed location".
                vessel.generalLocation != null ->
                    "${vessel.generalLocation} (approximate — no known port nearby)"
                vessel.state == VesselState.UNDERWAY -> "At sea — no fixed location"
                signalLost && vessel.probablePortUnlocode != null ->
                    "Possibly arrived near ${label(vessel.probablePortUnlocode)} (unconfirmed)"
                signalLost -> "Possibly arrived — location unclear (unconfirmed)"
                else -> "No signal"
            }
        )

        // The maritime mirror of FlightCard's identical addition above — the
        // raw fix behind "(approximate — no known port nearby)", spelled out
        // as text next to the same number already placing the dashed
        // "approximate" pin (see vesselMapPin). No state check needed here,
        // same as the generalLocation branch just above: currentLat/currentLon
        // are only ever set (IN_PORT or UNDERWAY) when there's a real fix
        // with no port match — see VesselStatus.currentLat's doc comment.
        if (vessel.currentLat != null && vessel.currentLon != null) {
            DetailRow(
                label = "Coordinates",
                value = Format.coordinate(vessel.currentLat, vessel.currentLon)
            )
        }

        Spacer(Modifier.height(8.dp))

        vessel.departedPortUnlocode?.let { dep ->
            DetailRow(
                label = "Departed",
                value = label(dep) + (vessel.departedAtEpoch?.let {
                    "  ·  ${Format.elapsedSince(it, nowSeconds)} ago"
                } ?: "")
            )
        }

        when (vessel.state) {
            VesselState.UNDERWAY -> {
                Spacer(Modifier.height(8.dp))
                // Self-reported beats estimated when both exist — crew-entered
                // text beats a course guess, same priority a confirmed
                // arrivedIcao gets over FlightStatus's own estimate. Falls
                // through to the live heading-based guess (the maritime
                // mirror of FlightCard's "Heading toward X (est.)" below)
                // only when nothing was self-reported, closing the asymmetry
                // where boats never got a live destination guess the way
                // planes always have while airborne.
                when {
                    vessel.selfReportedDestination != null -> {
                        DetailRow(
                            label = "Destination",
                            value = vessel.selfReportedDestination + " (self-reported)" +
                                (vessel.selfReportedEta?.let { "  ·  ETA $it" } ?: "")
                        )
                    }
                    vessel.estimatedDestinationPortUnlocode != null -> {
                        DetailRow(
                            label = "Heading toward",
                            value = label(vessel.estimatedDestinationPortUnlocode) + " (est.)"
                        )
                    }
                    else -> {
                        Text(
                            text = "At sea — course doesn't point clearly at a known port yet.",
                            color = TT.inkMuted,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            VesselState.IN_PORT -> {
                vessel.arrivedPortUnlocode?.let { arr ->
                    DetailRow(
                        label = "Arrived",
                        value = label(arr) + (vessel.arrivedAtEpoch?.let {
                            "  ·  ${Format.agoShort(it, nowSeconds)}"
                        } ?: "")
                    )
                }
            }

            VesselState.UNKNOWN -> Unit
        }

        // The maritime mirror of FlightCard's identical addition above — see
        // vesselTripDistanceNm's doc comment.
        vesselTripDistanceNm(vessel.recentStops, ports)?.let { nm ->
            Spacer(Modifier.height(8.dp))
            DetailRow(label = "Distance (7d)", value = Format.nauticalMiles(nm))
        }

        if (vessel.lastSeenEpoch > 0) {
            Spacer(Modifier.height(8.dp))
            DetailRow(
                label = "Last seen",
                value = Format.agoShort(vessel.lastSeenEpoch, nowSeconds)
            )
        }

        vessel.liveMapUrl?.let { url ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Open live map ↗",
                color = TT.accentCyan,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .padding(2.dp)
                    .clickable { uriHandler.openUri(url) }
            )
        }
    }
}

/**
 * Port-level, 7 days, newest first — the maritime mirror of
 * [LocationHistoryCard]. Never a coordinate trail; see [VesselStatus].
 */
@Composable
private fun PortHistoryCard(stops: List<PortStop>, nowSeconds: Long, ports: Map<String, PortInfo>) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(TT.surface, TT.panelShape(14.dp))
            .border(1.dp, TT.border, TT.panelShape(14.dp))
            .padding(14.dp)
    ) {
        SectionLabel(text = "LAST 7 DAYS (PORTS)")

        Spacer(Modifier.height(8.dp))

        stops.forEachIndexed { index, stop ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = ports[stop.unlocode]?.label ?: stop.unlocode,
                    color = TT.inkPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.width(108.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "arrived ${Format.agoShort(stop.arrivedAtEpoch, nowSeconds)}",
                        color = TT.inkSecondary,
                        fontSize = 12.sp
                    )
                    Text(
                        text = stop.departedAtEpoch?.let {
                            "left ${Format.agoShort(it, nowSeconds)}"
                        } ?: "still there",
                        color = if (stop.departedAtEpoch == null) TT.good else TT.inkMuted,
                        fontSize = 12.sp
                    )
                }
            }
            if (index != stops.lastIndex) {
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/**
 * The map's plane pin, built entirely from fields already shown as text on
 * [FlightCard] — never a new position source. Solid/live only when the
 * aircraft is actually on the ground right now; airborne or no-signal falls
 * back to the most recent stop ([FlightStatus.recentStops] is newest-first)
 * as a clearly-labeled "last known" marker, exactly mirroring how the app
 * already talks about position everywhere else — see [FlightStatus] and
 * [MapPin].
 */
/**
 * Polls [LiveFlightTracker] — OpenSky, then adsb.lol — for [flight]'s live
 * ADS-B position, but only while it's actually worth
 * asking: [FlightState.AIRBORNE] and a usable [FlightStatus.icaoHex]. On the
 * ground there's nothing moving to track — the static airport pin already
 * is the accurate position — and this whole mechanism only runs while this
 * composable is in the composition, i.e. only while this exact person's
 * detail screen is open, so closing it stops the polling instead of it
 * running in the background for everyone. `produceState` fetches once
 * immediately on entering composition (before the first [delay]), so
 * opening the screen shows a live dot right away rather than waiting out a
 * full poll interval first. See `OpenSkyClient` (in `LiveTracking.kt`) for
 * the rate-limit reasoning behind the interval.
 */
// Lint's ProduceStateDoesNotAssignValue check flags this and
// rememberLiveNews below with "produceState calls should assign value
// inside the producer lambda" — a false positive specific to this exact,
// otherwise-idiomatic shape: an infinite `while (true) { poll; delay }`
// loop that assigns `value` conditionally partway through the loop body.
// The checker's own AST walk only looks at the producer lambda's
// top-level statements, never recursing into a while/for loop's body (a
// documented limitation, not something particular to this codebase — the
// same pattern trips the same check in plenty of other Compose projects
// doing ordinary continuous polling), so it can't see the assignment
// that's actually there every tick. Suppressed rather than restructured:
// rewriting a correct, already-tested polling loop just to satisfy a
// shallow static check would trade real, verified behavior for lint
// silence, which is the wrong direction. See LiveFlightTracker's own
// "never throws" contract for why the loop body never needs a try/catch
// of its own around the fetch this assigns from.
@Suppress("ProduceStateDoesNotAssignValue")
@Composable
private fun rememberLiveFlightPosition(flight: FlightStatus?): LivePosition? {
    val icaoHex = flight?.let { f ->
        f.icaoHex.takeIf { f.state == FlightState.AIRBORNE && it.isNotBlank() }
    }

    return produceState<LivePosition?>(initialValue = null, icaoHex) {
        value = null
        if (icaoHex == null) return@produceState
        while (true) {
            awaitAppForeground()
            LiveFlightTracker.fetchPosition(icaoHex)?.let { value = it }
            delay(LIVE_POSITION_POLL_MILLIS)
        }
    }.value
}

private const val LIVE_POSITION_POLL_MILLIS = 20_000L

/**
 * Polls [GoogleNewsClient] for [query] (a person's own name — the same
 * string `backend/holdings.json`'s `newsQuery` field carries for everyone
 * currently wired up) for as long as this person's detail screen stays
 * open, same "only while actually being looked at" scoping as
 * [rememberLiveFlightPosition]. Fetches once immediately on entering
 * composition, then on [LIVE_NEWS_POLL_MILLIS] — headlines don't turn over
 * fast enough to justify the flight tracker's 20s cadence, so this polls
 * far less often.
 *
 * Falls back to [seedNews] (the hand-curated/backend-snapshot list already
 * baked into this [Person]) whenever the live fetch hasn't returned
 * anything yet or comes back empty — a network hiccup or a momentarily
 * empty RSS response should never blank out a section that already had
 * something to show.
 */
// Same ProduceStateDoesNotAssignValue false positive as
// rememberLiveFlightPosition above, same reason (the `value = result`
// assignment sits inside this function's own `while (true)` loop, which
// the lint check's shallow AST walk never looks inside) — see that
// function's doc comment for the full explanation.
@Suppress("ProduceStateDoesNotAssignValue")
@Composable
private fun rememberLiveNews(query: String, seedNews: List<NewsItem>): List<NewsItem> {
    val live = produceState(initialValue = emptyList<NewsItem>(), query) {
        value = emptyList()
        while (true) {
            awaitAppForeground()
            val result = GoogleNewsClient.fetchNews(query)
            if (result.isNotEmpty()) value = result
            delay(LIVE_NEWS_POLL_MILLIS)
        }
    }.value

    return live.ifEmpty { seedNews }
}

private const val LIVE_NEWS_POLL_MILLIS = 5 * 60_000L

/**
 * Straight-line ("great-circle") distance between two points, nautical
 * miles — the same haversine formula backend/snapshot_worker.py's own
 * _haversine_nm() uses, so the client and backend never drift into
 * disagreeing about what a nautical mile between two coordinates is.
 */
private fun haversineNm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 3440.065
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dPhi = Math.toRadians(lat2 - lat1)
    val dLambda = Math.toRadians(lon2 - lon1)
    val a = sin(dPhi / 2).let { it * it } + cos(p1) * cos(p2) * sin(dLambda / 2).let { it * it }
    return 2 * r * asin(sqrt(a))
}

/**
 * Straight-line total across confirmed stop-to-stop legs in [stops] — an
 * honest lower bound on distance actually traveled (a real flight path
 * curves, refuels, diverts; this is "as the crow flies" leg by leg summed
 * up), not a claim about the literal route flown. Only ever built from
 * [FlightStatus.recentStops], the same trailing-window data the timeline
 * strip and map pins already draw from — no new tracking, no new position
 * source, just arithmetic on airport-to-airport legs already on record.
 * Null when fewer than two stops resolve to a known airport with
 * coordinates — same "we don't guess" rule as everywhere else in this app.
 */
private fun flightTripDistanceNm(stops: List<AirportStop>, airports: Map<String, AirportInfo>): Double? {
    val coords = stops.sortedBy { it.arrivedAtEpoch }.mapNotNull { stop ->
        airports[stop.icao]?.let { info ->
            if (info.lat != null && info.lon != null) info.lat to info.lon else null
        }
    }
    if (coords.size < 2) return null
    return (1 until coords.size).sumOf { i ->
        haversineNm(coords[i - 1].first, coords[i - 1].second, coords[i].first, coords[i].second)
    }
}

/** The maritime mirror of [flightTripDistanceNm] — see its doc comment. */
private fun vesselTripDistanceNm(stops: List<PortStop>, ports: Map<String, PortInfo>): Double? {
    val coords = stops.sortedBy { it.arrivedAtEpoch }.mapNotNull { stop ->
        ports[stop.unlocode]?.let { info ->
            if (info.lat != null && info.lon != null) info.lat to info.lon else null
        }
    }
    if (coords.size < 2) return null
    return (1 until coords.size).sumOf { i ->
        haversineNm(coords[i - 1].first, coords[i - 1].second, coords[i].first, coords[i].second)
    }
}

private fun flightMapPin(
    flight: FlightStatus?,
    airports: Map<String, AirportInfo>,
    livePosition: LivePosition? = null
): MapPin? {
    if (flight == null) return null

    if (livePosition != null) {
        val speedLabel = livePosition.groundSpeedMps
            ?.let { mps -> " — ~${(mps * 1.94384).roundToInt()} kt" }
            .orEmpty()
        return MapPin(
            label = flight.tail,
            lat = livePosition.lat,
            lon = livePosition.lon,
            glyph = "✈",
            isLive = true,
            caption = "airborne now — live position (${livePosition.source})$speedLabel"
        )
    }

    val isLive = flight.state == FlightState.ON_GROUND && flight.currentAirportIcao != null
    val icao = if (isLive) {
        flight.currentAirportIcao
    } else {
        flight.recentStops.firstOrNull()?.icao ?: flight.arrivedIcao ?: flight.departedIcao
    }
    val info = icao?.let { airports[it] }
    val lat = info?.lat
    val lon = info?.lon
    if (icao != null && lat != null && lon != null) {
        val caption = if (isLive) {
            "on the ground now"
        } else when (flight.state) {
            FlightState.AIRBORNE -> "airborne now — no live signal yet, last known ground position"
            else -> "no current signal — last known position"
        }
        return MapPin(label = info.label, lat = lat, lon = lon, glyph = "✈", isLive = isLive, caption = caption)
    }

    // Fallback added 2026-09-23 — a real ON_GROUND fix that didn't resolve
    // to any known airport (see FlightStatus.currentLat's doc comment).
    // Rather than showing nothing, plot the raw position with a coarse
    // place name, clearly flagged as approximate (see MapPin.isApproximate).
    val fallbackLat = flight.currentLat
    val fallbackLon = flight.currentLon
    val fallbackLocation = flight.generalLocation
    if (fallbackLat != null && fallbackLon != null && fallbackLocation != null) {
        return MapPin(
            label = fallbackLocation,
            lat = fallbackLat,
            lon = fallbackLon,
            glyph = "✈",
            isLive = false,
            caption = "on the ground now — no known airport nearby, approximate area only",
            isApproximate = true
        )
    }
    return null
}

/** The maritime mirror of [flightMapPin] — see [VesselStatus] and [MapPin]. */
private fun vesselMapPin(vessel: VesselStatus?, ports: Map<String, PortInfo>): MapPin? {
    if (vessel == null) return null
    val isLive = vessel.state == VesselState.IN_PORT && vessel.currentPortUnlocode != null
    val code = if (isLive) {
        vessel.currentPortUnlocode
    } else {
        vessel.recentStops.firstOrNull()?.unlocode ?: vessel.arrivedPortUnlocode ?: vessel.departedPortUnlocode
    }
    val info = code?.let { ports[it] }
    val lat = info?.lat
    val lon = info?.lon
    if (code != null && lat != null && lon != null) {
        val caption = if (isLive) {
            "in port now"
        } else when (vessel.state) {
            VesselState.UNDERWAY -> "underway now — last known port, not live"
            else -> "no current signal — last known position"
        }
        return MapPin(label = info.label, lat = lat, lon = lon, glyph = "⚓", isLive = isLive, caption = caption)
    }

    // Fallback added 2026-09-23 — a real AIS fix (moored or underway) that
    // didn't resolve to any known port (see VesselStatus.currentLat's doc
    // comment). Rather than showing nothing, plot the raw position with a
    // coarse place name, clearly flagged as approximate (see MapPin.isApproximate).
    val fallbackLat = vessel.currentLat
    val fallbackLon = vessel.currentLon
    val fallbackLocation = vessel.generalLocation
    if (fallbackLat != null && fallbackLon != null && fallbackLocation != null) {
        val caption = when (vessel.state) {
            VesselState.UNDERWAY -> "underway now — no known port nearby, approximate area only"
            else -> "in port now — no known port nearby, approximate area only"
        }
        return MapPin(
            label = fallbackLocation,
            lat = fallbackLat,
            lon = fallbackLon,
            glyph = "⚓",
            isLive = false,
            caption = caption,
            isApproximate = true
        )
    }
    return null
}

/** Sentinel buckets get a fixed status-style treatment; real airports get identity colors. */
private fun bucketLabel(bucket: String, airports: Map<String, AirportInfo>): String = when (bucket) {
    "IN_FLIGHT" -> "In flight"
    "NO_SIGNAL" -> "No signal"
    "SIGNAL_LOST" -> "Signal lost — possibly landed"
    "UNKNOWN_AIRPORT" -> "Unmatched airport"
    "OTHER" -> "Other airports"
    else -> airports[bucket]?.label ?: bucket
}

/**
 * What the *live, right-now* edge of the flight strip reads as, whenever
 * there's no confirmed stop covering this exact moment (either the whole
 * tracked window is empty, or there's a gap after the last known stop).
 * Plain [FlightState.UNKNOWN] alone can't tell an ordinary short ADS-B gap
 * apart from one that's run long enough the backend no longer believes the
 * aircraft is still on the same leg — see [FlightStatus.currentBucket]'s doc
 * comment and flight_status()'s `was == "AIRBORNE"` branch for where that
 * distinction actually gets made.
 */
private fun liveFlightBucket(flightState: FlightState, currentBucket: String?): String = when {
    flightState == FlightState.AIRBORNE -> "IN_FLIGHT"
    currentBucket == "SIGNAL_LOST" -> "SIGNAL_LOST"
    else -> "NO_SIGNAL"
}

private fun liveFlightColor(bucket: String): Color = when (bucket) {
    "IN_FLIGHT" -> TT.warning
    "SIGNAL_LOST" -> TT.critical
    else -> TT.inkMuted
}

/**
 * Reconstructs the trailing window as literal chronological blocks from
 * [FlightStatus.recentStops] — the same real arrival/departure epochs
 * [LocationHistoryCard] already lists, just laid out on a timeline instead
 * of read top to bottom. Deliberately not built from
 * [FlightStatus.locationBreakdown]: that field is a server-side aggregate
 * (bucket -> total seconds, no per-entry ordering promised), and deriving
 * totals from these same segments instead — see [TimelineStrip]'s own
 * legend — means the strip and its numbers can't drift out of sync with a
 * second source of truth.
 *
 * Gaps get the same two-sentinel honesty [FlightStatus.locationBreakdown]
 * always used: the stretch before the first stop we have any record of is
 * "No signal" (we genuinely don't know), while every gap *between* two
 * confirmed stops is "In flight" (it necessarily got from one to the other
 * somehow). A trailing gap after the last known stop — the aircraft has
 * since left but no new stop has landed yet — reads the same way, keyed off
 * [flightState] rather than assumed.
 *
 * Real, distinct airports are capped at 5 identity colors (sorted ICAO,
 * same stable-order philosophy as everywhere else in this app — a slot
 * never repaints just because the data reshuffled), folding any more into
 * one "Other airports" color while each stop keeps its own true position on
 * the strip.
 */
private fun buildTimelineSegments(
    recentStops: List<AirportStop>,
    flightState: FlightState,
    currentBucket: String?,
    trackedSeconds: Long,
    nowEpoch: Long,
    airports: Map<String, AirportInfo>
): List<TimelineSegment> {
    val windowStart = nowEpoch - trackedSeconds
    val sorted = recentStops.sortedBy { it.arrivedAtEpoch }
    val cutoffIndex = sorted.indexOfFirst { (it.departedAtEpoch ?: nowEpoch) > windowStart }
    val ordered = if (cutoffIndex == -1) emptyList() else sorted.subList(cutoffIndex, sorted.size)
    // A stop excluded above always had a real, confirmed departedAtEpoch (a
    // still-parked stop's departedAtEpoch is null, which always passes the
    // filter) - so if one was excluded, the plane definitely left an airport
    // before windowStart. trackedSeconds (from the backend's own state
    // history) and recentStops' epochs (from ADS-B) are two independently
    // updated clocks and can disagree by a few minutes, so this anchor can
    // sit just outside windowStart even for a fully-accounted-for leg.
    // Without checking for it, that leg rendered as "No signal" instead of
    // "In flight" - see the bug this fixes: a landed plane whose departure
    // fell a few minutes before the computed window start showed its entire
    // flight as unexplained no-signal time.
    val hasConfirmedAnchorBefore = cutoffIndex > 0

    if (ordered.isEmpty()) {
        // No confirmed *stop* falls inside the window — but a plane that took
        // off before this window started and hasn't landed since (the normal
        // shape of a long-haul leg, or just "we started watching mid-flight")
        // will always have zero stops while still being solidly confirmed
        // IN_FLIGHT for the entire window. This card only renders at all when
        // trackedSeconds > 0, which already guarantees *something* real was
        // confirmed, so "no stops yet" means "no landing yet", not "no signal
        // ever" — reflect the live state instead of defaulting to the
        // no-signal sentinel, which used to paint a plane that's been
        // confirmed airborne the whole time as 100% "No signal". A long
        // enough signal-loss gap still resolves to SIGNAL_LOST here too,
        // same as the trailing-gap case below.
        val bucket = liveFlightBucket(flightState, currentBucket)
        val color = liveFlightColor(bucket)
        return listOf(TimelineSegment(bucketLabel(bucket, airports), windowStart, nowEpoch, color))
    }

    val topIcaos = ordered.map { it.icao }.distinct().sorted().take(5)
    val colorByIcao = topIcaos.withIndex().associate { (i, icao) -> icao to TT.categorical[i] }
    fun colorFor(icao: String) = colorByIcao[icao] ?: TT.categorical.last()
    fun labelFor(icao: String) = bucketLabel(if (colorByIcao.containsKey(icao)) icao else "OTHER", airports)

    val segments = mutableListOf<TimelineSegment>()
    var cursor = windowStart

    for (stop in ordered) {
        val start = maxOf(stop.arrivedAtEpoch, windowStart)
        val end = (stop.departedAtEpoch ?: nowEpoch).coerceAtMost(nowEpoch)
        if (start > cursor) {
            val unknown = segments.isEmpty() && !hasConfirmedAnchorBefore
            val gapKey = if (unknown) "NO_SIGNAL" else "IN_FLIGHT"
            val gapColor = if (unknown) TT.inkMuted else TT.warning
            segments += TimelineSegment(bucketLabel(gapKey, airports), cursor, start, gapColor)
        }
        if (end > start) {
            segments += TimelineSegment(labelFor(stop.icao), start, end, colorFor(stop.icao))
        }
        cursor = maxOf(cursor, end)
    }

    if (cursor < nowEpoch) {
        val trailingKey = liveFlightBucket(flightState, currentBucket)
        val trailingColor = liveFlightColor(trailingKey)
        segments += TimelineSegment(bucketLabel(trailingKey, airports), cursor, nowEpoch, trailingColor)
    }

    return segments
}

/**
 * One "TIME BY LOCATION" card holding whichever timelines this person has —
 * a small "Plane" strip, a small "Boat" strip, or both stacked together —
 * instead of two separate cards with duplicate headers/borders. A plane and
 * a boat move independently of each other, so both stay visible at once
 * rather than forcing a tap to switch between them. Either half is left out
 * entirely (not just gated on trackedSeconds > 0 inside) so the header
 * doesn't render at all for a person with neither.
 */
@Composable
private fun TimeByLocationCard(
    flight: FlightStatus?,
    vessel: VesselStatus?,
    nowSeconds: Long,
    airports: Map<String, AirportInfo>,
    ports: Map<String, PortInfo>
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(TT.surface, TT.panelShape(14.dp))
            .border(1.dp, TT.border, TT.panelShape(14.dp))
            .padding(14.dp)
    ) {
        SectionLabel(text = "TIME BY LOCATION")

        if (flight != null) {
            Spacer(Modifier.height(10.dp))
            TimeByLocationStrip(
                label = "Plane",
                trackedCaption = "${Format.duration(flight.trackedSeconds)} tracked",
                segments = buildTimelineSegments(
                    recentStops = flight.recentStops,
                    flightState = flight.state,
                    currentBucket = flight.currentBucket,
                    trackedSeconds = flight.trackedSeconds,
                    nowEpoch = nowSeconds,
                    airports = airports
                ),
                windowStartEpoch = nowSeconds - flight.trackedSeconds,
                windowEndEpoch = nowSeconds
            )
        }

        if (vessel != null) {
            Spacer(Modifier.height(if (flight != null) 16.dp else 10.dp))
            TimeByLocationStrip(
                label = "Boat",
                trackedCaption = "${Format.duration(vessel.trackedSeconds)} tracked",
                segments = buildVesselTimelineSegments(
                    recentStops = vessel.recentStops,
                    vesselState = vessel.state,
                    currentBucket = vessel.currentBucket,
                    trackedSeconds = vessel.trackedSeconds,
                    nowEpoch = nowSeconds,
                    ports = ports
                ),
                windowStartEpoch = nowSeconds - vessel.trackedSeconds,
                windowEndEpoch = nowSeconds
            )
        }
    }
}

/**
 * One labeled timeline strip ("Plane" or "Boat") inside the shared
 * [TimeByLocationCard] — just the label row plus [TimelineStrip], factored
 * out so the plane and boat halves render identically instead of drifting
 * apart the way two full copy-pasted cards eventually would.
 */
@Composable
private fun TimeByLocationStrip(
    label: String,
    trackedCaption: String,
    segments: List<TimelineSegment>,
    windowStartEpoch: Long,
    windowEndEpoch: Long
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = TT.inkSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = trackedCaption,
                color = TT.inkMuted,
                fontSize = 11.sp
            )
        }

        Spacer(Modifier.height(8.dp))

        TimelineStrip(
            segments = segments,
            windowStartEpoch = windowStartEpoch,
            windowEndEpoch = windowEndEpoch
        )
    }
}

/** The maritime mirror of [bucketLabel] — see [VesselStatus.locationBreakdown]'s sentinel names. */
private fun vesselBucketLabel(bucket: String, ports: Map<String, PortInfo>): String = when (bucket) {
    "UNDERWAY" -> "Underway"
    "NO_SIGNAL" -> "No signal"
    "SIGNAL_LOST" -> "Signal lost — possibly arrived"
    "UNKNOWN_PORT" -> "Unmatched port"
    "OTHER" -> "Other ports"
    else -> ports[bucket]?.label ?: bucket
}

/**
 * The maritime mirror of [liveFlightBucket] — see its doc comment. What the
 * *live, right-now* edge of the vessel strip reads as, whenever there's no
 * confirmed stop covering this exact moment. Added 2026-09-24 alongside
 * [VesselStatus.currentBucket] itself — previously this case couldn't
 * distinguish an ordinary short AIS gap from a confirmed SIGNAL_LOST one the
 * way the flight side always could.
 */
private fun liveVesselBucket(vesselState: VesselState, currentBucket: String?): String = when {
    vesselState == VesselState.UNDERWAY -> "UNDERWAY"
    currentBucket == "SIGNAL_LOST" -> "SIGNAL_LOST"
    else -> "NO_SIGNAL"
}

/** The maritime mirror of [liveFlightColor]. */
private fun liveVesselColor(bucket: String): Color = when (bucket) {
    "UNDERWAY" -> TT.warning
    "SIGNAL_LOST" -> TT.critical
    else -> TT.inkMuted
}

/**
 * The maritime mirror of [buildTimelineSegments] — same construction, same
 * reasoning (rebuilt from [PortStop]s rather than from
 * [VesselStatus.locationBreakdown] so the strip and its legend can't drift
 * out of sync with a second, server-computed total), just swapping in
 * [VesselState]/[PortStop]/[PortInfo] for their flight equivalents. "In
 * flight" becomes "Underway" for the gaps between two confirmed port stops —
 * a vessel that left one port and arrived at another necessarily spent that
 * gap at sea, exactly as a plane between two airports necessarily spent it
 * airborne.
 */
private fun buildVesselTimelineSegments(
    recentStops: List<PortStop>,
    vesselState: VesselState,
    currentBucket: String?,
    trackedSeconds: Long,
    nowEpoch: Long,
    ports: Map<String, PortInfo>
): List<TimelineSegment> {
    val windowStart = nowEpoch - trackedSeconds
    val sorted = recentStops.sortedBy { it.arrivedAtEpoch }
    val cutoffIndex = sorted.indexOfFirst { (it.departedAtEpoch ?: nowEpoch) > windowStart }
    val ordered = if (cutoffIndex == -1) emptyList() else sorted.subList(cutoffIndex, sorted.size)
    // Same fix, same reasoning as buildTimelineSegments above: a stop
    // excluded here always had a real, confirmed departedAtEpoch, so its
    // existence means the vessel definitely left a port before windowStart
    // even though trackedSeconds and recentStops can disagree by a few
    // minutes. Without this, a completed port-to-port leg rendered as "No
    // signal" instead of "Underway".
    val hasConfirmedAnchorBefore = cutoffIndex > 0

    if (ordered.isEmpty()) {
        // Same fix as buildTimelineSegments' matching branch above: a yacht
        // that's been underway the whole tracked window (no port stop yet)
        // has zero recentStops by construction, but trackedSeconds > 0 (the
        // only way this card renders) already guarantees it's been genuinely
        // confirmed — so reflect the live state rather than defaulting to
        // "No signal" for a vessel that's actually been tracked the entire time.
        // A long enough signal-loss gap still resolves to SIGNAL_LOST here
        // too, same as the trailing-gap case below.
        val bucket = liveVesselBucket(vesselState, currentBucket)
        val color = liveVesselColor(bucket)
        return listOf(TimelineSegment(vesselBucketLabel(bucket, ports), windowStart, nowEpoch, color))
    }

    val topCodes = ordered.map { it.unlocode }.distinct().sorted().take(5)
    val colorByCode = topCodes.withIndex().associate { (i, code) -> code to TT.categorical[i] }
    fun colorFor(code: String) = colorByCode[code] ?: TT.categorical.last()
    fun labelFor(code: String) = vesselBucketLabel(if (colorByCode.containsKey(code)) code else "OTHER", ports)

    val segments = mutableListOf<TimelineSegment>()
    var cursor = windowStart

    for (stop in ordered) {
        val start = maxOf(stop.arrivedAtEpoch, windowStart)
        val end = (stop.departedAtEpoch ?: nowEpoch).coerceAtMost(nowEpoch)
        if (start > cursor) {
            val unknown = segments.isEmpty() && !hasConfirmedAnchorBefore
            val gapKey = if (unknown) "NO_SIGNAL" else "UNDERWAY"
            val gapColor = if (unknown) TT.inkMuted else TT.warning
            segments += TimelineSegment(vesselBucketLabel(gapKey, ports), cursor, start, gapColor)
        }
        if (end > start) {
            segments += TimelineSegment(labelFor(stop.unlocode), start, end, colorFor(stop.unlocode))
        }
        cursor = maxOf(cursor, end)
    }

    if (cursor < nowEpoch) {
        val trailingKey = liveVesselBucket(vesselState, currentBucket)
        val trailingColor = liveVesselColor(trailingKey)
        segments += TimelineSegment(vesselBucketLabel(trailingKey, ports), cursor, nowEpoch, trailingColor)
    }

    return segments
}

/**
 * Entry point into [FamilyHistoryScreen] — only ever placed in the list when
 * [FamilyHistoryRepository] actually has a sourced entry for this person
 * (see the `hasFamilyHistory` check above), so this card itself never has
 * to render an empty or "coming soon" state.
 */
@Composable
private fun FamilyHistoryEntryCard(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            // Whole card is the tap target, so hudTouchable goes first — see
            // PersonRow's identical reasoning in TrackerScreen.kt.
            .hudTouchable(
                cornerLength = 9.dp,
                cornerInset = 3.dp,
                elevation = 2.dp,
                shape = TT.panelShape(14.dp),
                onClick = onClick
            )
            .background(TT.surface, TT.panelShape(14.dp))
            .border(1.dp, TT.border, TT.panelShape(14.dp))
            .hudCorners()
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            SectionLabel(text = "FAMILY HISTORY")
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Where they came from, as far back as the record goes.",
                color = TT.inkSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(text = "→", color = TT.accentCyan, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Renders [Person.bio] — see that field's doc comment for why it's one
 * pre-written paragraph rather than separate birthplace/education fields.
 * The link-out social glyph that used to sit at the end of this card now
 * lives on [PersonHeader] instead, overlaid on the photo next to the back
 * button — same identity information, no longer several cards away from
 * the rest of it.
 */
@Composable
private fun BiographyCard(bio: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(TT.surface, TT.panelShape(14.dp))
            .border(1.dp, TT.border, TT.panelShape(14.dp))
            .padding(14.dp)
    ) {
        SectionLabel(text = "BIOGRAPHY")
        Spacer(Modifier.height(8.dp))
        Text(
            text = bio,
            color = TT.inkSecondary,
            fontSize = 13.sp,
            lineHeight = 19.sp
        )
    }
}

/**
 * Link out, never embed — pulling live posts means a metered per-read API
 * bill (X's read API has no free tier as of 2026) that scales with users,
 * the one thing the whole snapshot architecture exists to avoid. Identifies
 * which mark to show from [url]'s host only — it never guesses from the
 * person, just reads the link they gave us. Rendered on [PersonHeader] as
 * just this platform mark inside its own dark chip — no "View on X" text,
 * since the mark alone already reads as a link-out the way it would on any
 * other profile.
 */
private fun socialPlatformGlyph(url: String): String {
    val host = try {
        java.net.URI(url).host.orEmpty().lowercase()
    } catch (_: Exception) {
        ""
    }
    return when {
        host.contains("x.com") || host.contains("twitter.com") -> "X"
        host.contains("bsky.app") -> "🦋"
        host.contains("instagram.com") -> "📷"
        host.contains("facebook.com") -> "f"
        host.contains("threads.net") || host.contains("threads.com") -> "@"
        host.contains("linkedin.com") -> "in"
        else -> "↗"
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(text = label, color = TT.inkMuted, fontSize = 13.sp, modifier = Modifier.width(92.dp))
        Text(text = value, color = TT.inkSecondary, fontSize = 13.sp)
    }
}

@Composable
private fun NewsRow(item: NewsItem, nowSeconds: Long) {
    val uriHandler = LocalUriHandler.current

    Column(
        Modifier
            .fillMaxWidth()
            .hudTouchable(
                cornerLength = 8.dp,
                cornerInset = 3.dp,
                elevation = 2.dp,
                shape = TT.panelShape(12.dp)
            ) { uriHandler.openUri(item.url) }
            .background(TT.surface, TT.panelShape(12.dp))
            .border(1.dp, TT.border, TT.panelShape(12.dp))
            .padding(14.dp)
    ) {
        Text(text = item.title, color = TT.inkPrimary, fontSize = 14.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${item.source} · ${Format.agoShort(item.publishedEpoch, nowSeconds)}",
            color = TT.inkMuted,
            fontSize = 11.sp
        )
    }
}
