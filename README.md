# Trillionaire Tracker

An Android app, built with Kotlin and Jetpack Compose, that tracks the world's richest people: live net worth, real-time private jet and yacht movements, news, and biography — backed by a free, serverless Python worker.

**[Download the latest APK](https://github.com/Hostu404/TrillionaireTracker/releases/latest)**

<img width="1216" height="874" alt="image" src="https://github.com/user-attachments/assets/9084f669-0f73-4a27-8175-7a3f0bd4cb55" />

## Features

- **Live net worth** — sum(shares × live price) + private stakes + cash − liabilities, from real SEC filings and live market/FX prices, ticking smoothly between polls. Currency-aware (a non-USD holding converts to USD via a live FX rate, never assumed). A real rolling 7-day price history, backfilled with real prices the moment a new person is tracked.
- **Live flight tracking** — a real-time ADS-B position on the map while a tracked plane is airborne, with three free, keyless data sources and automatic fallback between them. Airport-level only on the ground, never a raw coordinate — except a narrow, discussed case (added 2026-09-23): a real ground fix that matches no known airport shows an approximate area and a visibly distinct dashed-ring pin instead of just disappearing. See DESIGN.md for the exact scoping.
- **Vessel tracking** — the same idea for yachts via free AIS data, with no always-on server required. Port-level only, with the same narrow approximate-area exception as flights (here for both moored and underway, since vessels have no separate live-position path).
- **Time-by-location strip** — a chronological readout of how the tracked window splits across airports/ports, in-transit time, and signal gaps — one shared component for both flights and vessels.
- **Biography** — birthdate (age computed live, never stored), city-level residence, a short sourced bio, a Wikipedia summary and free-license photo, and a link-out for social — nothing scraped or embedded live.
- **News, with AI theme tags** — each person's latest headlines from free, keyless RSS, optionally classified by a free-tier Gemini call into a fixed set of themes (Markets & Wealth, Business & Deals, Legal & Regulatory, Technology & Innovation, Public Life & Controversy, Profile & Commentary, or Other). Fully opt-in — the app works identically with plain, untagged headlines if you skip the API key. A permanent lifetime tally per person shows what they tend to get covered for, across their whole tracked history.
- **Self-managing backend** — every time series (prices, flight/vessel history, the trillionaire-crossing log) trims itself to a real rolling 7 days automatically, so the data and the repo it lives in stay flat-sized forever.

## Why is Trillionaire Tracker?

https://youtube.com/watch?v=wP8RgWJ76xE


## How it works

One backend worker polls upstream sources on a schedule and publishes a single `snapshot.json` to a free CDN; every client just reads that cached file, so upstream cost is flat no matter how many people install the app.

```
worker (every 5 min) → snapshot.json → CDN → any number of clients
```

The app ships with a working seed snapshot, so it runs immediately with no setup. Point `Config.SNAPSHOT_URL` at your own published snapshot to go live — nothing else changes.

## Run it

1. Open the project in Android Studio, let it sync, and run on a device or emulator (minSdk 26). It works right away on seed data.
2. To go live: stand up the backend below, then set `Config.SNAPSHOT_URL` to your published `snapshot.json` URL.

## Backend

```bash
cd backend
cp holdings.example.json holdings.json   # fill it in from SEC filings
python3 snapshot_worker.py               # writes snapshot.json
```

Python standard library only, no API keys required. `.github/workflows/snapshot.yml` runs this for free every 5 minutes on GitHub Actions and publishes to GitHub Pages — a complete free hosting path, no server of your own. `.github/workflows/ais.yml` does the same for vessel tracking (needs a free `AISSTREAM_API_KEY` secret), and AI news theme tags need a free `GEMINI_API_KEY`/`GEMINI_NEWS_MODEL` pair — both are opt-in, and everything works with plain untagged/no-signal data if you skip them.

See **[DESIGN.md](DESIGN.md)** for the step-by-step go-live checklist, the detailed design rationale, data sourcing, and every documented caveat or limitation this project has.

## Known gaps

- Two of ten people's share counts (Dell, Ortega) are derived rather than filing-sourced, and aren't auto-monitored for drift the way the other eight are.

- Everything tracked is already public information (SEC filings, ADS-B/AIS, Wikipedia, public social links) — never inferred or scraped from a private source. That limits how wrong the app can be, and also how much it will ever show.

- A news theme taxonomy change isn't retroactive — an already-cached headline keeps whatever theme it got before, until it naturally ages out of the tracked window and gets refetched fresh.

Full detail on all of the above: **[DESIGN.md](DESIGN.md)**.

## License

MIT — see [LICENSE](LICENSE). Free to use, modify, and redistribute.
