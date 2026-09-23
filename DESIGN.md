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

## Social, biography, and news

Each person can carry a `socialUrl` (X, Bluesky, wherever they post) — the app links out, never embeds or polls live posts, since X's read API has no free tier as of 2026 and a metered per-view bill is exactly what the snapshot architecture exists to avoid.

`birthDate`, `residence`, and `bio` are static, hand-curated fields in `holdings.json`, not fetched live:

- **`birthDate`** is set once; age is computed client-side from the device's current date every render (`Format.ageFrom()`), so it advances on its own after a birthday with no snapshot change needed.
- **`residence`** is city/region granularity only ("Starbase, TX," never a street address) — same privacy rule as flight/vessel location, for the same reason.
- **`bio`** is one or two plain sentences (birthplace, last school attended) rather than separate structured fields, because several of this cohort left a *later* graduate program unfinished after completing an earlier degree elsewhere — prose is the only honest way to say "last attended" without it reading as "graduated from."

All three are left out entirely rather than guessed when public reporting is stale or conflicting (see `residenceFor()` in `SeedData.kt` for two real examples hit while researching the seed data).

**Wikipedia link and photo.** `wikipediaUrl`/`photoUrl` come from the free, keyless Wikipedia REST summary API, cached 30 days per person. `photoUrl` is only ever a Wikimedia Commons URL — the backend checks the hosting path of Wikipedia's own thumbnail and only keeps it if it's under `/wikipedia/commons/` (Commons is free-license-only by policy); a "fair use" image hosted under `/wikipedia/en/` never makes it into the app, so a null `photoUrl` means no free image existed, not that the fetch failed. A disambiguation page is skipped entirely rather than risk showing the wrong person. Portraits need a real browser-like `User-Agent` header or they silently never load — Wikimedia's API etiquette policy deprioritizes a generic OkHttp UA, which is why `TrillionaireTrackerApp.kt` installs a custom `OkHttpClient` for Coil with the same UA string the Python backend uses.

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
- **Honeycomb backdrop tiling was tried and removed.** An earlier pass added a tileable hex-grid background (`honeycombBackdrop()` in `HudDecorations.kt`) behind most cards; it visually bled past its own element's bounds into neighboring cards and the gaps between them (Compose's `drawBehind`/`drawWithContent` don't auto-clip to a composable's layout box) and was judged too busy even after that was fixed with `clipRect`. The function is still defined but unused everywhere. What stayed: `honeycombGlowCell()`, a small persistent glow used as a status layer (e.g., an amber/critical tint on a flight or vessel card) — this was never about decoration, only about surfacing a real state, so it survived the honeycomb removal on every card that uses it.
- **"Tracking," not "Live."** A person's wealth card and leaderboard row used to show a pulsing "LIVE" chip whenever a real market-price anchor existed for them — which stays true straight through market close, so a dead-flat sparkline sitting next to "LIVE" read as the app being broken. The label was renamed to "TRACKING" everywhere (it only ever meant "we have a real anchor for this person," not "actively moving right now"), and `isSparklineFlat()` (in `Sparkline.kt`) separately detects a flat trailing window (last 6 points, exact equality, no market-hours calendar needed) and mutes the line color plus swaps the caption to "flat · market's closed right now" so a closed market reads as an explained state, not a bug.
- **A dashed pin means "approximate," on purpose.** Added 2026-09-23 alongside the `currentLat`/`currentLon`/`generalLocation` fallback (see Flights/Vessels above): a solid pin means confirmed-at-that-airport/port-right-now, a hollow ring means last-known-not-live, and a new dashed ring means neither — a real fix landed, but nothing known matched it, so the pin and its label (a coarse place name, never the raw numbers) are honestly less certain than the other two. Three visually distinct styles rather than reusing one of the existing two, so reduced confidence is legible on the map itself, not just buried in caption text someone has to read.

## Known gaps, in detail

- Seed figures are the Forbes top ten as of 1 Sep 2026 and are a demo starting point, not a live feed.
- `holdings.json` has all ten people filled in (Dell and Ortega were the last two added, both live-ticker-trackable: `DELL` and Madrid-listed `ITX.MC`). `edgar-check.yml` only monitors the eight people with an `edgarCik` set for filing drift — Dell's and Ortega's share counts are derived (a reported percentage × shares outstanding, not a filing figure directly) and need re-verifying by hand periodically.
- Ortega's yacht (`DRIZZLE`) now has its `mmsi` (256867000) copied into `holdings.json` (2026-09-23, from `SeedData.kt`'s existing value) — this was open as of the previous pass and is now closed.
- `backend/ports.csv` was added 2026-09-23 (16,666 UN/LOCODE seaports, see the Vessels section above for sourcing) after a real live-service catch of Ellison's MUSASHI position exposed the gap: without a ports file, `nearest_port()` had nothing to match a real position against, so a genuinely-received AIS position still showed as "No signal" with no map pin. Confirmed the demo roster's ports (`MCMON`, `NZAKL`, `USFLL`, and the rest) are all present in the file.
- `ais_listener.py` has now been verified against the live aisstream.io service in burst mode (this used to say it hadn't been). Checked 2026-09-23 against the repo's own Actions history: run #202 of `.github/workflows/ais.yml` connected, subscribed to the 4 MMSIs currently in `holdings.json`, ran its full listening window, and exited clean with no errors; `backend/ais_cache.json` on `main` holds a real position report it received that run for Ellison's MUSASHI (MMSI 319032600, timestamped 2026-09-23 08:31:58 UTC) — a live-service round trip, not just a clean connect. Burst mode and continuous mode still share the same message-handling code, so this covers both. What's *not* yet exercised: an aisstream.io outage or malformed-message response, since only normal operation has been observed so far.
- **Everything this app shows is already public.** Share counts come from public SEC filings, positions come from public ADS-B/AIS broadcasts, and biography/photo/news are public Wikipedia and social-link-outs — nothing is inferred, scraped from a private source, or derived from anything the tracked person hasn't already made publicly available. That bounds this project in both directions: it can never be more wrong than the public record it reads from (a bad number here is a stale or misfiled public figure, not a guess), and it can never show more than the public record already does — no private itinerary, no non-public holding, no address more precise than a city. The Larry Page yacht correction above (`SeedData.kt`/`holdings.json`, 2026-09-22) is a concrete example of this rule working as intended: once public sourcing showed the old attribution was wrong, the entry was pulled rather than left in or guessed at.
