# Trillionaire Tracker

An Android client built with **Kotlin and Jetpack Compose** that tracks live net worth milestones, real-time aircraft and vessel movements, news, and biographical histories. 

**[Download Latest APK Release](https://github.com/Hostu404/TrillionaireTracker/releases/latest)**

<img width="1216" height="874" alt="image" src="https://github.com/user-attachments/assets/9084f669-0f73-4a27-8175-7a3f0bd4cb55" />


---

## Features

### Net Worth
* **Formula:** $\text{sum}(\text{shares} \times \text{live price}) + \text{private stakes} - \text{liabilities} + \text{cash}$
* **Sourcing:** Share counts come from SEC filings (Form 4, 13D/G, proxy statements). Private stakes use hand-set marks from the last funding round. 
* **The Debt-Clock Trick:** Between snapshots, the client extrapolates locally from a drift rate so the figure animates continuously without extra network traffic.

### Flights
* **Live ADS-B Tracking:** Powered by `adsb.lol` (free, unfiltered, community-fed).
* **Privacy-First Location:** Airport-level granularity only (never raw coordinates). A 7-day rolling history tracks airport stops.
* **Heading Estimates:** Projects current position against known airports within a $\pm 12^\circ$ cone while airborne.
* **Time-by-Location Breakdown:** A person screen donut chart splits the tracked window across airports, in-flight time, and signal gaps.

### Vessels
* **Maritime AIS Tracking:** Uses a separate long-term backend listener (`backend/ais_listener.py`) connected to `aisstream.io` via WebSockets, caching updates to prevent per-user API costs.
* **Port Granularity:** Follows the same strict privacy shape as flights—port stops only, never open-water coordinates.

### Biography, Social, & News
* **Static Profile Data:** Birthdates, primary residences (city/region only), and short biographical prose (birthplace and education). Age is computed client-side dynamically.
* **Wikipedia Integration:** Fetches summaries and free-license Wikimedia Commons photos via the keyless Wikipedia REST API, cached for 30 days.
* **Socials & News:** Clean link-outs to verified profiles (X, Bluesky) without pay-per-read API overhead.

---
## Why is Trillionaire Tracker?
https://www.youtube.com/watch?v=wP8RgWJ76xE

Further reading,

https://www.youtube.com/watch?v=jUwh-C5w7II


---

## Architecture & Cost Efficiency

Clients never call third-party APIs directly. A single backend worker polls upstream data and writes a single JSON document, which a CDN edge serves to any number of clients:

$$\text{Worker (1 pass/min)} \longrightarrow \texttt{snapshot.json} \longrightarrow \text{CDN Edge} \longrightarrow \text{Any Number of Clients}$$

* **Flat Scaling Cost:** Ten users and ten million users cost the exact same upstream.
* **Embedded Seed Snapshot:** The app ships with a working seed snapshot so it opens and runs out-of-the-box without manual setup.

## Run it

1. Open the project folder in Android Studio, let it sync.
2. Run on a device or emulator (minSdk 26).

The number ticks immediately on seed data. To go live, set one constant:

```kotlin
// app/src/main/java/com/hostu404/trilliontracker/data/Config.kt
const val SNAPSHOT_URL = "https://cdn.yourdomain.com/v1/snapshot.json"
```

Nothing else changes.

## Why it can take a lot of users

Clients never call a third-party API. The backend polls upstream on a fixed
budget and writes **one JSON document**; every client reads that cached copy.

```
worker (1 pass/min) --> snapshot.json --> CDN edge --> any number of clients
```

Upstream call volume is a function of how many people you track, not how many
users you have. Ten users and ten million users cost the same upstream.

Between snapshots the client extrapolates locally from a drift rate, so the
figure animates continuously without any extra network traffic — the debt-clock
trick. It is a projection and the UI says so, it is clamped so a stale snapshot
can't run away, and every real value still comes from the backend.

## Net worth

```
sum(shares x live price) + private stakes - liabilities + cash
```

Share counts come from SEC filings (Form 4, 13D/G, proxy statements). Private
stakes are hand-set marks from the last funding round. Computing it yourself
rather than scraping a published index is both cheaper and the only version with
no licensing exposure.

If any public holding is unpriced the person is skipped for that pass. A partial
sum silently understates, and nobody notices.

## Flights

Aircraft rows:

| Field | Timing | Source |
|---|---|---|
| Airborne / on-ground state | live | ADS-B |
| Departure airport and elapsed time | live | ADS-B |
| "Heading toward" while airborne | live, labeled as an estimate | computed |
| Confirmed destination and arrival time | as soon as it lands | ADS-B |
| Current location | live, airport granularity only | ADS-B |
| Location history | trailing 7 days, airport stops only | ADS-B |
| Live map pin | live | not hosted — links out to ADS-B Exchange |

**Current location and history are airport-level, never coordinates.**
`currentAirportIcao` is null while airborne — "no fixed location" is the honest
answer for a plane in the air, not a live lat/lon we could show instead.
`recentStops` is a rolling 7-day list of airports it's touched, each with
arrival/departure time, pruned every pass. This is the granularity every public
jet-tracking site already operates at (which airfield, when). A precise
position trail is a meaningfully worse version of the risk the estimate field
above already treats carefully — a week of coordinates is far more actionable
than "it landed at KVNY" — so this app doesn't produce one.

**Airport codes are never shown raw in the UI.** ICAO codes like `KAUS` mean
nothing to most people, so every airport-facing field — current location,
departed/arrived/heading-toward, and the 7-day history list — resolves through
`Snapshot.airports` (built by `airport_label()` in `snapshot_worker.py`, e.g.
`KAUS` → "Austin, TX") before it ever reaches a screen. The snapshot only
carries entries for codes it actually used that pass, not the whole airport
database. If a code has no match (no `airports.csv`, or the code isn't in it),
the raw ICAO is shown as a fallback rather than nothing.

**Time-by-location breakdown.** The person screen shows a donut of how the
tracked window splits across airports, in-flight time, and signal gaps
(`FlightStatus.locationBreakdown` + `trackedSeconds`). It's built entirely from
the same per-pass ADS-B read as everything else above — one sample per pass,
attributed to a bucket, summed at snapshot time — so it costs zero extra
upstream calls. Percentages are of `trackedSeconds`, not a blind 7-day
assumption: right after the backend starts tracking a new tail, that's
whatever's actually been observed so far, and the UI says so. Real airports cap at 5 identity colors with the rest folded into "Other
airports"; in-flight and no-signal time get fixed status colors instead of
eating into that budget (see `buildDonutSlices` in `PersonDetailScreen.kt`).

Two things worth knowing:

1. **Raw ADS-B has no destination field.** An aircraft's transponder broadcasts
   position, not where it's going. FlightAware/FR24 get destinations from FAA
   SWIM flight-plan data — a separate, gated feed, and one that respects the
   same LADD blocking anyway. So the confirmed destination only exists once the
   aircraft is on the ground; that's a fact about the data, not a delay we
   chose.
2. **`estimatedDestinationIcao`** fills that gap while airborne: the backend
   projects current position + track against known large/medium airports
   (`estimate_heading_destination` in `snapshot_worker.py`) and picks the
   nearest one within a ±12° cone of the current heading. It's a guess from
   public course data, not a filed flight plan, the app always labels it as an
   estimate, and it will sometimes be wrong or absent. Worth knowing this
   re-introduces a lighter version of the "where are they headed right now"
   signal that got early jet-tracking accounts pulled from platforms — treat
   it as informational, not something to push notifications on.

Data comes from adsb.lol — community-fed, unfiltered, free, and rate-limited by
courtesy rather than by key. One request per aircraft per pass. If you grow past
a handful of tails, feed a receiver back to the network or move to a paid feed.

**Tail numbers are not guessed.** `tailVerified` stays false until the aircraft
is confirmed to the person from filings or archived ADS-B, and the app shows an
"unverified" badge until it is.

The seed/demo snapshot now carries real, publicly reported registrations
instead of placeholders — `N628TS` (Musk), `N758PB` (Bezos), `N68885`
(Zuckerberg), `N817GS` (Ellison), `9H-VID` (Huang), `N709DS` (Ballmer),
`N28MS` (Dell) — each cross-corroborated across multiple independent
aviation trackers. Musk's and Bezos's demo entries also carry a real Mode S
hex and a working "Open live map" link; the other five have a real tail but
no confirmed hex, so they show as `NO SIGNAL` with no live-map link rather
than one pointed at a guess. For all seven, only the *identity* (tail/hex)
is real — the flight states, airports and timestamps around Musk's and
Bezos's entries are still a fabricated demo scenario to exercise the UI, not
an actual flight. Larry Page, Sergey Brin, and Amancio Ortega have no entry
at all: nothing turned up that was solid enough to publish, which is the
same bar a real backend run applies before setting `tailVerified`.

## Vessels

The maritime mirror of the Flights section above — same privacy shape, and
mostly the same code path (`VesselStatus`/`PortStop` in `Models.kt`,
`BoatCard`/`PortHistoryCard` in `PersonDetailScreen.kt` are line-for-line
structural copies of the flight versions). The one real difference is the
data source, which changes the architecture underneath it:

**Vessels broadcast AIS, not ADS-B — same idea, different transport, and
crucially no free "ask, get an answer" API that covers open water the way
adsb.lol does for planes.** The free tier that actually works globally
(aisstream.io) is a persistent WebSocket subscription, not a poll. So
tracking vessels needs a second, separate, long-running process —
`backend/ais_listener.py` — that stays connected and writes the latest
known position per MMSI to `ais_cache.json`. `snapshot_worker.py` only ever
reads that local file; it makes zero network calls for vessels, so the "one
poll cycle, flat cost regardless of user count" architecture still holds —
the WebSocket connection is a fixed backend-operator cost, not a per-user
one, same as everything else here.

Run it once, continuously (systemd, supervisord, whatever you'd use for any
long-lived process) — **not** on the same one-minute cron as
`snapshot_worker.py`:

```bash
cd backend
pip install websockets        # the one non-stdlib dependency in this
                               # backend, confined to this one file
export AISSTREAM_API_KEY=...  # free key: https://aisstream.io/authenticate
python3 ais_listener.py
```

It only subscribes to the MMSIs you've actually listed in `holdings.json`
(up to 200), never the whole ocean. This was written against aisstream.io's
published docs, not exercised against the live service — the environment
it was built in has no route to it — so its logs are worth a look the
first time you run it for real.

Everything else follows the flight pattern exactly:

- **Port granularity only, never a coordinate**, live or historical — same
  reasoning as `currentAirportIcao`/`recentStops`. `currentPortUnlocode`,
  `recentStops` (7-day rolling, port stops).
- **`selfReportedDestination` is the one place vessels differ from planes**:
  AIS actually has a destination field (an aircraft's ADS-B doesn't), but
  it's free text the crew types in by hand — routinely blank, stale, or
  informal shorthand, not a filed plan. It's shown, but always with that
  caveat, never presented as confirmed.
- **MMSI is not guessed.** `vesselVerified` stays false until the vessel is
  confirmed to the person from public registries/AIS trackers, same as
  `tailVerified` for aircraft.
- Optionally drop a `ports.csv` beside `snapshot_worker.py` to get port
  names instead of bare UN/LOCODE-style codes — see `load_ports()`'s
  docstring for the expected columns. Unlike airports, you only need the
  handful of ports your tracked yachts actually visit, not a global
  database, so hand-curating this file from UN/LOCODE + the World Port
  Index (both free) is realistic.
- The seed/demo snapshot carries real MMSIs for six people — `KORU`
  (Bezos), `MUSASHI` (Ellison), `SENSES` (Page), `DRAGONFLY` (Brin),
  `LAUNCHPAD` (Zuckerberg), `DRIZZLE` (Ortega), each cross-corroborated
  across multiple independent AIS trackers — with the same split as the
  flight seed data: real identity, fabricated demo activity. Musk, Dell,
  Huang, and Ballmer have no entry — no yacht confidently linked to any of
  them turned up in research.

## Social

Each person can carry a `socialUrl` — a plain profile link (X, Bluesky,
wherever they actually post). The app links out to it; it never embeds or polls
live posts. X's read API has no free tier as of 2026 (pay-per-read, no flat
rate), which would put a metered bill behind every page view — exactly what the
snapshot architecture exists to avoid. Bluesky's API is free and keyless if you
want to pull real posts for people who post there, but coverage for this cohort
is thin, so link-out is the default.

## Age, birthdate, residence and bio

Shown right under the name on the detail screen, next to the photo:

- **`birthDate`** ("YYYY-MM-DD") is the one static fact in this group — set
  once in `holdings.json` and never touched again. **Age is not stored
  anywhere.** `Format.ageFrom()` computes it client-side from `birthDate`
  and the device's current date every time the screen renders, so it
  advances on its own the next time someone opens the app after a
  birthday — no new snapshot, no backend change, nothing to keep in sync.
- **`residence`** is a publicly reported primary residence, at city/region
  granularity only — "Starbase, TX", never a street address. Same rule
  that keeps flight/vessel location at airport/port granularity applies
  here for the same reason: precise enough to be informative, never
  precise enough to be a target.
- **`bio`** is one or two plain sentences — birthplace and the last school
  or university actually attended, whether or not they finished it — shown
  above the family history and wealth cards (see "Layout order" in
  `PersonDetailScreen.kt`). Prose rather than separate fields on purpose:
  several of this cohort left a *later* graduate program unfinished after
  completing an earlier degree elsewhere, and prose is the only honest way
  to say "last attended" without it silently reading as "graduated from."
- All three are static, hand-curated fields in `holdings.json` (like
  `company`), not fetched from anywhere — a birthdate, a home city and a
  birthplace/education fact don't change often enough to justify a live
  lookup the way a Wikipedia photo does. Leave `residence`/`bio` out
  entirely rather than guess when public reporting is stale or
  conflicting — see the `residenceFor()` comment in `SeedData.kt` for two
  real examples from researching the seed data (Larry Ellison's reported
  home turned out to be Florida, not the Hawaiian island most coverage
  still associates with him; Sergey Brin was mid-move with no settled
  address, so he has no entry at all).

## Wikipedia link and photo

Every person carries `wikipediaUrl` and `photoUrl`, shown near the top of
their detail screen next to a plain initial disc that stands in until a photo
loads (or forever, if there isn't one).

- `wikipediaUrl` comes from the free, keyless Wikipedia REST summary API
  (`fetch_wikipedia_info()` in `snapshot_worker.py`). It's looked up by
  `wikipediaTitle` in `holdings.json` (defaults to the name with spaces
  replaced by underscores — override it for a disambiguation page or a stage
  name), and results are cached for 30 days per person since a photo and an
  article link barely ever change, so this is a rare request, not a per-pass
  one.
- `photoUrl` is **only ever a Wikimedia Commons URL.** The backend checks the
  hosting path of Wikipedia's own thumbnail and only keeps it if that path is
  `/wikipedia/commons/` — Commons is free-license-only by policy and deletes
  anything that isn't, so a "fair use" image (which Wikipedia hosts locally,
  under `/wikipedia/en/`) never makes it into the app. That person just gets
  the article link with no photo, which is why `photoUrl` being null doesn't
  mean the fetch failed — it means no free image existed to show.
- A disambiguation page is also skipped entirely (no link, no photo) rather
  than showing the wrong person.
- The bundled seed/demo snapshot (used automatically whenever `Config.SNAPSHOT_URL`
  is blank — i.e. no backend wired up yet) sets a real `wikipediaUrl` for all
  ten people, and for nine of them a real `photoUrl` too: a specific Commons
  file picked by hand for each (`photoFor()` in `SeedData.kt`), addressed via
  `Special:FilePath/<name>` — Commons' own stable redirect to whatever
  currently lives at that title, so it won't go stale like a hot-linked
  thumbnail would. It's a hand-picked file rather than a live lookup because
  this fallback snapshot makes no network calls of its own, but the file
  still has to actually live at commons.wikimedia.org, same bar the backend
  enforces. Amancio Ortega is the one person left with no photo — he's rarely
  photographed and no clean freely-licensed portrait of him turned up on
  Commons, so he gets the plain initial disc, same as anyone a live snapshot
  couldn't find a photo for.
- **Portraits need a real User-Agent header, or they silently never load.**
  Wikimedia's servers apply their published API etiquette policy to plain
  Commons file requests, not just the api.wikimedia.org endpoints, and that
  policy rejects/deprioritizes a generic HTTP-client User-Agent (Coil's
  default OkHttp client sends a bare `okhttp/4.x`). A browser works because
  it sends a normal browser UA; the app doesn't, by default. `TrillionaireTrackerApp.kt`
  fixes this — it's a custom `Application` that hands Coil an `OkHttpClient`
  with an interceptor setting the same `User-Agent` string the Python
  backend already uses for its own Wikipedia calls, and it's registered via
  `android:name=".TrillionaireTrackerApp"` in `AndroidManifest.xml`. Without
  this class the URLs work fine pasted into a browser but the in-app image
  never appears — and Coil logs nothing about it either, since it has no
  logger attached by default.

## Map

Each detail screen carries a small world map whenever the person has a plane
or boat mapped — a self-drawn Jetpack Compose `Canvas`, no WebView, no maps
SDK, no per-user API calls, same as every other screen here.

- **Country outlines** come from Natural Earth's public-domain Admin-0
  boundaries (1:110m scale), redistributed as TopoJSON by the `world-atlas`
  npm package (Copyright 2013-2019 Michael Bostock, permissive
  use/copy/modify/distribute license) and converted once, at build time, to
  a flat, bundled JSON asset:
  `app/src/main/assets/world_countries.json` — 177 countries, each an
  exterior-ring-only outline (interior holes/enclaves like Lesotho are
  dropped) rounded to 0.05° precision. About 125KB. `WorldGeo.kt` loads and
  caches it once via `context.assets`; there's no network call involved and
  nothing about it ever changes at runtime.
- **Ocean/sea labels** ("Pacific Ocean", "Mediterranean Sea", etc.) are a
  short hand-picked list in `WorldGeo.kt` (`OCEAN_LABELS`) rather than a
  dataset — there's no equivalent boundary polygon for open water at this
  scale, so these are just a name plus the conventional label point used on
  most reference atlases. They're always visible, at any zoom.
- **Country names** only render once zoomed in a few steps
  (`WorldMapCard.kt`, gated on `scale > 3f`). All 177 at once, at
  world-view scale, is unreadable overlapping text — the "identical
  data, unreadable chart" outcome the same way an ungated 177-slice pie
  would be. Zoom gating is the fix, not fewer countries.
- **Pan and zoom** are one-finger drag and pinch (`detectTransformGestures`),
  nothing fancier.
- **Pins are never a live position.** This is the same airport/port-only
  privacy rule that governs `FlightStatus`/`VesselStatus` everywhere else in
  the app, just drawn on a map instead of written as text. A pin is either:
  - **solid/green** — the plane or boat is confirmed at that
    airport/port *right now* (`ON_GROUND`/`IN_PORT`), or
  - **hollow/amber, "last known"** — it's airborne, underway, or signal has
    gone quiet, so there's no current position to show at all. The pin marks
    the most recent confirmed stop instead, and the caption underneath says
    so plainly. The map never invents or interpolates a live in-transit dot,
    because nothing upstream of it ever computes one — see `MapPin` and
    `flightMapPin()`/`vesselMapPin()` in `PersonDetailScreen.kt`.
  - Airport/port coordinates themselves (`AirportInfo.lat/lon`,
    `PortInfo.lat/lon`) are each place's own fixed, published reference
    point — the same one every aircraft or vessel that ever visits shares —
    not a live fix on any specific person.

## Backend

```bash
cd backend
cp holdings.example.json holdings.json   # then fill it in from filings
python3 snapshot_worker.py               # writes snapshot.json
```

Stdlib only, no API keys. Put it on a one-minute schedule and publish
`snapshot.json` to a CDN.

**Free path, no server of your own:** `.github/workflows/snapshot.yml` runs
this on a GitHub Actions schedule (every 5 minutes — Actions' cron is
best-effort, so 1 minute isn't reliable anyway) and publishes the result to
`docs/snapshot.json`, which GitHub Pages serves as the CDN. It also fetches
OurAirports' `airports.csv` itself on its first run, so there's nothing to
source by hand. Once your fork is pushed and Pages is turned on (Deploy from
a branch → `/docs`), point `Config.SNAPSHOT_URL` at the resulting
`https://<user>.github.io/<repo>/snapshot.json` and the app stops depending
on whenever it was last built — see the doc comment on `SNAPSHOT_URL` itself.

If you're running the worker yourself rather than through the GitHub Actions
path above, optionally drop OurAirports' `airports.csv` beside it — it's used
both to resolve coordinates to ICAO codes for the heading estimate and to turn
those codes into the friendly "City, ST" / "City, Country" labels the app
displays, plus the airport's own fixed `lat`/`lon` published to the client for
the map (see [Map](#map)). Without it, the app falls back to showing raw ICAO
codes and that person's map has no pin. Same idea for ports via `ports.csv`
(see the Vessels section above) — that one has no free universal source and
isn't auto-fetched either way, so it stays a manual step regardless of which
path you use.

Swap `fetch_quotes()` for Finnhub or Twelve Data if you want intraday
granularity — it is the only function that knows where prices come from.

**Keeping `holdings.json` from going stale:** `.github/workflows/edgar-check.yml`
runs `backend/edgar_check.py` once a month and opens a pull request if it finds
new SEC Form 4 activity for anyone with an `edgarCik` set. It never edits
`holdings.json` itself — some of that file's share counts are a single Form 4's
figure directly, but others are derived from a reported percentage or summed
across several filings (see `holdings.json`'s own `_comment`), and only a human
can tell which is which for a given entry. The PR just surfaces what EDGAR says
next to what's currently on file, with a link to the filing, so updating stays
a deliberate, reviewed edit — same as the rest of this project's sourcing bar.
An `EDGAR_USER_AGENT` repository variable lets you set your own contact info
for better SEC fair-access compliance than the generic default the script
falls back to — see its docstring. Its first run per person doesn't report
anything — it just records where "now" is on EDGAR as a baseline (a prolific
filer can have hundreds of historical Form 4s, and backfilling all of them
isn't what a monthly drift check is for). Activity shows up from the second
run onward. Note that GitHub disables PR creation by GitHub Actions by
default on new repos, so this workflow needs that turned on to actually open
anything (Settings → Actions → General → Workflow permissions) —
`snapshot.yml` doesn't need this, since it pushes directly rather than
opening a PR.

## Design notes

- Dark-only. The palette's dark steps were selected and validated against the
  dark surface; an automatic light flip would not hold up.
- Status never signals with colour alone. The good and critical steps sit about
  ΔE 4 apart under deuteranopia, so every status carries a glyph and a word.
- One series per chart, so no legends — the row names the line.
- The hero figure uses monospace figures. Without a fixed advance width the row
  jitters sideways on every digit change.

## Known gaps

- Seed figures are the Forbes top ten as of 1 Sep 2026 and are a starting point
  for the UI, not a feed.
- `holdings.json` ships filled in for the ten people `SeedData.kt` tracks,
  sourced from SEC filings and reporting as of Sep 2026 (see the sourcing
  notes at the top of the file). Share counts drift with every 10b5-1
  sale/grant, so `edgar-check.yml` re-checks EDGAR monthly and opens a PR
  when it finds new activity — see its own section above. That only covers
  the 8 people with an `edgarCik` set; dell/ortega aren't SEC-registered at
  all (Ortega's Inditex is Spanish, not US-listed) and stay fully manual,
  same as the rest of their entries.
- Crossing history only accumulates from the moment your worker starts running.
- `ais_listener.py` was written against aisstream.io's documentation, not
  tested against the live WebSocket service (no route to it from the
  environment it was built in). The backend logic that consumes its output
  (`vessel_status()` in `snapshot_worker.py`) is fully unit-tested against
  mocked cache data; the listener's message parsing is unit-tested too, but
  the actual connect/subscribe/stream round-trip against the real service
  hasn't been exercised end to end. Watch its logs the first real run.
