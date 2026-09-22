package com.hostu404.trilliontracker.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.io.IOException

/**
 * One aircraft's live ADS-B state, as reported right now by
 * [LiveFlightTracker] — deliberately NOT part of [Snapshot]/[FlightStatus].
 * Everywhere else in this app, position data is baked into the snapshot
 * (seed or backend) precisely so nothing calls a third party live from the
 * client. This is the one deliberate exception: a moving live position on
 * the map, fetched only while a person's detail screen has them actually
 * airborne right now (see `rememberLiveFlightPosition` in
 * `PersonDetailScreen.kt`), held in memory only, and never written back
 * into the snapshot or shown anywhere the person isn't already looking.
 */
data class LivePosition(
    val lat: Double,
    val lon: Double,
    /** True heading in degrees, 0 = north, clockwise. Null if unreported. */
    val headingDegrees: Double? = null,
    val groundSpeedMps: Double? = null,
    val onGround: Boolean,
    val observedAtEpoch: Long,
    /**
     * Which live source actually answered this tick — "OpenSky", "adsb.lol",
     * or "airplanes.live" (see [LiveFlightTracker]). Surfaced in the UI
     * caption so a fix from a fallback source is never silently presented
     * as if the primary source had it — the three differ in coverage and
     * latency, so which one answered is itself informative.
     */
    val source: String = "OpenSky"
)

/**
 * Thin client for OpenSky Network's public `/states/all` REST endpoint
 * (https://openskynetwork.github.io/opensky-api/rest.html) — free, keyless,
 * and callable straight from a device with no backend of any kind. This is
 * [LiveFlightTracker]'s primary source; [AdsbLolClient] and
 * [AirplanesLiveClient] back it up when OpenSky itself has nothing for a
 * given aircraft this tick. There's no equivalent redundancy for vessels:
 * real-time AIS positions aren't available anywhere for free without either
 * running a receiver or holding a paid/keyed account, so boats still show
 * port-granularity only (see [vesselMapPin] in `PersonDetailScreen.kt`) —
 * all three clients here are aircraft-only.
 *
 * Every request is scoped to specific `icao24` transponder hex codes —
 * never the whole-world feed — which is both far cheaper against OpenSky's
 * rate limits and the only honest way to ask "where is this one aircraft"
 * instead of pulling a global feed to answer it. Anonymous access is
 * rate-limited by OpenSky per source IP; the polling cadence this app uses
 * (one aircraft, only while its detail screen is open and it's actually
 * airborne — see `rememberLiveFlightPosition`) stays well inside that. A
 * free OpenSky account raises the budget further if it's ever needed; this
 * client has no credentials wired in either way, so add Basic auth to the
 * request builder here if that ever changes.
 */
object OpenSkyClient {
    private val client get() = NetworkClients.shared

    private val json = Json { ignoreUnknownKeys = true }

    private const val USER_AGENT = "trillionaire-tracker/0.1 (+https://github.com/Hostu404)"

    /**
     * Looks up current state vectors for the given transponder hex codes
     * (e.g. [FlightStatus.icaoHex]). Returns only the aircraft OpenSky
     * actually has fresh data for, keyed by lowercase icao24 — a hex
     * missing from the result has no current live position (not airborne
     * right now, out of ADS-B receiver coverage, or between reports), not
     * an error. Never throws: a network failure or bad response is just an
     * empty map, so a caller polling in a loop doesn't need its own
     * try/catch around every tick.
     */
    suspend fun fetchStates(icaoHexes: List<String>): Map<String, LivePosition> {
        if (icaoHexes.isEmpty()) return emptyMap()
        val query = icaoHexes.joinToString("&") { "icao24=${it.lowercase()}" }
        val request = Request.Builder()
            .url("https://opensky-network.org/api/states/all?$query")
            .header("User-Agent", USER_AGENT)
            .build()

        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyMap()
                    val body = response.body?.string() ?: return@use emptyMap()
                    parseStates(body)
                }
            } catch (_: IOException) {
                emptyMap()
            } catch (_: Exception) {
                // Malformed/unexpected JSON shape — treat exactly like "no
                // current data" rather than crashing the poll loop.
                emptyMap()
            }
        }
    }

    /**
     * OpenSky reports each aircraft as a flat, positional JSON array (not
     * an object) inside a top-level `"states"` array — this is the stable
     * public schema their docs describe, not something this app infers:
     * index 0 = icao24, 5/6 = longitude/latitude, 8 = on_ground,
     * 9 = ground speed (m/s), 10 = true track (heading, degrees).
     */
    private fun parseStates(body: String): Map<String, LivePosition> {
        val root = json.parseToJsonElement(body).jsonObject
        val states = root["states"]?.jsonArray ?: return emptyMap()
        val now = System.currentTimeMillis() / 1000

        return states.mapNotNull { entry ->
            try {
                val row = entry.jsonArray
                val icao = row.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val lon = row.getOrNull(5)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val lat = row.getOrNull(6)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val onGround = row.getOrNull(8)?.jsonPrimitive?.booleanOrNull ?: false
                val speed = row.getOrNull(9)?.jsonPrimitive?.doubleOrNull
                val heading = row.getOrNull(10)?.jsonPrimitive?.doubleOrNull
                icao to LivePosition(
                    lat = lat,
                    lon = lon,
                    headingDegrees = heading,
                    groundSpeedMps = speed,
                    onGround = onGround,
                    observedAtEpoch = now,
                    source = "OpenSky"
                )
            } catch (_: Exception) {
                null
            }
        }.toMap()
    }
}

/**
 * `adsb.lol` and `airplanes.live` are both community, crowd-fed ADS-B
 * aggregators — different receiver networks from OpenSky's and from each
 * other — and both publish the same "readsb"/tar1090-shaped JSON: a
 * `{"ac": [...]}` object where each element is one aircraft with `hex`,
 * `lat`, `lon`, `track`, `gs` (ground speed, in **knots** — unlike OpenSky's
 * m/s, hence the conversion below), and `alt_baro` (a number in feet, or the
 * literal string `"ground"` when parked/taxiing). One parser covers both.
 */
private fun parseReadsbAircraft(body: String, icaoHex: String, json: Json, source: String): LivePosition? {
    return try {
        val root = json.parseToJsonElement(body).jsonObject
        val list = root["ac"]?.jsonArray ?: return null
        val now = System.currentTimeMillis() / 1000

        for (entry in list) {
            val obj = entry.jsonObject
            val hex = obj["hex"]?.jsonPrimitive?.contentOrNull ?: continue
            if (!hex.equals(icaoHex, ignoreCase = true)) continue

            val lat = obj["lat"]?.jsonPrimitive?.doubleOrNull ?: continue
            val lon = obj["lon"]?.jsonPrimitive?.doubleOrNull ?: continue
            val onGround = obj["alt_baro"]?.jsonPrimitive?.contentOrNull
                ?.equals("ground", ignoreCase = true) ?: false
            val groundSpeedKnots = obj["gs"]?.jsonPrimitive?.doubleOrNull
            val heading = obj["track"]?.jsonPrimitive?.doubleOrNull

            return LivePosition(
                lat = lat,
                lon = lon,
                headingDegrees = heading,
                groundSpeedMps = groundSpeedKnots?.let { it * 0.514444 },
                onGround = onGround,
                observedAtEpoch = now,
                source = source
            )
        }
        null
    } catch (_: Exception) {
        null
    }
}

/**
 * First fallback behind [OpenSkyClient] — see [parseReadsbAircraft] for the
 * shared response shape. Free, keyless, single-aircraft lookups by hex, same
 * "never throws, empty/null just means no current data" contract as
 * [OpenSkyClient.fetchStates].
 */
object AdsbLolClient {
    private val client get() = NetworkClients.shared
    private val json = Json { ignoreUnknownKeys = true }
    private const val USER_AGENT = "trillionaire-tracker/0.1 (+https://github.com/Hostu404)"

    suspend fun fetchState(icaoHex: String): LivePosition? {
        val request = Request.Builder()
            .url("https://api.adsb.lol/v2/icao/${icaoHex.lowercase()}")
            .header("User-Agent", USER_AGENT)
            .build()
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string() ?: return@use null
                    parseReadsbAircraft(body, icaoHex, json, "adsb.lol")
                }
            } catch (_: IOException) {
                null
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Second fallback behind [OpenSkyClient], tried only after [AdsbLolClient]
 * also comes up empty — a different receiver network again, same schema and
 * contract. See [parseReadsbAircraft].
 */
object AirplanesLiveClient {
    private val client get() = NetworkClients.shared
    private val json = Json { ignoreUnknownKeys = true }
    private const val USER_AGENT = "trillionaire-tracker/0.1 (+https://github.com/Hostu404)"

    suspend fun fetchState(icaoHex: String): LivePosition? {
        val request = Request.Builder()
            .url("https://api.airplanes.live/v2/hex/${icaoHex.lowercase()}")
            .header("User-Agent", USER_AGENT)
            .build()
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string() ?: return@use null
                    parseReadsbAircraft(body, icaoHex, json, "airplanes.live")
                }
            } catch (_: IOException) {
                null
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * The single entry point [rememberLiveFlightPosition] (in
 * `PersonDetailScreen.kt`) actually calls. Tries [OpenSkyClient] first, then
 * [AdsbLolClient], then [AirplanesLiveClient], stopping at the first one
 * that actually has this aircraft right now — so a single down/rate-limited/
 * no-coverage source no longer means "no live dot" the way it did with only
 * one source wired in. Still never more than one network call per source
 * per tick, and the fallbacks only fire when the previous source truly came
 * back empty, so this doesn't multiply request volume against any of the
 * three on a healthy poll.
 */
object LiveFlightTracker {
    suspend fun fetchPosition(icaoHex: String): LivePosition? {
        OpenSkyClient.fetchStates(listOf(icaoHex))[icaoHex.lowercase()]?.let { return it }
        AdsbLolClient.fetchState(icaoHex)?.let { return it }
        AirplanesLiveClient.fetchState(icaoHex)?.let { return it }
        return null
    }
}
