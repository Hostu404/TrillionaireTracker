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

**Current location and history are airport-level, never coordinates.** `currentAirportIcao` is null while airborne — "no fixed location" is the honest answer for a plane in the air. `recentStops` is a rolling 7-day list of airports touched, each with arrival/departure time. This is the granularity every public jet-tracking site already operates at; a precise position trail would be a meaningfully worse version of the same risk `estimatedDestinationIcao` already treats carefully, so this app doesn't produce one.

**Airport codes are never shown raw.** Every airport-facing field resolves through `Snapshot.airports` (built by `airport_label()`, e.g. `KAUS` → "Austin, TX") before reaching a screen, falling back to the raw ICAO only if there's no match.

**Time-by-location strip.** The person screen shows a chronological strip (`TimelineStrip.kt` — a left-to-right timeline, not a proportional/donut chart, despite older internal naming) of how the tracked window splits across airports, in-flight time, and signal gaps, rebuilt client-side from the same 7-day stop history everything else on the card uses (see `buildTimelineSegments` in `PersonDetailScreen.kt`) rather than from the server's own `locationBreakdown` aggregate, so the strip and its legend can never drift out of sync with a second source of truth. Real airports cap at 5 identity colors, with the rest folded into "Other airports."

Two things worth knowing about destinations:

1. **Raw ADS-B has no destination field.** An aircraft's transponder broadcasts position, not where it's going — FlightAware/FR24 get destinations from a separate, gated FAA feed. So the confirmed destination only exists once the aircraft is on the ground.
2. **`estimatedDestinationIcao`** fills that gap while airborne: the backend projects current position + track against known large/medium airports and picks the nearest one within a ±12° cone. It's a guess from public course data, always labeled as an estimate, and will sometimes be wrong or absent.

Live position data comes from `adsb.lol` (free, community-fed) server-side, and from a three-source fallback chain client-side (`LiveFlightTracker` in `LiveTracking.kt`): OpenSky first, then `adsb.lol`, then `airplanes.live`, stopping at the first source that actually has the aircraft. All three are free and keyless, scoped to one aircraft's hex per request, and polled only while that person's detail screen is open and the plane is actually airborne — never in the background, never stored.

**Tail numbers are not guessed.** `tailVerified` stays false until the aircraft is confirmed to the person from filings or archived ADS-B, and the app shows an "unverified" badge until it is. The seed/demo snapshot carries real, publicly reported registrations for seven people, cross-corroborated across multiple independent aviation trackers; only the *identity* (tail/hex) is real for the demo data, not the flight states/timestamps around it. Larry Page, Sergey Brin, and Amancio Ortega have no aircraft entry — nothing solid enough turned up, the same bar a real backend run applies.

## Vessels

The maritime mirror of Flights — same privacy shape, and mostly the same code path (`VesselStatus`/`PortStop`, `BoatCard`/`PortHistoryCard`, `VesselTimeByLocationCard`/`TimeByLocationCard` are structural copies of the flight versions, sharing the same `TimelineStrip` component rather than a second chart type). The one real difference is the data source:

**Vessels broadcast AIS, not ADS-B — same idea, different transport, and no free "ask, get an answer" API that covers open water the way `adsb.lol` does for planes.** The free tier that actually works globally (`aisstream.io`) is a persistent WebSocket subscription, not a poll — but aisstream.io itself is free either way; only the two ways of *hosting* the listener differ in whether they need a server of your own.

**Option A — free, no server (recommended default).** `backend/ais_listener.py` supports a burst mode: set `AIS_BURST_SECONDS`, and it connects, subscribes, listens for that window, saves whatever came in, and exits — no always-on process needed. `.github/workflows/ais.yml` runs this on the same free 5-minute Actions cron as `snapshot.yml`, reading the `AISSTREAM_API_KEY` repo secret (free at aisstream.io/authenticate). Without that secret set, the workflow skips itself rather than failing. This catches only whatever a vessel transmits during the ~45s window, so think "refreshed every 5 minutes," not instant. `snapshot_worker.py` only ever reads the resulting `ais_cache.json` — zero extra network calls of its own.

**Option B — continuous, tighter freshness, needs your own always-on box.** If you already have somewhere that stays on 24/7, run the listener without `AIS_BURST_SECONDS` set, kept running, **not** on the same cron as `snapshot_worker.py`:

```bash
cd backend
pip install websockets        # the one non-stdlib dependency, confined to this file
export AISSTREAM_API_KEY=...
python3 ais_listener.py
```

Either way it only subscribes to MMSIs actually listed in `holdings.json` (up to 200), never the whole ocean. It was written against aisstream.io's published docs, not exercised against the live service — worth watching its logs the first time you run it for real.

Everything else follows the flight pattern: port granularity only (never a coordinate, live or historical); a time-by-location strip in the same shared component, using `IN_PORT`/`UNDERWAY`/`NO_SIGNAL` states and port UNLOCODEs; `selfReportedDestination` (AIS's one edge over ADS-B — a real destination field, but free text the crew types in, routinely blank/stale/informal, always shown with that caveat); `vesselVerified`, same unguessed-identity rule as `tailVerified`. Optionally drop a `ports.csv` beside `snapshot_worker.py` for real port names (see `load_ports()`'s docstring) — you only need the handful of ports your tracked yachts actually visit. The seed/demo snapshot carries real MMSIs for six people, cross-corroborated the same way the flight seed data is; Musk, Dell, Huang, and Ballmer have no yacht entry — nothing confidently linked to them turned up.

## Social, biography, and news

Each person can carry a `socialUrl` (X, Bluesky, wherever they post) — the app links out, never embeds or polls live posts, since X's read API has no free tier as of 2026 and a metered per-view bill is exactly what the snapshot architecture exists to avoid.

`birthDate`, `residence`, and `bio` are static, hand-curated fields in `holdings.json`, not fetched live:

- **`birthDate`** is set once; age is computed client-side from the device's current date every render (`Format.ageFrom()`), so it advances on its own after a birthday with no snapshot change needed.
- **`residence`** is city/region granularity only ("Starbase, TX," never a street address) — same privacy rule as flight/vessel location, for the same reason.
- **`bio`** is one or two plain sentences (birthplace, last school attended) rather than separate structured fields, because several of this cohort left a *later* graduate program unfinished after completing an earlier degree elsewhere — prose is the only honest way to say "last attended" without it reading as "graduated from."

All three are left out entirely rather than guessed when public reporting is stale or conflicting (see `residenceFor()` in `SeedData.kt` for two real examples hit while researching the seed data).

**Wikipedia link and photo.** `wikipediaUrl`/`photoUrl` come from the free, keyless Wikipedia REST summary API, cached 30 days per person. `photoUrl` is only ever a Wikimedia Commons URL — the backend checks the hosting path of Wikipedia's own thumbnail and only keeps it if it's under `/wikipedia/commons/` (Commons is free-license-only by policy); a "fair use" image hosted under `/wikipedia/en/` never makes it into the app, so a null `photoUrl` means no free image existed, not that the fetch failed. A disambiguation page is skipped entirely rather than risk showing the wrong person. Portraits need a real browser-like `User-Agent` header or they silently never load — Wikimedia's API etiquette policy deprioritizes a generic OkHttp UA, which is why `TrillionaireTrackerApp.kt` installs a custom `OkHttpClient` for Coil with the same UA string the Python backend uses.

## Map

Each detail screen carries a self-drawn Jetpack Compose `Canvas` world map — no WebView, no maps SDK, no per-user API calls. Country outlines come from Natural Earth's public-domain Admin-0 boundaries, bundled as a flat ~125KB JSON asset with no network call involved. Country names only render once zoomed in (world-view scale with all 177 at once is unreadable). Pins are never a live position except the one documented exception (a currently-airborne plane with a live ADS-B fix, see Flights above) — otherwise a pin is either solid (confirmed at that airport/port right now) or hollow "last known" (airborne/underway/signal lost), and the caption always says which.

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

`.github/workflows/ais.yml` can't be created by pushing through most remote/automated tooling — GitHub blocks writes into `.github/workflows/` from exactly that kind of access, for the obvious reason that nobody wants a remote tool able to silently plant CI that runs with a repo's secrets. Create it by hand at that path with this content, and it'll pick up the same 5-minute cron `snapshot.yml` uses:

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
          AIS_BURST_SECONDS: "45"
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
          git add backend/ais_cache.json
          if git diff --cached --quiet; then
            echo "nothing changed, skipping commit"
          else
            git commit -m "Refresh AIS cache ($(date -u +%Y-%m-%dT%H:%M:%SZ))"
            git push
          fi
```

Optionally, also set an `EDGAR_USER_AGENT` repository variable (Settings → Secrets and variables → Actions → Variables) to something like `"YourProjectName (your-real-email@example.com)"` — works fine without it (falls back to a generic default in `edgar_check.py`), but SEC's fair-access guidance prefers a real contact.

**Keeping `holdings.json` from going stale:** `.github/workflows/edgar-check.yml` runs `backend/edgar_check.py` monthly and opens a PR if it finds new SEC Form 4 activity for anyone with an `edgarCik` set. It never edits `holdings.json` directly — some share counts are a single filing's figure, others are derived from a reported percentage, and only a human can tell which is which — so the PR just surfaces what EDGAR says next to what's on file, with a link. Its first run per person just records a baseline; activity shows up from the second run onward. GitHub disables Actions PR creation by default on new repos, so this workflow needs that turned on (Settings → Actions → General → Workflow permissions) to actually open anything.

## UI design notes

- Dark-only. The palette's dark steps were selected and validated against the dark surface; an automatic light flip would not hold up.
- Status never signals with color alone — the good/critical steps sit close together under deuteranopia, so every status carries a glyph and a word too.
- One series per chart, so no legend is needed — the row names the line.
- The hero figure uses monospace digits so the row doesn't jitter sideways on every change.

## Known gaps, in detail

- Seed figures are the Forbes top ten as of 1 Sep 2026 and are a demo starting point, not a live feed.
- `holdings.json` has all ten people filled in (Dell and Ortega were the last two added, both live-ticker-trackable: `DELL` and Madrid-listed `ITX.MC`). `edgar-check.yml` only monitors the eight people with an `edgarCik` set for filing drift — Dell's and Ortega's share counts are derived (a reported percentage × shares outstanding, not a filing figure directly) and need re-verifying by hand periodically.
- Ortega's yacht (`DRIZZLE`, per `SeedData.kt`) has no `mmsi` in `holdings.json` yet — the real MMSI lives only in the Kotlin seed file and needs copying over by hand.
- `ais_listener.py` was written against aisstream.io's documentation, not tested against the live WebSocket service — true of both continuous and burst mode, which share the same message-handling code. Its message parsing and the backend logic that consumes its output are both unit-tested against mocked data; the actual connect/subscribe/stream round-trip against the real service hasn't been exercised end to end.
