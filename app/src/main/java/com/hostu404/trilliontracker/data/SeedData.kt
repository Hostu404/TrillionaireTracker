package com.hostu404.trilliontracker.data

import java.time.Instant
import kotlin.math.abs
import kotlin.random.Random

/**
 * Embedded fallback snapshot so the app runs with no backend.
 *
 * Figures are Forbes': for Musk, Bezos and Zuckerberg as published
 * 1 September 2026; for Thiel, Luckey, Altman, Amodei and Trump, Forbes'
 * real-time figures of 16 October 2026 (with that day's change for Thiel;
 * none for the others). They are a starting point for the UI, not a live feed —
 * once [Config.SNAPSHOT_URL] is set, this is only used when the network
 * read fails.
 */
object SeedData {

    private const val B = 1_000_000_000.0

    private data class Row(
        val id: String,
        val name: String,
        val company: String,
        val billions: Double,
        val dayChangeBillions: Double
    )

    private val rows = listOf(
        Row("musk", "Elon Musk", "SpaceX, Tesla", 892.0, 4.8),
        Row("bezos", "Jeff Bezos", "Amazon", 268.0, 0.9),
        Row("zuckerberg", "Mark Zuckerberg", "Meta", 197.0, 2.2),
        Row("thiel", "Peter Thiel", "Palantir", 36.4, 1.6),
        Row("amodei", "Dario Amodei", "Anthropic", 15.5, 0.0),
        Row("trump", "Donald Trump", "Trump Organization", 6.6, 0.0),
        Row("luckey", "Palmer Luckey", "Anduril", 6.0, 0.0),
        Row("altman", "Sam Altman", "OpenAI", 3.2, 0.0)
    )

    fun snapshot(nowEpoch: Long = Instant.now().epochSecond): Snapshot {
        val people = rows.map { row ->
            val net = row.billions * B
            val dayChange = row.dayChangeBillions * B

            Person(
                id = row.id,
                name = row.name,
                company = row.company,
                netWorthUsd = net,
                // Spread the day's move across a trading day so the ticker breathes.
                driftPerSecondUsd = dayChange / (6.5 * 3600.0),
                dayChangeUsd = dayChange,
                // No day change means nothing priced live: a flat line, as the
                // backend would publish for a hand-set estimate.
                history = if (row.dayChangeBillions == 0.0) List(48) { net }
                else walk(seed = row.id.hashCode(), end = net, points = 48),
                flight = flightFor(row.id, nowEpoch),
                vessel = vesselFor(row.id, nowEpoch),
                news = newsFor(row.id, nowEpoch),
                socialUrl = socialFor(row.id),
                wikipediaUrl = wikipediaFor(row.id),
                photoUrl = photoFor(row.id),
                birthDate = birthDateFor(row.id),
                residence = residenceFor(row.id),
                bio = bioFor(row.id),
                pricedTickers = Holdings.forId(row.id)?.tickers.orEmpty(),
                estimate = estimateFor(row.id)
            )
        }

        return Snapshot(
            generatedAt = nowEpoch,
            nextUpdateIn = Config.POLL_INTERVAL_SECONDS,
            currentTrillionaireId = people.firstOrNull { it.isOverThreshold }?.id,
            lastCrossing = Crossing(
                personId = "musk",
                personName = "Elon Musk",
                crossedAtEpoch = Instant.parse("2026-06-12T00:00:00Z").epochSecond,
                heldForSeconds = 0L,
                note = "First ever, on the SpaceX Nasdaq listing. Fell back below since."
            ),
            people = people,
            airports = SEED_AIRPORTS,
            ports = SEED_PORTS
        )
    }

    /** Friendly labels for the ICAO codes used by [flightFor]'s demo rows. */
    private val SEED_AIRPORTS: Map<String, AirportInfo> = mapOf(
        "KAUS" to AirportInfo(name = "Austin-Bergstrom International Airport", city = "Austin", country = "US", label = "Austin, TX", lat = 30.1975, lon = -97.6664),
        "KTEB" to AirportInfo(name = "Teterboro Airport", city = "Teterboro", country = "US", label = "Teterboro, NJ", lat = 40.8501, lon = -74.0608),
        "KLAS" to AirportInfo(name = "Harry Reid International Airport", city = "Las Vegas", country = "US", label = "Las Vegas, NV", lat = 36.0840, lon = -115.1537),
        "KVNY" to AirportInfo(name = "Van Nuys Airport", city = "Los Angeles", country = "US", label = "Van Nuys, CA", lat = 34.2098, lon = -118.4900),
        "KBFI" to AirportInfo(name = "King County International Airport", city = "Seattle", country = "US", label = "Seattle, WA", lat = 47.5300, lon = -122.3019)
    )

    /** Friendly labels for the UN/LOCODEs used by [vesselFor]'s demo rows. */
    private val SEED_PORTS: Map<String, PortInfo> = mapOf(
        "USFLL" to PortInfo(name = "Port Everglades", city = "Fort Lauderdale", country = "US", label = "Fort Lauderdale, US", lat = 26.0928, lon = -80.1181),
        "MCMON" to PortInfo(name = "Port Hercules", city = "Monaco", country = "MC", label = "Monaco, MC", lat = 43.7325, lon = 7.4231)
    )

    /** Deterministic random walk ending exactly at [end], for the sparklines. */
    private fun walk(seed: Int, end: Double, points: Int): List<Double> {
        val rng = Random(seed)
        val out = DoubleArray(points)
        out[points - 1] = end
        for (i in points - 2 downTo 0) {
            val step = out[i + 1] * (rng.nextDouble(-0.018, 0.018))
            out[i] = abs(out[i + 1] - step)
        }
        return out.toList()
    }

    /**
     * Demo flight rows.
     *
     * The tail numbers are placeholders on purpose. Linking a registration to a
     * person is the real work of this feature and it has to come from primary
     * sources — SEC filings, historical ADS-B archives, aircraft serial numbers —
     * not from guessing. Anything unverified is flagged in the UI.
     */
    private fun flightFor(id: String, nowEpoch: Long): FlightStatus? = when (id) {
        // Real, publicly reported registration + Mode S hex (both cross-
        // corroborated across multiple independent aviation trackers — this
        // was also the subject of extensive 2022 press coverage). The
        // flight STATE below (airborne, these particular airports, these
        // timestamps) is still a made-up demo scenario, not a real flight —
        // only the tail/icaoHex identity is real. verified=true reflects
        // confidence in that identity, not in the fabricated activity.
        "musk" -> FlightStatus(
            tail = "N628TS",
            icaoHex = "a835af",
            state = FlightState.AIRBORNE,
            verified = true,
            departedIcao = "KAUS",
            departedAtEpoch = nowEpoch - 8_040,
            arrivedIcao = null,              // not known yet — genuinely airborne
            // Demo value for a course + groundspeed estimate. A real one comes
            // from the backend's heading-projection heuristic, not a lookup.
            estimatedDestinationIcao = "KBFI",
            lastSeenEpoch = nowEpoch - 45,
            liveMapUrl = Config.liveMapFor("a835af"),
            currentAirportIcao = null,        // airborne — no current location
            recentStops = listOf(
                AirportStop(icao = "KAUS", arrivedAtEpoch = nowEpoch - 172_040, departedAtEpoch = nowEpoch - 8_040),
                AirportStop(icao = "KTEB", arrivedAtEpoch = nowEpoch - 349_000, departedAtEpoch = nowEpoch - 300_000),
                AirportStop(icao = "KLAS", arrivedAtEpoch = nowEpoch - 520_000, departedAtEpoch = nowEpoch - 470_000)
            ),
            // Consistent with recentStops above: KLAS->KTEB and KTEB->KAUS legs
            // plus the current ongoing leg, all folded into IN_FLIGHT. Every
            // second of the 520,000s tracked span is accounted for exactly
            // once — same accounting the real backend does per pass.
            locationBreakdown = listOf(
                LocationShare(bucket = "IN_FLIGHT", seconds = 257_000),
                LocationShare(bucket = "KAUS", seconds = 164_000),
                LocationShare(bucket = "KLAS", seconds = 50_000),
                LocationShare(bucket = "KTEB", seconds = 49_000)
            ),
            trackedSeconds = 520_000
        )

        "bezos" -> FlightStatus(
            tail = "N758PB",
            icaoHex = "aa3908",
            state = FlightState.ON_GROUND,
            verified = true,
            departedIcao = "KBFI",
            departedAtEpoch = nowEpoch - 86_400,
            arrivedIcao = "KVNY",
            arrivedAtEpoch = nowEpoch - 79_200,
            currentAirportIcao = "KVNY",
            recentStops = listOf(
                AirportStop(icao = "KVNY", arrivedAtEpoch = nowEpoch - 79_200, departedAtEpoch = null),
                AirportStop(icao = "KBFI", arrivedAtEpoch = nowEpoch - 400_000, departedAtEpoch = nowEpoch - 86_400)
            ),
            locationBreakdown = listOf(
                LocationShare(bucket = "KBFI", seconds = 313_600),
                LocationShare(bucket = "KVNY", seconds = 79_200),
                LocationShare(bucket = "IN_FLIGHT", seconds = 7_200)
            ),
            trackedSeconds = 400_000,
            lastSeenEpoch = nowEpoch - 1_200,
            liveMapUrl = Config.liveMapFor("aa3908")
        )

        // Real registration and Mode S address (see holdings.json's
        // _comment). Identity only: no made-up flight activity.
        "trump" -> FlightStatus(
            tail = "N757AF",
            icaoHex = "aa3410",
            state = FlightState.UNKNOWN,
            verified = true,
            liveMapUrl = Config.liveMapFor("aa3410")
        )
        else -> null
    }

    /**
     * Demo vessel rows — the maritime mirror of [flightFor]. MMSIs are real,
     * publicly reported and cross-corroborated across multiple independent
     * AIS trackers (MarineTraffic, VesselFinder, MyShipTracking); the
     * underway/in-port STATE, ports and timestamps below are still a
     * fabricated demo scenario, not real positions — same split as the
     * flight rows above. Musk has no entry: no yacht confidently linked to
     * him turned up in research, and guessing one is exactly what this
     * feature refuses to do.
     */
    private fun vesselFor(id: String, nowEpoch: Long): VesselStatus? = when (id) {
        "bezos" -> VesselStatus(
            name = "KORU",
            mmsi = "319225400",
            state = VesselState.IN_PORT,
            verified = true,
            arrivedPortUnlocode = "USFLL",
            arrivedAtEpoch = nowEpoch - 240_000,
            currentPortUnlocode = "USFLL",
            recentStops = listOf(
                PortStop(unlocode = "USFLL", arrivedAtEpoch = nowEpoch - 240_000, departedAtEpoch = null),
                PortStop(unlocode = "MCMON", arrivedAtEpoch = nowEpoch - 600_000, departedAtEpoch = nowEpoch - 260_000)
            ),
            lastSeenEpoch = nowEpoch - 900,
            liveMapUrl = "https://www.marinetraffic.com/en/ais/details/ships/mmsi:319225400"
        )

        // Real MMSI, but no live position for this static demo — same
        // UNKNOWN-with-no-fabricated-activity treatment as the plane-only
        // entries in flightFor() above.
        "zuckerberg" -> VesselStatus(name = "LAUNCHPAD", mmsi = "538072122", state = VesselState.UNKNOWN, verified = true)
        else -> null
    }

    /**
     * Link out only — see [Person.socialUrl] for why we don't embed live
     * posts. Every entry here is a person's own confirmed personal account,
     * checked (Sept 2026) against live posts and/or independent reporting —
     * never a company account standing in for them, and never a guess. Same
     * "omit rather than fabricate" rule this file already holds itself to
     * everywhere else (bios, vessel MMSIs): `bezos` is missing on purpose,
     * not by oversight — search results for his name are dominated by
     * fan/parody accounts, with no independently confirmable verified badge
     * on any of them, so no link rather than risk linking an impersonator.
     */
    private fun socialFor(id: String): String? = when (id) {
        "musk" -> "https://x.com/elonmusk"
        "zuckerberg" -> "https://www.threads.com/@zuck"
        "luckey" -> "https://x.com/PalmerLuckey"
        "altman" -> "https://x.com/sama"
        "amodei" -> "https://x.com/DarioAmodei"
        "trump" -> "https://truthsocial.com/@realDonaldTrump"
        else -> null
    }

    /**
     * Real article URLs — these are just facts, not fabricated content — even
     * though this is the offline fallback snapshot. A real snapshot's link
     * comes from [fetch_wikipedia_info] on the backend instead.
     */
    private fun wikipediaFor(id: String): String = when (id) {
        "musk" -> "https://en.wikipedia.org/wiki/Elon_Musk"
        "bezos" -> "https://en.wikipedia.org/wiki/Jeff_Bezos"
        "zuckerberg" -> "https://en.wikipedia.org/wiki/Mark_Zuckerberg"
        "luckey" -> "https://en.wikipedia.org/wiki/Palmer_Luckey"
        "altman" -> "https://en.wikipedia.org/wiki/Sam_Altman"
        "amodei" -> "https://en.wikipedia.org/wiki/Dario_Amodei"
        "trump" -> "https://en.wikipedia.org/wiki/Donald_Trump"
        "thiel" -> "https://en.wikipedia.org/wiki/Peter_Thiel"
        else -> "https://en.wikipedia.org/wiki/${id.replaceFirstChar { it.uppercase() }}"
    }

    /**
     * A named, real file already hosted at commons.wikimedia.org for each
     * person — same "must actually live on Commons" bar the backend's
     * [fetch_wikipedia_info] enforces for live snapshots, just picked by hand
     * here instead of resolved from a live thumbnail lookup, since this
     * fallback snapshot makes no network calls of its own.
     *
     * `Special:FilePath/<name>` is Commons' own stable redirect to whatever
     * file currently lives at that title — it doesn't go stale the way a
     * hot-linked thumbnail URL would.
     */
    private fun photoFor(id: String): String? = when (id) {
        "musk" -> commonsFile("Elon Musk (54816836217) (cropped 2).jpg")
        "bezos" -> commonsFile("Jeff Bezos (cropped).jpg")
        "zuckerberg" -> commonsFile("Mark Zuckerberg (2025) (cropped).jpg")
        "luckey" -> commonsFile("CES 2026 - Palmer Luckey 01 (cropped).jpg")
        "thiel" -> commonsFile("Peter Thiel by Gage Skidmore.jpg")
        else -> null
    }

    /** width=200 asks Commons for a downsized render instead of the (often
     *  several-MB) original — plenty for a 56dp avatar, far less data. */
    private fun commonsFile(filename: String): String =
        "https://commons.wikimedia.org/wiki/Special:FilePath/" +
            filename.replace(" ", "_") + "?width=200"

    /** "YYYY-MM-DD" — a stable fact; the UI computes current age from this itself. */
    private fun birthDateFor(id: String): String? = when (id) {
        "musk" -> "1971-06-28"
        "bezos" -> "1964-01-12"
        "zuckerberg" -> "1984-05-14"
        "luckey" -> "1992-09-19"
        "altman" -> "1985-04-22"
        "trump" -> "1946-06-14"
        "thiel" -> "1967-10-11"
        else -> null
    }

    /**
     * Publicly reported primary residence, city/region only — same rule as
     * everywhere else location appears in this app. Worth double-checking
     * periodically, since residences go stale fast (Bezos's moved to
     * Florida in 2026).
     */
    private fun residenceFor(id: String): String? = when (id) {
        "musk" -> "Starbase, TX"
        "bezos" -> "Indian Creek, FL"
        "zuckerberg" -> "Palo Alto, CA"
        "luckey" -> "Newport Beach, CA"
        "altman" -> "San Francisco, CA"
        "trump" -> "Palm Beach, FL"
        else -> null
    }

    /**
     * Birthplace + last school attended, one or two plain sentences — see
     * [Person.bio] for why this is prose rather than two separate fields.
     * "Last attended" is reported literally: for Musk that's a graduate
     * program he left unfinished, with the earlier degrees he did complete
     * named alongside it so neither fact reads as the whole story on its own.
     */
    /** Same figures as backend/holdings.json (see its _comment for sources). */
    private fun estimateFor(id: String): WealthEstimate? = when (id) {
        "amodei" -> WealthEstimate(
            asOf = "2026-10-16", source = "Forbes",
            stakes = listOf(StakeMark("Anthropic", 0.01606, 965_000_000_000.0, "2026-05-28"))
        )
        "luckey" -> WealthEstimate(
            asOf = "2026-10-16", source = "Forbes",
            stakes = listOf(StakeMark("Anduril", 0.0984, 61_000_000_000.0, "2026-05-13"))
        )
        "trump" -> WealthEstimate(
            asOf = "2026-10-16", source = "Forbes",
            otherUsd = 5_800_000_000.0, otherLabel = "Real estate, crypto and other assets"
        )
        "altman" -> WealthEstimate(
            asOf = "2026-10-16", source = "Forbes",
            otherUsd = 3_200_000_000.0, otherLabel = "Stripe, Helion and other investments"
        )
        "thiel" -> WealthEstimate(
            asOf = "2026-10-16", source = "Forbes",
            otherUsd = 18_200_000_000.0, otherLabel = "Founders Fund and other investments"
        )
        else -> null
    }

    private fun bioFor(id: String): String? = when (id) {
        "musk" -> "Born in Pretoria, South Africa. Earned bachelor's degrees in " +
            "physics and economics from the University of Pennsylvania (1997); " +
            "left a Stanford PhD program in materials science after two days " +
            "to co-found Zip2."
        "bezos" -> "Born in Albuquerque, New Mexico. Graduated summa cum laude " +
            "from Princeton University in 1986 with a B.S.E. in electrical " +
            "engineering and computer science."
        "zuckerberg" -> "Born in White Plains, New York. Studied computer " +
            "science at Harvard College, leaving in 2004 — sophomore year — " +
            "to run Facebook full-time; Harvard awarded him an honorary " +
            "degree in 2017."
        "luckey" -> "Born and raised in Long Beach, California, and homeschooled. " +
            "Studied journalism at California State University, Long Beach, " +
            "and left to work on Oculus full-time."
        "altman" -> "Born in Chicago, Illinois, and raised in Clayton, Missouri. " +
            "Attended Stanford University and left without a degree."
        "amodei" -> "Born in San Francisco, California. Earned a bachelor's " +
            "degree in physics at Stanford and a doctorate in biophysics at " +
            "Princeton."
        "trump" -> "Born in Queens, New York City. Graduated from the " +
            "University of Pennsylvania's Wharton School with a bachelor's " +
            "degree in economics."
        "thiel" -> "Born in Frankfurt, West Germany, and raised in the United " +
            "States, South Africa and Namibia. Studied philosophy at Stanford " +
            "University, then earned a law degree at Stanford Law School in 1992."
        else -> null
    }

    private fun newsFor(id: String, nowEpoch: Long): List<NewsItem> = when (id) {
        "musk" -> listOf(
            NewsItem(
                "SpaceX shares extend post-listing run",
                "Seed data",
                "https://example.com",
                nowEpoch - 5_400
            ),
            NewsItem(
                "Tesla delivery figures due this week",
                "Seed data",
                "https://example.com",
                nowEpoch - 26_000
            )
        )

        "bezos" -> listOf(
            NewsItem(
                "Amazon logistics unit opens new regional hubs",
                "Seed data",
                "https://example.com",
                nowEpoch - 11_200
            )
        )

        "zuckerberg" -> listOf(
            NewsItem(
                "Meta details next wave of AI datacentre buildout",
                "Seed data",
                "https://example.com",
                nowEpoch - 9_600
            )
        )

        else -> emptyList()
    }
}
