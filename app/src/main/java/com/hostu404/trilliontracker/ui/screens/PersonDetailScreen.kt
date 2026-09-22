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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
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
import com.hostu404.trilliontracker.ui.Format
import com.hostu404.trilliontracker.ui.TrackerUiState
import com.hostu404.trilliontracker.ui.awaitAppForeground
import com.hostu404.trilliontracker.ui.components.DeltaChip
import com.hostu404.trilliontracker.ui.components.MapFocusRequest
import com.hostu404.trilliontracker.ui.components.MapPin
import com.hostu404.trilliontracker.ui.components.NoteChip
import com.hostu404.trilliontracker.ui.components.TimelineSegment
import com.hostu404.trilliontracker.ui.components.TimelineStrip
import com.hostu404.trilliontracker.ui.components.hudCorners
import com.hostu404.trilliontracker.ui.components.sickeningChromaticAberration
import com.hostu404.trilliontracker.ui.components.RollingNumber
import com.hostu404.trilliontracker.ui.components.Sparkline
import com.hostu404.trilliontracker.ui.components.StatusChip
import com.hostu404.trilliontracker.ui.components.ThresholdGauge
import com.hostu404.trilliontracker.ui.components.WorldMapCard
import com.hostu404.trilliontracker.ui.theme.TT

@Composable
fun PersonDetailScreen(
    personId: String,
    state: TrackerUiState,
    onBack: () -> Unit,
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
        var mapFocusToken by remember { mutableStateOf(0) }
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
                    onBack = onBack
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
                        .border(1.dp, TT.border, TT.panelShape(14.dp))
                        .hudCorners()
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
                            StatusChip(glyph = "●", label = "LIVE · market price", color = TT.good)
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

                    Sparkline(
                        values = person.history,
                        color = TT.series,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "last ${person.history.size} closes",
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

            item {
                FlightCard(
                    flight = person.flight,
                    nowSeconds = nowSeconds,
                    airports = airports,
                    onFocusMap = flightPin?.let { pin ->
                        { mapFocusPin = pin; mapFocusToken++ }
                    }
                )
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

            person.flight?.let { flight ->
                // trackedSeconds alone is the right gate — buildTimelineSegments
                // already renders a correct, honest strip with zero stops (the
                // whole window as "No signal"), for exactly the case of a plane
                // that's been tracked but never yet caught on the ground.
                // Requiring recentStops too used to hide the card for that
                // case entirely, even though there was real tracked time to
                // show.
                if (flight.trackedSeconds > 0) {
                    item { TimeByLocationCard(flight, nowSeconds, airports) }
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
 * height buys back space rather than spending it. The back button lives
 * inside this banner too now — tucked into its own top-left corner using
 * the same [TT.panelShape] chamfer the banner itself is clipped to, rather
 * than sitting above it as a separate row that only added empty space.
 * [socialUrl] mirrors that same treatment in the top-right corner — moved
 * off the end of [BiographyCard] so a profile's link-out sits with the rest
 * of its identity (photo, name, back button) instead of several cards
 * further down the screen. It needs the same dark chip [onBack] already
 * uses, not [BiographyCard]'s old bare-glyph treatment, because it now has
 * to stay legible over an arbitrary photo instead of this app's own
 * surface color.
 */
@Composable
private fun PersonHeader(
    name: String,
    company: String,
    photoUrl: String?,
    wikipediaUrl: String?,
    birthDate: String?,
    residence: String?,
    socialUrl: String?,
    onBack: () -> Unit
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
                placeholder = ColorPainter(TT.surface),
                modifier = Modifier
                    .fillMaxSize()
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
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = name,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(text = company, color = Color.White.copy(alpha = 0.78f), fontSize = 13.sp)

            if (age != null && birthLabel != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "Age $age · Born $birthLabel",
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 11.sp
                )
            }

            if (residence != null || wikipediaUrl != null) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (residence != null) {
                        Text(
                            text = "Lives in $residence ↗",
                            color = TT.accentCyan,
                            fontSize = 11.sp,
                            modifier = Modifier.clickable { uriHandler.openUri(townMapUrl(residence)) }
                        )
                    }
                    if (residence != null && wikipediaUrl != null) {
                        Text(
                            text = "   ·   ",
                            color = Color.White.copy(alpha = 0.4f),
                            fontSize = 11.sp
                        )
                    }
                    if (wikipediaUrl != null) {
                        Text(
                            text = "Wikipedia ↗",
                            color = TT.accentCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { uriHandler.openUri(wikipediaUrl) }
                        )
                    }
                }
            }
        }

        // Nested into the banner's own top-left chamfer rather than sitting
        // above it — same [TT.panelShape] cut the whole photo is clipped to,
        // so the button's corner and the photo's corner read as one bevel,
        // not two different shapes stacked together.
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .clip(TT.panelShape(14.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .border(1.dp, TT.borderBright, TT.panelShape(14.dp))
                .clickable(onClick = onBack)
                .padding(start = 14.dp, top = 9.dp, end = 11.dp, bottom = 8.dp)
        ) {
            Text(
                text = "←",
                color = TT.accentCyan,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // The mirror image of the back button above — same chip, same
        // chamfer, opposite corner. Link-out only, never embedded (see
        // [socialPlatformGlyph]'s own doc comment for why).
        if (socialUrl != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(TT.panelShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .border(1.dp, TT.borderBright, TT.panelShape(14.dp))
                    .clickable { uriHandler.openUri(socialUrl) }
                    .padding(start = 11.dp, top = 9.dp, end = 14.dp, bottom = 8.dp)
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
    flight: FlightStatus?,
    nowSeconds: Long,
    airports: Map<String, AirportInfo>,
    onFocusMap: (() -> Unit)? = null
) {
    val uriHandler = LocalUriHandler.current
    fun label(icao: String) = airports[icao]?.label ?: icao

    Column(
        Modifier
            .fillMaxWidth()
            .background(TT.surface, TT.panelShape(14.dp))
            .border(1.dp, TT.border, TT.panelShape(14.dp))
            .then(
                if (onFocusMap != null) Modifier.clickable(onClick = onFocusMap) else Modifier
            )
            .padding(14.dp)
    ) {
        SectionLabel(text = "AIRCRAFT")

        Spacer(Modifier.height(8.dp))

        if (flight == null) {
            Text(
                text = "No aircraft mapped.",
                color = TT.inkSecondary,
                fontSize = 14.sp
            )
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            when (flight.state) {
                FlightState.AIRBORNE ->
                    StatusChip(glyph = "✈", label = "AIRBORNE NOW", color = TT.warning)
                FlightState.ON_GROUND ->
                    StatusChip(glyph = "●", label = "ON GROUND", color = TT.inkSecondary)
                FlightState.UNKNOWN ->
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
                flight.state == FlightState.AIRBORNE -> "In the air — no fixed location"
                else -> "No signal"
            }
        )

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
                modifier = Modifier.clickable { uriHandler.openUri(url) }
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
 * destination straight from the vessel's own AIS broadcast. It's shown, but
 * clearly labeled as crew-entered and unverified — it's routinely blank,
 * stale, or informal shorthand, nothing like a filed flight plan.
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

    Column(
        Modifier
            .fillMaxWidth()
            .background(TT.surface, TT.panelShape(14.dp))
            .border(1.dp, TT.border, TT.panelShape(14.dp))
            .then(
                if (onFocusMap != null) Modifier.clickable(onClick = onFocusMap) else Modifier
            )
            .padding(14.dp)
    ) {
        SectionLabel(text = "VESSEL")

        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            when (vessel.state) {
                VesselState.UNDERWAY ->
                    StatusChip(glyph = "⚓", label = "UNDERWAY NOW", color = TT.warning)
                VesselState.IN_PORT ->
                    StatusChip(glyph = "●", label = "IN PORT", color = TT.inkSecondary)
                VesselState.UNKNOWN ->
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
                vessel.state == VesselState.UNDERWAY -> "At sea — no fixed location"
                else -> "No signal"
            }
        )

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
                if (vessel.selfReportedDestination != null) {
                    DetailRow(label = "Destination", value = vessel.selfReportedDestination + " (self-reported)")
                } else {
                    Text(
                        text = "At sea — no destination currently broadcast.",
                        color = TT.inkMuted,
                        fontSize = 13.sp
                    )
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
                modifier = Modifier.clickable { uriHandler.openUri(url) }
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
 * Polls [LiveFlightTracker] — OpenSky, then adsb.lol, then airplanes.live —
 * for [flight]'s live ADS-B position, but only while it's actually worth
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
    } ?: return null
    val info = airports[icao] ?: return null
    val lat = info.lat ?: return null
    val lon = info.lon ?: return null
    val caption = if (isLive) {
        "on the ground now"
    } else when (flight.state) {
        FlightState.AIRBORNE -> "airborne now — no live signal yet, last known ground position"
        else -> "no current signal — last known position"
    }
    return MapPin(label = info.label, lat = lat, lon = lon, glyph = "✈", isLive = isLive, caption = caption)
}

/** The maritime mirror of [flightMapPin] — see [VesselStatus] and [MapPin]. */
private fun vesselMapPin(vessel: VesselStatus?, ports: Map<String, PortInfo>): MapPin? {
    if (vessel == null) return null
    val isLive = vessel.state == VesselState.IN_PORT && vessel.currentPortUnlocode != null
    val code = if (isLive) {
        vessel.currentPortUnlocode
    } else {
        vessel.recentStops.firstOrNull()?.unlocode ?: vessel.arrivedPortUnlocode ?: vessel.departedPortUnlocode
    } ?: return null
    val info = ports[code] ?: return null
    val lat = info.lat ?: return null
    val lon = info.lon ?: return null
    val caption = if (isLive) {
        "in port now"
    } else when (vessel.state) {
        VesselState.UNDERWAY -> "underway now — last known port, not live"
        else -> "no current signal — last known position"
    }
    return MapPin(label = info.label, lat = lat, lon = lon, glyph = "⚓", isLive = isLive, caption = caption)
}

/** Sentinel buckets get a fixed status-style treatment; real airports get identity colors. */
private fun bucketLabel(bucket: String, airports: Map<String, AirportInfo>): String = when (bucket) {
    "IN_FLIGHT" -> "In flight"
    "NO_SIGNAL" -> "No signal"
    "UNKNOWN_AIRPORT" -> "Unmatched airport"
    "OTHER" -> "Other airports"
    else -> airports[bucket]?.label ?: bucket
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
    trackedSeconds: Long,
    nowEpoch: Long,
    airports: Map<String, AirportInfo>
): List<TimelineSegment> {
    val windowStart = nowEpoch - trackedSeconds
    val ordered = recentStops
        .sortedBy { it.arrivedAtEpoch }
        .filter { (it.departedAtEpoch ?: nowEpoch) > windowStart }

    if (ordered.isEmpty()) {
        return listOf(TimelineSegment(bucketLabel("NO_SIGNAL", airports), windowStart, nowEpoch, TT.inkMuted))
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
            val isLeadingGap = segments.isEmpty()
            val gapKey = if (isLeadingGap) "NO_SIGNAL" else "IN_FLIGHT"
            val gapColor = if (isLeadingGap) TT.inkMuted else TT.warning
            segments += TimelineSegment(bucketLabel(gapKey, airports), cursor, start, gapColor)
        }
        if (end > start) {
            segments += TimelineSegment(labelFor(stop.icao), start, end, colorFor(stop.icao))
        }
        cursor = maxOf(cursor, end)
    }

    if (cursor < nowEpoch) {
        val trailingKey = if (flightState == FlightState.AIRBORNE) "IN_FLIGHT" else "NO_SIGNAL"
        val trailingColor = if (flightState == FlightState.AIRBORNE) TT.warning else TT.inkMuted
        segments += TimelineSegment(bucketLabel(trailingKey, airports), cursor, nowEpoch, trailingColor)
    }

    return segments
}

/**
 * Time by location as a real chronological log — built entirely from data
 * already on the flight card, no extra tracking, just a different view of
 * the same trailing-window airport stops (see [buildTimelineSegments]).
 */
@Composable
private fun TimeByLocationCard(flight: FlightStatus, nowSeconds: Long, airports: Map<String, AirportInfo>) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(TT.surface, TT.panelShape(14.dp))
            .border(1.dp, TT.border, TT.panelShape(14.dp))
            .padding(14.dp)
    ) {
        SectionLabel(text = "TIME BY LOCATION")
        Spacer(Modifier.height(2.dp))
        Text(
            text = "${Format.duration(flight.trackedSeconds)} tracked",
            color = TT.inkMuted,
            fontSize = 11.sp
        )

        Spacer(Modifier.height(10.dp))

        TimelineStrip(
            segments = buildTimelineSegments(
                recentStops = flight.recentStops,
                flightState = flight.state,
                trackedSeconds = flight.trackedSeconds,
                nowEpoch = nowSeconds,
                airports = airports
            ),
            windowStartEpoch = nowSeconds - flight.trackedSeconds,
            windowEndEpoch = nowSeconds
        )
    }
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
            .background(TT.surface, TT.panelShape(14.dp))
            .border(1.dp, TT.border, TT.panelShape(14.dp))
            .hudCorners()
            .clickable(onClick = onClick)
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
 * just this platform mark inside the same dark chip [onBack] uses — no
 * "View on X" text, since the mark alone already reads as a link-out the
 * way it would on any other profile.
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
            .clickable { uriHandler.openUri(item.url) }
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
