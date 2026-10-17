# Design notes

Design decisions, data sources and known limitations. None of this is needed to run the app.

## Net worth

```
sum(shares × live price) + private stakes + cash − liabilities
```

- Share counts come from SEC filings (Form 4, 13D/G, proxy statements).
- A private stake is a percentage of a private company times that company's last funding-round valuation, with the round's date (`privateStakes`). Anything that cannot be split that way is a hand-set `privateStakesUsd` described by `estimateNote`. `estimateAsOf` and `estimateSource` record when and from whom the hand-set parts were last checked, and the app flags an estimate older than three weeks.
- If any public holding cannot be priced, including a missing exchange rate for a non-USD holding, the person is skipped for that pass rather than shown with a partial total (`price_quotes_in_usd()`, and the same rule in `Holdings.kt`).
- Each history point carries its own timestamp and is trimmed to a rolling 7 days. Day change is measured from the price nearest the most recent UTC midnight. A newly added person's history is backfilled from each holding's 7-day intraday chart (`backfill_history()`), and a person whose history was estimate-only is rebuilt from real closes once they gain a priced stock.
- `state.json` is committed back after every run, so every rolling window accumulates across runs.

## Aircraft

| Field | Timing | Source |
|---|---|---|
| Airborne / on-ground state | live | ADS-B |
| Departure airport and elapsed time | live | ADS-B |
| Heading toward (while airborne) | live, labelled as an estimate | computed |
| Destination and arrival time | once landed | ADS-B |
| Current location | live, airport level | ADS-B |
| Location history | trailing 7 days, airports only | ADS-B |
| Live map position | only while a person's page is open and their plane is airborne | OpenSky, then adsb.lol |

- Locations are airport-level. `currentAirportIcao` is null while airborne, and `recentStops` lists the airports visited in the last 7 days with arrival and departure times. No coordinate is stored.
- A ground fix that matches no airport in `airports.csv` is published as `currentLat`/`currentLon`/`generalLocation` for the current pass only, never stored, and drawn as a dashed "approximate" pin.
- ADS-B carries no destination. While airborne, `estimatedDestinationIcao` is the nearest large or medium airport within ±12° of the current track, always labelled as an estimate.
- `fetch_aircraft()` tries adsb.lol, adsb.fi and OpenSky by ICAO hex, then adsb.lol and adsb.fi by registration when a tail number is on file, stopping at the first source that has the aircraft. The registration lookup covers aircraft broadcasting under a different or anonymised hex. MLAT positions are accepted. A source that fails is logged and the next one is tried.
- The live map position (`LiveFlightTracker`) is polled from the device and never stored.
- Tail numbers are not guessed: `tailVerified` stays false until confirmed from filings or archived ADS-B, and the app marks the plane unverified until then. Planes on record: Musk (N628TS), Bezos (N758PB), Trump (N757AF).

## Vessels

- AIS comes from aisstream.io, a WebSocket stream rather than a polled API. On GitHub Actions, `ais.yml` runs `ais_listener.py` in burst mode every 5 minutes: it listens for `AIS_BURST_SECONDS` (270) and writes `ais_cache.json`, which `snapshot_worker.py` reads. Without `AIS_BURST_SECONDS` the listener runs continuously, for use on an always-on machine (`pip install websockets`). It subscribes only to the MMSIs in `holdings.json`.
- Locations are port-level, matched against `backend/ports.csv`: 16,666 UN/LOCODE seaports with coordinates, filtered from `cristan/improved-un-locodes` (see the file's header). A fix with no port match is shown as approximate, as for aircraft, whether moored or underway.
- During a short AIS gap, a moving vessel (at least 0.5 knots with a valid course) is projected along its course for up to 30 minutes (`_dead_reckon_nm()`). This affects the displayed position only; port matching and history use the received fix.
- `selfReportedDestination` is the crew-entered AIS destination and is shown as such. `vesselVerified` follows the same rule as `tailVerified`. IMO numbers are captured from AIS static data when broadcast; none are set in `holdings.json` yet.
- Yachts on record: Bezos (KORU), Zuckerberg (LAUNCHPAD).

## Biography and Wikipedia

- `birthDate`, `residence` (city level only) and `bio` are hand-curated in `holdings.json`, and left out where public reporting conflicts. Age is computed on the device.
- `socialUrl` is a link only; posts are never fetched.
- The Wikipedia link and photo come from the Wikipedia REST summary API, cached for 30 days and retried after an hour on failure. Only Wikimedia Commons images are used, so every photo is freely licensed, and disambiguation pages are skipped. Images are requested with the same identifying User-Agent as the backend, as Wikimedia's policy asks.

## News

- `fetch_news()` takes each person's top 8 headlines (`NEWS_FETCH_LIMIT`) from Google News RSS every pass. The app's live poll uses the same query and limit, so a headline it finds first may appear untagged until the next backend pass.
- Every third pass (15 minutes), `classify_news_themes()` sends every untagged headline, for everyone, to Gemini in one call: at most 96 calls a day, within the free tier. A tagged headline is never re-sent, and a failed call is retried at the next tagging pass. Without `GEMINI_API_KEY` and `GEMINI_NEWS_MODEL`, headlines are left untagged. The call has its own 30-second timeout (`GEMINI_TIMEOUT`).
- Tracked themes (`TRACKED_NEWS_THEMES`, mirrored by `Config.TRACKED_NEWS_THEMES`):
  - **Alien News**: aliens, UFOs and UAPs, and the search for life beyond Earth.
  - **Bunkers**: any mention of a bunker, doomsday shelter, survival compound or Miami's "Billionaire Bunker" island. Takes precedence over every other theme.
  - **Underground**: anything built, dug or fought underground, including The Boring Company, underground transport, underground weapons sites and tunnel warfare. Takes precedence over every theme except Bunkers.
  - **Antichrist**: any mention of the Antichrist. Takes precedence over every theme except Bunkers and Underground.
  - **Apocalypse**: the end of the world or of civilisation, including doomsday, Armageddon, the end times and human extinction from AI, nuclear war or other causes. Takes precedence over God & Spirituality and War & Defense.
  - **God & Spirituality**: faith, religion and the meaning of life.
  - **War & Defense**: wars, armed forces, weapons and defence policy, including a person's or company's part in them.
- Anything else, including business, markets, products and politics, is classified "Other": stored so it is not re-sent, but never shown or counted.
- Changing the theme list (`migrate_news_themes()`) clears every tag and all-time count, so results from different lists never mix.
- Each person's all-time count (`lifetimeNewsThemes`) counts distinct headlines per theme, deduplicated by title, and is never pruned. Trending Topics on the main page is the three most common themes across everyone's current headlines, with a story under two people counted once.

## Connections

`app/src/main/assets/connections.json` ships with the app and is loaded the same way as `family_history.json`; it is not part of the live snapshot. Each entry is a shared background trait with one sourced fact per person. Only background is included: upbringing, schooling, family origins and formative influences. Business, investments, politics, religion and ethnicity are excluded. Facts were checked against Wikipedia on 17 October 2026.

## Backend operations

- Only the people in `holdings.json` are fetched or kept. `forget_untracked()` removes anyone else from `state.json`, `ais_listener.py` prunes `ais_cache.json`, and the app ignores anyone not in `Config.TRACKED_PEOPLE`.
- `snapshot.yml` runs every 5 minutes (GitHub's scheduler does not run reliably more often) and publishes `docs/snapshot.json`. It downloads OurAirports' `airports.csv` on its first run. `snapshot.yml` and `ais.yml` both push to `main` and retry on a rejected push.
- `edgar-check.yml` runs `edgar_check.py` on the 1st of each month for everyone with an `edgarCik` (Musk, Bezos, Zuckerberg, Thiel). It records the newest Form 4 seen per person and opens an issue when there is new activity. It never edits `holdings.json`, because some share counts are derived or summed across entities and need a person to check them. An optional `EDGAR_USER_AGENT` repository variable supplies the contact address SEC asks for.
- `fetch_quotes()` is the only function that knows where prices come from.

### Going live

1. Push the repository to GitHub.
2. Run "Refresh snapshot" once from the Actions tab. This creates `docs/snapshot.json`.
3. Under Settings → Pages, deploy from branch `main`, folder `/docs`.
4. Check that `https://<user>.github.io/<repo>/snapshot.json` returns JSON.
5. Set `Config.SNAPSHOT_URL` to that URL and rebuild the app.
6. Optionally, add repository secrets: `AISSTREAM_API_KEY` (from aisstream.io) for vessels, and `GEMINI_API_KEY` and `GEMINI_NEWS_MODEL` (from aistudio.google.com; a current Flash-Lite model) for news themes. Without them those features stay off and nothing fails.

## Interface

- **Black, white and monospace.** OLED black with no background effects, and monospace text with line height tied to its size (`Theme.kt`).
- **One sheet per screen.** Each screen is a single bordered sheet, with sections divided by `SheetLine()`.
- **Colour means today's change only.** Green and red are used for a person's change today and nothing else. Status is carried by a glyph and a word (✈ AIRBORNE, ● ON GROUND, ! SIGNAL LOST, ? NO SIGNAL), and map pins differ by shape: filled for live, ring for last known, dashed ring for approximate.
- **Main page**, top to bottom: "Trillionaire right now?", with who and for how long, and any earlier crossings; Total Wealth Tracked and how many days it would fund ending extreme poverty at $318B a year (NBER Working Paper 34583, `Config.ANNUAL_POVERTY_ELIMINATION_COST_USD`); the Connections diagram; Trending Topics; then one row per person, richest first. A ✈ or ⚓ beside a name means that person's plane is airborne or yacht is underway.
- **Connections diagram.** Surnames run round the outside of a ring, each face sits in a medallion, and a line joins any two people who share something, thicker for more. It turns slowly, once every two minutes, while no face is selected. Selecting a face highlights its lines, dims anyone with nothing in common, and lists the shared traits, each of which opens to show its sourced facts.
- **Person page**, always in this order: identity, net worth, movements, news. An empty section says so rather than disappearing.
- **Net worth** shows its makeup: tickers priced live, each private stake with its valuation and date, any hand-set remainder, and the estimate's source and date. A flat 7-day line is muted and labelled "flat · market's closed"; a figure with nothing priced live is labelled "not priced live".
- **Movements**: the map, then a section per vehicle with its status, recent route, the week's time-by-location strip (`TimelineStrip`) and every place visited. The map is drawn on a Compose Canvas from Natural Earth country outlines (`world_countries.json`, also used by the backend for approximate place names). It has recentre and reset buttons but no pan or pinch, so it never interferes with scrolling.
- **Places** are written out in full ("Temple, Texas", "Devonport, New Zealand") by `PlaceNames`.
- **Photos** are greyscale, and **links** are text ending in ↗.
- **Back gesture.** A swipe from the left edge goes back on pushed screens (`EdgeSwipeBack.kt`), for devices where the system back gesture is unavailable. It only claims a touch that starts within 32dp of the edge and moves at least 18dp, mostly horizontally. Some devices need the system's gesture navigation mode enabled for it to work.
- **Family history**: one section per generation, present to past, each fact sourced.

## Known limitations

- All data comes from public sources. Errors reflect stale or misfiled public records, and nothing is shown beyond what those records contain: no itineraries, no non-public holdings, no address more precise than a city.
- Seed figures are Forbes estimates (1 September 2026 for Musk, Bezos and Zuckerberg; 16 October 2026 for the others) and are a starting point, not live data.
- Amodei and Luckey are a stake times their company's last valuation; Trump is his Trump Media shares priced live plus a hand-set remainder; Altman is a single hand-set figure, excluding his Reddit stake while sources conflict on whether he still holds it; Thiel is his Palantir Class A and B shares priced live plus a hand-set remainder. These are updated by hand after new funding rounds or estimates.
- Zuckerberg's META share count is derived from a reported percentage of shares outstanding and needs periodic re-checking.
- Location history starts when a person is added; there is no historical ADS-B or AIS feed to backfill from.
- Vessel positions are only received during each 270-second listening window. Dead reckoning has been verified with round-trip tests, not against live traffic.
- Changing the theme list starts every theme count again from zero.
