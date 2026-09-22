package com.hostu404.trilliontracker.data

import kotlinx.serialization.Serializable

/**
 * The entire app reads exactly one document: this snapshot.
 *
 * The backend polls the upstream sources (EDGAR, quotes, ADS-B, RSS) on a fixed
 * budget and writes this file to a CDN. Every client in the world reads the same
 * cached copy, so upstream call volume is constant no matter how many users there
 * are.
 *
 * Three deliberate, narrowly-scoped exceptions to "clients never talk to a
 * third-party API directly," all added after this snapshot design was
 * already in place and all documented where they live: a live aircraft
 * position on the map while someone's detail screen has them actually
 * airborne (see [FlightStatus], [com.hostu404.trilliontracker.data.LiveFlightTracker]);
 * a live, stock-price-computed net worth for whichever billionaires
 * [com.hostu404.trilliontracker.data.Holdings] has a priced holding set for
 * (see [Person.driftPerSecondUsd], [NetWorthEngine], `LiveQuoteClient`); and
 * live headlines for whoever's detail screen is open right now (see
 * [Person.news], `GoogleNewsClient`). All three run entirely client-side —
 * there's no backend in seed mode to do this fetching on the client's
 * behalf — all three are batched/scoped to the minimum each tick actually
 * needs, and none of them is written back into a snapshot anywhere;
 * everything else in this document still comes only from here.
 */
@Serializable
data class Snapshot(
    /** Epoch seconds at which the backend generated this document. */
    val generatedAt: Long,
    /** Seconds until the backend is expected to publish the next one. */
    val nextUpdateIn: Long = 60,
    /** The line. $1,000,000,000,000. */
    val thresholdUsd: Double = 1_000_000_000_000.0,
    /** Set when someone is currently over the line. */
    val currentTrillionaireId: String? = null,
    /** Most recent entry of [crossingHistory] — kept alongside it for the "it has happened before" footnote. */
    val lastCrossing: Crossing? = null,
    /**
     * Every threshold crossing the backend has ever recorded, oldest first,
     * capped to a bounded recent window server-side. Unlike [lastCrossing]
     * (which is just this list's last entry) this is the actual log: someone
     * who's crossed and dropped back under multiple times shows up multiple
     * times, each with its own [Crossing.heldForSeconds] for exactly how
     * long that particular time over the line lasted.
     */
    val crossingHistory: List<Crossing> = emptyList(),
    val people: List<Person> = emptyList(),
    /**
     * Every airport ICAO referenced anywhere in this snapshot, resolved to a
     * name people actually recognize. Keyed by ICAO — look codes up here
     * rather than displaying them raw. Only carries the codes this snapshot
     * actually uses, not the whole airport database.
     */
    val airports: Map<String, AirportInfo> = emptyMap(),
    /**
     * Every port UN/LOCODE referenced anywhere in this snapshot, resolved the
     * same way [airports] resolves ICAO codes — see [portLabel].
     */
    val ports: Map<String, PortInfo> = emptyMap()
) {
    /** "Austin, TX" for a known code; the bare code itself if we have nothing better. */
    fun airportLabel(icao: String): String = airports[icao]?.label ?: icao

    /** "Seattle, WA" for a known UN/LOCODE; the bare code itself otherwise. */
    fun portLabel(unlocode: String): String = ports[unlocode]?.label ?: unlocode
}

@Serializable
data class AirportInfo(
    val name: String,
    val city: String = "",
    val country: String = "",
    /** The pre-formatted "City, ST" / "City, Country" string — prefer this. */
    val label: String,
    /**
     * The airport's own fixed reference point — surveyed once, published by
     * the aviation authority, the same for every aircraft that ever lands
     * there. Not a live position: see [FlightStatus.currentAirportIcao] for
     * why the app never carries one of those. This just lets the world map
     * place a pin at an airport it already knows the name of.
     */
    val lat: Double? = null,
    val lon: Double? = null
)

/** Same idea as [AirportInfo], keyed by UN/LOCODE instead of ICAO. */
@Serializable
data class PortInfo(
    val name: String,
    val city: String = "",
    val country: String = "",
    val label: String,
    /** Fixed port reference point, same rationale as [AirportInfo.lat]. */
    val lat: Double? = null,
    val lon: Double? = null
)

@Serializable
data class Crossing(
    val personId: String,
    val personName: String,
    val crossedAtEpoch: Long,
    /** Kept current every pass while [ongoing], frozen the moment it stops being true. */
    val heldForSeconds: Long,
    val note: String = "",
    /** True while this specific crossing is still in progress — false once they've dropped back under. */
    val ongoing: Boolean = false
)

@Serializable
data class Person(
    val id: String,
    val name: String,
    val company: String,
    /**
     * ISO 8601 "YYYY-MM-DD", e.g. "1971-06-28". A stable fact, set once —
     * the age shown next to it in the UI is computed client-side from
     * today's date, not stored here, so it advances on its own the next
     * time someone opens the app after a birthday.
     */
    val birthDate: String? = null,
    /**
     * Publicly reported primary residence, at city/region granularity only
     * — "Starbase, TX", never a street address. Same principle as
     * [FlightStatus.currentAirportIcao]: precise enough to be informative,
     * never precise enough to be a target. Left null rather than guessed
     * when nothing solid enough to publish turned up, or when public
     * reporting disagrees on where someone currently lives.
     */
    val residence: String? = null,
    /**
     * A short, hand-curated biography — birthplace and the last school or
     * university they attended (whether or not they finished it), same
     * sourcing bar and same hand-curated-static-fact pipeline as
     * [birthDate]/[residence]: written once into `backend/holdings.json`
     * and passed straight through by the backend on every snapshot (see
     * `Subject.bio` in `snapshot_worker.py`), never fetched live from a
     * third party. [SeedData] carries its own copy for the bundled offline
     * fallback. Deliberately a single pre-written sentence or two rather
     * than separate structured fields: several of these people left a
     * *later* graduate program without finishing after completing an
     * earlier degree elsewhere, and prose is the only honest way to say
     * "last attended" and "actually completed" without them collapsing
     * into one misleading fact. Null only if it hasn't been written yet
     * for that person.
     */
    val bio: String? = null,
    /** Net worth in USD at [Snapshot.generatedAt]. */
    val netWorthUsd: Double,
    /**
     * Signed USD-per-second drift, derived by the backend from the most recent
     * market movement. The client uses it only to animate between snapshots —
     * see [NetWorthEngine]. It is a projection, not a measurement, and the UI
     * says so.
     */
    val driftPerSecondUsd: Double = 0.0,
    /** Change since the previous session close, in USD. */
    val dayChangeUsd: Double = 0.0,
    /** Recent closes, oldest first, for the sparkline. */
    val history: List<Double> = emptyList(),
    val flight: FlightStatus? = null,
    val vessel: VesselStatus? = null,
    /**
     * Seed/backend-baked fallback headlines — shown only until (or unless)
     * a live fetch succeeds. [PersonDetailScreen][com.hostu404.trilliontracker.ui.screens]
     * polls Google News' free RSS search for this person's own name while
     * their detail screen is open (see `GoogleNewsClient`, `rememberLiveNews`)
     * and prefers that live result; this list is only what's actually
     * rendered when that fetch hasn't returned anything yet, or comes back
     * empty. This one has a free live source to poll — unlike a person's
     * calendar, which nothing broadcasts — which is why there's no
     * seed/snapshot-only equivalent field the way there used to be for
     * public appearances.
     */
    val news: List<NewsItem> = emptyList(),
    /**
     * Link to the person's profile on whatever platform they actually post to
     * (X, Bluesky, wherever). We link out rather than embedding — pulling live
     * posts would mean a metered per-read API bill that scales with users,
     * which breaks the whole point of the snapshot architecture.
     */
    val socialUrl: String? = null,
    /** Wikipedia article link. Null only if no matching article was found. */
    val wikipediaUrl: String? = null,
    /**
     * Portrait image, only ever a Wikimedia Commons URL. The backend checks
     * the thumbnail's own hosting path and drops anything not on Commons —
     * Commons is free-license-only by policy, so this is never a "fair use"
     * image. Null means no free photo was available, not that the fetch
     * failed to try.
     */
    val photoUrl: String? = null
) {
    val isOverThreshold: Boolean get() = netWorthUsd >= 1_000_000_000_000.0
}

/**
 * Flight state follows what the data actually supports:
 *
 *  - Departure and in-air status are real-time and come straight off ADS-B.
 *  - Raw ADS-B has no destination field — an aircraft's transponder never
 *    broadcasts where it's going, only where it is. [arrivedIcao] is filled in
 *    on landing because that is the earliest moment this data source can know
 *    it, not because of any deliberate delay.
 *  - [estimatedDestinationIcao] is a live best guess while airborne, computed
 *    from current track + groundspeed against known airports. It is a
 *    projection from position data, not a filed flight plan, and the UI labels
 *    it as an estimate — it can be wrong, and it re-introduces a lighter
 *    version of the "where are they headed right now" signal that gets
 *    trackers like this pulled from platforms, so treat it as informational,
 *    not something to publicize aggressively.
 *  - [liveMapUrl] still links out to a full third-party tracker for anyone
 *    who wants that. What *is* hosted in this app now — by explicit request,
 *    overriding what this file used to say here — is a genuine live ADS-B
 *    position on the in-app map while an aircraft is airborne, fetched
 *    client-side from OpenSky (see [com.hostu404.trilliontracker.data.OpenSkyClient])
 *    only for the one person whose detail screen is actually open, held in
 *    memory only, never persisted into a [Snapshot] and never shown for
 *    anyone not being actively looked at.
 */
@Serializable
data class FlightStatus(
    val tail: String,
    val icaoHex: String,
    val state: FlightState,
    /** False until the tail -> person mapping has been confirmed from filings. */
    val verified: Boolean = false,
    val departedIcao: String? = null,
    val departedAtEpoch: Long? = null,
    /** Confirmed destination. Populated once the aircraft is on the ground. */
    val arrivedIcao: String? = null,
    val arrivedAtEpoch: Long? = null,
    /** Live, unconfirmed. Only meaningful while [state] is AIRBORNE. */
    val estimatedDestinationIcao: String? = null,
    val lastSeenEpoch: Long = 0L,
    val liveMapUrl: String? = null,
    /**
     * Where it's sitting right now — airport granularity only, never a
     * coordinate. Null while airborne: "no current location" is the honest
     * answer for a plane in the air, not a live lat/lon we could publish
     * instead.
     */
    val currentAirportIcao: String? = null,
    /**
     * Airport stops over the trailing 7 days, newest first. This is the
     * granularity every public jet-tracking site already operates at — which
     * airfield, when it landed, when it left — never a GPS trail. A precise
     * historical position feed is a materially heavier version of exactly the
     * risk [estimatedDestinationIcao] already treats carefully, so it isn't
     * something this app produces.
     */
    val recentStops: List<AirportStop> = emptyList(),
    /**
     * Time-share breakdown over the trailing window, sorted by seconds
     * descending. Built entirely from [recentStops] plus the gaps between
     * them (flight time) and any signal gaps — costs zero extra upstream
     * calls, it's just bookkeeping on data already fetched for the fields
     * above. [bucket] on each entry is either an airport ICAO, or one of the
     * fixed sentinels: "IN_FLIGHT", "NO_SIGNAL", "UNKNOWN_AIRPORT", "OTHER"
     * (the last one only added client-side when folding a busy week's tail
     * into "Other airports" — see [com.hostu404.trilliontracker.ui.screens]).
     */
    val locationBreakdown: List<LocationShare> = emptyList(),
    /**
     * How much of the trailing window is actually accounted for. Usually
     * less than 7 full days right after the backend starts tracking a new
     * aircraft — percentages are of this, not a blind 7-day assumption.
     */
    val trackedSeconds: Long = 0L,
    /**
     * The bucket the backend just assigned for *this* pass — "IN_FLIGHT",
     * "SIGNAL_LOST", an airport ICAO, or null on older cached snapshots that
     * predate this field. [state] alone can't distinguish an ordinary short
     * ADS-B gap (still UNKNOWN, but honestly "no signal yet") from one
     * that's run long enough the backend no longer believes the aircraft is
     * still airborne on the same leg — that's what this is for. See
     * snapshot_worker.py's flight_status() for exactly when SIGNAL_LOST
     * gets set.
     */
    val currentBucket: String? = null,
    /**
     * Only meaningful when [currentBucket] is "SIGNAL_LOST": the last
     * heading-based guess at where the aircraft was headed before contact
     * was lost, so the UI can say *where* it probably landed instead of a
     * bare "somewhere, unknown". Null when no such guess exists — the UI
     * should read that as "possibly landed, location unclear," not as
     * missing data.
     */
    val probableIcao: String? = null
)

@Serializable
data class LocationShare(
    val bucket: String,
    val seconds: Long
)

@Serializable
data class AirportStop(
    val icao: String,
    val arrivedAtEpoch: Long,
    /** Null if the aircraft is still there. */
    val departedAtEpoch: Long? = null
)

@Serializable
enum class FlightState { AIRBORNE, ON_GROUND, UNKNOWN }

/**
 * The maritime mirror of [FlightStatus] — same privacy shape, different
 * transport. AIS is genuinely more revealing than ADS-B in one respect: a
 * Message 5 broadcast can include a free-text "Destination" the crew typed
 * in themselves, so unlike planes this isn't a computed estimate — it's
 * only ever shown as [selfReportedDestination], clearly labeled as
 * crew-entered and unverified (it's routinely blank, stale, or informal
 * shorthand), never presented as a confirmed plan. Position is port
 * granularity only, exactly like [FlightStatus.currentAirportIcao] — never
 * a raw lat/lon, live or historical.
 */
@Serializable
data class VesselStatus(
    val name: String,
    val mmsi: String,
    val state: VesselState,
    /** False until the MMSI -> person mapping is confirmed from public registries. */
    val verified: Boolean = false,
    val departedPortUnlocode: String? = null,
    val departedAtEpoch: Long? = null,
    val arrivedPortUnlocode: String? = null,
    val arrivedAtEpoch: Long? = null,
    /**
     * Crew-entered, from the vessel's own AIS Message 5 — not computed, not
     * verified, frequently blank or wrong. Shown only with that caveat.
     */
    val selfReportedDestination: String? = null,
    val lastSeenEpoch: Long = 0L,
    val liveMapUrl: String? = null,
    /** Port granularity only, never a coordinate. Null while underway. */
    val currentPortUnlocode: String? = null,
    /**
     * Port stops over the trailing 7 days, newest first — see [AirportStop].
     */
    val recentStops: List<PortStop> = emptyList(),
    /**
     * Server-side time-share aggregate, mirroring [FlightStatus.locationBreakdown]
     * bucket-for-bucket ("UNDERWAY", "NO_SIGNAL", "UNKNOWN_PORT" in place of the
     * flight sentinels). Not currently consumed client-side — same as the flight
     * field, the UI rebuilds its own timeline segments from [recentStops] instead
     * (see `buildTimelineSegments` in `PersonDetailScreen.kt`) so the strip and its
     * legend can never drift out of sync with a second, server-computed total. Kept
     * here for schema parity with the backend and in case a future consumer wants
     * the raw aggregate without reconstructing it.
     */
    val locationBreakdown: List<LocationShare> = emptyList(),
    /**
     * How much of the trailing window is actually accounted for — see
     * [FlightStatus.trackedSeconds]. This is the field the vessel "TIME BY
     * LOCATION" card actually gates and sizes its window on.
     */
    val trackedSeconds: Long = 0L
)

@Serializable
enum class VesselState { UNDERWAY, IN_PORT, UNKNOWN }

@Serializable
data class PortStop(
    val unlocode: String,
    val arrivedAtEpoch: Long,
    /** Null if the vessel is still there. */
    val departedAtEpoch: Long? = null
)

@Serializable
data class NewsItem(
    val title: String,
    val source: String,
    val url: String,
    val publishedEpoch: Long
)
