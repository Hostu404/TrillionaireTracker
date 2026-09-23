package com.hostu404.trilliontracker.data

import java.time.Instant
import kotlin.math.abs
import kotlin.random.Random

/**
 * Embedded fallback snapshot so the app runs with no backend.
 *
 * Figures are the Forbes top ten as published 1 September 2026. They are a
 * starting point for the UI, not a live feed — once [Config.SNAPSHOT_URL] is
 * set, this is only used when the network read fails.
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
        Row("page", "Larry Page", "Google", 277.0, -1.1),
        Row("bezos", "Jeff Bezos", "Amazon", 268.0, 0.9),
        Row("brin", "Sergey Brin", "Google", 256.0, -1.0),
        Row("dell", "Michael Dell", "Dell Technologies", 241.0, 0.4),
        Row("zuckerberg", "Mark Zuckerberg", "Meta", 197.0, 2.2),
        Row("ellison", "Larry Ellison", "Oracle", 193.0, -0.6),
        Row("huang", "Jensen Huang", "Nvidia", 191.0, 3.1),
        Row("ballmer", "Steve Ballmer", "Microsoft", 155.0, 0.5),
        Row("ortega", "Amancio Ortega", "Inditex", 148.0, -0.2)
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
                history = walk(seed = row.id.hashCode(), end = net, points = 48),
                flight = flightFor(row.id, nowEpoch),
                vessel = vesselFor(row.id, nowEpoch),
                news = newsFor(row.id, nowEpoch),
                socialUrl = socialFor(row.id),
                wikipediaUrl = wikipediaFor(row.id),
                photoUrl = photoFor(row.id),
                birthDate = birthDateFor(row.id),
                residence = residenceFor(row.id),
                bio = bioFor(row.id)
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
        "MCMON" to PortInfo(name = "Port Hercules", city = "Monaco", country = "MC", label = "Monaco, MC", lat = 43.7325, lon = 7.4231),
        "NZAKL" to PortInfo(name = "Port of Auckland", city = "Auckland", country = "NZ", label = "Auckland, NZ", lat = -36.8444, lon = 174.7692)
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

        // Real, publicly reported tail numbers, but no Mode S hex or flight
        // activity to go with them (couldn't confidently source one, and
        // making one up is exactly the kind of guess this feature refuses
        // to do — see the class doc above). FlightState.UNKNOWN + no stops
        // is the honest shape for "we know the aircraft, this static demo
        // has no live position for it" rather than fabricating a scenario.
        "zuckerberg" -> FlightStatus(
            tail = "N68885", icaoHex = "unknown-zuckerberg", state = FlightState.UNKNOWN, verified = true
        )
        "ellison" -> FlightStatus(
            tail = "N817GS", icaoHex = "unknown-ellison", state = FlightState.UNKNOWN, verified = true
        )
        "huang" -> FlightStatus(
            tail = "9H-VID", icaoHex = "unknown-huang", state = FlightState.UNKNOWN, verified = true
        )
        "ballmer" -> FlightStatus(
            tail = "N709DS", icaoHex = "unknown-ballmer", state = FlightState.UNKNOWN, verified = true
        )
        "dell" -> FlightStatus(
            tail = "N28MS", icaoHex = "unknown-dell", state = FlightState.UNKNOWN, verified = true
        )

        else -> null
    }

    /**
     * Demo vessel rows — the maritime mirror of [flightFor]. MMSIs are real,
     * publicly reported and cross-corroborated across multiple independent
     * AIS trackers (MarineTraffic, VesselFinder, MyShipTracking); the
     * underway/in-port STATE, ports and timestamps below are still a
     * fabricated demo scenario, not real positions — same split as the
     * flight rows above. Musk, Dell, Huang, Ballmer, and Page have no entry:
     * no yacht confidently linked to the first four turned up in research,
     * and guessing one is exactly what this feature refuses to do. Page
     * *did* carry an entry here until this correction — his old "SENSES"
     * MMSI (319833000) — but Boat International's tech-billionaire yacht
     * roundup states outright that Senses was sold by Page to an unknown
     * buyer in 2020, and superyachtfan.com's own SENSES page separately
     * names its current owner as Andrea Recordati, an unrelated Italian
     * pharma billionaire, corroborating the sale. Tracking that MMSI under
     * Page's name would have quietly attributed Recordati's real boat
     * movements to him — worse than showing no vessel at all — so it was
     * removed here to match the same correction already made in
     * `backend/holdings.json` (see that file's own `_comment`, "page.mmsi/
     * vesselName removed 2026-09-22"). Add him back only once a current,
     * confidently-sourced yacht turns up.
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

        "ellison" -> VesselStatus(
            name = "MUSASHI",
            mmsi = "319032600",
            state = VesselState.UNDERWAY,
            verified = true,
            departedPortUnlocode = "MCMON",
            departedAtEpoch = nowEpoch - 18_000,
            selfReportedDestination = "MONACO",
            lastSeenEpoch = nowEpoch - 300,
            liveMapUrl = "https://www.marinetraffic.com/en/ais/details/ships/mmsi:319032600"
        )

        "brin" -> VesselStatus(
            name = "DRAGONFLY",
            mmsi = "319296900",
            state = VesselState.UNDERWAY,
            verified = true,
            departedPortUnlocode = "USFLL",
            departedAtEpoch = nowEpoch - 40_000,
            selfReportedDestination = null,  // honest demo case: often just blank
            lastSeenEpoch = nowEpoch - 600,
            liveMapUrl = "https://www.marinetraffic.com/en/ais/details/ships/mmsi:319296900"
        )

        // Real MMSI, but no live position for this static demo — same
        // UNKNOWN-with-no-fabricated-activity treatment as the plane-only
        // entries in flightFor() above.
        "zuckerberg" -> VesselStatus(name = "LAUNCHPAD", mmsi = "538072122", state = VesselState.UNKNOWN, verified = true)
        // Demoing the 2026-09-23 general-location fallback (see
        // VesselStatus.currentLat's doc comment): a real position with no
        // known port nearby now shows an approximate area instead of
        // nothing, so this entry exercises that path in the seed data
        // rather than leaving it untested until a live AIS catch does.
        "ortega" -> VesselStatus(
            name = "DRIZZLE",
            mmsi = "256867000",
            state = VesselState.UNDERWAY,
            verified = true,
            currentLat = 39.5,
            currentLon = 2.9,
            generalLocation = "Mediterranean Sea",
            lastSeenEpoch = nowEpoch - 600,
            liveMapUrl = "https://www.marinetraffic.com/en/ais/details/ships/mmsi:256867000"
        )

        else -> null
    }

    /**
     * Link out only — see [Person.socialUrl] for why we don't embed live
     * posts. Every entry here is a person's own confirmed personal account,
     * checked (Sept 2026) against live posts and/or independent reporting —
     * never a company account standing in for them (the old `huang` entry
     * pointed at @nvidia, NVIDIA's own account, not Jensen Huang's; fixed
     * below) and never a guess. Same "omit rather than fabricate" rule this
     * file already holds itself to everywhere else (bios, vessel MMSIs, tax
     * rate sourcing): `page`, `bezos`, `brin`, `ellison`, and `ortega` are
     * missing on purpose, not by oversight —
     *
     *  - `page`/`brin`: no genuine personal account for either has ever
     *    surfaced — Wikipedia's own infobox carries none, and every "Larry
     *    Page"/"Sergey Brin" handle findable is an unverified impersonator
     *    or fan account.
     *  - `bezos`: search results for his name are similarly dominated by
     *    fan/parody accounts, with no independently confirmable verified
     *    badge on any of them — same call as page/brin rather than risk
     *    linking someone to an impersonator.
     *  - `ellison`: his long-dormant @larryellison X account was reported
     *    deactivated/deleted in March 2026.
     *  - `ortega`: notoriously reclusive; no legitimate account has ever
     *    been attributed to him by mainstream reporting.
     */
    private fun socialFor(id: String): String? = when (id) {
        "musk" -> "https://x.com/elonmusk"
        "huang" -> "https://x.com/JensenHuang"
        "dell" -> "https://x.com/MichaelDell"
        "zuckerberg" -> "https://www.threads.com/@zuck"
        "ballmer" -> "https://x.com/Steven_Ballmer"
        else -> null
    }

    /**
     * Real article URLs — these are just facts, not fabricated content — even
     * though this is the offline fallback snapshot. A real snapshot's link
     * comes from [fetch_wikipedia_info] on the backend instead.
     */
    private fun wikipediaFor(id: String): String = when (id) {
        "musk" -> "https://en.wikipedia.org/wiki/Elon_Musk"
        "page" -> "https://en.wikipedia.org/wiki/Larry_Page"
        "bezos" -> "https://en.wikipedia.org/wiki/Jeff_Bezos"
        "brin" -> "https://en.wikipedia.org/wiki/Sergey_Brin"
        "dell" -> "https://en.wikipedia.org/wiki/Michael_Dell"
        "zuckerberg" -> "https://en.wikipedia.org/wiki/Mark_Zuckerberg"
        "ellison" -> "https://en.wikipedia.org/wiki/Larry_Ellison"
        "huang" -> "https://en.wikipedia.org/wiki/Jensen_Huang"
        "ballmer" -> "https://en.wikipedia.org/wiki/Steve_Ballmer"
        "ortega" -> "https://en.wikipedia.org/wiki/Amancio_Ortega"
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
     *
     * Amancio Ortega is deliberately left with no entry: he's rarely
     * photographed and no clean freely-licensed portrait of him could be
     * found on Commons. Null here means the same thing it means from the
     * real backend — no free photo exists, not that nobody checked — and the
     * UI already handles that with a plain initial disc.
     */
    private fun photoFor(id: String): String? = when (id) {
        "musk" -> commonsFile("Elon Musk (54816836217) (cropped 2).jpg")
        "page" -> commonsFile("Larry Page.jpg")
        "bezos" -> commonsFile("Jeff Bezos (cropped).jpg")
        "brin" -> commonsFile("Sergey Brin.JPG")
        "dell" -> commonsFile("Michael Dell (52548152888) (cropped).jpg")
        "zuckerberg" -> commonsFile("Mark Zuckerberg (2025) (cropped).jpg")
        "ellison" -> commonsFile("Larry Ellison 2013 (9887589546).jpg")
        "huang" -> commonsFile("Jensen Huang (cropped).jpg")
        "ballmer" -> commonsFile("Steve Ballmer 2014.jpg")
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
        "page" -> "1973-03-26"
        "bezos" -> "1964-01-12"
        "brin" -> "1973-08-21"
        "dell" -> "1965-02-23"
        "zuckerberg" -> "1984-05-14"
        "ellison" -> "1944-08-17"
        "huang" -> "1963-02-17"
        "ballmer" -> "1956-03-24"
        "ortega" -> "1936-03-28"
        else -> null
    }

    /**
     * Publicly reported primary residence, city/region only — same rule as
     * everywhere else location appears in this app. Two notes from the
     * research behind this list, because both are the kind of thing that
     * goes stale fast and is worth someone double-checking periodically:
     *
     * - Ellison's is NOT Lanai, Hawaii, despite that being the most
     *   commonly repeated fact about him — multiple 2026 sources (voter
     *   registration, a filed declaration of domicile, and flight-tracking
     *   data with no Hawaii legs since late 2023) point to Manalapan, FL.
     * - Brin has no entry at all: as of early 2026 he was reported to be
     *   actively house-hunting in Miami after leaving Los Altos Hills —
     *   an unsettled, in-progress move, not a fact to publish as current.
     */
    private fun residenceFor(id: String): String? = when (id) {
        "musk" -> "Starbase, TX"
        "page" -> "Palo Alto, CA"
        "bezos" -> "Indian Creek, FL"
        "dell" -> "Austin, TX"
        "zuckerberg" -> "Palo Alto, CA"
        "ellison" -> "Manalapan, FL"
        "huang" -> "Los Altos Hills, CA"
        "ballmer" -> "Hunts Point, WA"
        "ortega" -> "A Coruña, Spain"
        else -> null
    }

    /**
     * Birthplace + last school attended, one or two plain sentences — see
     * [Person.bio] for why this is prose rather than two separate fields.
     * "Last attended" is reported literally: for Musk, Page, Brin and
     * Ballmer that's a graduate program they left unfinished, with the
     * earlier degree they did complete named alongside it so neither fact
     * reads as the whole story on its own.
     */
    private fun bioFor(id: String): String? = when (id) {
        "musk" -> "Born in Pretoria, South Africa. Earned bachelor's degrees in " +
            "physics and economics from the University of Pennsylvania (1997); " +
            "left a Stanford PhD program in materials science after two days " +
            "to co-found Zip2."
        "page" -> "Born in East Lansing, Michigan. Earned a B.S. in computer " +
            "engineering from the University of Michigan, then a master's at " +
            "Stanford — where he took a leave from the PhD program in 1998 to " +
            "co-found Google."
        "bezos" -> "Born in Albuquerque, New Mexico. Graduated summa cum laude " +
            "from Princeton University in 1986 with a B.S.E. in electrical " +
            "engineering and computer science."
        "brin" -> "Born in Moscow, in what was then the Soviet Union. Studied " +
            "computer science and mathematics at the University of Maryland, " +
            "then took a leave from Stanford's computer science PhD program " +
            "in 1995 to co-found Google."
        "dell" -> "Born in Houston, Texas. Enrolled as a pre-med student at the " +
            "University of Texas at Austin in 1983 and dropped out the " +
            "following year, at 19, to run Dell full-time."
        "zuckerberg" -> "Born in White Plains, New York. Studied computer " +
            "science at Harvard College, leaving in 2004 — sophomore year — " +
            "to run Facebook full-time; Harvard awarded him an honorary " +
            "degree in 2017."
        "ellison" -> "Born in New York City, raised in Chicago. Attended the " +
            "University of Illinois and later the University of Chicago, " +
            "leaving school without a degree after one term studying physics " +
            "and math."
        "huang" -> "Born in Taipei, Taiwan. Earned a B.S. in electrical " +
            "engineering from Oregon State University in 1984, then a " +
            "master's from Stanford in 1992 while working at LSI Logic."
        "ballmer" -> "Born in Detroit, Michigan. Graduated from Harvard with a " +
            "degree in applied mathematics and economics in 1977, then left " +
            "Stanford's MBA program in 1980 to join Microsoft as its 30th " +
            "employee."
        "ortega" -> "Born in Busdongo de Arbas, in León, Spain. Left school at " +
            "14 to work as a delivery boy for a local shirtmaker — he has no " +
            "university education."
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

        "page" -> listOf(
            NewsItem(
                "Alphabet board weighs new AI infrastructure spend",
                "Seed data",
                "https://example.com",
                nowEpoch - 19_800
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

        "brin" -> listOf(
            NewsItem(
                "Google DeepMind ships next model update",
                "Seed data",
                "https://example.com",
                nowEpoch - 31_500
            )
        )

        "dell" -> listOf(
            NewsItem(
                "Dell Technologies raises enterprise hardware guidance",
                "Seed data",
                "https://example.com",
                nowEpoch - 42_000
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

        "ellison" -> listOf(
            NewsItem(
                "Oracle cloud infrastructure bookings climb again",
                "Seed data",
                "https://example.com",
                nowEpoch - 22_400
            )
        )

        "huang" -> listOf(
            NewsItem(
                "Nvidia adds on datacentre guidance",
                "Seed data",
                "https://example.com",
                nowEpoch - 14_000
            )
        )

        "ballmer" -> listOf(
            NewsItem(
                "Microsoft's largest outside shareholder trims stake slightly",
                "Seed data",
                "https://example.com",
                nowEpoch - 51_000
            )
        )

        "ortega" -> listOf(
            NewsItem(
                "Inditex quarterly sales top estimates",
                "Seed data",
                "https://example.com",
                nowEpoch - 36_800
            )
        )

        else -> emptyList()
    }
}
