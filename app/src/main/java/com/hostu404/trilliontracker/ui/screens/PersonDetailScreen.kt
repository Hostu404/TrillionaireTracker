package com.hostu404.trilliontracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hostu404.trilliontracker.data.AirportInfo
import com.hostu404.trilliontracker.data.AirportStop
import com.hostu404.trilliontracker.data.FamilyHistoryRepository
import com.hostu404.trilliontracker.data.FlightState
import com.hostu404.trilliontracker.data.FlightStatus
import com.hostu404.trilliontracker.data.GoogleNewsClient
import com.hostu404.trilliontracker.data.LivePosition
import com.hostu404.trilliontracker.data.NetWorthEngine
import com.hostu404.trilliontracker.data.NewsItem
import com.hostu404.trilliontracker.data.NewsThemeShare
import com.hostu404.trilliontracker.data.LifetimeLocationShare
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
import com.hostu404.trilliontracker.ui.PlaceNames
import com.hostu404.trilliontracker.ui.TrackerUiState
import com.hostu404.trilliontracker.ui.awaitAppForeground
import com.hostu404.trilliontracker.ui.components.DayChange
import com.hostu404.trilliontracker.ui.components.SheetLine
import com.hostu404.trilliontracker.ui.components.MapFocusRequest
import com.hostu404.trilliontracker.ui.components.MapPin
import com.hostu404.trilliontracker.ui.components.PersonPhoto
import com.hostu404.trilliontracker.ui.components.SegmentStyle
import com.hostu404.trilliontracker.ui.components.SegmentSwatch
import com.hostu404.trilliontracker.ui.components.ThemeLabel
import com.hostu404.trilliontracker.ui.components.TimelineSegment
import com.hostu404.trilliontracker.ui.components.TimelineStrip
import com.hostu404.trilliontracker.ui.components.Sparkline
import com.hostu404.trilliontracker.ui.components.StatusChip
import com.hostu404.trilliontracker.ui.components.isSparklineFlat
import com.hostu404.trilliontracker.ui.components.ThresholdGauge
import com.hostu404.trilliontracker.ui.components.WorldMapCard
import com.hostu404.trilliontracker.ui.components.pinSymbol
import java.util.Locale
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

    // No background here — App() already paints the page black.
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
        // sourced entry for — see FamilyHistoryScreen's own doc comment.
        // Nobody sees an empty or "nothing here yet" card; the entry point
        // simply doesn't render until there's real, cited content behind it.
        val hasFamilyHistory = remember(person.id) {
            FamilyHistoryRepository.entryFor(person.id, mapContext) != null
        }

        // One bordered sheet, the same on everyone's page: who they are ->
        // the number this app is about -> where their plane and boat are ->
        // what's being written about them. A section with nothing to show
        // says so rather than disappearing.
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, TT.border, TT.panelShape)
            ) {
                PersonHeader(
                    person = person,
                    onOpenFamilyHistory = if (hasFamilyHistory) {
                        { onOpenFamilyHistory(person.id) }
                    } else {
                        null
                    }
                )
                SheetLine()
                WealthCard(
                    person = person,
                    projected = projected,
                    threshold = threshold,
                    isLive = state.isLive(person.id),
                    driftPerSecond = state.driftPerSecond(person)
                )
                SheetLine()
                SectionLabel(text = "MOVEMENTS", modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 6.dp))
                WorldMapCard(countries = countries, pins = pins, focusRequest = mapFocusRequest)
                SheetLine(subtle = true)
                person.flight?.let { flight ->
                    FlightCard(
                        flight = flight,
                        pin = flightPin,
                        nowSeconds = nowSeconds,
                        airports = airports,
                        onFocusMap = flightPin?.let { pin -> { mapFocusPin = pin; mapFocusToken++ } }
                    )
                } ?: NothingOnRecord("✈ No plane on record")
                SheetLine(subtle = true)
                person.vessel?.let { vessel ->
                    BoatCard(
                        vessel = vessel,
                        pin = vesselPin,
                        nowSeconds = nowSeconds,
                        ports = ports,
                        onFocusMap = vesselPin?.let { pin -> { mapFocusPin = pin; mapFocusToken++ } }
                    )
                } ?: NothingOnRecord("⚓ No yacht on record")
                SheetLine()
                // distinctBy: two headlines with the same link are the same
                // story. Only the top few are shown; the backend still reads
                // (and classifies) more for the all-time theme tally.
                NewsCard(liveNews.distinctBy { it.url }.take(SHOWN_HEADLINES), person.lifetimeNewsThemes, nowSeconds)
            }
        }
    }
}

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
 * Who they are, in one card: photo, name, age, links out, the short bio and
 * the way into their family history.
 *
 * Age is computed from [Person.birthDate] at render time (see
 * [Format.ageFrom]), never stored. [Person.residence] is city/region only,
 * same granularity rule as everywhere else location shows up in this app:
 * never a street address, and left blank rather than guessed when public
 * reporting doesn't clearly agree on where someone currently lives.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonHeader(person: Person, onOpenFamilyHistory: (() -> Unit)?) {
    val uriHandler = LocalUriHandler.current
    val age = person.birthDate?.let { Format.ageFrom(it) }
    val birthLabel = person.birthDate?.let { Format.dateLabel(it) }
    // Strictly "moving right now" (AIRBORNE / UNDERWAY), not merely a
    // confirmed-but-parked fix.
    val moving = listOfNotNull(
        "✈ AIRBORNE".takeIf { person.flight?.state == FlightState.AIRBORNE },
        "⚓ UNDERWAY".takeIf { person.vessel?.state == VesselState.UNDERWAY }
    )

    Column(Modifier.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PersonPhoto(name = person.name, photoUrl = person.photoUrl, size = 56.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                if (moving.isNotEmpty()) {
                    Text(
                        text = moving.joinToString("  "),
                        color = TT.inkPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = person.name.uppercase(),
                    color = TT.inkPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(text = person.company, color = TT.inkMuted, fontSize = 11.sp)
                if (age != null && birthLabel != null) {
                    Text(text = "Age $age · born $birthLabel", color = TT.inkSecondary, fontSize = 11.sp)
                }
            }
        }

        // Every link out on one line, wrapping only when it has to.
        FlowRow(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            person.residence?.let { place ->
                LinkLine(text = "${PlaceNames.expand(place, "US")} ↗") { uriHandler.openUri(townMapUrl(place)) }
            }
            person.wikipediaUrl?.let { url ->
                LinkLine(text = "Wikipedia ↗") { uriHandler.openUri(url) }
            }
            // Link out only, never embedded (see [socialPlatformName]).
            person.socialUrl?.let { url ->
                LinkLine(text = "${socialPlatformName(url)} ↗") { uriHandler.openUri(url) }
            }
        }

        // One pre-written paragraph — see [Person.bio].
        person.bio?.let { bio ->
            Text(
                text = bio,
                color = TT.inkSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        // A link only when there's a sourced entry behind it.
        if (onOpenFamilyHistory != null) {
            LinkLine(text = "Family history →", onClick = onOpenFamilyHistory)
        } else {
            Text(
                text = "Family history · not researched yet",
                color = TT.inkMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
}

/** A tappable line of text — links out end in "↗", links within the app in "→". */
@Composable
private fun LinkLine(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text = text,
        color = TT.accent,
        fontSize = 11.sp,
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
    )
}

/** A plain section heading — "AIRCRAFT", "LAST 7 DAYS"... */
@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = TT.accent,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = modifier
    )
}

/**
 * A plane or boat card's top line: what it is, its tail number or name,
 * and its status chip on the right.
 */
@Composable
private fun CardTitle(title: String, pin: MapPin?, identity: String, verified: Boolean, chip: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // The same shape as this vehicle's pin on the map above.
        pin?.let { Text(text = pinSymbol(it) + " ", color = TT.inkPrimary, fontSize = 12.sp) }
        SectionLabel(text = title)
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (verified) identity else "$identity · unverified",
            color = TT.inkSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        chip()
    }
}

/**
 * The bottom line of a plane or boat card: distance over the week and the
 * last fix, plus the link to a full live tracker.
 */
@Composable
private fun CardFooter(distanceNm: Double?, lastSeenEpoch: Long, nowSeconds: Long, liveMapUrl: String?) {
    val uriHandler = LocalUriHandler.current
    val facts = listOfNotNull(
        distanceNm?.let { "${Format.nauticalMiles(it)} this week" },
        // 0 means never actually seen, not a real 1970 timestamp.
        lastSeenEpoch.takeIf { it > 0 }?.let { "seen ${Format.coarseElapsed(it, nowSeconds)} ago" }
    )
    if (facts.isEmpty() && liveMapUrl == null) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = facts.joinToString(" · "),
            color = TT.inkMuted,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f)
        )
        liveMapUrl?.let { url ->
            Spacer(Modifier.width(8.dp))
            LinkLine(text = "Live map ↗") { uriHandler.openUri(url) }
        }
    }
}

/** The number this app is about: live net worth, today's move, and the road to $1T. */
@Composable
private fun WealthCard(
    person: Person,
    projected: Double,
    threshold: Double,
    isLive: Boolean,
    driftPerSecond: Double
) {
    val over = projected >= threshold
    Column(Modifier.padding(12.dp)) {
        Text(
            text = Format.exactUsd(projected),
            color = TT.inkPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            DayChange(deltaUsd = person.dayChangeUsd, suffix = " today")
            if (isLive) {
                Text(text = " · tracking", color = TT.inkMuted, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(8.dp))

        ThresholdGauge(
            progress = NetWorthEngine.progressToThreshold(projected, threshold),
            leftLabel = "${Format.percentOf(projected, threshold)} of \$1T",
            rightLabel = if (over) "over the line" else
                "${Format.compactUsd(NetWorthEngine.shortfall(projected, threshold))} to go",
            barColor = TT.series
        )

        Spacer(Modifier.height(8.dp))

        // Nothing priced live means the figure is a hand-set estimate (see
        // holdings.json): its line is flat, and drawn muted.
        val estimateOnly = person.estimate != null && person.pricedTickers.isEmpty()
        val historyFlat = isSparklineFlat(person.history)
        Sparkline(
            // Always a line, so every page looks the same: flat until there
            // are two points to join.
            values = person.history.takeIf { it.size >= 2 } ?: listOf(projected, projected),
            // Muted the moment the tail stops moving — see [isSparklineFlat]
            // for why that reads as "the market's closed," not a stuck feed.
            color = if (historyFlat || estimateOnly) TT.inkMuted else TT.series,
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
        )
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(
                text = when {
                    estimateOnly -> "not priced live"
                    historyFlat -> "flat · market's closed"
                    else -> "past 7 days"
                },
                color = TT.inkMuted,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f)
            )
            NetWorthEngine.secondsToThreshold(projected, driftPerSecond, threshold)?.let { secs ->
                val eta = Format.roughDuration(secs)
                Text(
                    text = if (secs < 86_400L) "<1d to \$1T" else "~$eta to \$1T",
                    color = TT.inkMuted,
                    fontSize = 11.sp
                )
            }
        }

        // How the number is made: which stocks are priced live, and each
        // hand-set part with where and when it comes from.
        Text(
            text = wealthBasis(person),
            color = TT.inkMuted,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

private fun wealthBasis(person: Person): String {
    val estimate = person.estimate
    return listOfNotNull(
        person.pricedTickers.takeIf { it.isNotEmpty() }?.let { "Priced live: ${it.joinToString(", ")}" },
        *estimate?.stakes.orEmpty().map { stake ->
            "${stake.company}: " + String.format(Locale.UK, "%.2f%%", stake.stakeFraction * 100) +
                " of ${Format.compactUsd(stake.valuationUsd)}" +
                (stake.valuedOn?.let { " (valued ${Format.dateLabel(it) ?: it})" } ?: "")
        }.toTypedArray(),
        estimate?.otherUsd?.takeIf { it > 0 }?.let { "${estimate.otherLabel ?: "Other assets"}: ${Format.compactUsd(it)}" },
        estimate?.let { e ->
            listOfNotNull(e.source, "estimate", e.asOf?.let { Format.dateLabel(it) ?: it }).joinToString(" ")
                .replace(" estimate ", " estimate, ") +
                // A hand-set figure gets flagged once it's a few weeks old.
                (e.asOf?.let { Format.daysSince(it) }?.takeIf { it >= 21 }?.let { " · $it days old, check for a newer figure" } ?: "")
        }
    ).joinToString("\n")
}

@Composable
private fun NothingOnRecord(text: String) {
    Text(
        text = text,
        color = TT.inkMuted,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

/**
 * The publishing rule, rendered.
 *
 * While the aircraft is airborne the map above tracks a live ADS-B position
 * (see [rememberLiveFlightPosition]); "Live map" is an extra external option
 * (ADS-B Exchange's own tracker). Everything about position shown as *text*
 * here stays airport granularity only, never a raw coordinate, except the
 * approximate-area case spelled out below.
 */
@Composable
private fun FlightCard(
    flight: FlightStatus,
    pin: MapPin?,
    nowSeconds: Long,
    airports: Map<String, AirportInfo>,
    onFocusMap: (() -> Unit)? = null
) {
    fun label(icao: String) = airports[icao]?.let(PlaceNames::of) ?: icao
    fun ago(epoch: Long) = Format.coarseElapsed(epoch, nowSeconds) + " ago"
    // A plain UNKNOWN reading covers two very different situations — see
    // snapshot_worker.py's flight_status() `was == "AIRBORNE"` branch — and
    // only currentBucket, not state, tells them apart.
    val signalLost = flight.state == FlightState.UNKNOWN && flight.currentBucket == "SIGNAL_LOST"
    // When the plane is sitting where it last landed, "when it landed" goes
    // on the same line instead of a second row naming the same airport.
    val landedHere = flight.state == FlightState.ON_GROUND &&
        flight.arrivedIcao != null && flight.arrivedIcao == flight.currentAirportIcao
    Column(
        Modifier
            .fillMaxWidth()
            // The whole section is the tap target when a map to focus exists.
            .then(if (onFocusMap != null) Modifier.clickable(onClick = onFocusMap) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        CardTitle(title = "AIRCRAFT", pin = pin, identity = flight.tail, verified = flight.verified) {
            when {
                flight.state == FlightState.AIRBORNE ->
                    StatusChip(glyph = "✈", label = "AIRBORNE", color = TT.warning)
                flight.state == FlightState.ON_GROUND ->
                    StatusChip(glyph = "●", label = "ON GROUND", color = TT.inkSecondary)
                signalLost ->
                    StatusChip(glyph = "!", label = "SIGNAL LOST", color = TT.critical)
                else ->
                    StatusChip(glyph = "?", label = "NO SIGNAL", color = TT.inkMuted)
            }
        }

        Spacer(Modifier.height(4.dp))

        // No "Now" row when it would only repeat the status chip.
        DetailRow(
            label = "Now",
            value = when {
                flight.state == FlightState.ON_GROUND && flight.currentAirportIcao != null ->
                    label(flight.currentAirportIcao) +
                        (flight.arrivedAtEpoch?.takeIf { landedHere }?.let { " · landed ${ago(it)}" } ?: "")
                // A real fix with no known airport nearby — see
                // FlightStatus.currentLat. Same caveat the dashed map pin carries.
                flight.state == FlightState.ON_GROUND && flight.generalLocation != null ->
                    "${flight.generalLocation} (approx.)"
                flight.state == FlightState.AIRBORNE -> pin?.caption
                signalLost && flight.probableIcao != null ->
                    "Possibly landed near ${label(flight.probableIcao)} (unconfirmed)"
                signalLost -> "Possibly landed, place unclear (unconfirmed)"
                else -> null
            }
        )

        // The raw fix behind "(approx.)", as text. It's already placing the
        // dashed pin on the map; current pass only, never persisted.
        if (flight.state == FlightState.ON_GROUND && flight.currentLat != null && flight.currentLon != null) {
            DetailRow(label = "Fix", value = Format.coordinate(flight.currentLat, flight.currentLon))
        }

        // "From" is left out only when it would just repeat "Now": same
        // airport, same time.
        flight.departedIcao?.takeIf { dep ->
            !(landedHere && dep == flight.currentAirportIcao &&
                flight.departedAtEpoch?.let(::ago) == flight.arrivedAtEpoch?.let(::ago))
        }?.let { dep ->
            DetailRow(
                label = "From",
                value = label(dep) + (flight.departedAtEpoch?.let { " · ${ago(it)}" } ?: "")
            )
        }

        when (flight.state) {
            FlightState.AIRBORNE -> DetailRow(
                label = "To",
                value = flight.estimatedDestinationIcao?.let { label(it) + " (est.)" } ?: "unclear yet"
            )
            FlightState.ON_GROUND -> if (!landedHere) {
                flight.arrivedIcao?.let { arr ->
                    DetailRow(
                        label = "Landed",
                        value = label(arr) + (flight.arrivedAtEpoch?.let { " · ${ago(it)}" } ?: "")
                    )
                }
            }
            FlightState.UNKNOWN -> Unit
        }

        MovementPlaces(
            trackedSeconds = flight.trackedSeconds,
            segments = if (flight.trackedSeconds > 0) {
                buildTimelineSegments(
                    recentStops = flight.recentStops,
                    flightState = flight.state,
                    currentBucket = flight.currentBucket,
                    trackedSeconds = flight.trackedSeconds,
                    nowEpoch = nowSeconds,
                    airports = airports
                )
            } else {
                emptyList()
            },
            stops = flight.recentStops.map { StopLine(it.icao, it.arrivedAtEpoch, it.departedAtEpoch) },
            lifetime = flight.lifetimeLocations,
            labelFor = ::label,
            foldLabel = bucketLabel("OTHER", airports),
            nowShowsCode = flight.currentAirportIcao.takeIf { landedHere },
            nowSeconds = nowSeconds
        )

        // Straight-line total across confirmed airport-to-airport legs (see
        // flightTripDistanceNm) — null until two stops resolve to known airports.
        CardFooter(
            distanceNm = flightTripDistanceNm(flight.recentStops, airports),
            lastSeenEpoch = flight.lastSeenEpoch,
            nowSeconds = nowSeconds,
            liveMapUrl = flight.liveMapUrl
        )
    }
}

/**
 * The maritime mirror of [FlightCard] — same publishing rule, same
 * granularity limits. The one thing planes don't have: a self-reported
 * destination (and ETA) straight from the vessel's own AIS broadcast, shown
 * but clearly labeled as crew-entered — it's routinely blank, stale, or
 * informal shorthand, nothing like a filed flight plan.
 */
@Composable
private fun BoatCard(
    vessel: VesselStatus,
    pin: MapPin?,
    nowSeconds: Long,
    ports: Map<String, PortInfo>,
    onFocusMap: (() -> Unit)? = null
) {
    fun label(unlocode: String) = ports[unlocode]?.let(PlaceNames::of) ?: unlocode
    fun ago(epoch: Long) = Format.coarseElapsed(epoch, nowSeconds) + " ago"
    // SIGNAL LOST only for a confirmed loss, not the everyday "haven't heard
    // from it in a bit" gap: AIS coverage is shore-based, so an ordinary
    // ocean crossing can go days unheard with nothing wrong.
    val signalLost = vessel.state == VesselState.UNKNOWN && vessel.currentBucket == "SIGNAL_LOST"
    val arrivedHere = vessel.state == VesselState.IN_PORT &&
        vessel.arrivedPortUnlocode != null && vessel.arrivedPortUnlocode == vessel.currentPortUnlocode
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onFocusMap != null) Modifier.clickable(onClick = onFocusMap) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        CardTitle(title = "VESSEL", pin = pin, identity = vessel.name, verified = vessel.verified) {
            when {
                vessel.state == VesselState.UNDERWAY ->
                    StatusChip(glyph = "⚓", label = "UNDERWAY", color = TT.warning)
                vessel.state == VesselState.IN_PORT ->
                    StatusChip(glyph = "●", label = "IN PORT", color = TT.inkSecondary)
                signalLost ->
                    StatusChip(glyph = "!", label = "SIGNAL LOST", color = TT.critical)
                else ->
                    StatusChip(glyph = "?", label = "NO SIGNAL", color = TT.inkMuted)
            }
        }

        Spacer(Modifier.height(4.dp))

        // No "Now" row when it would only repeat the status chip.
        DetailRow(
            label = "Now",
            value = when {
                vessel.state == VesselState.IN_PORT && vessel.currentPortUnlocode != null ->
                    label(vessel.currentPortUnlocode) +
                        (vessel.arrivedAtEpoch?.takeIf { arrivedHere }?.let { " · arrived ${ago(it)}" } ?: "")
                // A real AIS fix (moored or underway) with no known port
                // nearby — see VesselStatus.currentLat. Checked before plain
                // UNDERWAY so a real position wins over "At sea".
                vessel.generalLocation != null -> "${vessel.generalLocation} (approx.)"
                signalLost && vessel.probablePortUnlocode != null ->
                    "Possibly arrived near ${label(vessel.probablePortUnlocode)} (unconfirmed)"
                signalLost -> "Possibly arrived, place unclear (unconfirmed)"
                else -> null
            }
        )

        // The raw fix behind "(approx.)" — only ever set when there's a real
        // fix with no port match (see VesselStatus.currentLat).
        if (vessel.currentLat != null && vessel.currentLon != null) {
            DetailRow(label = "Fix", value = Format.coordinate(vessel.currentLat, vessel.currentLon))
        }

        // Same rule as the plane: no "From" that only repeats "Now".
        vessel.departedPortUnlocode?.takeIf { dep ->
            !(arrivedHere && dep == vessel.currentPortUnlocode &&
                vessel.departedAtEpoch?.let(::ago) == vessel.arrivedAtEpoch?.let(::ago))
        }?.let { dep ->
            DetailRow(
                label = "From",
                value = label(dep) + (vessel.departedAtEpoch?.let { " · ${ago(it)}" } ?: "")
            )
        }

        when (vessel.state) {
            // Self-reported beats estimated when both exist: crew-entered
            // text beats a course guess.
            VesselState.UNDERWAY -> DetailRow(
                label = "To",
                value = when {
                    vessel.selfReportedDestination != null ->
                        vessel.selfReportedDestination + " (self-reported)" +
                            (vessel.selfReportedEta?.let { " · ETA $it" } ?: "")
                    vessel.estimatedDestinationPortUnlocode != null ->
                        label(vessel.estimatedDestinationPortUnlocode) + " (est.)"
                    else -> "unclear yet"
                }
            )
            VesselState.IN_PORT -> if (!arrivedHere) {
                vessel.arrivedPortUnlocode?.let { arr ->
                    DetailRow(
                        label = "Arrived",
                        value = label(arr) + (vessel.arrivedAtEpoch?.let { " · ${ago(it)}" } ?: "")
                    )
                }
            }
            VesselState.UNKNOWN -> Unit
        }

        MovementPlaces(
            trackedSeconds = vessel.trackedSeconds,
            segments = if (vessel.trackedSeconds > 0) {
                buildVesselTimelineSegments(
                    recentStops = vessel.recentStops,
                    vesselState = vessel.state,
                    currentBucket = vessel.currentBucket,
                    trackedSeconds = vessel.trackedSeconds,
                    nowEpoch = nowSeconds,
                    ports = ports
                )
            } else {
                emptyList()
            },
            stops = vessel.recentStops.map { StopLine(it.unlocode, it.arrivedAtEpoch, it.departedAtEpoch) },
            lifetime = vessel.lifetimeLocations,
            labelFor = ::label,
            foldLabel = vesselBucketLabel("OTHER", ports),
            nowShowsCode = vessel.currentPortUnlocode.takeIf { arrivedHere },
            nowSeconds = nowSeconds
        )

        CardFooter(
            distanceNm = vesselTripDistanceNm(vessel.recentStops, ports),
            lastSeenEpoch = vessel.lastSeenEpoch,
            nowSeconds = nowSeconds,
            liveMapUrl = vessel.liveMapUrl
        )
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

    if (live.isEmpty()) return seedNews

    // [live] is this device's own on-the-spot RSS poll — fresher than
    // [seedNews] (the backend snapshot, which only refreshes a given
    // person's news roughly every NEWS_EVERY passes — see
    // snapshot_worker.py), but never carries a theme tag, by design: theme
    // classification needs the Gemini API key, and that key only ever lives
    // backend-side (see NewsItem.theme's doc comment). Rather than pick one
    // of "fresh" or "themed", carry a theme over onto a live headline
    // whenever the exact same headline is also sitting in [seedNews] — both
    // are the same Google News RSS query (see GoogleNewsClient.fetchNews's
    // doc comment), just polled at different times, so a headline both
    // sides have seen carries the identical title text. A brand-new
    // headline the backend hasn't picked up and classified yet simply has
    // no match here and shows no tag, same as always, until the backend's
    // own next pass catches up to it.
    //
    // Title, not url, is the match key: Google News' RSS links embed a
    // per-fetch token, so the same article's url can differ between this
    // device's poll and the backend's — title text for a given article is
    // the only field guaranteed stable across two separate fetches.
    val themeByTitle = seedNews.mapNotNull { item -> item.theme?.let { theme -> item.title to theme } }.toMap()
    return live.map { item -> themeByTitle[item.title]?.let { theme -> item.copy(theme = theme) } ?: item }
}

private const val LIVE_NEWS_POLL_MILLIS = 5 * 60_000L

/** How many headlines a person's page shows. */
private const val SHOWN_HEADLINES = 3

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
            ?.let { mps -> "~${(mps * 1.94384).roundToInt()} kt " }
            .orEmpty()
        return MapPin(
            label = flight.tail,
            lat = livePosition.lat,
            lon = livePosition.lon,
            glyph = "✈",
            isLive = true,
            caption = "Live position, ${speedLabel}via ${livePosition.source}"
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
        return MapPin(label = PlaceNames.of(info), lat = lat, lon = lon, glyph = "✈", isLive = isLive)
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
        return MapPin(label = PlaceNames.of(info), lat = lat, lon = lon, glyph = "⚓", isLive = isLive)
    }

    // Fallback added 2026-09-23 — a real AIS fix (moored or underway) that
    // didn't resolve to any known port (see VesselStatus.currentLat's doc
    // comment). Rather than showing nothing, plot the raw position with a
    // coarse place name, clearly flagged as approximate (see MapPin.isApproximate).
    val fallbackLat = vessel.currentLat
    val fallbackLon = vessel.currentLon
    val fallbackLocation = vessel.generalLocation
    if (fallbackLat != null && fallbackLon != null && fallbackLocation != null) {
        return MapPin(
            label = fallbackLocation,
            lat = fallbackLat,
            lon = fallbackLon,
            glyph = "⚓",
            isLive = false,
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
    else -> airports[bucket]?.let(PlaceNames::of) ?: bucket
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
 * How a non-place block is drawn on the strip: hatched while moving (in
 * flight / underway), an empty outline when there's no signal.
 */
private fun transitStyle(bucket: String): SegmentStyle = when (bucket) {
    "IN_FLIGHT", "UNDERWAY" -> SegmentStyle.HATCHED
    else -> SegmentStyle.OUTLINE
}

/**
 * Reconstructs the trailing window as literal chronological blocks from
 * [FlightStatus.recentStops] — the same real arrival/departure epochs
 * the stops list under the strip shows, just laid out on a timeline instead
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
        return listOf(TimelineSegment(bucketLabel(bucket, airports), windowStart, nowEpoch, color, transitStyle(bucket)))
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
            segments += TimelineSegment(bucketLabel(gapKey, airports), cursor, start, gapColor, transitStyle(gapKey))
        }
        if (end > start) {
            segments += TimelineSegment(labelFor(stop.icao), start, end, colorFor(stop.icao))
        }
        cursor = maxOf(cursor, end)
    }

    if (cursor < nowEpoch) {
        val trailingKey = liveFlightBucket(flightState, currentBucket)
        val trailingColor = liveFlightColor(trailingKey)
        segments += TimelineSegment(bucketLabel(trailingKey, airports), cursor, nowEpoch, trailingColor, transitStyle(trailingKey))
    }

    return segments
}

/**
 * Everywhere this plane or boat has been, inside its own card: the week as
 * a chronological strip, then one row per place with its time this week,
 * all-time total and when it was last there, plus a line of this week's
 * visits (when it arrived, how long it stayed) only when the columns can't
 * already say it. Time that isn't at a place (in flight, underway, no
 * signal) gets its own rows at the bottom.
 */
@Composable
private fun MovementPlaces(
    trackedSeconds: Long,
    segments: List<TimelineSegment>,
    stops: List<StopLine>,
    lifetime: List<LifetimeLocationShare>,
    labelFor: (String) -> String,
    foldLabel: String,
    /** The place the "Now" row already names with its arrival time, if any. */
    nowShowsCode: String?,
    nowSeconds: Long
) {
    val rows = placeRows(segments, stops, lifetime, labelFor, foldLabel, nowSeconds)
    if (segments.isEmpty() && rows.isEmpty()) return
    Column(Modifier.padding(top = 10.dp)) {
        if (segments.isNotEmpty()) {
            Text(text = "${Format.duration(trackedSeconds)} tracked", color = TT.inkMuted, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            // The table below names every block, so the strip's own legend is off.
            TimelineStrip(
                segments = segments,
                windowStartEpoch = nowSeconds - trackedSeconds,
                windowEndEpoch = nowSeconds,
                showLegend = false
            )
        }

        if (rows.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.width(16.dp))
                PlaceCells(
                    label = "",
                    week = "WEEK",
                    allTime = "ALL TIME",
                    last = "LAST",
                    color = TT.inkMuted,
                    fontSize = 10.sp
                )
            }
            rows.forEach { row ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.width(16.dp)) {
                        row.swatch?.let { (color, style) -> SegmentSwatch(color = color, style = style) }
                    }
                    PlaceCells(
                        label = row.label,
                        week = row.weekSeconds?.takeIf { it > 0 }?.let { Format.duration(it) } ?: "–",
                        allTime = row.allTimeSeconds?.let { Format.duration(it) } ?: "–",
                        last = when {
                            row.stillThere -> "now"
                            row.lastEpoch != null -> Format.coarseElapsed(row.lastEpoch, nowSeconds)
                            else -> "–"
                        },
                        color = if (row.isPlace) TT.inkPrimary else TT.inkSecondary,
                        fontSize = 11.sp
                    )
                }
                // This week's visits: when it arrived, and how long it
                // stayed (or "since" while it's still there) — left out when
                // the row's own columns already say it.
                // The stay "Now" already describes isn't repeated here.
                val visits = row.visits.filterNot { it.departedAtEpoch == null && it.code == nowShowsCode }
                if (visits.isNotEmpty() && !row.visitsShownByColumns) {
                    Text(
                        text = visits.joinToString(" · ") { visit ->
                            val arrived = Format.coarseElapsed(visit.arrivedAtEpoch, nowSeconds)
                            visit.departedAtEpoch
                                ?.let { "$arrived ago, ${Format.duration(it - visit.arrivedAtEpoch)}" }
                                ?: "since $arrived ago"
                        },
                        color = TT.inkMuted,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(start = 16.dp)
                    )
                }
            }
        }
    }
}

/** The place name and three right-aligned columns of a [MovementPlaces] row. */
@Composable
private fun RowScope.PlaceCells(
    label: String,
    week: String,
    allTime: String,
    last: String,
    color: Color,
    fontSize: TextUnit
) {
    // Full place names wrap rather than being cut short.
    Text(
        text = label,
        color = color,
        fontSize = fontSize,
        modifier = Modifier.weight(1f)
    )
    listOf(week to 54.dp, allTime to 60.dp, last to 36.dp).forEach { (text, width) ->
        Text(
            text = text,
            color = if (text == "–") TT.inkMuted else color,
            fontSize = fontSize,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.width(width)
        )
    }
}

/** One row of a [MovementPlaces] table. */
private data class PlaceRow(
    val label: String,
    val swatch: Pair<Color, SegmentStyle>?,
    val weekSeconds: Long?,
    val allTimeSeconds: Long?,
    val lastEpoch: Long?,
    val stillThere: Boolean,
    val visits: List<StopLine>,
    val isPlace: Boolean,
    /**
     * True when there's a single visit the columns already describe: it
     * left at LAST after staying WEEK (so it arrived LAST + WEEK ago), or
     * it's still there and WEEK is the whole stay.
     */
    val visitsShownByColumns: Boolean = false
)

/**
 * Merges the week (stops and strip) with the all-time tally into one row
 * per place, most recently there first, then the non-place blocks (in
 * flight, no signal...) from the strip.
 */
private fun placeRows(
    segments: List<TimelineSegment>,
    stops: List<StopLine>,
    lifetime: List<LifetimeLocationShare>,
    labelFor: (String) -> String,
    foldLabel: String,
    nowSeconds: Long
): List<PlaceRow> {
    val weekStart = nowSeconds - 7 * 86_400L
    val swatchByLabel = segments.associate { it.label to (it.color to it.style) }
    val lifetimeByCode = lifetime.associateBy { it.location }

    val places = (stops.map { it.code } + lifetime.map { it.location }).distinct().map { code ->
        val visits = stops.filter { it.code == code }.sortedByDescending { it.arrivedAtEpoch }
        val week = visits.sumOf { visit ->
            val start = maxOf(visit.arrivedAtEpoch, weekStart)
            val end = minOf(visit.departedAtEpoch ?: nowSeconds, nowSeconds)
            (end - start).coerceAtLeast(0)
        }
        val label = labelFor(code)
        val lastEpoch = lifetimeByCode[code]?.lastSeenEpoch ?: visits.mapNotNull { it.departedAtEpoch }.maxOrNull()
        val only = visits.singleOrNull()
        PlaceRow(
            label = label,
            // Past the strip's 5 named shades, a place shares the fold shade.
            swatch = if (week > 0) swatchByLabel[label] ?: swatchByLabel[foldLabel] else null,
            weekSeconds = week,
            allTimeSeconds = lifetimeByCode[code]?.totalSeconds,
            lastEpoch = lastEpoch,
            stillThere = visits.any { it.departedAtEpoch == null },
            visits = visits,
            isPlace = true,
            visitsShownByColumns = only != null && only.arrivedAtEpoch >= weekStart &&
                (only.departedAtEpoch == null || only.departedAtEpoch == lastEpoch)
        )
    }.sortedWith(compareByDescending<PlaceRow> { it.stillThere }.thenByDescending { it.lastEpoch ?: 0L })

    val elsewhere = segments
        .filter { it.style != SegmentStyle.SOLID }
        .groupBy { it.label }
        .map { (label, group) ->
            PlaceRow(
                label = label,
                swatch = group.first().color to group.first().style,
                weekSeconds = group.sumOf { it.endEpoch - it.startEpoch },
                allTimeSeconds = null,
                lastEpoch = null,
                stillThere = false,
                visits = emptyList(),
                isPlace = false
            )
        }
        .sortedByDescending { it.weekSeconds }

    return places + elsewhere
}

/** One stop on a plane or boat's week: the airport ICAO or port UN/LOCODE, and when. */
private data class StopLine(val code: String, val arrivedAtEpoch: Long, val departedAtEpoch: Long?)

/** The maritime mirror of [bucketLabel] — see [VesselStatus.locationBreakdown]'s sentinel names. */
private fun vesselBucketLabel(bucket: String, ports: Map<String, PortInfo>): String = when (bucket) {
    "UNDERWAY" -> "Underway"
    "NO_SIGNAL" -> "No signal"
    "SIGNAL_LOST" -> "Signal lost — possibly arrived"
    "UNKNOWN_PORT" -> "Unmatched port"
    "OTHER" -> "Other ports"
    else -> ports[bucket]?.let(PlaceNames::of) ?: bucket
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
        return listOf(TimelineSegment(vesselBucketLabel(bucket, ports), windowStart, nowEpoch, color, transitStyle(bucket)))
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
            segments += TimelineSegment(vesselBucketLabel(gapKey, ports), cursor, start, gapColor, transitStyle(gapKey))
        }
        if (end > start) {
            segments += TimelineSegment(labelFor(stop.unlocode), start, end, colorFor(stop.unlocode))
        }
        cursor = maxOf(cursor, end)
    }

    if (cursor < nowEpoch) {
        val trailingKey = liveVesselBucket(vesselState, currentBucket)
        val trailingColor = liveVesselColor(trailingKey)
        segments += TimelineSegment(vesselBucketLabel(trailingKey, ports), cursor, nowEpoch, trailingColor, transitStyle(trailingKey))
    }

    return segments
}

/**
 * Link out, never embed — pulling live posts means a metered per-read API
 * bill (X's read API has no free tier as of 2026) that scales with users,
 * the one thing the whole snapshot architecture exists to avoid. Names the
 * platform from [url]'s host only — it never guesses from the person, just
 * reads the link they gave us.
 */
private fun socialPlatformName(url: String): String {
    val host = try {
        java.net.URI(url).host.orEmpty().lowercase()
    } catch (_: Exception) {
        ""
    }
    return when {
        host.contains("x.com") || host.contains("twitter.com") -> "X"
        host.contains("bsky.app") -> "Bluesky"
        host.contains("instagram.com") -> "Instagram"
        host.contains("facebook.com") -> "Facebook"
        host.contains("threads.net") || host.contains("threads.com") -> "Threads"
        host.contains("linkedin.com") -> "LinkedIn"
        host.contains("truthsocial.com") -> "Truth Social"
        else -> "Profile"
    }
}

@Composable
private fun DetailRow(label: String, value: String?) {
    if (value == null) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(text = label, color = TT.inkMuted, fontSize = 12.sp, modifier = Modifier.width(56.dp))
        Spacer(Modifier.width(8.dp))
        Text(text = value, color = TT.inkSecondary, fontSize = 12.sp)
    }
}

/**
 * Headlines in one card, then the all-time theme tally: how many distinct
 * headlines have ever been classified into each theme for this person (see
 * [Person.lifetimeNewsThemes] and snapshot_worker.py's
 * `accumulate_lifetime_news_themes()`). Bars are relative to this person's
 * own biggest theme, not a scale shared across people.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewsCard(news: List<NewsItem>, themes: List<NewsThemeShare>, nowSeconds: Long) {
    val uriHandler = LocalUriHandler.current
    Column(Modifier.padding(vertical = 10.dp)) {
        SectionLabel(text = "IN THE NEWS", modifier = Modifier.padding(horizontal = 12.dp))
        Spacer(Modifier.height(4.dp))
        if (news.isEmpty()) NothingOnRecord("No headlines right now")

        news.forEachIndexed { index, item ->
            if (index > 0) SheetLine(subtle = true)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { uriHandler.openUri(item.url) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                // Capped so a card can never grow past a few lines whatever
                // the feed puts in the title.
                Text(
                    text = item.title,
                    color = TT.inkPrimary,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                // The theme drops to its own line when it doesn't fit beside
                // the source, so neither is ever squeezed. (A long outlet name
                // once starved a side-by-side theme chip into a tower of
                // one-letter lines, 2026-09-25.)
                FlowRow(
                    modifier = Modifier.padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${item.source} · ${Format.coarseElapsed(item.publishedEpoch, nowSeconds)} ago",
                        color = TT.inkMuted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // No tag when classification didn't run, nothing fit, or
                    // the headline came from the live poll — see NewsItem.theme.
                    item.theme?.let { theme ->
                        ThemeLabel(theme = theme, color = TT.inkSecondary, fontSize = 11.sp)
                    }
                }
            }
        }

        if (themes.isNotEmpty()) {
            val maxCount = themes.maxOf { it.count }.coerceAtLeast(1)
            Text(
                text = "ALL-TIME THEMES",
                color = TT.inkMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp)
            )
            themes.forEach { share ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ThemeLabel(
                        theme = share.theme,
                        color = TT.inkPrimary,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .width(72.dp)
                            .height(6.dp)
                            .border(1.dp, TT.border, TT.panelShape)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(fraction = share.count.toFloat() / maxCount.toFloat())
                                .height(6.dp)
                                .background(TT.series)
                        )
                    }
                    Text(
                        text = share.count.toString(),
                        color = TT.inkMuted,
                        fontSize = 11.sp,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(32.dp)
                    )
                }
            }
        }
    }
}
