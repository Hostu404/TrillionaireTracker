# Design notes

The full rationale, data sourcing, and every documented caveat behind Trillionaire Tracker — split out of the README so that stays a quick read. This file is the deep dive; nothing here is required reading to run the app.

## Net worth

```
sum(shares × live price) + private stakes − liabilities + cash
```

Share counts come from SEC filings (Form 4, 13D/G, proxy statements). Private stakes are hand-set marks from the last funding round. Computing it directly rather than scraping a published index is both cheaper and the only version with no licensing exposure.

If any public holding is unpriced, the person is skipped for that pass — a partial sum would silently understate their worth while looking complete. A non-USD holding follows the same rule one step further back: if its currency's exchange rate can't be fetched that pass, it's treated exactly like an unpriced ticker (see `price_quotes_in_usd()` in `snapshot_worker.py`, and the equivalent client-side rule in `Holdings.kt`) — never a guessed rate.

History and day-change are computed from real timestamps, not an assumed cadence: each stored history point carries its own epoch, trimmed to a rolling 7 days (not the fixed 48-sample window this used to be, which at the project's real 5-minute Actions cadence was only ~4 hours). Drift divides by the *actual* elapsed time between samples, so it stays correct whether the worker runs every minute locally or every 5 minutes on Actions. Day-over-day change is backdated to the real price nearest today's UTC midnight, so it's meaningful from the first pass of the day instead of reading $0 until tomorrow.

**New person, instant real history.** The first time `snapshot_worker.py` sees a person, it backfills their price history in one shot from each holding's own 7-day intraday chart (`backfill_history()`) instead of starting from a flat point — a one-time cost per person. There's no equivalent backfill for flight/vessel *location* history: there's no historical ADS-B/AIS feed to pull from, so that genuinely starts from nothing for a newly-tracked person and fills in over its first week — a data-availability limit, not an inconsistency.

**Rolling window, not a periodic reset.** Every series described above (and every series in the Flights/Vessels sections below) trims the same way: each pass appends one new sample, then drops anything older than `now − 7 days`. `state.json` is committed back to the repo on every run, so this genuinely accumulates across runs rather than resetting — at any moment you have a real, continuous trailing 7 days, not a sawtooth that periodically wipes to zero.

## Flights

| Field | Timing | Source |
|---|---|---|
| Airborne / on-ground state | live | ADS-B |
| Departure airport and elapsed time | live | ADS-B |
| "Heading toward" while airborne | live, labeled as an estimate | computed |
| Confirmed destination and arrival time | as soon as it lands | ADS-B |
| Current location | live, airport granularity only | ADS-B |
| Location history | trailing 7 days, airport stops only | ADS-B |
| Live map dot | real-time, only while a detail screen has this tail airborne | OpenSky → adsb.lol → airplanes.live |

**Current location and history are airport-level, never coordinates — with one narrow, discussed exception.** `currentAirportIcao` is null while airborne — "no fixed location" is the honest answer for a plane in the air. `recentStops` is a rolling 7-day list of airports touched, each with arrival/departure time, and this historical record is never given a raw coordinate, full stop. This is the granularity every public jet-tracking site already operates at; a precise historical position trail would be a meaningfully worse version of the same risk `estimatedDestinationIcao` already treats carefully, so this app doesn't produce one.

The exception, added 2026-09-23: when a real ADS-B fix lands the aircraft ON_GROUND somewhere with no matching airport in `airports.csv` at all, that used to just vanish into "No signal" — technically honest (nothing *known* matched), but also technically wrong (a real position genuinely was caught). `currentLat`/`currentLon`/`generalLocation` now carry that raw fix through instead, current-pass only, never written into `recentStops` or persisted in `state.json`, and always rendered with a visibly distinct "approximate" marker on the map (a dashed ring, not the normal solid/hollow pin — see `MapPin.isApproximate` in `WorldMapCard.kt`) and an "(approximate — no known airport nearby)" caption in the card text. This was a deliberate, discussed tradeoff, not a default: the alternative was a real catch quietly disappearing, which is its own kind of dishonesty about what the app actually knows. It does not apply while AIRBORNE — that live position already reaches the client through a completely separate, non-persisted path (see below), and adding a second, *committed-to-git-every-few-minutes* copy of an in-flight coordinate would be a materially bigger and more permanent exposure than a single current-pass ground fix.

**Airport codes are never shown raw.** Every airport-facing field resolves through `Snapshot.airports` (built by `airport_label()`, e.g. `KAUS` → "Austin, TX") before reaching a screen, falling back to the raw ICAO only if there's no match.

**Time-by-location strip.** The person screen shows a chronological strip (`TimelineStrip.kt` — a left-to-right timeline, not a proportional/donut chart, despite older internal naming) of how the tracked window splits across airports, in-flight time, and signal gaps, rebuilt client-side from the same 7-day stop history everything else on the card uses (see `buildTimelineSegments` in `PersonDetailScreen.kt`) rather than from the server's own `locationBreakdown` aggregate, so the strip and its legend can never drift out of sync with a second source of truth. Real airports cap at 5 identity colors, with the rest folded into "Other airports."

Two things worth knowing about destinations:

1. **Raw ADS-B has no destination field.** An aircraft's transponder broadcasts position, not where it's going — FlightAware/FR24 get destinations from a separate, gated FAA feed. So the confirmed destination only exists once the aircraft is on the ground.
2. **`estimatedDestinationIcao`** fills that gap while airborne: the backend projects current position + track against known large/medium airports and picks the nearest one within a ±12° cone. It's a guess from public course data, always labeled as an estimate, and will sometimes be wrong or absent.

Live position data comes from `adsb.lol` (free, community-fed) server-side, and from a three-source fallback chain client-side (`LiveFlightTracker` in `LiveTracking.kt`): OpenSky first, then `adsb.lol`, then `airplanes.live`, stopping at the first source that actually has the aircraft. All three are free and keyless, scoped to one aircraft's hex per request, and polled only while that person's detail screen is open and the plane is actually airborne — never in the background, never stored.

**Server-side ADS-B coverage, expanded 2026-09-23.** `fetch_aircraft()` (`snapshot_worker.py`) used to try only `adsb.lol` then OpenSky. It now tries, in order: `adsb.lol` by hex, `adsb.fi` by hex (a second free, keyless, community-fed source running the same readsb/tar1090 software as `adsb.lol` — verified to return the same `{"ac": [...]}` shape), OpenSky by hex, then — only if a `tail` registration is on file — `adsb.lol` by registration and `adsb.fi` by registration. The registration fallback matters for a specific gap: an aircraft can go quiet on its *current* ICAO hex (a repainted/reassigned Mode S address, a data error at the source) while still broadcasting under the same tail number, and both `adsb.lol`/`adsb.fi` support looking a tail up directly (`/v2/reg/{tail}` and `/v2/registration/{tail}` respectively) as a fallback path that doesn't depend on the hex being current. Every step still costs nothing and needs no key; the chain just stops at the first source that actually answers, same as before.

**MLAT-derived fixes were already accepted, not a gap that needed fixing.** One of the ideas considered alongside the above was "accept MLAT fixes, not just raw ADS-B" — multilateration positions computed by ground receiver networks for aircraft whose own ADS-B signal isn't reaching enough receivers for a direct fix. Checked before writing any code: readsb (the software behind both `adsb.lol` and `adsb.fi`) tags a record's source via a `"type"` field (`adsb_icao`, `mlat`, `tisb_icao`, and others per its own `README-json.md`), and `_fetch_readsb_style()` here was never filtering on that field at all — every record type, MLAT included, already passed through unmodified. (The `~` prefix readsb sometimes puts on a hex is a separate thing entirely — a non-ICAO/anonymized address, e.g. the FAA's PIA program — not an MLAT marker, despite how it might look.) So this item needed a docstring update explaining the finding, not a code fix for a bug that turned out not to exist.

**Tail numbers are not guessed.** `tailVerified` stays false until the aircraft is confirmed to the person from filings or archived ADS-B, and the app shows an "unverified" badge until it is. The seed/demo snapshot carries real, publicly reported registrations for seven people, cross-corroborated across multiple independent aviation trackers; only the *identity* (tail/hex) is real for the demo data, not the flight states/timestamps around it. Larry Page, Sergey Brin, and Amancio Ortega have no aircraft entry — nothing solid enough turned up, the same bar a real backend run applies.

## Vessels

The maritime mirror of Flights — same privacy shape, and mostly the same code path (`VesselStatus`/`PortStop`, `BoatCard`/`PortHistoryCard`, `VesselTimeByLocationCard`/`TimeByLocationCard` are structural copies of the flight versions, sharing the same `TimelineStrip` component rather than a second chart type). The one real difference is the data source:

**Vessels broadcast AIS, not ADS-B — same idea, different transport, and no free "ask, get an answer" API that covers open water the way `adsb.lol` does for planes.** The free tier that actually works globally (`aisstream.io`) is a persistent WebSocket subscription, not a poll — but aisstream.io itself is free either way; only the two ways of *hosting* the listener differ in whether they need a server of your own.

**Option A — free, no server (recommended default).** `backend/ais_listener.py` supports a burst mode: set `AIS_BURST_SECONDS`, and it connects, subscribes, listens for that window, saves whatever came in, and exits — no always-on process needed. `.github/workflows/ais.yml` runs this on the same free 5-minute Actions cron as `snapshot.yml`, reading the `AISSTREAM_API_KEY` repo secret (free at aisstream.io/authenticate). Without that secret set, the workflow skips itself rather than failing. This catches only whatever a vessel transmits during the listening window — currently 270 seconds (`AIS_BURST_SECONDS`, raised from an initial 45s) — so think "refreshed every 5 minutes," not instant. `snapshot_worker.py` only ever reads the resulting `ais_cache.json` — zero extra network calls of its own.

**Option B — continuous, tighter freshness, needs your own always-on box.** If you already have somewhere that stays on 24/7, run the listener without `AIS_BURST_SECONDS` set, kept running, **not** on the same cron as `snapshot_worker.py`:

```bash
cd backend
pip install websockets        # the one non-stdlib dependency, confined to this file
export AISSTREAM_API_KEY=...
python3 ais_listener.py
```

Either way it only subscribes to MMSIs actually listed in `holdings.json` (up to 200), never the whole ocean. Verified working against the live service as of 2026-09-23 (see Known gaps below) — a real scheduled run connected, subscribed, and wrote back an actual position report for one tracked yacht.

Everything else follows the flight pattern: port granularity — with the same one narrow exception the Flights section above now documents in full: a real, fresh AIS fix (moored or underway) that doesn't resolve to a known port surfaces as an approximate `currentLat`/`currentLon` + `generalLocation` instead of vanishing into "no signal", current-pass only, never written into `recentStops` or `state.json`, always shown with a visibly distinct dashed-ring pin and an "approximate" caption. Vessels get this in both moored and underway states (unlike flights, which only get it ON_GROUND) — a boat genuinely never gets a live position source the way an airborne plane's `LiveFlightTracker` does, so an underway AIS fix that doesn't match a port is the closest thing to "live" this feature can ever show for one, and withholding it would mean underway boats basically never show a position at all. A time-by-location strip in the same shared component, using `IN_PORT`/`UNDERWAY`/`NO_SIGNAL` states and port UNLOCODEs; `selfReportedDestination` (AIS's one edge over ADS-B — a real destination field, but free text the crew types in, routinely blank/stale/informal, always shown with that caveat); `vesselVerified`, same unguessed-identity rule as `tailVerified`. `backend/ports.csv` (added 2026-09-23) gives `nearest_port()` something to resolve an AIS position against — without it, a caught position never turns into a place: an early live run genuinely caught a real position for Ellison's MUSASHI, and the app still showed "No signal" and no map pin, because `load_ports()` had nothing to match it to. Rather than hand-curate just the handful of ports the current demo roster's yachts visit, `ports.csv` covers all 16,666 UN/LOCODE-listed seaports worldwide that have usable coordinates — filtered from `cristan/improved-un-locodes`' `code-list-improved.csv` (a fork of the official `datasets/un-locode` release, itself sourced from UNECE's UN/LOCODE with coordinates cross-checked against OpenStreetMap/Wikidata) down to entries whose Function code marks them as a sea/maritime port and whose UN/LOCODE status isn't rejected or slated for removal. Same shape and source lineage as `airports.csv` next to it (also a full global file, not a hand-picked subset — OurAirports' complete large/medium/small airport list, auto-fetched by the worker itself on first run). See that CSV's own header for the exact filter; re-running the same fetch-and-filter picks up whatever the upstream dataset corrects over time. The seed/demo snapshot carries real MMSIs for five people, cross-corroborated the same way the flight seed data is; Musk, Dell, Huang, Ballmer, and Page have no yacht entry. The first four turned up nothing confidently linked; Page's entry was removed on 2026-09-22 — `SeedData.kt` had been carrying his old "SENSES" yacht (MMSI 319833000), but Boat International's tech-billionaire yacht roundup states that boat was sold by Page to an unknown buyer in 2020, and superyachtfan.com's own SENSES page corroborates this by naming its current owner as Andrea Recordati, an unrelated Italian pharma billionaire. Tracking that MMSI under Page's name would have quietly attributed Recordati's boat movements to him, which is worse than showing no vessel at all, so it was pulled from both `holdings.json` and the Kotlin seed data rather than left in.

**Short-horizon dead reckoning during AIS gaps, added 2026-09-23.** AIS coverage is patchier than ADS-B's — a receiver network hearing a vessel once every several minutes rather than continuously is normal, not a fault — and the old behavior during a gap was to keep showing the *last received* fix, silently going stale for however long the gap lasted. `vessel_status()` now projects a fresh, moving fix (`sog` ≥ 0.5kt, a valid `cog`) forward along its reported course using the standard great-circle "destination given start, bearing, distance" formula (`_dead_reckon_nm()`), but only up to `DEAD_RECKON_MAX_SECONDS` (30 minutes) past the fix's timestamp — long enough to smooth over a routine gap, short enough that the projection doesn't wander far from reality if the vessel changes course or speed mid-gap (dead reckoning accumulates error with time and says nothing about a course change; treat it as "probably closer than the last fix," not a real-time position). This is a display-only estimate: the port-matching (`here`) and everything written into `state.json`'s stop/arrival history still use the *raw*, non-projected fix, so an estimate can never be mistaken for a confirmed arrival or leak into `recentStops`. It only ever changes what `currentLat`/`currentLon`/`generalLocation` show, exactly like the existing "fix with no port match" fallback those fields already implement.

**A second identifier per vessel (IMO number), added 2026-09-23.** MMSI is a radio call-sign-style number that changes when a vessel re-flags to a different country or changes hands — a ship's IMO number, by contrast, is assigned once at build and never changes for that hull, making it the more durable of the two identifiers (the plane-side equivalent already existed: `tail`/`icaoHex`, with `fetch_aircraft()`'s tail-registration fallback above being exactly this same idea applied to planes). `Subject.imo` and an optional `imo` key in `holdings.json` are the schema for it; `ais_listener.py`'s `ShipStaticData` handling now also captures `ImoNumber` off live aisstream.io broadcasts straight into `ais_cache.json`, independent of whether `holdings.json` has been filled in (0 — aisstream.io's "no IMO assigned" value, common for smaller/non-SOLAS yachts — is treated as absent, not stored). No vessel in `holdings.json` has an `imo` value yet: confirming one to a specific hull is its own sourcing pass (cross-referencing MarineTraffic/VesselFinder/equasis.org by name), same evidentiary bar this file holds everything else to, and wasn't done in this pass — see that file's own 2026-09-23 note.

## Social, biography, and news

Each person can carry a `socialUrl` (X, Bluesky, wherever they post) — the app links out, never embeds or polls live posts, since X's read API has no free tier as of 2026 and a metered per-view bill is exactly what the snapshot architecture exists to avoid.

`birthDate`, `residence`, and `bio` are static, hand-curated fields in `holdings.json`, not fetched live:

- **`birthDate`** is set once; age is computed client-side from the device's current date every render (`Format.ageFrom()`), so it advances on its own after a birthday with no snapshot change needed.
- **`residence`** is city/region granularity only ("Starbase, TX," never a street address) — same privacy rule as flight/vessel location, for the same reason.
- **`bio`** is one or two plain sentences (birthplace, last school attended) rather than separate structured fields, because several of this cohort left a *later* graduate program unfinished after completing an earlier degree elsewhere — prose is the only honest way to say "last attended" without it reading as "graduated from."

All three are left out entirely rather than guessed when public reporting is stale or conflicting (see `residenceFor()` in `SeedData.kt` for two real examples hit while researching the seed data).

**Wikipedia link and photo.** `wikipediaUrl`/`photoUrl` come from the free, keyless Wikipedia REST summary API, cached 30 days per person. `photoUrl` is only ever a Wikimedia Commons URL — the backend checks the hosting path of Wikipedia's own thumbnail and only keeps it if it's under `/wikipedia/commons/` (Commons is free-license-only by policy); a "fair use" image hosted under `/wikipedia/en/` never makes it into the app, so a null `photoUrl` means no free image existed, not that the fetch failed. A disambiguation page is skipped entirely rather than risk showing the wrong person. Portraits need a real browser-like `User-Agent` header or they silently never load — Wikimedia's API etiquette policy deprioritizes a generic OkHttp UA, which is why `TrillionaireTrackerApp.kt` installs a custom `OkHttpClient` for Coil with the same UA string the Python backend uses.

**News: fetched free, classified for a fraction of a token.** `fetch_news()` pulls each person's top `NEWS_FETCH_LIMIT` (8, raised from 4 on 2026-09-25) headlines from Google News' public RSS search — free, keyless, every pass, no reason to gate a request that costs nothing. The client (`GoogleNewsClient` in `LiveNews.kt`) runs the identical query at the same limit, so a person's detail screen shows roughly the same window of stories whether it's reading the backend's cache or polling live the moment the screen opens. Before 2026-09-25 both windows were 4, but fetched at different moments — a story that briefly sat just outside the backend's top 4 could still surface in a live poll, and since only the backend ever classifies (below), it would show with no theme tag, looking like a bug rather than the timing gap it actually was. Doubling both windows to the same number doesn't close that gap entirely (the two fetches still happen at different times), but it shrinks it a lot, for free — RSS cost doesn't scale with items requested.

**Theme classification is opt-in and free.** `classify_news_themes()` sends a person's genuinely new headlines to the Google Gemini API and gets back one theme per headline from a fixed list (see the taxonomy below). `GEMINI_API_KEY`/`GEMINI_NEWS_MODEL` are optional repo secrets, checked the same way `AISSTREAM_API_KEY` is for vessels: unset, this silently no-ops — headlines just show with no theme chip, nothing else in the app changes. Gemini specifically because its free tier (aistudio.google.com/apikey) is genuinely permanent and no-card, with a daily quota comfortably above this app's real volume — see that function's own doc comment for the exact numbers this was checked against, and ai.google.dev/gemini-api/docs/models for the current Flash-Lite model name to set. A dedicated `GEMINI_TIMEOUT` (30s) is used only for this call; every other request in `snapshot_worker.py` is a fast, keyless REST lookup sharing a tighter 15s `TIMEOUT`, which turned out to be genuinely too short for real LLM generation latency the first time this shipped.

**Token-safe by construction, not by throttling.** Every new headline for a person is batched into one Gemini call per person per pass — never one call per headline — and only headlines whose title isn't already sitting in that person's previous cache entry with a known theme are sent at all (a null theme, from a prior failure or from before classification was configured, is treated as still "new" and retried the very next pass instead of waiting out a longer cycle). This means widening `NEWS_FETCH_LIMIT` doesn't add request volume, which is the actual free-tier constraint — it only occasionally adds a couple more lines to an already-tiny prompt. A person whose top headlines genuinely haven't changed since last pass makes zero classification calls at all.

**The taxonomy: 7 real themes, capped there on purpose.** Markets & Wealth, Business & Deals, Legal & Regulatory, Technology & Innovation, Public Life & Controversy, Profile & Commentary, and Other (`NEWS_THEMES` in `snapshot_worker.py`). Seven, not more: this app's dataviz methodology caps a reliably colorblind-safe categorical palette at 8 hues total, and a brute-force OKLCH search confirmed it in practice here — no 9th hue exists that clears CVD separation (ΔE ≥ 8) and the normal-vision floor (ΔE ≥ 15) against the other 8 colors already in play (5 existing `TT.categorical` slots, the "Other" orange, this app's own 3 reserved status colors, and one new addition). "Profile & Commentary" (added 2026-09-25) is that one new addition — a dedicated, computed-not-eyeballed color (`#B21557`) living in `PersonDetailScreen.kt` as `newsProfileCommentary` rather than a 7th `TT.categorical` slot, since that palette is independently validated for `TimelineStrip`'s own needs. It exists because real observed headlines — an encyclopedia-style biography, an inspirational quote piece — were substantive, classifiable coverage with nowhere to go but "Other": the theme is for someone *writing about* a person (bios, interviews, retrospectives, quotes), as distinct from "Public Life & Controversy," which is for something that actually *happened* (a dispute, scandal, backlash). A taxonomy change is never retroactive — a headline already cached under an old theme (or "Other," before this addition existed) keeps it until it naturally ages out of the tracked window and gets refetched and reclassified fresh.

**Lifetime theme tally.** Each person's `lifetimeNewsThemes` is a permanent, never-pruned count of how many *distinct* headlines have ever been classified into each theme, deduplicated by title (`accumulate_lifetime_news_themes()`) so a story that stays in the top results for days doesn't inflate its theme's count once per pass — the same "lifetime tally" idea `lifetimeLocations` already uses for flight/vessel stops, just for coverage themes instead of places. Rendered client-side as `LifetimeNewsThemesCard`, a proportional bar per theme sorted by count.

## Gestures

Two places use a hand-rolled `pointerInput` gesture instead of a stock Compose modifier, both built to the same two safety rules so a deliberate gesture never steals an ordinary scroll or tap: nothing is consumed until the touch clears a real slop distance, and either one backs off immediately if a descendant (a scrollable list, another gesture) claims the pointer first.

**Profile photo pull** (`Modifier.rubberBandPhotoDrag`, `HudDecorations.kt`) is pure tactile polish — dragging a person's profile photo gives a small, hard-capped "rubber band" pull that springs back on release, paired with `overscanBy` so there's always spare image outside the frame to pull into (without it, an already-cropped `ContentScale.Crop` photo would just slide sideways and reveal bare background instead).

**Edge-swipe-back** (`Modifier.edgeSwipeBack`, `HudDecorations.kt`, added 2026-09-24) is functional, not decorative: a left-edge swipe drives the same `popBackStack()` call the system back gesture/button/key already reach through Navigation Compose's automatic wiring — it's a second path to that call, not a replacement. It exists because that system affordance isn't reachable everywhere this app runs: the Android Studio emulator can run in gesture-nav mode with no on-screen back button and no way to swipe in from outside its own window edge, which is exactly the case that came up testing here. Applied only to the two pushed destinations (`person/…`, `familyHistory/…`) at their `MainActivity.kt` call sites — never the root tracker screen, which never had a back affordance to begin with. Only a touch starting within 32dp of the left edge is considered at all, and nothing is consumed until the drag clears 18dp of slop and commits to a direction that's at least about as horizontal as it is vertical (deliberately lenient, not a strict 45° split — a real one-handed flick on a real phone arcs enough that a strict split reads it as a vertical scroll), so a genuine vertical scroll starting in that narrow edge strip still reaches the list underneath it untouched.

Shipping this against a real signed build (2026-09-25) surfaced two more layers past the Compose-level logic above, both now fixed in code: it has to run on `PointerEventPass.Initial` rather than the default Main pass, since `PersonDetailScreen`/`FamilyHistoryScreen` are edge-to-edge `LazyColumn`s of full-width cards whose own press/drag-cancel detection otherwise wins the race for the same touch; and on a real device it has to claim the edge strip back from Android's own system gesture navigation with `View.setSystemGestureExclusionRects` (API 29+), which normally reserves that exact region for the OS's own back gesture and never lets the touch reach the app's window at all. Past both of those, one more variable turned out to matter that no code change addresses: this only works when the device's own System navigation setting is actually Gesture navigation. On a Moto Edge 20 Lite in 2/3-button navigation mode, the edge-swipe still didn't register with all three fixes in place; switching the phone itself to gesture navigation, no further code change, made it work. The exact mechanism isn't confirmed — `systemGestureExclusionRects` only un-reserves gesture-nav's own edge strip, so something else (possibly OEM-specific) is claiming the touch in button-nav mode instead — and it isn't being chased further, since a button-nav user already has a dedicated back button and was never depending on this modifier in the first place.

**The world map deliberately has neither.** No manual pan/pinch: a touch-drag handler on the map used to fight the parent screen's own scroll (grabbing a vertical drag that started over the map instead of letting it reach the list underneath). Framing there is button-only (⌖ recenter / ⟲ reset) plus tap-to-focus from a flight/vessel card elsewhere on the screen — see Map below.

## Map

Each detail screen carries a self-drawn Jetpack Compose `Canvas` world map — no WebView, no maps SDK, no per-user API calls. Country outlines come from Natural Earth's public-domain Admin-0 boundaries, bundled as a flat ~125KB JSON asset with no network call involved (this exact file, `app/src/main/assets/world_countries.json`, is also what the backend's `general_location()` reverse-geocodes against — see Flights/Vessels above — so the client's map and the backend's coarse place names are always drawn from the same 177-country set, not two that could drift apart). Country names only render once zoomed in (world-view scale with all 177 at once is unreadable). A pin is one of three things, each visually distinct so confidence level is legible at a glance without reading the caption: solid (confirmed at that airport/port right now), hollow "last known" (airborne/underway/signal lost), or, added 2026-09-23, a dashed ring — a real position that didn't resolve to any known airport/port, labeled with a coarse place name instead (`MapPin.isApproximate` in `WorldMapCard.kt`; see Flights/Vessels above for the backend side). The one still-live position — a currently-airborne plane with a live ADS-B fix — remains the sole exception that was never stored anywhere, current-pass fallback fields included.

## Backend

```bash
cd backend
cp holdings.example.json holdings.json   # then fill it in from filings
python3 snapshot_worker.py               # writes snapshot.json
```

Stdlib only, no API keys. `.github/workflows/snapshot.yml` runs this free on a GitHub Actions schedule (every 5 minutes — Actions' cron is best-effort, so 1 minute isn't reliable) and publishes to `docs/snapshot.json`, served by GitHub Pages as the CDN. It fetches OurAirports' `airports.csv` itself on first run. Until your fork is pushed and Pages is turned on, the app runs on its bundled seed snapshot with no visible error. `.github/workflows/ais.yml` does the equivalent for vessels (see Vessels above).

Swap `fetch_quotes()` for Finnhub or Twelve Data if you want intraday granularity — it's the only function that knows where prices come from.

### Going live, step by step

1. Push the repo (`git init && git add . && git commit -m "Initial commit" && git remote add origin <your fork's URL> && git push -u origin main`, or just add the remote and push if it's already a local repo).
2. Allow the EDGAR-check workflow to open PRs — off by default on new personal repos: Settings → Actions → General → Workflow permissions → check "Allow GitHub Actions to create and approve pull requests" → Save. (`snapshot.yml` doesn't need this; it pushes directly.)
3. Trigger `snapshot.yml` once by hand *before* touching Pages settings: Actions tab → "Refresh snapshot" → Run workflow. This creates `docs/snapshot.json` in the repo.
4. Enable Pages *only after* step 3 — GitHub's Pages UI rejects `/docs` as a source folder if it doesn't exist in the repo yet. Settings → Pages → Source: Deploy from a branch → Branch `main`, folder `/docs` → Save.
5. Confirm it's actually live: open `https://<user>.github.io/<repo>/snapshot.json` in a browser and check for real JSON, not a 404 (can take a minute or two after step 4).
6. Point the app at it — set `Config.SNAPSHOT_URL` to that same URL and rebuild. Nothing else changes.
7. For vessels, add an `AISSTREAM_API_KEY` repo secret (free at aisstream.io/authenticate → Settings → Secrets and variables → Actions → New repository secret). Without it, `.github/workflows/ais.yml` runs but skips itself every time rather than failing, and every vessel just reads as no signal.
8. For AI news theme tags, add `GEMINI_API_KEY` (free, no-card, from aistudio.google.com/apikey) and `GEMINI_NEWS_MODEL` (check ai.google.dev/gemini-api/docs/models for the current Flash-Lite model name) as repo secrets alongside `snapshot.yml`'s existing env. Without either one, `classify_news_themes()` no-ops every pass and headlines just show with no theme chip — everything else about news is unaffected.

`.github/workflows/ais.yml` can't be created by pushing through most remote/automated tooling — GitHub blocks writes into `.github/workflows/` from exactly that kind of access, for the obvious reason that nobody wants a remote tool able to silently plant CI that runs with a repo's secrets. Create it by hand at that path with this content, and it'll pick up the same 5-minute cron `snapshot.yml` uses (this is the actual currently-deployed version, kept in sync here — see the `AIS_BURST_SECONDS` value and the commit step's retry logic below, both added after the workflow's first version):

```yaml
name: Refresh AIS cache

on:
  schedule:
    - cron: "*/5 * * * *"
  workflow_dispatch: {}

permissions:
  contents: write

concurrency:
  group: ais-refresh
  cancel-in-progress: false

jobs:
  refresh:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Install websockets
        run: pip install websockets

      - name: Burst-listen for AIS updates
        env:
          AISSTREAM_API_KEY: ${{ secrets.AISSTREAM_API_KEY }}
          AIS_BURST_SECONDS: "270"
        run: |
          if [ -z "$AISSTREAM_API_KEY" ]; then
            echo "AISSTREAM_API_KEY repo secret not set — skipping this run." \
                 "Get a free key at https://aisstream.io/authenticate and add it" \
                 "under Settings -> Secrets and variables -> Actions."
            exit 0
          fi
          python3 backend/ais_listener.py

      - name: Commit updated AIS cache
        run: |
          git config user.name "ais-bot"
          git config user.email "actions@users.noreply.github.com"
          # ais_listener.py only writes backend/ais_cache.json when a tracked
          # vessel's message actually arrives during the burst window — a
          # quiet burst (no AIS traffic heard for any tracked MMSI in the
          # listening window, which is normal and expected, not an error)
          # means the file never gets created at all. `git add` on a
          # nonexistent path fails the whole job, so check first and treat
          # "nothing heard" as the ordinary no-op it is, same as an unpriced
          # ticker just gets skipped in snapshot_worker.py rather than
          # failing that job.
          if [ ! -f backend/ais_cache.json ]; then
            echo "No AIS traffic heard for any tracked vessel this burst — nothing to commit."
            exit 0
          fi
          git add backend/ais_cache.json
          if git diff --cached --quiet; then
            echo "nothing changed, skipping commit"
            exit 0
          fi
          git commit -m "Refresh AIS cache ($(date -u +%Y-%m-%dT%H:%M:%SZ))"

          # This job and snapshot.yml both run on the same 5-minute cron and
          # both push straight to main. concurrency: only serializes runs of
          # THIS workflow against each other — it does nothing for a push
          # landing on main from the other workflow at the same moment, which
          # is exactly the "! [rejected] main -> main (fetch first)" failure
          # this was written to fix. Rebasing this one commit onto whatever
          # just landed and retrying the push turns that race into a self-
          # healing no-op instead of a failed run, since the two workflows
          # never touch the same files (this one only ever writes
          # backend/ais_cache.json), so the rebase itself can't conflict.
          for attempt in 1 2 3 4 5; do
            if git push; then
              exit 0
            fi
            echo "push rejected (attempt $attempt/5) — another workflow committed first, rebasing onto origin/main and retrying"
            git fetch origin main
            if ! git rebase origin/main; then
              echo "rebase hit a real conflict — bailing out rather than pushing something broken; next scheduled run will pick this up"
              git rebase --abort
              exit 1
            fi
            sleep $((RANDOM % 5 + 1))
          done
          echo "push still rejected after 5 retries — giving up this run; next scheduled run will retry"
          exit 1
```

Optionally, also set an `EDGAR_USER_AGENT` repository variable (Settings → Secrets and variables → Actions → Variables) to something like `"YourProjectName (your-real-email@example.com)"` — works fine without it (falls back to a generic default in `edgar_check.py`), but SEC's fair-access guidance prefers a real contact.

**Keeping `holdings.json` from going stale:** `.github/workflows/edgar-check.yml` runs `backend/edgar_check.py` monthly and opens a PR if it finds new SEC Form 4 activity for anyone with an `edgarCik` set. It never edits `holdings.json` directly — some share counts are a single filing's figure, others are derived from a reported percentage, and only a human can tell which is which — so the PR just surfaces what EDGAR says next to what's on file, with a link. Its first run per person just records a baseline; activity shows up from the second run onward. GitHub disables Actions PR creation by default on new repos, so this workflow needs that turned on (Settings → Actions → General → Workflow permissions) to actually open anything.

## UI design notes

- Dark-only. The palette's dark steps were selected and validated against the dark surface; an automatic light flip would not hold up.
- Status never signals with color alone — the good/critical steps sit close together under deuteranopia, so every status carries a glyph and a word too.
- One series per chart, so no legend is needed — the row names the line.
- The hero figure uses monospace digits so the row doesn't jitter sideways on every change.
- **Corner-tick decoration means two different things, on purpose.** `hudCorners()` is a static, always-on decorative frame reserved for a handful of non-interactive "hero panel" containers — it implies nothing about tappability. `hudTouchable()` is the separate touch-affordance treatment (corner ticks + an optional elevation shadow + a brief press glitch) applied only to elements that actually do something when tapped — cards, rows, chips. The two were briefly conflated (corner brackets showing up on the top-level status board and a wealth hero card that aren't tappable, and a heavier bracket-and-shadow treatment leaking onto plain inline text links), and both were corrected: `hudCorners()` was removed from the two non-interactive panels, and bare text links (residence/Wikipedia/live-map links) went back to a plain `Modifier.clickable` with no bracket decoration at all. The remaining rule of thumb: brackets only on things you can press, and only `hudTouchable`'s variant on those.
- **Depth (elevation shadow) is reserved for genuinely interactive panel/row/chip elements**, added via `hudTouchable(elevation = ...)` (a real `Modifier.shadow`, not another canvas trick — see `HudDecorations.kt`) so a tap target visibly sits above the surface it's on. It is not applied to plain text links or to purely decorative panels, for the same reason those don't get corner ticks either.
- **`honeycombGlowCell()` is a small persistent glow used as a status layer** (e.g., an amber/critical tint on a flight or vessel card, or the leaderboard row for someone currently airborne/underway) — never decoration, only ever used to surface a real state.
- **"Tracking," not "Live."** A person's wealth card and leaderboard row used to show a pulsing "LIVE" chip whenever a real market-price anchor existed for them — which stays true straight through market close, so a dead-flat sparkline sitting next to "LIVE" read as the app being broken. The label was renamed to "TRACKING" everywhere (it only ever meant "we have a real anchor for this person," not "actively moving right now"), and `isSparklineFlat()` (in `Sparkline.kt`) separately detects a flat trailing window (last 6 points, exact equality, no market-hours calendar needed) and mutes the line color plus swaps the caption to "flat · market's closed right now" so a closed market reads as an explained state, not a bug.
- **A dashed pin means "approximate," on purpose.** Added 2026-09-23 alongside the `currentLat`/`currentLon`/`generalLocation` fallback (see Flights/Vessels above): a solid pin means confirmed-at-that-airport/port-right-now, a hollow ring means last-known-not-live, and a new dashed ring means neither — a real fix landed, but nothing known matched it, so the pin and its label (a coarse place name, never the raw numbers) are honestly less certain than the other two. Three visually distinct styles rather than reusing one of the existing two, so reduced confidence is legible on the map itself, not just buried in caption text someone has to read.
- **The boat icon mirrors the plane icon on purpose.** Added 2026-09-23. Before this, the leaderboard only ever showed a little ✈ next to a name when that person's plane was AIRBORNE — there was no equivalent for a boat underway. A new ⚓ (the same anchor symbol `BoatCard`'s "UNDERWAY NOW" chip and the vessel map pin already use, rather than introducing a second vessel symbol) now shows up the same way. The bar for showing it matches the plane's exactly: not "we have any AIS data at all" (which includes the common, unremarkable IN_PORT case, already covered by its own status text), but `VesselState.UNDERWAY` specifically — a boat actually moving, the same "worth interrupting a glance at the list" threshold AIRBORNE already sets for planes. The leaderboard row's `honeycombGlowCell` glow (see below) now triggers on `airborne || underway` for the same reason.

## Known gaps, in detail

- Seed figures are the Forbes top ten as of 1 Sep 2026 and are a demo starting point, not a live feed.
- `holdings.json` has all ten people filled in (Dell and Ortega were the last two added, both live-ticker-trackable: `DELL` and Madrid-listed `ITX.MC`). `edgar-check.yml` only monitors the eight people with an `edgarCik` set for filing drift — Dell's and Ortega's share counts are derived (a reported percentage × shares outstanding, not a filing figure directly) and need re-verifying by hand periodically.
- Ortega's yacht (`DRIZZLE`) now has its `mmsi` (256867000) copied into `holdings.json` (2026-09-23, from `SeedData.kt`'s existing value) — this was open as of the previous pass and is now closed.
- `backend/ports.csv` was added 2026-09-23 (16,666 UN/LOCODE seaports, see the Vessels section above for sourcing) after a real live-service catch of Ellison's MUSASHI position exposed the gap: without a ports file, `nearest_port()` had nothing to match a real position against, so a genuinely-received AIS position still showed as "No signal" with no map pin. Confirmed the demo roster's ports (`MCMON`, `NZAKL`, `USFLL`, and the rest) are all present in the file.
- `ais_listener.py` has now been verified against the live aisstream.io service in burst mode (this used to say it hadn't been). Checked 2026-09-23 against the repo's own Actions history: run #202 of `.github/workflows/ais.yml` connected, subscribed to the 4 MMSIs currently in `holdings.json`, ran its full listening window, and exited clean with no errors; `backend/ais_cache.json` on `main` holds a real position report it received that run for Ellison's MUSASHI (MMSI 319032600, timestamped 2026-09-23 08:31:58 UTC) — a live-service round trip, not just a clean connect. Burst mode and continuous mode still share the same message-handling code, so this covers both. What's *not* yet exercised: an aisstream.io outage or malformed-message response, since only normal operation has been observed so far.
- Dead reckoning (`_dead_reckon_nm()` in `snapshot_worker.py`, see Vessels above) is verified via a self-consistent round-trip test (project forward, then compute distance/bearing back from the result, confirm they match the inputs) across six cases, but has no live-traffic test — there's no way to check a *projected* position against reality without an independent live position to compare it to, which is exactly the gap this feature exists to smooth over. The 30-minute cap and 0.5kt-moving gate are deliberately conservative defaults chosen from first principles, not tuned against observed AIS gap durations for this app's specific tracked vessels — worth revisiting once real gap-length data exists in `ais_cache.json`/`state.json` history.
- News theme classification (added 2026-09-25) is opt-in (`GEMINI_API_KEY`/`GEMINI_NEWS_MODEL`) and never retroactive even once configured: a headline already cached under an older theme — including "Other," from before "Profile & Commentary" existed — keeps that theme until it naturally ages out of the tracked window and gets refetched and reclassified fresh. There's no batch re-classification pass over `state.json`'s existing cache.
- Widening `NEWS_FETCH_LIMIT` to 8 (same date) narrows but doesn't fully close the gap between the backend's classified window and a live client poll's window, since the two fetches genuinely happen at different moments — a headline can still occasionally show untagged on a live poll for one cycle before the backend's own next pass catches and classifies it.
- **Everything this app shows is already public.** Share counts come from public SEC filings, positions come from public ADS-B/AIS broadcasts, and biography/photo/news are public Wikipedia and social-link-outs — nothing is inferred, scraped from a private source, or derived from anything the tracked person hasn't already made publicly available. That bounds this project in both directions: it can never be more wrong than the public record it reads from (a bad number here is a stale or misfiled public figure, not a guess), and it can never show more than the public record already does — no private itinerary, no non-public holding, no address more precise than a city. The Larry Page yacht correction above (`SeedData.kt`/`holdings.json`, 2026-09-22) is a concrete example of this rule working as intended: once public sourcing showed the old attribution was wrong, the entry was pulled rather than left in or guessed at.
