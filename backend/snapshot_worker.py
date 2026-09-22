#!/usr/bin/env python3
"""
snapshot_worker.py — produces the one document the app reads.

Run this on a schedule (once a minute is plenty). It writes snapshot.json, which
you put behind a CDN. Every client in the world reads that cached file, so the
upstream call volume below is the TOTAL for the whole product — it does not grow
when the user count does.

Per pass, upstream:
    K quote requests       (K = distinct tracked tickers, one request each —
                            see [fetch_quotes] for why this isn't batched)
    <=F FX requests        (F = distinct non-USD currencies among tracked
                            tickers — see [fetch_fx_rate] — 0 once everyone
                            tracked trades in USD)
    N ADS-B requests      (N = tracked aircraft, only when due)
    0 AIS requests        (vessel positions are read from ais_cache.json,
                           written by ais_listener.py — see that file. This
                           worker never talks to AIS.)
    M RSS requests        (M = tracked people, only every NEWS_EVERY passes)
    <=M Wikipedia requests (only every WIKI_EVERY_DAYS days per person — a
                            photo and article link barely ever change)
    A one-time backfill (K' historical-range quote requests, K' = tickers
    belonging to a person seen for the first time) seeds ~7 days of real
    history immediately instead of a flat line that only starts filling in
    from whenever the worker happened to first run for that person — see
    [backfill_history]. Paid for once per person, ever, not per pass.

Self-managing by design: every per-person time series this script keeps
(price history, crossing history, flight/vessel stops and time-share
samples) is trimmed to a rolling 7-day window every pass — see the "trim to
7 days" comments below. Nothing here grows without bound, so state.json
stays roughly flat-sized and the repo it's committed to doesn't grow
without bound either.

Stdlib only. No API keys. (ais_listener.py is the one exception — see its
own docstring — it's a separate process this script never imports or calls.)
"""

from __future__ import annotations

import bisect
import calendar
import csv
import json
import math
import os
import sys
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from typing import Any

HISTORY_WINDOW_SECONDS = 7 * 86_400   # every self-managed series in this file trims to this

USER_AGENT = "trillionaire-tracker/0.1 (+https://github.com/Hostu404)"
TIMEOUT = 15

HERE = os.path.dirname(os.path.abspath(__file__))
HOLDINGS_PATH = os.path.join(HERE, "holdings.json")
STATE_PATH = os.path.join(HERE, "state.json")
OUTPUT_PATH = os.path.join(HERE, "snapshot.json")
AIRPORTS_PATH = os.path.join(HERE, "airports.csv")  # optional, OurAirports format
PORTS_PATH = os.path.join(HERE, "ports.csv")         # optional, see load_ports()
AIS_CACHE_PATH = os.path.join(HERE, "ais_cache.json")  # written by ais_listener.py

THRESHOLD_USD = 1_000_000_000_000.0
POLL_SECONDS = 60
FLIGHT_EVERY = 1     # passes between ADS-B reads
VESSEL_EVERY = 1     # passes between ais_cache.json reads — it's a local file,
                     # not a network call, so there's no rate-limit reason to
                     # space these out the way FLIGHT_EVERY exists for.
VESSEL_STALE_SECONDS = 6 * 3_600   # cache entry older than this reads as no signal
NEWS_EVERY = 15      # passes between RSS reads
WIKI_REFRESH_SECONDS = 30 * 86_400   # a photo/article link barely ever changes


# ---------------------------------------------------------------- http


def get(url: str, headers: dict[str, str] | None = None) -> bytes:
    req = urllib.request.Request(url, headers=headers or {"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
        return resp.read()


def get_json(url: str, headers: dict[str, str] | None = None) -> Any:
    return json.loads(get(url, headers).decode("utf-8", "replace"))


# ---------------------------------------------------------------- quotes


# Stooq's old free /q/l/ CSV endpoint (used here until Sept 2026) started
# requiring a registered API key as of March 2026 — see
# github.com/pydata/pandas-datareader/issues/1012 — which turned every
# quote into a 404 instead of data. Yahoo Finance's unofficial "chart"
# endpoint is the replacement: still free and keyless (Yahoo shut down its
# *official*, key-requiring API back in 2017 and never replaced it — this
# is the same undocumented endpoint yfinance and most scrapers use for a
# live price), just no longer batch-capable, hence one request per ticker
# below rather than Stooq's old single combined call. Fine at this app's
# scale (a handful of tickers, polled every 5 minutes) — see [fetch_quotes].
YAHOO_QUOTE_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)


def fetch_quotes(tickers: list[str]) -> dict[str, dict]:
    """
    One request per ticker against Yahoo Finance's unofficial chart
    endpoint (see the module-level comment above this function for why
    Stooq's old batched CSV call was replaced). A plain custom User-Agent
    gets blocked here in practice — Yahoo's anti-bot layer treats it like
    any other scraper request — so this one call uses a real browser UA
    ([YAHOO_QUOTE_UA]) instead of this module's usual [USER_AGENT]. Swap
    this function for a paid provider (Finnhub, Twelve Data) if you ever
    want a real SLA instead of an unofficial endpoint; nothing else changes.

    Returns ticker -> {"price": float, "currency": str}. Yahoo covers
    non-US exchanges too (e.g. "ITX.MC" for a Madrid-listed stock), and
    those come back priced in their own local currency, not USD — see
    [fetch_fx_rate] and its call site in [build_snapshot] for how that gets
    converted before it ever reaches net_worth().
    """
    if not tickers:
        return {}

    out: dict[str, dict] = {}
    for ticker in tickers:
        url = (
            "https://query2.finance.yahoo.com/v8/finance/chart/"
            f"{urllib.parse.quote(ticker)}?range=1d&interval=1d"
        )
        try:
            payload = get_json(url, headers={"User-Agent": YAHOO_QUOTE_UA})
            meta = payload["chart"]["result"][0]["meta"]
            price = meta["regularMarketPrice"]
            out[ticker.upper()] = {
                "price": float(price),
                "currency": (meta.get("currency") or "USD").upper(),
            }
        except Exception as exc:                      # noqa: BLE001
            print(f"[quotes] {ticker}: failed: {exc}", file=sys.stderr)
            continue

    missing = [t for t in tickers if t.upper() not in out]
    if missing:
        print(f"[quotes] no price for {missing}", file=sys.stderr)
    return out


def fetch_fx_rate(currency: str) -> float | None:
    """
    USD per 1 unit of `currency`, via Yahoo's "<CUR>USD=X" pairs — same
    endpoint and UA as [fetch_quotes], just a different symbol shape. USD
    itself is free (rate 1.0, no request). Returns None on any failure, and
    the caller treats that exactly like an unpriced ticker: skip the person
    that pass rather than publish a number computed with a guessed rate.
    """
    if currency == "USD":
        return 1.0
    pair = f"{currency}USD=X"
    url = (
        "https://query2.finance.yahoo.com/v8/finance/chart/"
        f"{urllib.parse.quote(pair)}?range=1d&interval=1d"
    )
    try:
        payload = get_json(url, headers={"User-Agent": YAHOO_QUOTE_UA})
        rate = payload["chart"]["result"][0]["meta"]["regularMarketPrice"]
        return float(rate)
    except Exception as exc:                          # noqa: BLE001
        print(f"[fx] {pair}: failed: {exc}", file=sys.stderr)
        return None


def price_quotes_in_usd(raw: dict[str, dict]) -> dict[str, float]:
    """
    [fetch_quotes]'s ticker -> {"price","currency"} map, converted to a
    plain ticker -> USD-price map — what net_worth() actually consumes.
    Fetches each distinct non-USD currency's rate once per pass regardless
    of how many tickers share it (e.g. two EUR-listed holdings cost one FX
    request, not two). A ticker whose currency's rate can't be fetched is
    left out entirely — net_worth() already treats a missing ticker as
    "skip this person this pass", so a bad FX rate degrades the same
    honest way a bad stock quote does, never as a silently wrong number.
    """
    currencies = {q["currency"] for q in raw.values()}
    rates = {c: fetch_fx_rate(c) for c in currencies}

    out: dict[str, float] = {}
    for ticker, q in raw.items():
        rate = rates.get(q["currency"])
        if rate is None:
            print(f"[fx] {ticker}: no {q['currency']}->USD rate, dropping quote", file=sys.stderr)
            continue
        out[ticker] = q["price"] * rate
    return out


def fetch_ticker_history(ticker: str, range_: str = "7d", interval: str = "15m") -> list[tuple[int, float]]:
    """
    Real historical (timestamp, close) pairs for one ticker over `range_`,
    via the same Yahoo chart endpoint as [fetch_quotes] with a wider range
    instead of "1d". Used only once per person — see [backfill_history] —
    to seed real price history instead of a flat line. Best-effort: a
    newly-IPO'd ticker or an unsupported symbol just comes back empty and
    that person's history starts from nothing, same as before this existed.
    """
    url = (
        "https://query2.finance.yahoo.com/v8/finance/chart/"
        f"{urllib.parse.quote(ticker)}?range={range_}&interval={interval}"
    )
    try:
        payload = get_json(url, headers={"User-Agent": YAHOO_QUOTE_UA})
        result = payload["chart"]["result"][0]
        timestamps = result.get("timestamp") or []
        closes = ((result.get("indicators") or {}).get("quote") or [{}])[0].get("close") or []
        return [
            (int(t), float(c)) for t, c in zip(timestamps, closes) if c is not None
        ]
    except Exception as exc:                          # noqa: BLE001
        print(f"[history] {ticker}: backfill failed: {exc}", file=sys.stderr)
        return []


def _nearest_by_time(series: list[tuple[int, float]], t: int) -> float | None:
    """Value from `series` (sorted ascending by timestamp) closest to `t`."""
    if not series:
        return None
    times = [s[0] for s in series]
    i = bisect.bisect_left(times, t)
    if i == 0:
        return series[0][1]
    if i == len(series):
        return series[-1][1]
    before, after = series[i - 1], series[i]
    return before[1] if (t - before[0]) <= (after[0] - t) else after[1]


def backfill_history(subject: Subject, now: int) -> list[dict]:
    """
    Seeds a brand-new person's price history with ~7 real days of it in one
    shot, computed from each holding's own historical closes (converted to
    USD via the current FX rate — good enough for a one-time backfill; a
    currency's rate barely moves pass-to-pass, let alone day-to-day, so
    this is a documented approximation, not a guess dressed up as one).
    Without this, a person added today would show a flat line for a week
    until real-time samples slowly filled the window in — exactly the "app
    opens and it's already working" bar this project holds everything else
    to (see the README's "Embedded Seed Snapshot").

    Runs once, ever, per person (only when they're not already in
    state["history"]) — a one-time cost, not a per-pass one.
    """
    if not subject.holdings:
        # Pure private-stake/cash person: no public price series to backfill
        # from. One honest starting point beats a fabricated week of them.
        return []

    per_ticker: dict[str, list[tuple[int, float]]] = {}
    currencies: dict[str, str] = {}
    for h in subject.holdings:
        raw_ticker = h.ticker.upper()
        series = fetch_ticker_history(raw_ticker)
        if not series:
            continue
        per_ticker[raw_ticker] = series
        # Reuse fetch_quotes' single-point call just for the currency tag —
        # cheap (same endpoint, "1d" range) and avoids guessing a currency.
        quote = fetch_quotes([raw_ticker]).get(raw_ticker)
        currencies[raw_ticker] = (quote or {}).get("currency", "USD")

    primary = subject.holdings[0].ticker.upper()
    base = per_ticker.get(primary)
    if not base:
        return []

    fx_cache: dict[str, float | None] = {}
    out: list[dict] = []
    for t, close in base:
        total = 0.0
        ok = True
        for h in subject.holdings:
            ticker = h.ticker.upper()
            if ticker == primary:
                price = close
            else:
                price = _nearest_by_time(per_ticker.get(ticker) or [], t)
            if price is None:
                ok = False
                break
            currency = currencies.get(ticker, "USD")
            if currency not in fx_cache:
                fx_cache[currency] = fetch_fx_rate(currency)
            rate = fx_cache[currency]
            if rate is None:
                ok = False
                break
            total += h.shares * price * rate
        if not ok:
            continue
        total += subject.private_stakes_usd + subject.cash_usd - subject.liabilities_usd
        out.append({"t": t, "v": total})

    return out


# ---------------------------------------------------------------- net worth


@dataclass
class Holding:
    ticker: str
    shares: float


@dataclass
class Subject:
    id: str
    name: str
    company: str
    holdings: list[Holding] = field(default_factory=list)
    private_stakes_usd: float = 0.0   # last-round marks, maintained by hand
    cash_usd: float = 0.0
    liabilities_usd: float = 0.0
    icao_hex: str | None = None
    tail: str | None = None
    tail_verified: bool = False
    mmsi: str | None = None
    vessel_name: str | None = None
    vessel_verified: bool = False
    news_query: str | None = None
    social_url: str | None = None   # link-out only, see holdings.example.json
    wikipedia_title: str | None = None   # defaults to name.replace(" ", "_")
    birth_date: str | None = None   # "YYYY-MM-DD" — static fact, age is computed client-side
    residence: str | None = None    # city/region only, never an address — see holdings.example.json
    bio: str | None = None          # birthplace + last school attended, one/two sentences — see holdings.example.json


def net_worth(subject: Subject, prices: dict[str, float]) -> float | None:
    """
    Sum(shares x price) + private marks + cash - liabilities.

    Returns None if any public holding is unpriced — a partial number is worse
    than no number, because it silently understates and nobody notices.
    """
    total = 0.0
    for h in subject.holdings:
        price = prices.get(h.ticker.upper())
        if price is None:
            return None
        total += h.shares * price
    return total + subject.private_stakes_usd + subject.cash_usd - subject.liabilities_usd


# ---------------------------------------------------------------- flights


def load_airports() -> list[dict]:
    """Optional. Drop OurAirports' airports.csv beside this file to get ICAO codes."""
    if not os.path.exists(AIRPORTS_PATH):
        return []
    out = []
    with open(AIRPORTS_PATH, newline="", encoding="utf-8") as fh:
        for row in csv.DictReader(fh):
            kind = row.get("type") or ""
            if kind not in ("large_airport", "medium_airport", "small_airport"):
                continue
            ident = row.get("ident") or ""
            try:
                lat = float(row["latitude_deg"])
                lon = float(row["longitude_deg"])
            except (KeyError, TypeError, ValueError):
                continue
            out.append({
                "ident": ident,
                "lat": lat,
                "lon": lon,
                "kind": kind,
                "name": row.get("name") or "",
                "municipality": row.get("municipality") or "",
                "iso_country": row.get("iso_country") or "",
                "iso_region": row.get("iso_region") or "",
            })
    return out


def airport_label(row: dict) -> str:
    """
    A person, not an ICAO code. "Austin, TX" beats "KAUS" for anyone who
    doesn't already know what KAUS means.
    """
    city = row.get("municipality") or ""
    country = row.get("iso_country") or ""
    if country == "US" and row.get("iso_region"):
        region = row["iso_region"].split("-")[-1]
        if city:
            return f"{city}, {region}"
        return f"{row.get('name') or row['ident']}, {region}"
    if city and country:
        return f"{city}, {country}"
    return row.get("name") or row["ident"]


def nearest_airport(airports, lat: float, lon: float) -> str | None:
    best, best_d = None, 1e18
    for row in airports:
        d = (row["lat"] - lat) ** 2 + ((row["lon"] - lon) * math.cos(math.radians(lat))) ** 2
        if d < best_d:
            best, best_d = row["ident"], d
    # ~0.45 degrees; beyond that we are guessing, so we say nothing.
    return best if best_d < 0.2 else None


def _haversine_nm(lat1, lon1, lat2, lon2) -> float:
    r = 3440.065  # nautical miles
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dlambda / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def _bearing_deg(lat1, lon1, lat2, lon2) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dlambda = math.radians(lon2 - lon1)
    x = math.sin(dlambda) * math.cos(p2)
    y = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dlambda)
    return math.degrees(math.atan2(x, y)) % 360.0


def _angular_diff(a: float, b: float) -> float:
    return abs((a - b + 180.0) % 360.0 - 180.0)


# Tunable, deliberately conservative — a wrong guess is worse than none.
HEADING_CONE_DEG = 12.0
HEADING_MIN_NM = 40.0
HEADING_MAX_NM = 3000.0


def estimate_heading_destination(
    airports, lat: float, lon: float, track_deg: float, exclude_icao: str | None = None
) -> str | None:
    """
    Best-effort guess at where an airborne aircraft is headed.

    Raw ADS-B carries no destination field at all — this is a projection from
    current position and course against known airports, nothing more. It picks
    the nearest large/medium airport whose bearing from the aircraft is within
    HEADING_CONE_DEG of the current track. Label it as an estimate everywhere
    it's shown; it will sometimes be wrong, and it should be — a filed flight
    plan we can't see is the actual answer.
    """
    best, best_d = None, 1e18
    for row in airports:
        if row["kind"] not in ("large_airport", "medium_airport"):
            continue
        ident = row["ident"]
        if exclude_icao and ident == exclude_icao:
            continue
        dist = _haversine_nm(lat, lon, row["lat"], row["lon"])
        if dist < HEADING_MIN_NM or dist > HEADING_MAX_NM:
            continue
        bearing = _bearing_deg(lat, lon, row["lat"], row["lon"])
        if _angular_diff(bearing, track_deg) > HEADING_CONE_DEG:
            continue
        if dist < best_d:
            best, best_d = ident, dist
    return best


def _fetch_readsb_style(url: str, icao_hex: str, source_label: str) -> dict | None:
    """
    Shared parser for adsb.lol's `{"ac": [...]}` ("readsb"/tar1090) response
    shape — the same source format the client's `parseReadsbAircraft`
    (LiveTracking.kt) already reads. Returns the matching aircraft's raw
    record (alt_baro/lat/lon/track — the same keys [flight_status] already
    expects from [fetch_aircraft]), or None if this source has nothing for
    this hex right now. One request per call, never a tight loop — be a good
    citizen to these free community feeds.
    """
    try:
        data = get_json(url)
    except Exception as exc:                      # noqa: BLE001
        print(f"[{source_label}] {icao_hex}: {exc}", file=sys.stderr)
        return None
    for entry in data.get("ac") or []:
        if str(entry.get("hex", "")).lower() == icao_hex.lower():
            return entry
    return None


def _fetch_opensky(icao_hex: str) -> dict | None:
    """
    OpenSky Network's `/states/all`, scoped to one icao24 — the same public
    schema the client's [OpenSkyClient] already relies on (index 5/6 =
    lon/lat, 8 = on_ground, 10 = true track). Adapted into the same
    alt_baro/lat/lon/track shape every other source in [fetch_aircraft]
    returns, so [flight_status] doesn't need to know which source actually
    answered.
    """
    try:
        data = get_json(f"https://opensky-network.org/api/states/all?icao24={icao_hex.lower()}")
    except Exception as exc:                      # noqa: BLE001
        print(f"[opensky] {icao_hex}: {exc}", file=sys.stderr)
        return None
    states = data.get("states") or []
    if not states:
        return None
    row = states[0]
    lon, lat, on_ground, track = row[5], row[6], row[8], row[10]
    if lat is None or lon is None:
        return None
    return {"lat": lat, "lon": lon, "alt_baro": "ground" if on_ground else None, "track": track}


def fetch_aircraft(icao_hex: str) -> dict | None:
    """
    Two free, keyless sources, tried in order, stopping at the first one
    that actually has this aircraft right now — the same redundancy the
    client already applies to the live in-app position dot (see
    `LiveFlightTracker` in LiveTracking.kt), extended here to the source that
    matters more: this function's return value gets permanently folded into
    every person's persisted 7-day locationBreakdown/recentStops history
    below, so a single source's transient gap or rate limit used to become a
    permanent "NO_SIGNAL" slice nothing could ever fix retroactively once it
    was written. adsb.lol stays first since it's the proven, already-working
    source for the rest of the roster — a healthy poll still costs exactly
    the one request it always has; OpenSky only fires when it comes up empty.

    A third source, airplanes.live, used to sit here too. Their public
    `/v2/hex/` endpoint has since locked down (returns a flat 403 telling
    integrators to contact them for approved access — confirmed against
    other open-source trackers hitting the exact same wall in 2026, not just
    a transient block), so it never succeeds anymore. Keeping it in the
    chain cost a wasted request and a stderr line on every single miss for
    no actual redundancy, so it's removed rather than left as dead weight.
    If they ever reopen public access, `_fetch_readsb_style` still works
    unchanged for their response shape — just add a third call back in here.
    """
    ac = _fetch_readsb_style(f"https://api.adsb.lol/v2/hex/{icao_hex.lower()}", icao_hex, "adsb")
    if ac is not None:
        return ac

    return _fetch_opensky(icao_hex)


def flight_status(subject: Subject, prev: dict, airports, now: int) -> dict | None:
    """
    - state and departure are live, straight off ADS-B
    - arrival is filled in once the aircraft is on the ground — that's the
      earliest this data source can know it, not a deliberate delay
    - while airborne, estimatedDestinationIcao is a live course-based guess,
      not a filed flight plan (see estimate_heading_destination)
    - currentAirportIcao and recentStops track parked location at airport
      granularity over a trailing 7 days — never a coordinate or a live trail
    - the live position is never included in the payload at all; the client
      links out to a map instead
    """
    if not subject.icao_hex:
        return None

    ac = fetch_aircraft(subject.icao_hex)
    memory = prev.get(subject.icao_hex, {})

    alt = ac.get("alt_baro") if ac else None
    on_ground = (alt == "ground") or (isinstance(alt, (int, float)) and alt < 500)
    state = "UNKNOWN" if ac is None else ("ON_GROUND" if on_ground else "AIRBORNE")

    lat = ac.get("lat") if ac else None
    lon = ac.get("lon") if ac else None
    track = ac.get("track") if ac else None
    here = nearest_airport(airports, lat, lon) if (airports and lat and lon) else None

    was = memory.get("state")
    stops: list[dict] = memory.setdefault("stops", [])

    if state == "AIRBORNE" and was != "AIRBORNE":
        memory["departed_icao"] = memory.get("last_ground_icao") or here
        memory["departed_at"] = now
        memory["arrived_icao"] = None
        memory["arrived_at"] = None
        # Close whichever stop was open — the plane just left it.
        if stops and stops[-1].get("departed") is None:
            stops[-1]["departed"] = now

    if state == "ON_GROUND":
        if was == "AIRBORNE":
            memory["arrived_icao"] = here
            memory["arrived_at"] = now
        memory["last_ground_icao"] = here or memory.get("last_ground_icao")

        # Open a new stop on arrival, and also on the very first observation —
        # we can't know the true arrival time for a plane already parked when
        # the worker started, so "now" is the honest starting point.
        no_open_stop = not stops or stops[-1].get("departed") is not None
        if here and (was == "AIRBORNE" or (was is None and no_open_stop)):
            stops.append({"icao": here, "arrived": now, "departed": None})

    # Trailing 7 days only. A stop still counts if it's still open, or was
    # active at any point inside the window.
    cutoff = now - HISTORY_WINDOW_SECONDS
    memory["stops"] = [
        s for s in stops if s.get("departed") is None or s["departed"] >= cutoff
    ]

    # --- time-share breakdown -------------------------------------------
    # One sample per pass, no extra network call — this is bookkeeping on
    # data already fetched above. Every second in the tracked window gets
    # attributed to exactly one bucket: an airport ICAO, IN_FLIGHT, or (only
    # when nothing at all is known yet) NO_SIGNAL.
    if state == "ON_GROUND":
        bucket = here or "UNKNOWN_AIRPORT"
    elif state == "AIRBORNE":
        bucket = "IN_FLIGHT"
    elif was == "ON_GROUND":
        # None of the three ADS-B sources have this aircraft right now, but
        # it was last confirmed parked. By far the most likely explanation
        # is a grounded aircraft's transponder being off, not a silent
        # takeoff with nobody hearing it — the same "stays there until
        # proven otherwise" assumption memory["last_ground_icao"] above
        # already relies on. So this time is attributed to wherever it was
        # last seen rather than a bare "no signal".
        bucket = memory.get("last_ground_icao") or "UNKNOWN_AIRPORT"
    elif was == "AIRBORNE":
        # Last confirmed airborne with no landing recorded since — an
        # ADS-B coverage gap mid-flight (rural or oceanic dead zones are
        # routine) is far likelier than the aircraft vanishing, so this
        # stays IN_FLIGHT: still the same leg, not a third state.
        bucket = "IN_FLIGHT"
    else:
        # This aircraft has never once been located — nothing to carry
        # forward to, so "no signal" is an honest description here, not a
        # fallback covering for a gap in an otherwise-known trajectory.
        bucket = "NO_SIGNAL"

    samples: list[dict] = memory.setdefault("samples", [])
    # One-time retroactive cleanup: an earlier version of this worker
    # recorded a "NO_SIGNAL" sample every pass even before this aircraft was
    # ever confirmed anywhere, so a plane that's simply never been caught by
    # any of the three ADS-B sources could still show a card reading "100%
    # no signal". Since bucket only ever becomes "NO_SIGNAL" when nothing has
    # ever been confirmed (see the else branch above), any such entry still
    # sitting in an old samples list is exactly that dead weight — drop it so
    # existing bad history clears immediately instead of aging out over the
    # next 7 days.
    samples[:] = [s for s in samples if s["bucket"] != "NO_SIGNAL"]

    if bucket == "NO_SIGNAL":
        # Nothing has ever been confirmed for this aircraft — don't even
        # start the tracking clock. Leaving samples empty means
        # trackedSeconds stays 0, which is exactly the signal the client
        # already uses to hide this card entirely (see PersonDetailScreen's
        # `if (flight.trackedSeconds > 0)` gate) instead of showing a
        # misleading "100% no signal" strip for a plane that was simply
        # never heard.
        pass
    else:
        samples.append({"t": now, "bucket": bucket})

    # Prune to the window, but carry forward whatever bucket was active going
    # into it, so the time between `cutoff` and the first kept sample is
    # still attributed to something real rather than silently dropped.
    carry_bucket = None
    for s in samples:
        if s["t"] <= cutoff:
            carry_bucket = s["bucket"]
        else:
            break
    kept = [s for s in samples if s["t"] > cutoff]
    if carry_bucket is not None:
        kept.insert(0, {"t": cutoff, "bucket": carry_bucket})
    memory["samples"] = kept

    totals: dict[str, int] = {}
    for i in range(len(kept)):
        start = kept[i]["t"]
        end = kept[i + 1]["t"] if i + 1 < len(kept) else now
        totals[kept[i]["bucket"]] = totals.get(kept[i]["bucket"], 0) + max(0, end - start)

    tracked_seconds = now - kept[0]["t"] if kept else 0
    location_breakdown = sorted(
        ({"bucket": k, "seconds": v} for k, v in totals.items() if v > 0),
        key=lambda x: -x["seconds"],
    )
    # ----------------------------------------------------------------------

    if state != "UNKNOWN":
        memory["state"] = state
        memory["last_seen"] = now
    prev[subject.icao_hex] = memory

    airborne = state == "AIRBORNE"

    estimate = None
    if airborne and airports and lat is not None and lon is not None and isinstance(track, (int, float)):
        estimate = estimate_heading_destination(
            airports, lat, lon, float(track), exclude_icao=memory.get("departed_icao")
        )

    return {
        "tail": subject.tail or subject.icao_hex.upper(),
        "icaoHex": subject.icao_hex.lower(),
        "state": state,
        "verified": bool(subject.tail_verified),
        "departedIcao": memory.get("departed_icao"),
        "departedAtEpoch": memory.get("departed_at"),
        "arrivedIcao": memory.get("arrived_icao"),
        "arrivedAtEpoch": memory.get("arrived_at"),
        "estimatedDestinationIcao": estimate,
        # 0, not `now`, when this aircraft has never once been confirmed —
        # the client's FlightStatus.lastSeenEpoch treats 0 as "genuinely
        # never seen" and hides the "Last seen" row entirely for it
        # (PersonDetailScreen.kt: `if (flight.lastSeenEpoch > 0)`).
        # memory.get("last_seen", now) used to default to *this pass's*
        # timestamp when the key was never set, which silently reported
        # "last seen just now" every single run for a plane that had never
        # actually been detected — decreasing back toward "just now" each
        # time the worker ran, looking exactly like a real, moving sighting
        # instead of the "nothing ever found" it actually was.
        "lastSeenEpoch": memory.get("last_seen", 0),
        "liveMapUrl": f"https://globe.adsbexchange.com/?icao={subject.icao_hex.lower()}",
        # Airport granularity only — see AirportStop in Models.kt for why.
        # Read off the open stop itself, not arrived_icao — arrived_icao is
        # only ever set on an AIRBORNE->ON_GROUND transition, so it stays
        # empty for a plane that was already parked when the worker started.
        "currentAirportIcao": (
            memory["stops"][-1]["icao"]
            if state == "ON_GROUND" and memory["stops"] and memory["stops"][-1].get("departed") is None
            else None
        ),
        "recentStops": [
            {"icao": s["icao"], "arrivedAtEpoch": s["arrived"], "departedAtEpoch": s.get("departed")}
            for s in reversed(memory["stops"])
        ],
        "locationBreakdown": [
            {"bucket": b["bucket"], "seconds": b["seconds"]} for b in location_breakdown
        ],
        "trackedSeconds": tracked_seconds,
    }


# ---------------------------------------------------------------- vessels


def load_ports() -> list[dict]:
    """
    Optional. Drop a ports.csv beside this file to get port names instead of
    bare UN/LOCODE-style identifiers. Expected columns:

        unlocode,name,municipality,iso_country,lat,lon

    There's no single free file in exactly this shape the way OurAirports'
    airports.csv covers every airport — but you don't need every port on
    Earth, only the handful your tracked yachts actually visit (marinas and
    harbours a superyacht cycles through number in the dozens, not
    thousands). Hand-curating this list from UN/LOCODE + the World Port
    Index (both free, NGA/UNECE-published) is realistic; see the README.
    """
    if not os.path.exists(PORTS_PATH):
        return []
    out = []
    with open(PORTS_PATH, newline="", encoding="utf-8") as fh:
        for row in csv.DictReader(fh):
            code = row.get("unlocode") or ""
            try:
                lat = float(row["lat"])
                lon = float(row["lon"])
            except (KeyError, TypeError, ValueError):
                continue
            if not code:
                continue
            out.append({
                "unlocode": code,
                "lat": lat,
                "lon": lon,
                "name": row.get("name") or "",
                "municipality": row.get("municipality") or "",
                "iso_country": row.get("iso_country") or "",
            })
    return out


def port_label(row: dict) -> str:
    """Same idea as airport_label() — "Fort Lauderdale, FL" beats "USFLL"."""
    city = row.get("municipality") or ""
    country = row.get("iso_country") or ""
    if city and country:
        return f"{city}, {country}"
    return row.get("name") or row["unlocode"]


def nearest_port(ports, lat: float, lon: float) -> str | None:
    best, best_d = None, 1e18
    for row in ports:
        d = (row["lat"] - lat) ** 2 + ((row["lon"] - lon) * math.cos(math.radians(lat))) ** 2
        if d < best_d:
            best, best_d = row["unlocode"], d
    # Coastline is sparser than airports, so a bit more slack than the
    # aircraft version's 0.2 — beyond this we are guessing, so we say nothing.
    return best if best_d < 0.5 else None


def read_ais_cache() -> dict:
    """
    Latest known position per MMSI, written by ais_listener.py. This worker
    never opens a network connection for AIS — see that file's docstring for
    why a WebSocket stream doesn't fit the poll-once-a-minute shape the rest
    of this script uses, and how the two processes hand off through this
    file instead. Missing file just means the listener isn't running yet —
    every vessel reads as no signal until it is, same as a missing
    airports.csv just means airports show as bare codes.
    """
    if not os.path.exists(AIS_CACHE_PATH):
        return {}
    try:
        with open(AIS_CACHE_PATH, encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, json.JSONDecodeError) as exc:
        print(f"[ais] couldn't read {AIS_CACHE_PATH}: {exc}", file=sys.stderr)
        return {}


def vessel_status(subject: Subject, prev: dict, ports, ais_cache: dict, now: int) -> dict | None:
    """
    The maritime mirror of flight_status() — see VesselStatus in Models.kt
    for the privacy rationale (port granularity only, self-reported
    destination clearly flagged as unverified). The state machine below
    (stop open/close, 7-day pruning) is deliberately the same shape as the
    aircraft version, just swapping "on the ground" for "in port" and
    "airborne" for "underway".
    """
    if not subject.mmsi:
        return None

    entry = ais_cache.get(subject.mmsi)
    fresh = bool(entry) and (now - int(entry.get("timestamp", 0)) < VESSEL_STALE_SECONDS)
    memory = prev.get(subject.mmsi, {})

    lat = entry.get("lat") if fresh else None
    lon = entry.get("lon") if fresh else None
    sog = entry.get("sog") if fresh else None  # knots
    destination = (entry.get("destination") or "").strip() if fresh else None

    # A moored/anchored vessel still shows a little drift in AIS SOG — 0.5kt
    # is the conventional "not really moving" cutoff most trackers use.
    if not fresh:
        state = "UNKNOWN"
    elif isinstance(sog, (int, float)) and sog < 0.5:
        state = "IN_PORT"
    else:
        state = "UNDERWAY"

    here = nearest_port(ports, lat, lon) if (ports and lat is not None and lon is not None) else None

    was = memory.get("state")
    stops: list[dict] = memory.setdefault("stops", [])

    if state == "UNDERWAY" and was != "UNDERWAY":
        memory["departed_port"] = memory.get("last_port") or here
        memory["departed_at"] = now
        memory["arrived_port"] = None
        memory["arrived_at"] = None
        if stops and stops[-1].get("departed") is None:
            stops[-1]["departed"] = now

    if state == "IN_PORT":
        if was == "UNDERWAY":
            memory["arrived_port"] = here
            memory["arrived_at"] = now
        memory["last_port"] = here or memory.get("last_port")

        no_open_stop = not stops or stops[-1].get("departed") is not None
        if here and (was == "UNDERWAY" or (was is None and no_open_stop)):
            stops.append({"unlocode": here, "arrived": now, "departed": None})

    cutoff = now - HISTORY_WINDOW_SECONDS
    memory["stops"] = [
        s for s in stops if s.get("departed") is None or s["departed"] >= cutoff
    ]

    # --- time-share breakdown -------------------------------------------
    # Exact mirror of flight_status()'s version — same field names
    # (locationBreakdown/trackedSeconds), same bucketing/pruning/carry-
    # forward logic, so the client's existing donut chart can plug a boat
    # card into it the same way it already does a plane card, rather than
    # needing a second chart. Only the bucket vocabulary is vessel-specific:
    # a port UNLOCODE, "UNDERWAY" (the "not parked anywhere" bucket — the
    # vessel equivalent of a flight's IN_FLIGHT), "UNKNOWN_PORT", or (only
    # when nothing at all is known yet) "NO_SIGNAL". AIS coverage is far
    # patchier than ADS-B's — a private yacht is only ever heard when it's
    # near a receiver aisstream.io's network happens to cover — so without
    # this carry-forward a boat sitting quietly at anchor between sparse
    # receiver passes would misleadingly read as mostly "no signal" instead
    # of mostly "in port".
    if state == "IN_PORT":
        bucket = here or "UNKNOWN_PORT"
    elif state == "UNDERWAY":
        bucket = "UNDERWAY"
    elif was == "IN_PORT":
        # No fresh AIS message this pass, but last confirmed moored — a
        # vessel doesn't quietly slip away unheard, so this time is
        # attributed to wherever it was last seen rather than "no signal".
        bucket = memory.get("last_port") or "UNKNOWN_PORT"
    elif was == "UNDERWAY":
        # Last confirmed underway with no arrival recorded since — a gap
        # in receiver coverage mid-transit, not a disappearance, so this
        # stays UNDERWAY: still the same passage, not a third state.
        bucket = "UNDERWAY"
    else:
        # This vessel has never once been located — nothing to carry
        # forward to, so "no signal" is an honest description here, not a
        # fallback covering for a gap in an otherwise-known trajectory.
        bucket = "NO_SIGNAL"

    samples: list[dict] = memory.setdefault("samples", [])
    # Same one-time retroactive cleanup as flight_status() — drop any
    # already-persisted "NO_SIGNAL" samples so a boat that's simply never
    # been heard on AIS clears immediately instead of aging out of the
    # 7-day window on its own.
    samples[:] = [s for s in samples if s["bucket"] != "NO_SIGNAL"]

    if bucket == "NO_SIGNAL":
        # Nothing has ever been confirmed for this vessel — don't start the
        # tracking clock. trackedSeconds stays 0, which is what the client
        # already gates the card's visibility on (see
        # PersonDetailScreen's `if (vessel.trackedSeconds > 0)`), instead of
        # showing a misleading "100% no signal" strip for a boat that was
        # simply never heard.
        pass
    else:
        samples.append({"t": now, "bucket": bucket})

    carry_bucket = None
    for s in samples:
        if s["t"] <= cutoff:
            carry_bucket = s["bucket"]
        else:
            break
    kept = [s for s in samples if s["t"] > cutoff]
    if carry_bucket is not None:
        kept.insert(0, {"t": cutoff, "bucket": carry_bucket})
    memory["samples"] = kept

    totals: dict[str, int] = {}
    for i in range(len(kept)):
        start = kept[i]["t"]
        end = kept[i + 1]["t"] if i + 1 < len(kept) else now
        totals[kept[i]["bucket"]] = totals.get(kept[i]["bucket"], 0) + max(0, end - start)

    tracked_seconds = now - kept[0]["t"] if kept else 0
    location_breakdown = sorted(
        ({"bucket": k, "seconds": v} for k, v in totals.items() if v > 0),
        key=lambda x: -x["seconds"],
    )
    # ----------------------------------------------------------------------

    if state != "UNKNOWN":
        memory["state"] = state
        memory["last_seen"] = now
    prev[subject.mmsi] = memory

    return {
        "name": subject.vessel_name or subject.mmsi,
        "mmsi": subject.mmsi,
        "state": state,
        "verified": bool(subject.vessel_verified),
        "departedPortUnlocode": memory.get("departed_port"),
        "departedAtEpoch": memory.get("departed_at"),
        "arrivedPortUnlocode": memory.get("arrived_port"),
        "arrivedAtEpoch": memory.get("arrived_at"),
        # Crew-entered AIS Message 5 free text, not a computed estimate and
        # not verified — routinely blank, stale, or informal shorthand. The
        # client labels it as such; we don't clean it up into looking more
        # authoritative than it is.
        "selfReportedDestination": destination or None,
        # Same fix as flight_status() — 0, not `now`, when never confirmed.
        # See the comment there for why the old `now` default silently
        # reported a fake, ever-decreasing "last seen" for a vessel that had
        # never actually been heard on AIS.
        "lastSeenEpoch": memory.get("last_seen", 0),
        "liveMapUrl": f"https://www.marinetraffic.com/en/ais/details/ships/mmsi:{subject.mmsi}",
        "currentPortUnlocode": (
            memory["stops"][-1]["unlocode"]
            if state == "IN_PORT" and memory["stops"] and memory["stops"][-1].get("departed") is None
            else None
        ),
        "recentStops": [
            {
                "unlocode": s["unlocode"],
                "arrivedAtEpoch": s["arrived"],
                "departedAtEpoch": s.get("departed"),
            }
            for s in reversed(memory["stops"])
        ],
        "locationBreakdown": [
            {"bucket": b["bucket"], "seconds": b["seconds"]} for b in location_breakdown
        ],
        "trackedSeconds": tracked_seconds,
    }


# ---------------------------------------------------------------- news


def fetch_news(query: str, limit: int = 4) -> list[dict]:
    url = (
        "https://news.google.com/rss/search?q="
        + urllib.parse.quote(f'"{query}"')
        + "&hl=en-GB&gl=GB&ceid=GB:en"
    )
    try:
        root = ET.fromstring(get(url))
    except Exception as exc:                      # noqa: BLE001
        print(f"[news] {query}: {exc}", file=sys.stderr)
        return []

    items = []
    for node in root.iterfind(".//item"):
        title = (node.findtext("title") or "").strip()
        link = (node.findtext("link") or "").strip()
        source = (node.findtext("source") or "").strip() or "Google News"
        pub = node.findtext("pubDate")
        try:
            from email.utils import parsedate_to_datetime
            published = int(parsedate_to_datetime(pub).timestamp()) if pub else 0
        except Exception:                          # noqa: BLE001
            published = 0
        if title and link:
            items.append(
                {"title": title, "source": source, "url": link, "publishedEpoch": published}
            )
        if len(items) >= limit:
            break
    return items


# ---------------------------------------------------------------- wikipedia


def fetch_wikipedia_info(title: str) -> dict | None:
    """
    The free, keyless Wikipedia REST summary endpoint. One request per person,
    refreshed roughly monthly (WIKI_REFRESH_SECONDS) since a photo and an
    article link barely ever change — this is not a per-pass cost.

    Royalty-free by construction, not by hope: Wikipedia's own hosting rules
    put non-free "fair use" images on the LOCAL /wikipedia/<lang>/ path and
    reserve the /wikipedia/commons/ path exclusively for freely-licensed
    media (Commons deletes anything that isn't). So rather than trust a
    license field, we just check which path the thumbnail actually lives on
    and drop anything that isn't Commons-hosted. If that filters out the
    photo, the person still gets the Wikipedia link with no image.

    Separately: the summary endpoint's "thumbnail" is simply whatever image
    a page's infobox happens to be set to — usually a real photo, but for
    some people (Amancio Ortega among them) that's a coat of arms, flag, or
    seal instead of a portrait. Every one of those is distributed as SVG on
    Wikipedia/Commons; an actual photograph never is. So an SVG thumbnail
    gets dropped the same way a non-Commons one does — same "no image beats
    the wrong image" rule, just catching content rather than licensing.
    """
    url = f"https://en.wikipedia.org/api/rest_v1/page/summary/{urllib.parse.quote(title)}"
    try:
        data = get_json(url)
    except Exception as exc:                      # noqa: BLE001
        print(f"[wikipedia] {title}: {exc}", file=sys.stderr)
        return None

    if data.get("type") == "disambiguation":
        print(f"[wikipedia] {title}: disambiguation page, skipping photo/link", file=sys.stderr)
        return None

    page_url = (data.get("content_urls") or {}).get("desktop", {}).get("page")
    thumb = (data.get("thumbnail") or {}).get("source")

    photo_url = None
    if thumb and "/wikipedia/commons/" not in thumb:
        print(f"[wikipedia] {title}: thumbnail not on Commons, dropping image", file=sys.stderr)
    elif thumb and thumb.lower().split("?")[0].endswith(".svg"):
        print(f"[wikipedia] {title}: thumbnail is a non-photo graphic (SVG), dropping image", file=sys.stderr)
    elif thumb:
        photo_url = thumb

    if not page_url:
        return None

    return {"wikipediaUrl": page_url, "photoUrl": photo_url}


# ---------------------------------------------------------------- build


def load_subjects() -> list[Subject]:
    with open(HOLDINGS_PATH, encoding="utf-8") as fh:
        raw = json.load(fh)
    subjects = []
    for entry in raw["people"]:
        subjects.append(
            Subject(
                id=entry["id"],
                name=entry["name"],
                company=entry.get("company", ""),
                holdings=[Holding(h["ticker"], float(h["shares"])) for h in entry.get("holdings", [])],
                private_stakes_usd=float(entry.get("privateStakesUsd", 0)),
                cash_usd=float(entry.get("cashUsd", 0)),
                liabilities_usd=float(entry.get("liabilitiesUsd", 0)),
                icao_hex=entry.get("icaoHex"),
                tail=entry.get("tail"),
                tail_verified=bool(entry.get("tailVerified", False)),
                mmsi=entry.get("mmsi"),
                vessel_name=entry.get("vesselName"),
                vessel_verified=bool(entry.get("vesselVerified", False)),
                news_query=entry.get("newsQuery") or entry["name"],
                social_url=entry.get("socialUrl"),
                wikipedia_title=entry.get("wikipediaTitle") or entry["name"].replace(" ", "_"),
                birth_date=entry.get("birthDate"),
                residence=entry.get("residence"),
                bio=entry.get("bio"),
            )
        )
    return subjects


def load_state() -> dict:
    if os.path.exists(STATE_PATH):
        with open(STATE_PATH, encoding="utf-8") as fh:
            return json.load(fh)
    return {"pass": 0, "flights": {}, "history": {}, "news": {}, "lastCrossing": None}


def save_state(state: dict) -> None:
    tmp = STATE_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(state, fh)
    os.replace(tmp, STATE_PATH)


CROSSING_HISTORY_LIMIT = 200   # exposed-in-snapshot cap; the stored list is separately
                               # trimmed to a 7-day window in build_snapshot(), see there


def track_crossing(state: dict, subject: Subject, value: float, now: int) -> None:
    """
    Maintains state["crossing_history"] — every time someone has gone over
    (and, if it's ended, come back under) the threshold, not just the
    single most-recent one lastCrossing used to overwrite every pass.

    Each entry is opened the moment a person's value transitions from
    under to at-or-over THRESHOLD_USD, has its heldForSeconds kept current
    every pass while they're still over it, and is left in place (not
    deleted) once they drop back under — ongoing just becomes False. This
    is what makes heldForSeconds actually mean something: the old code
    reset it to a hardcoded 0 every single pass, which never accumulated.

    Identifies the open entry for a subject by its index into
    crossing_history, which is safe because that list is only ever
    appended to here — nothing before it is ever reordered or removed
    (the CROSSING_HISTORY_LIMIT cap is applied only to what's copied into
    the outgoing snapshot, not to this stored list).
    """
    history: list[dict] = state.setdefault("crossing_history", [])
    tracking: dict = state.setdefault("crossing_tracking", {})
    was_over = tracking.get(subject.id, {}).get("over", False)
    is_over = value >= THRESHOLD_USD

    if is_over and not was_over:
        history.append({
            "personId": subject.id,
            "personName": subject.name,
            "crossedAtEpoch": now,
            "heldForSeconds": 0,
            "note": "",
            "ongoing": True,
        })
        tracking[subject.id] = {"over": True, "index": len(history) - 1}
    elif is_over and was_over:
        idx = tracking.get(subject.id, {}).get("index")
        if idx is not None and idx < len(history):
            history[idx]["heldForSeconds"] = now - history[idx]["crossedAtEpoch"]
    elif not is_over and was_over:
        idx = tracking.get(subject.id, {}).get("index")
        if idx is not None and idx < len(history):
            history[idx]["heldForSeconds"] = now - history[idx]["crossedAtEpoch"]
            history[idx]["ongoing"] = False
        tracking[subject.id] = {"over": False}


def build_snapshot() -> dict:
    now = int(time.time())
    subjects = load_subjects()
    state = load_state()
    state["pass"] = state.get("pass", 0) + 1
    passes = state["pass"]

    tickers = sorted({h.ticker.upper() for s in subjects for h in s.holdings})
    prices = price_quotes_in_usd(fetch_quotes(tickers))

    airports = load_airports()
    airports_by_ident = {row["ident"]: row for row in airports}
    used_icaos: set[str] = set()

    ports = load_ports()
    ports_by_code = {row["unlocode"]: row for row in ports}
    used_unlocodes: set[str] = set()
    ais_cache = read_ais_cache()   # one local read per pass, no network call

    history = state.setdefault("history", {})
    news_cache = state.setdefault("news", {})
    wiki_cache = state.setdefault("wikipedia", {})
    opens = state.setdefault("day_open", {})
    today = time.strftime("%Y-%m-%d", time.gmtime(now))
    if state.get("day_open_date") != today:
        opens.clear()
        state["day_open_date"] = today

    people = []
    current_trillionaire = None

    for subject in subjects:
        value = net_worth(subject, prices)
        if value is None:
            print(f"[skip] {subject.id}: unpriced holding", file=sys.stderr)
            continue

        series = history.get(subject.id)
        if not series or not isinstance(series[-1], dict):
            # Either genuinely new, or a leftover history entry in a shape
            # this code no longer writes (e.g. a bare number instead of a
            # {"t","v"} point — this has actually happened for a couple of
            # people whose price history predates the current schema).
            # Both get the same one-time backfill treatment rather than
            # crashing the whole pass on data this code doesn't recognize.
            if series:
                print(
                    f"[history] {subject.id}: discarding malformed history entry, re-backfilling",
                    file=sys.stderr,
                )
            series = backfill_history(subject, now)
            if series:
                print(f"[history] {subject.id}: backfilled {len(series)} points ({HISTORY_WINDOW_SECONDS // 86_400}d)")
        prev_value = series[-1]["v"] if series else value
        prev_t = series[-1]["t"] if series else now
        series.append({"t": now, "v": value})
        cutoff = now - HISTORY_WINDOW_SECONDS
        series = [s for s in series if s["t"] >= cutoff]
        history[subject.id] = series

        if subject.id not in opens:
            # Prefer the value nearest today's UTC midnight from whatever
            # history we have (backfilled or accumulated) so day-over-day
            # change is real from the very first pass of the day, instead
            # of "$0 until tomorrow" just because the worker happened to
            # start mid-day.
            today_start = calendar.timegm(time.strptime(today, "%Y-%m-%d"))
            opens[subject.id] = _nearest_by_time(
                [(s["t"], s["v"]) for s in series], today_start
            ) or value
        day_change = value - opens[subject.id]
        elapsed = max(1, now - prev_t)
        drift = (value - prev_value) / float(elapsed)

        if passes % FLIGHT_EVERY == 0:
            flight = flight_status(subject, state.setdefault("flights", {}), airports, now)
            state.setdefault("flight_payload", {})[subject.id] = flight
        else:
            flight = state.get("flight_payload", {}).get(subject.id)

        if passes % VESSEL_EVERY == 0:
            vessel = vessel_status(subject, state.setdefault("vessels", {}), ports, ais_cache, now)
            state.setdefault("vessel_payload", {})[subject.id] = vessel
        else:
            vessel = state.get("vessel_payload", {}).get(subject.id)

        if subject.news_query and (passes % NEWS_EVERY == 1 or subject.id not in news_cache):
            news_cache[subject.id] = fetch_news(subject.news_query)
        news = news_cache.get(subject.id, [])

        wiki_entry = wiki_cache.get(subject.id)
        # A cached photoUrl that's an SVG predates the content filter above
        # (it could only have gotten in before that check existed) — force
        # a re-fetch regardless of the normal 30-day timer rather than
        # waiting up to a month for a known-wrong cached image to clear.
        wiki_photo_bad = bool(
            wiki_entry
            and wiki_entry.get("photoUrl")
            and wiki_entry["photoUrl"].lower().split("?")[0].endswith(".svg")
        )
        wiki_stale = (
            not wiki_entry
            or wiki_photo_bad
            or (now - wiki_entry.get("fetchedAt", 0)) >= WIKI_REFRESH_SECONDS
        )
        if subject.wikipedia_title and wiki_stale:
            if wiki_photo_bad:
                print(
                    f"[wikipedia] {subject.id}: discarding non-photo (SVG) cached image, re-fetching",
                    file=sys.stderr,
                )
            info = fetch_wikipedia_info(subject.wikipedia_title)
            if info is not None:
                wiki_cache[subject.id] = {**info, "fetchedAt": now}
            elif wiki_entry is None:
                # First attempt failed outright — cache a placeholder so a
                # dead lookup doesn't retry every single pass.
                wiki_cache[subject.id] = {"wikipediaUrl": None, "photoUrl": None, "fetchedAt": now}
        wiki_entry = wiki_cache.get(subject.id, {})

        if flight:
            for k in ("departedIcao", "arrivedIcao", "estimatedDestinationIcao", "currentAirportIcao"):
                if flight.get(k):
                    used_icaos.add(flight[k])
            for stop in flight.get("recentStops") or []:
                used_icaos.add(stop["icao"])
            for share in flight.get("locationBreakdown") or []:
                if share["bucket"] not in ("IN_FLIGHT", "NO_SIGNAL", "UNKNOWN_AIRPORT", "OTHER"):
                    used_icaos.add(share["bucket"])

        if vessel:
            for k in ("departedPortUnlocode", "arrivedPortUnlocode", "currentPortUnlocode"):
                if vessel.get(k):
                    used_unlocodes.add(vessel[k])
            for stop in vessel.get("recentStops") or []:
                used_unlocodes.add(stop["unlocode"])
            for share in vessel.get("locationBreakdown") or []:
                if share["bucket"] not in ("UNDERWAY", "NO_SIGNAL", "UNKNOWN_PORT", "OTHER"):
                    used_unlocodes.add(share["bucket"])

        track_crossing(state, subject, value, now)
        if value >= THRESHOLD_USD and current_trillionaire is None:
            current_trillionaire = subject.id

        people.append(
            {
                "id": subject.id,
                "name": subject.name,
                "company": subject.company,
                "birthDate": subject.birth_date,
                "residence": subject.residence,
                "bio": subject.bio,
                "netWorthUsd": value,
                "driftPerSecondUsd": drift,
                "dayChangeUsd": day_change,
                # Wire format stays a plain list of values, oldest to newest
                # (unchanged from before this file added timestamps
                # internally) — only the retention window changed, from the
                # last 48 raw samples (~4h at this project's real 5-minute
                # polling cadence) to a real rolling 7 days.
                "history": [s["v"] for s in history[subject.id]],
                "flight": flight,
                "vessel": vessel,
                "news": news,
                "socialUrl": subject.social_url,
                "wikipediaUrl": wiki_entry.get("wikipediaUrl"),
                "photoUrl": wiki_entry.get("photoUrl"),
            }
        )

    people.sort(key=lambda p: p["netWorthUsd"], reverse=True)

    # Trim crossing_history to the same rolling 7-day window as everything
    # else — a closed (non-ongoing) entry older than the window is dropped;
    # an ongoing one is kept regardless of age, since it's live status, not
    # just history. (This is the "internal list is never trimmed" gap the
    # CROSSING_HISTORY_LIMIT comment used to admit to — the 200-item cap
    # below only ever bounded what left this function, not what state.json
    # accumulated forever.) crossing_tracking is rebuilt from the trimmed
    # list rather than shifted, since a kept old-but-ongoing entry can sit
    # before a dropped newer-but-closed one — index arithmetic would be
    # wrong there, a fresh rebuild can't be.
    trim_cutoff = now - HISTORY_WINDOW_SECONDS
    stored_crossings = state.get("crossing_history", [])
    kept_crossings = [
        e for e in stored_crossings
        if e.get("ongoing") or e.get("crossedAtEpoch", 0) >= trim_cutoff
    ]
    if len(kept_crossings) != len(stored_crossings):
        state["crossing_history"] = kept_crossings
        state["crossing_tracking"] = {
            e["personId"]: {"over": True, "index": i}
            for i, e in enumerate(kept_crossings)
            if e.get("ongoing")
        }

    save_state(state)

    airports_out = {
        icao: {
            "name": row.get("name") or icao,
            "city": row.get("municipality") or "",
            "country": row.get("iso_country") or "",
            "label": airport_label(row),
            # The airport's own fixed, published reference point — not a
            # live aircraft position. Lets the client's world map drop a
            # pin at a place it already names; see FlightStatus doc
            # comments for why no live coordinate is ever included here.
            "lat": row.get("lat"),
            "lon": row.get("lon"),
        }
        for icao in used_icaos
        if (row := airports_by_ident.get(icao)) is not None
    }

    ports_out = {
        code: {
            "name": row.get("name") or code,
            "city": row.get("municipality") or "",
            "country": row.get("iso_country") or "",
            "label": port_label(row),
            # Fixed port reference point, same rationale as airports_out.
            "lat": row.get("lat"),
            "lon": row.get("lon"),
        }
        for code in used_unlocodes
        if (row := ports_by_code.get(code)) is not None
    }

    crossing_history = state.get("crossing_history", [])[-CROSSING_HISTORY_LIMIT:]

    return {
        "generatedAt": now,
        "nextUpdateIn": POLL_SECONDS,
        "thresholdUsd": THRESHOLD_USD,
        "currentTrillionaireId": current_trillionaire,
        # Kept for anything still reading the single most-recent crossing —
        # now the last (and, if ongoing, still-updating) entry of the real
        # history below, rather than a value some earlier pass reset to 0.
        "lastCrossing": crossing_history[-1] if crossing_history else None,
        "crossingHistory": crossing_history,
        "people": people,
        "airports": airports_out,
        "ports": ports_out,
    }


def main() -> int:
    snapshot = build_snapshot()
    tmp = OUTPUT_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(snapshot, fh, separators=(",", ":"))
    os.replace(tmp, OUTPUT_PATH)

    size = os.path.getsize(OUTPUT_PATH)
    print(f"wrote {OUTPUT_PATH} ({size} bytes, {len(snapshot['people'])} people)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
