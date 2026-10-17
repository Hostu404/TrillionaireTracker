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

**Tail numbers are not guessed.** `tailVerified` stays false until the aircraft is confirmed to the person from filings or archived ADS-B, and the app shows an "unverified" badge until it is. The seed/demo snapshot carries real, publicly reported registrations for all three people, cross-corroborated across multiple independent aviation trackers; only the *identity* (tail/hex) is real for the demo data, not the flight states/timestamps around it. Zuckerberg's has a tail but no confidently sourced Mode S hex, so his plane shows no live position — the same bar a real backend run applies.

## Vessels

The maritime mirror of Flights — same privacy shape, and mostly the same code path (`VesselStatus`/`PortStop`, `BoatCard` is a structural copy of `FlightCard`, and the "Last 7 days" card stacks the boat's strip and stops under the plane's, sharing the same `TimelineStrip` component rather than a second chart type). The one real difference is the data source:

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

Everything else follows the flight pattern: port granularity — with the same one narrow exception the Flights section above now documents in full: a real, fresh AIS fix (moored or underway) that doesn't resolve to a known port surfaces as an approximate `currentLat`/`currentLon` + `generalLocation` instead of vanishing into "no signal", current-pass only, never written into `recentStops` or `state.json`, always shown with a visibly distinct dashed-ring pin and an "approximate" caption. Vessels get this in both moored and underway states (unlike flights, which only get it ON_GROUND) — a boat genuinely never gets a live position source the way an airborne plane's `LiveFlightTracker` does, so an underway AIS fix that doesn't match a port is the closest thing to "live" this feature can ever show for one, and withholding it would mean underway boats basically never show a position at all. A time-by-location strip in the same shared component, using `IN_PORT`/`UNDERWAY`/`NO_SIGNAL` states and port UNLOCODEs; `selfReportedDestination` (AIS's one edge over ADS-B — a real destination field, but free text the crew types in, routinely blank/stale/informal, always shown with that caveat); `vesselVerified`, same unguessed-identity rule as `tailVerified`. `backend/ports.csv` (added 2026-09-23) gives `nearest_port()` something to resolve an AIS position against — without it, a caught position never turns into a place: an early live run genuinely caught a real position for a tracked yacht, and the app still showed "No signal" and no map pin, because `load_ports()` had nothing to match it to. Rather than hand-curate just the handful of ports the current demo roster's yachts visit, `ports.csv` covers all 16,666 UN/LOCODE-listed seaports worldwide that have usable coordinates — filtered from `cristan/improved-un-locodes`' `code-list-improved.csv` (a fork of the official `datasets/un-locode` release, itself sourced from UNECE's UN/LOCODE with coordinates cross-checked against OpenStreetMap/Wikidata) down to entries whose Function code marks them as a sea/maritime port and whose UN/LOCODE status isn't rejected or slated for removal. Same shape and source lineage as `airports.csv` next to it (also a full global file, not a hand-picked subset — OurAirports' complete large/medium/small airport list, auto-fetched by the worker itself on first run). See that CSV's own header for the exact filter; re-running the same fetch-and-filter picks up whatever the upstream dataset corrects over time. The seed/demo snapshot carries real MMSIs for Bezos (KORU) and Zuckerberg (LAUNCHPAD), cross-corroborated the same way the flight seed data is; Musk has no yacht entry, since nothing confidently linked to him turned up.

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

**Theme classification is opt-in and free.** `classify_news_themes()` sends every untagged headline to the Google Gemini API and gets back one theme per headline from a fixed list (see the taxonomy below). `GEMINI_API_KEY`/`GEMINI_NEWS_MODEL` are optional repo secrets, checked the same way `AISSTREAM_API_KEY` is for vessels: unset, this silently no-ops — headlines just show with no theme chip, nothing else in the app changes. Gemini specifically because its free tier (aistudio.google.com/apikey) is genuinely permanent and no-card, with a daily quota above the at most 96 calls a day this makes — see ai.google.dev/gemini-api/docs/models for the current Flash-Lite model name to set. A dedicated `GEMINI_TIMEOUT` (30s) is used only for this call; every other request in `snapshot_worker.py` is a fast, keyless REST lookup sharing a tighter 15s `TIMEOUT`, which turned out to be genuinely too short for real LLM generation latency the first time this shipped.

**One call every 15 minutes, for everyone.** `refresh_news()` fetches everyone's headlines every pass (free), then sends every untagged headline, across all tracked people, to Gemini in a single call every `THEME_EVERY` passes (15 minutes): at most 96 calls a day. A headline that already has a theme, under anyone, keeps it and is never re-sent, so a story under two people is classified once; a failed call leaves its headlines untagged until the next try. It used to be one call per person per pass, retried every pass on failure: about 1,800 calls a day, which ran through the free tier's daily quota by mid-afternoon UTC and left every later headline untagged until it reset at midnight Pacific.

**The taxonomy: eight tracked themes.** Alien News, Bunkers, Underground, Business & Deals, God & Spirituality, Technology & Innovation, Markets & Wealth and War & Defense (`TRACKED_NEWS_THEMES` in `snapshot_worker.py`, mirrored by `Config.TRACKED_NEWS_THEMES` in the app). War & Defense covers military matters and wars, including a person commenting on them or their companies' part in them. Bunkers wins over every other theme whenever a bunker, doomsday shelter, survival compound or Miami's "Billionaire Bunker" island is mentioned. Underground is anything built, dug or fought underground (The Boring Company and its tunnels, tunnel boring, underground transport, underground weapons sites, tunnel warfare) and wins over everything except Bunkers. God & Spirituality is faith, religion and the meaning of life, including a person talking about them. A headline that fits none of them is classified "Other": it's stored so it isn't re-sent to Gemini every pass, but it's never shown or counted. Alien News carries a 👽, drawn in greyscale like everything else. Changing the list starts the themes over: `migrate_news_themes()` notices the new list, wipes every all-time theme tally and sends every cached headline back to be re-sorted, so neither the tallies nor what's trending carry results from the old list.

**Lifetime theme tally.** Each person's `lifetimeNewsThemes` is a permanent, never-pruned count of how many *distinct* headlines have ever been classified into each theme, deduplicated by title (`accumulate_lifetime_news_themes()`) so a story that stays in the top results for days doesn't inflate its theme's count once per pass — the same "lifetime tally" idea `lifetimeLocations` already uses for flight/vessel stops, just for coverage themes instead of places. Rendered client-side at the bottom of the news section, a proportional bar per theme sorted by count. The main page's **Trending** list is separate: the three most common themes across everyone's current headlines (a story filed under two people counts once; untagged and "Other" headlines don't count).

## Gestures

One place uses a hand-rolled `pointerInput` gesture instead of a stock Compose modifier, built to two safety rules so a deliberate gesture never steals an ordinary scroll or tap: nothing is consumed until the touch clears a real slop distance, and it backs off immediately if a descendant (a scrollable list, another gesture) claims the pointer first.

**Edge-swipe-back** (`Modifier.edgeSwipeBack`, `EdgeSwipeBack.kt`, added 2026-09-24) is functional, not decorative: a left-edge swipe drives the same `popBackStack()` call the system back gesture/button/key already reach through Navigation Compose's automatic wiring — it's a second path to that call, not a replacement. It exists because that system affordance isn't reachable everywhere this app runs: the Android Studio emulator can run in gesture-nav mode with no on-screen back button and no way to swipe in from outside its own window edge, which is exactly the case that came up testing here. Applied only to the two pushed destinations (`person/…`, `familyHistory/…`) at their `MainActivity.kt` call sites — never the root tracker screen, which never had a back affordance to begin with. Only a touch starting within 32dp of the left edge is considered at all, and nothing is consumed until the drag clears 18dp of slop and commits to a direction that's at least about as horizontal as it is vertical (deliberately lenient, not a strict 45° split — a real one-handed flick on a real phone arcs enough that a strict split reads it as a vertical scroll), so a genuine vertical scroll starting in that narrow edge strip still reaches the list underneath it untouched.

Shipping this against a real signed build (2026-09-25) surfaced two more layers past the Compose-level logic above, both now fixed in code: it has to run on `PointerEventPass.Initial` rather than the default Main pass, since `PersonDetailScreen`/`FamilyHistoryScreen` are edge-to-edge `LazyColumn`s of full-width cards whose own press/drag-cancel detection otherwise wins the race for the same touch; and on a real device it has to claim the edge strip back from Android's own system gesture navigation with `View.setSystemGestureExclusionRects` (API 29+), which normally reserves that exact region for the OS's own back gesture and never lets the touch reach the app's window at all. Past both of those, one more variable turned out to matter that no code change addresses: this only works when the device's own System navigation setting is actually Gesture navigation. On a Moto Edge 20 Lite in 2/3-button navigation mode, the edge-swipe still didn't register with all three fixes in place; switching the phone itself to gesture navigation, no further code change, made it work. The exact mechanism isn't confirmed — `systemGestureExclusionRects` only un-reserves gesture-nav's own edge strip, so something else (possibly OEM-specific) is claiming the touch in button-nav mode instead — and it isn't being chased further, since a button-nav user already has a dedicated back button and was never depending on this modifier in the first place.

**The world map deliberately has none.** No manual pan/pinch: a touch-drag handler on the map used to fight the parent screen's own scroll (grabbing a vertical drag that started over the map instead of letting it reach the list underneath). Framing there is button-only (⌖ recenter / ⟲ reset) plus tap-to-focus from a flight/vessel card elsewhere on the screen — see Map below.

## Map

Each detail screen carries a self-drawn Jetpack Compose `Canvas` world map — no WebView, no maps SDK, no per-user API calls. Country outlines come from Natural Earth's public-domain Admin-0 boundaries, bundled as a flat ~125KB JSON asset with no network call involved (this exact file, `app/src/main/assets/world_countries.json`, is also what the backend's `general_location()` reverse-geocodes against — see Flights/Vessels above — so the client's map and the backend's coarse place names are always drawn from the same 177-country set, not two that could drift apart). Country names only render once zoomed in (world-view scale with all 177 at once is unreadable). A pin is one of three things, each visually distinct so confidence level is legible at a glance without reading the caption: solid (confirmed at that airport/port right now), hollow "last known" (airborne/underway/signal lost), or, added 2026-09-23, a dashed ring — a real position that didn't resolve to any known airport/port, labeled with a coarse place name instead (`MapPin.isApproximate` in `WorldMapCard.kt`; see Flights/Vessels above for the backend side). The one still-live position — a currently-airborne plane with a live ADS-B fix — remains the sole exception that was never stored anywhere, current-pass fallback fields included.

## Backend

```bash
cd backend
cp holdings.example.json holdings.json   # then fill it in from filings
python3 snapshot_worker.py               # writes snapshot.json
```

**A failed Wikipedia lookup is retried an hour later** (`WIKI_RETRY_SECONDS`), not left empty for the month a successful one is kept (`WIKI_REFRESH_SECONDS`); one rate-limited request used to cost a person their photo and Wikipedia link for 30 days.

**Only the people in `holdings.json` are collected or kept.** The worker only fetches for the people listed there, and each pass `forget_untracked()` deletes whatever `state.json` still holds about anyone who isn't (price history, news, Wikipedia info, flight and vessel trails, lifetime tallies, crossings). `ais_listener.py` does the same for `ais_cache.json` (`prune_cache()`), and the app keeps only `Config.TRACKED_PEOPLE` from any snapshot it reads, so an older snapshot still on the CDN can't bring anyone back.

**Private wealth is a stake times a valuation, with a date.** A person's net worth is public shares priced live, plus `privateStakes` (each a stake in a private company × that company's last funding-round valuation, with the round's date), plus `privateStakesUsd` for anything that can't be split that way (described by `estimateNote`). `estimateAsOf`/`estimateSource` record when and from whom the hand-set parts were last checked. The worker publishes all of it with each person (`pricedTickers`, `estimate`) so the app can show how every number is made. When someone with only a flat, estimate-based history gains a priced stock, the worker rebuilds their history from that stock's real closes instead of keeping the flat line.

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
      - uses: actions/checkout@v5

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

**Keeping `holdings.json` from going stale:** `.github/workflows/edgar-check.yml` runs `backend/edgar_check.py` on the 1st of each month (or by hand from the Actions tab) for everyone with an `edgarCik`. It commits `backend/edgar_report.md` and `backend/edgar_state.json` (the newest filing seen per person, so each run only reports what's new), and opens an issue with the report when there's new Form 4 activity. It never edits `holdings.json` directly — some share counts are a single filing's figure, others are derived from a reported percentage or summed across entities, and only a human can tell which is which — so the issue just surfaces what EDGAR says next to what's on file, with links. Its first run per person just records a baseline; activity shows up from the second run onward. The workflow was described here before it existed; it was added on 2026-10-17, along with two fixes that had kept the script from ever working (it asked SEC for compressed responses it couldn't decode, and fetched each filing's rendered web page instead of its XML).

## UI design notes

- **Black, white and monospace.** Every screen is plain OLED black (`TT.plane`) with no background image or effects. All text is monospace with line height tied to its size (`Typography` in `Theme.kt`: 1.3em on every Material style, instead of Material's fixed 24sp rows), so figures line up and small text doesn't sit in tall empty rows.
- **One sheet per screen.** Each screen is a plain scrolling column holding one bordered sheet: sections are split by `SheetLine()` (white edge to edge between sections, grey and inset within one) instead of separate cards with gaps between them. No lazy lists: there are seven people and three headlines, so there's nothing to recycle.
- **Colour only means today's move.** Green (`TT.up`) and red (`TT.down`) are used for a person's change today and nothing else: the ▲/▼ text on the main page and on their own page. Everything else is white or a step of grey.
- **The map is line art.** No land fill, grey country outlines, monospace labels, and white pins told apart by shape: filled = live, ring = last known, dashed ring = approximate. Under the map, one line per pin repeats its shape, place and a few words of status.
- **Shape and words carry status instead.** Every status chip has a glyph and a word (✈ AIRBORNE, ● ON GROUND, ! SIGNAL LOST, ? NO SIGNAL). The time-by-location strip tells blocks apart by fill: grey shades for time at a place, hatching for time in flight or underway, an empty outline for no signal. Its legend names every block, so a shade is never the only cue.
- **One main page.** At the top, the question the app is named for: "Trillionaire right now?" YES/NO, who, and for how long (from the crossing history, with any earlier crossings listed under it). Under that, everyone's total wealth and what it could fund: days of a poverty-free world at $318B a year (`Config.ANNUAL_POVERTY_ELIMINATION_COST_USD`, NBER Working Paper 34583), labelled as a comparison. Then the three trending news themes across everyone's current headlines, then each tracked person as a compact three-line row in the same sheet, richest first: photo, name and today's change, live net worth, then company and how far they are from (or past) $1T. Each row opens that person's page; the only other controls are ↻ refresh and a "View source ↗" link to the repo beside the title (it drops under the title on a phone too narrow for both). Rows simply re-sort when someone overtakes; there's no swap animation. Numbers are plain text that ticks; there's no rolling-digit animation (it couldn't keep up with 80ms ticks and smeared the digits).
- **Every person's page is the same sheet, in the same order:** who they are, net worth, movements, news. A section with nothing to show says so ("No plane on record", "Family history · not researched yet", "No headlines right now") instead of disappearing. Today's change is one line of text (`DayChange`), not a boxed chip.
- **Movements.** The map, then a section for the plane and one for the boat. Each section starts with the vehicle's map-pin shape, name and status chip, then short Now/From/To rows, the week's strip, one row per place it has ever been (time this week, all-time total, when last there), and distance plus the live-map link. Nothing is said twice: no "Now" row that only repeats the status chip, no "From" that repeats "Now", no visit line that repeats either.
- **News:** the top three headlines, with the all-time theme tally under them; the backend still reads and classifies more headlines than that, for the tally.
- **Lean over clever.** When a simpler piece of code gives the same look in this style, use it.
- **Places are spelled out in full.** The backend labels places "Temple, TX" or "Devonport, NZ"; the app expands the state or country (`PlaceNames` in `ui/PlaceNames.kt`: "Temple, Texas", "Devonport, New Zealand", and just "Monaco" rather than "Monaco, Monaco") and lets long names wrap instead of cutting them short. A place the snapshot has no entry for still shows its code.
- **Family history** is one sheet too: one section per generation, present to past, each fact with its source link, ending with where the record runs out. Every tracked person has an entry.
- **Every net worth says how it's made.** Under the line: which stocks are priced live ("Priced live: TSLA, SPCX"), each private stake as "Anthropic: 1.61% of $965.0B (valued May 28, 2026)", any remaining hand-set figure with what it covers, and whose estimate it is and when ("Forbes estimate, October 16, 2026"), flagged as "N days old, check for a newer figure" once it's three weeks old. Someone with nothing priced live gets a flat grey line and "no change today" in grey rather than a green +$0.
- **Links are text ending in ↗.** Residence, Wikipedia, social, sources and live maps are plain text links, with no buttons or icons.
- **Photos are greyscale** (a zero-saturation `ColorFilter`) in a bordered square, so a colour photo never breaks the page.
- **One series per chart**, so no legend is needed: the row names the line.
- **"Tracking," not "Live."** A person's wealth card used to show a "LIVE" chip whenever a real market-price anchor existed for them, which stays true straight through market close, so a dead-flat sparkline next to "LIVE" read as the app being broken. The label is "TRACKING" (it only ever meant "we have a real anchor for this person"), and `isSparklineFlat()` (in `Sparkline.kt`) separately detects a flat trailing window (last 6 points, exact equality, no market-hours calendar needed) and mutes the line plus swaps the caption to "flat · market's closed", so a closed market reads as an explained state, not a bug.
- **A dashed pin means "approximate," on purpose.** Added 2026-09-23 alongside the `currentLat`/`currentLon`/`generalLocation` fallback (see Flights/Vessels above): a solid pin means confirmed-at-that-airport/port-right-now, a hollow ring means last-known-not-live, and a dashed ring means neither — a real fix landed, but nothing known matched it, so the pin and its label (a coarse place name, never the raw numbers) are honestly less certain than the other two.
- **The boat mirrors the plane.** A person's page header shows ✈ AIRBORNE while their plane is in the air and ⚓ UNDERWAY while their boat is actually moving (`VesselState.UNDERWAY`, not just "we have AIS data", since in port is unremarkable and already covered by the vessel card). Same symbols as the status chips and map pins.

## Known gaps, in detail

- The roster was cut to three people (Musk, Zuckerberg, Bezos) on 2026-10-16; the other seven were removed from the backend, seed data, family history, state and snapshot, not just hidden. Later that day Amodei, Trump, Luckey and Altman were added, then Thiel; see `holdings.json`'s `_comment` for each one's sourcing.
- Seed figures are Forbes' (1 Sep 2026 for Musk, Bezos and Zuckerberg; 16 Oct 2026 for the five added later) and are a demo starting point, not a live feed.
- Musk, Bezos, Zuckerberg and Thiel each have an `edgarCik`, so `edgar-check.yml` monitors them for filing drift. Amodei, Trump, Luckey and Altman are mostly private wealth: Amodei and Luckey are a stake × their company's last funding-round valuation (`privateStakes`; after a new round, change the valuation and its date), Trump is his Trump Media shares priced live plus a hand-set figure for everything else, and Altman is one hand-set figure (his Reddit stake is left out while sources conflict on whether he still has it). All four were set against Forbes on 2026-10-16 (`estimateAsOf`), and the app shows that date. Thiel is his Palantir Class A and Class B shares from the 2026 proxy statement, priced live, plus a hand-set Forbes remainder for Founders Fund and his other investments. Only Trump has a plane on record (N757AF); none of the five has a yacht on record. Zuckerberg's META share count is derived (a reported percentage × shares outstanding, not a filing figure directly) and needs re-verifying by hand periodically.
- `backend/ports.csv` was added 2026-09-23 (16,666 UN/LOCODE seaports, see the Vessels section above for sourcing) after a real live-service catch of a tracked yacht's position exposed the gap: without a ports file, `nearest_port()` had nothing to match a real position against, so a genuinely-received AIS position still showed as "No signal" with no map pin. Confirmed the demo roster's ports (`MCMON`, `USFLL`) are present in the file.
- `ais_listener.py` has now been verified against the live aisstream.io service in burst mode (this used to say it hadn't been). Checked 2026-09-23 against the repo's own Actions history: run #202 of `.github/workflows/ais.yml` connected, subscribed to the MMSIs then in `holdings.json`, ran its full listening window, and exited clean with no errors; `backend/ais_cache.json` on `main` held a real position report it received that run (2026-09-23 08:31:58 UTC, for a yacht since removed with the roster cut) — a live-service round trip, not just a clean connect. Burst mode and continuous mode still share the same message-handling code, so this covers both. What's *not* yet exercised: an aisstream.io outage or malformed-message response, since only normal operation has been observed so far.
- Dead reckoning (`_dead_reckon_nm()` in `snapshot_worker.py`, see Vessels above) is verified via a self-consistent round-trip test (project forward, then compute distance/bearing back from the result, confirm they match the inputs) across six cases, but has no live-traffic test — there's no way to check a *projected* position against reality without an independent live position to compare it to, which is exactly the gap this feature exists to smooth over. The 30-minute cap and 0.5kt-moving gate are deliberately conservative defaults chosen from first principles, not tuned against observed AIS gap durations for this app's specific tracked vessels — worth revisiting once real gap-length data exists in `ais_cache.json`/`state.json` history.
- News theme classification (added 2026-09-25) is opt-in (`GEMINI_API_KEY`/`GEMINI_NEWS_MODEL`). Changing the theme list wipes every all-time theme tally and re-sorts the cached headlines, so the tallies restart from zero and only count headlines seen from then on.
- Widening `NEWS_FETCH_LIMIT` to 8 (same date) narrows but doesn't fully close the gap between the backend's classified window and a live client poll's window, since the two fetches genuinely happen at different moments — a headline can still occasionally show untagged on a live poll for one cycle before the backend's own next pass catches and classifies it.
- **Everything this app shows is already public.** Share counts come from public SEC filings, positions come from public ADS-B/AIS broadcasts, and biography/photo/news are public Wikipedia and social-link-outs — nothing is inferred, scraped from a private source, or derived from anything the tracked person hasn't already made publicly available. That bounds this project in both directions: it can never be more wrong than the public record it reads from (a bad number here is a stale or misfiled public figure, not a guess), and it can never show more than the public record already does — no private itinerary, no non-public holding, no address more precise than a city.
