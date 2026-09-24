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

# How long flight_status() keeps assuming "still on the same flight" after
# ADS-B goes quiet mid-air, before admitting it's lost the plot. Two
# different triggers, because they answer two different questions:
#   - SIGNAL_LOST_GRACE_SECONDS only fires once there's *also* a recent
#     heading-based guess at where the aircraft was headed (see
#     estimate_heading_destination). A course that clearly pointed at one
#     specific nearby airport, followed by silence well past this grace
#     period, is a landing there we simply didn't catch — not a flight
#     still in progress.
#   - SIGNAL_LOST_HARD_CEILING_SECONDS applies regardless of any estimate —
#     no aircraft in a billionaire's hangar has the range to stay airborne
#     this long on one leg, so past this point "still flying" stops being
#     plausible at all, estimate or not.
# Below both thresholds this still reads IN_FLIGHT, unchanged from before —
# the overwhelming majority of ADS-B gaps are minutes long (rural coverage,
# a brief oceanic hole) and shouldn't trip either trigger.
SIGNAL_LOST_GRACE_SECONDS = 90 * 60          # 90 minutes
SIGNAL_LOST_HARD_CEILING_SECONDS = 14 * 3600  # 14 hours

USER_AGENT = "trillionaire-tracker/0.1 (+https://github.com/Hostu404)"
TIMEOUT = 15

HERE = os.path.dirname(os.path.abspath(__file__))
HOLDINGS_PATH = os.path.join(HERE, "holdings.json")
STATE_PATH = os.path.join(HERE, "state.json")
OUTPUT_PATH = os.path.join(HERE, "snapshot.json")
AIRPORTS_PATH = os.path.join(HERE, "airports.csv")  # optional, OurAirports format
PORTS_PATH = os.path.join(HERE, "ports.csv")         # optional, see load_ports()
AIS_CACHE_PATH = os.path.join(HERE, "ais_cache.json")  # written by ais_listener.py
COUNTRIES_PATH = os.path.join(HERE, "world_countries.json")  # optional, see general_location()

THRESHOLD_USD = 1_000_000_000_000.0
POLL_SECONDS = 60
FLIGHT_EVERY = 1     # passes between ADS-B reads
VESSEL_EVERY = 1     # passes between ais_cache.json reads — it's a local file,
                     # not a network call, so there's no rate-limit reason to
                     # space these out the way FLIGHT_EVERY exists for.
VESSEL_STALE_SECONDS = 6 * 3_600   # cache entry older than this reads as no signal
DEAD_RECKON_MAX_SECONDS = 30 * 60  # how far past the last real fix we'll still
                                    # project forward a display-only estimate —
                                    # see the dead-reckoning block in
                                    # vessel_status() for the full rationale.

# The maritime mirror of SIGNAL_LOST_GRACE_SECONDS/SIGNAL_LOST_HARD_CEILING_SECONDS
# above — same two-trigger shape, deliberately much longer thresholds. AIS
# coverage here is entirely terrestrial (aisstream.io's network is shore/
# island-based receivers, not satellite), so a yacht mid-ocean crossing —
# genuinely underway the whole time, nothing wrong at all — can legitimately
# go a week or more with zero hits simply because no receiver was ever in
# range. Using the flight thresholds here would flag every routine Atlantic
# or Pacific crossing as "signal lost" almost immediately, which is worse
# than the plain "Underway" it already reads as. These are set past what
# even a slow ocean crossing plausibly takes:
#   - VESSEL_SIGNAL_LOST_GRACE_SECONDS only fires once there's *also* a
#     recent heading-based guess at which port the vessel was making for
#     (see estimate_heading_port) — a course that clearly pointed at one
#     specific port, followed by silence well past this grace period, reads
#     as an arrival there that just never got picked up by a receiver.
#   - VESSEL_SIGNAL_LOST_HARD_CEILING_SECONDS applies regardless of any
#     estimate — past this point, continuing to call it "Underway" with no
#     confirmation at all stops being an honest description of what's known.
VESSEL_SIGNAL_LOST_GRACE_SECONDS = 4 * 86_400    # 4 days
VESSEL_SIGNAL_LOST_HARD_CEILING_SECONDS = 10 * 86_400  # 10 days

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
    imo: str | None = None          # second identifier, see fetch_aircraft()'s
                                     # tail fallback for the plane-side mirror
                                     # of this — MMSI can and does change when
                                     # a vessel re-flags/re-registers, but its
                                     # IMO number is permanent for the ship's
                                     # hull, so it's the fallback lookup key
                                     # once ais_listener.py starts recording it
                                     # (see ShipStaticData handling there).
                                     # Not yet used for an active fallback
                                     # lookup path — aisstream.io has no
                                     # IMO-keyed query, unlike adsb.lol/adsb.fi's
                                     # registration lookup — so today this is
                                     # captured and surfaced for cross-referencing
                                     # (e.g. against MarineTraffic/VesselFinder
                                     # by hand) rather than driving code here.
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


def _wrapped_dlon(lon1: float, lon2: float) -> float:
    """
    lon2 - lon1, but taking the shorter way around the globe instead of the
    raw arithmetic difference. Matters once a fix or an airport/port sits
    near the antimeridian (Fiji, the Aleutians, eastern Russia, any Pacific
    crossing at high latitude): a plain subtraction says a point at 179.95°E
    and one at 179.95°W are 359.9 degrees apart, when they're actually 0.1°
    apart. `_haversine_nm`/`_bearing_deg` never had this problem — they feed
    the raw difference through sin()/cos(), which are periodic and self-
    correct — but nearest_airport()/nearest_port()/_nearest_country() do a
    plain flat-Earth (dlat, dlon*cos(lat)) approximation with no trig to
    save them, so they need this normalization explicitly. Without it, a
    real fix within a few nm of an airport/port straddling the date line
    reads as ~130,000 degrees² away and silently fails to match anything.
    """
    d = (lon2 - lon1) % 360.0
    return d - 360.0 if d > 180.0 else d


def nearest_airport(airports, lat: float, lon: float) -> str | None:
    best, best_d = None, 1e18
    for row in airports:
        dlat = row["lat"] - lat
        dlon = _wrapped_dlon(lon, row["lon"]) * math.cos(math.radians(lat))
        d = dlat ** 2 + dlon ** 2
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


def _dead_reckon_nm(lat: float, lon: float, bearing_deg: float, distance_nm: float) -> tuple[float, float]:
    """
    Standard great-circle "destination point given start, bearing and
    distance" formula (the same one behind most GPS/marine-nav dead-
    reckoning tools) — the inverse of what `_haversine_nm`/`_bearing_deg`
    above compute. Used by vessel_status()'s short-horizon dead reckoning
    (see DEAD_RECKON_MAX_SECONDS) to project a vessel's position forward
    from its last real AIS fix using that fix's own reported course/speed.
    Never used for flights: a stationary or airborne aircraft's own live
    position path already covers that case differently (see FlightStatus's
    currentLat doc comment), and this project-forward approach only makes
    sense for a genuinely moving subject with a known heading.
    """
    r_nm = 3440.065
    lat1 = math.radians(lat)
    lon1 = math.radians(lon)
    brng = math.radians(bearing_deg)
    d_r = distance_nm / r_nm
    lat2 = math.asin(
        math.sin(lat1) * math.cos(d_r) + math.cos(lat1) * math.sin(d_r) * math.cos(brng)
    )
    lon2 = lon1 + math.atan2(
        math.sin(brng) * math.sin(d_r) * math.cos(lat1),
        math.cos(d_r) - math.sin(lat1) * math.sin(lat2),
    )
    return math.degrees(lat2), (math.degrees(lon2) + 540.0) % 360.0 - 180.0


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


def estimate_heading_port(
    ports, lat: float, lon: float, course_deg: float, exclude_unlocode: str | None = None
) -> str | None:
    """
    The maritime mirror of estimate_heading_destination() — same cone-search
    idea (nearest thing ahead, within HEADING_CONE_DEG of the current
    course), swapping airports for ports.csv's ~16,666 UN/LOCODE seaports.
    No large/medium-only filter the way the airport version has (ports.csv
    carries no size classification), so this can land on a small harbor as
    easily as a major one — reasonable, since a slow-moving vessel is just as
    likely headed for a small one, and the result is always labeled an
    unconfirmed guess wherever it's shown, never a filed plan.

    Used only to give vessel_status()'s SIGNAL_LOST state something concrete
    to name ("possibly near X") instead of a bare "unconfirmed" — see
    VESSEL_SIGNAL_LOST_GRACE_SECONDS's doc comment for why that state exists
    at all and why it fires so much later than the flight equivalent.
    """
    best, best_d = None, 1e18
    for row in ports:
        code = row["unlocode"]
        if exclude_unlocode and code == exclude_unlocode:
            continue
        dist = _haversine_nm(lat, lon, row["lat"], row["lon"])
        if dist < HEADING_MIN_NM or dist > HEADING_MAX_NM:
            continue
        bearing = _bearing_deg(lat, lon, row["lat"], row["lon"])
        if _angular_diff(bearing, course_deg) > HEADING_CONE_DEG:
            continue
        if dist < best_d:
            best, best_d = code, dist
    return best


def format_ais_eta(eta: dict | None, now: int) -> str | None:
    """
    Turns the raw {month, day, hour, minute} ais_listener.py caches (see its
    apply_message() doc comment for the AIS "not available" sentinels
    already stripped out before it gets here) into a display string, or None
    if there's nothing usable.

    AIS's ETA field has no year at all — a ship broadcasts "Oct 3, 14:30"
    every voyage, indefinitely — so this never invents one; it just formats
    month/day (+ time-of-day, when broadcast) exactly as reported. `now` is
    only used to sanity-check the day against the right month/year length
    (so a broadcast day-31-in-a-30-day-month doesn't slip through); it
    otherwise plays no role, since there's no year to compare against.
    Still just a display convenience, not a correction of what was actually
    broadcast — the crew-entered, routinely-stale nature of this field is
    the caller's caveat to add, not this function's.
    """
    if not isinstance(eta, dict):
        return None
    month, day = eta.get("month"), eta.get("day")
    if not isinstance(month, int) or not (1 <= month <= 12):
        return None
    this_year = time.gmtime(now).tm_year
    # Leap-year-safe for `this_year`; good enough for a Feb-29-in-a-non-leap-
    # year sanity check on a field that's routinely stale or mistyped
    # anyway, not for anything that needs calendar correctness.
    if not isinstance(day, int) or not (1 <= day <= calendar.monthrange(this_year, month)[1]):
        return None

    hour, minute = eta.get("hour"), eta.get("minute")
    date_part = f"{calendar.month_abbr[month]} {day}"
    if isinstance(hour, int) and isinstance(minute, int):
        return f"{date_part}, {hour:02d}:{minute:02d} UTC"
    return date_part


def _fetch_readsb_style(url: str, icao_hex: str, source_label: str) -> dict | None:
    """
    Shared parser for adsb.lol's and adsb.fi's `{"ac": [...]}`
    ("readsb"/tar1090) response shape — the same source format the client's
    `parseReadsbAircraft` (LiveTracking.kt) already reads. Returns the
    matching aircraft's raw record (alt_baro/lat/lon/track — the same keys
    [flight_status] already expects from [fetch_aircraft]), or None if this
    source has nothing for this hex right now. One request per call, never a
    tight loop — be a good citizen to these free community feeds.

    This deliberately does NOT check the record's `type` field (readsb's
    source-quality enum: adsb_icao, mlat, tisb_icao, other, ...) before
    accepting it — an MLAT-derived position (ground stations triangulating
    an aircraft rather than reading its own broadcast position) is still a
    real, current position for a real aircraft, and this app has no need
    to distinguish "how" a fix was obtained from "whether" one exists. Ruled
    out one plausible-sounding theory here: readsb's `~`-prefixed `hex`
    values mark a non-ICAO address space, not MLAT specifically (confirmed
    against readsb's own source), so there was never a hex-matching quirk
    silently dropping MLAT fixes either — nothing to fix there, just
    confirming (and now documenting) that every source type readsb reports
    was already being accepted here.
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


def _fetch_by_registration(tail: str, source_label: str, base_url: str, reg_segment: str = "reg") -> dict | None:
    """
    Registration/tail-number fallback, tried only when every hex-keyed
    source above came up empty for this pass and this subject has a known
    tail. The one real gap this closes: some private jets broadcast under a
    temporary, anonymized ICAO address (the FAA's "PIA" — Privacy ICAO
    Address — program exists specifically so an aircraft's normal hex
    doesn't show up in public trackers, which is exactly the situation a
    second identifier is for) — hex-based lookup can never find that
    aircraft under its usual address, but the aggregator's own
    registration index still resolves the same physical airframe.

    Unlike `_fetch_readsb_style`, no client-side re-matching against a
    returned field is needed: the endpoint itself is scoped to this one
    registration, same `{"ac": [...]}` shape as the hex endpoints — so
    whatever comes back in `ac[0]` — if anything — is already the right
    aircraft.

    `reg_segment` exists because adsb.lol (a readsb-api fork, like
    airplanes.live/ADS-B One) and adsb.fi (its own independent
    implementation) don't actually agree on this path: adsb.lol's is
    `/v2/reg/{tail}`, but adsb.fi's is `/v2/registration/{tail}` — calling
    adsb.fi with `/v2/reg/` 400s outright rather than just coming up empty,
    which is what tipped this off (both APIs' hex endpoints do agree, at
    `/v2/hex/`).
    """
    try:
        data = get_json(f"{base_url}/v2/{reg_segment}/{urllib.parse.quote(tail)}")
    except Exception as exc:                      # noqa: BLE001
        print(f"[{source_label}] reg={tail}: {exc}", file=sys.stderr)
        return None
    ac_list = data.get("ac") or []
    return ac_list[0] if ac_list else None


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


def fetch_aircraft(icao_hex: str, tail: str | None = None) -> dict | None:
    """
    Free, keyless sources, tried in order, stopping at the first one that
    actually has this aircraft right now — the same redundancy the client
    already applies to the live in-app position dot (see
    `LiveFlightTracker` in LiveTracking.kt), extended here to the source that
    matters more: this function's return value gets permanently folded into
    every person's persisted 7-day locationBreakdown/recentStops history
    below, so a single source's transient gap or rate limit used to become a
    permanent "NO_SIGNAL" slice nothing could ever fix retroactively once it
    was written.

    adsb.lol stays first since it's the proven, already-working source for
    the rest of the roster. adsb.fi (added 2026-09-23) is a second,
    independently-run community aggregator of the same "readsb" shape —
    genuinely different receivers than adsb.lol's, so it's tried right
    after rather than lumped in with OpenSky, which is a structurally
    different, generally sparser source and stays last. A healthy poll
    still costs exactly one request; each later source only ever fires when
    everything before it came up empty.

    A fourth source, airplanes.live, used to sit here too. Their public
    `/v2/hex/` endpoint has since locked down (returns a flat 403 telling
    integrators to contact them for approved access — confirmed against
    other open-source trackers hitting the exact same wall in 2026, not just
    a transient block), so it never succeeds anymore. Keeping it in the
    chain cost a wasted request and a stderr line on every single miss for
    no actual redundancy, so it's removed rather than left as dead weight.
    If they ever reopen public access, `_fetch_readsb_style` still works
    unchanged for their response shape — just add a call back in here.

    If every hex-keyed source above comes up empty and `tail` is known, one
    last try: a registration-based lookup on adsb.lol and adsb.fi (see
    `_fetch_by_registration`'s doc comment for why this can succeed when
    every hex-based attempt just failed — a temporary/anonymized ICAO
    address). OpenSky's public API has no equivalent registration lookup,
    so it's not part of this fallback.
    """
    ac = _fetch_readsb_style(f"https://api.adsb.lol/v2/hex/{icao_hex.lower()}", icao_hex, "adsb")
    if ac is not None:
        return ac

    ac = _fetch_readsb_style(f"https://opendata.adsb.fi/api/v2/hex/{icao_hex.lower()}", icao_hex, "adsbfi")
    if ac is not None:
        return ac

    ac = _fetch_opensky(icao_hex)
    if ac is not None:
        return ac

    if tail:
        ac = _fetch_by_registration(tail, "adsb-reg", "https://api.adsb.lol")
        if ac is not None:
            return ac

        ac = _fetch_by_registration(tail, "adsbfi-reg", "https://opendata.adsb.fi/api", reg_segment="registration")
        if ac is not None:
            return ac

    return None


def flight_status(subject: Subject, prev: dict, airports, countries, now: int) -> dict | None:
    """
    - state and departure are live, straight off ADS-B
    - arrival is filled in once the aircraft is on the ground — that's the
      earliest this data source can know it, not a deliberate delay
    - while airborne, estimatedDestinationIcao is a live course-based guess,
      not a filed flight plan (see estimate_heading_destination)
    - currentAirportIcao and recentStops track parked location at airport
      granularity over a trailing 7 days — never a coordinate or a live trail
    - the live position is never persisted into that history at all
    - the one narrow, discussed exception (added 2026-09-23): when the
      aircraft is ON_GROUND with a real fresh fix that matches no known
      airport, currentLat/currentLon/generalLocation carry that one fix
      through instead of it just vanishing into "no signal" — current-pass
      only, never written into recentStops/state.json. Deliberately NOT
      done for AIRBORNE: that live position already reaches the client
      through the separate, non-persisted LiveFlightTracker path, and
      adding a second copy here would mean committing an in-flight
      coordinate to git history every few minutes. See general_location()
      and Models.kt's currentLat doc comment for the full reasoning.
    """
    if not subject.icao_hex:
        return None

    ac = fetch_aircraft(subject.icao_hex, tail=subject.tail)
    memory = prev.get(subject.icao_hex, {})

    alt = ac.get("alt_baro") if ac else None
    on_ground = (alt == "ground") or (isinstance(alt, (int, float)) and alt < 500)
    state = "UNKNOWN" if ac is None else ("ON_GROUND" if on_ground else "AIRBORNE")

    lat = ac.get("lat") if ac else None
    lon = ac.get("lon") if ac else None
    track = ac.get("track") if ac else None
    # `is not None`, not truthy `lat and lon`: a real ADS-B fix can legitimately
    # land exactly on the equator or the prime meridian (lat/lon == 0.0), and
    # Python's truthiness would silently treat that as "no coordinates" -
    # the same check already used correctly a few lines down at the estimate
    # call and in vessel_status's equivalent line below.
    here = nearest_airport(airports, lat, lon) if (airports and lat is not None and lon is not None) else None

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

        # Open a new stop on arrival, on the very first observation (we
        # can't know the true arrival time for a plane already parked when
        # the worker started, so "now" is the honest starting point), and
        # also whenever the currently-open stop doesn't match this pass's
        # real airport match — the real case this catches: a plane sits
        # ON_GROUND the whole time with no AIRBORNE transition while `here`
        # was unresolvable (no match in airports.csv, or borderline
        # matching), so no stop ever opened; once a later pass resolves a
        # real ICAO for the same unmoved aircraft, this makes sure
        # currentAirportIcao actually picks it up instead of silently
        # staying null forever just because the plane never left in
        # between. A genuinely open, already-matching stop is left alone.
        open_stop = stops[-1] if stops and stops[-1].get("departed") is None else None
        if here and (open_stop is None or open_stop.get("icao") != here):
            if open_stop is not None:
                open_stop["departed"] = now
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
        # Last confirmed airborne with no landing recorded since. A short
        # gap is an ordinary ADS-B coverage hole (rural or oceanic dead
        # zones are routine — free feeds like adsb.lol/OpenSky have no
        # satellite fill-in over open water) and the aircraft vanishing
        # mid-leg is still far likelier than that, so this keeps reading
        # IN_FLIGHT at first, same as always.
        #
        # But "assume still flying" forever becomes actively dishonest once
        # the silence outlasts what's physically plausible for the same
        # leg, or runs well past wherever the aircraft's own course was
        # clearly pointing right before it went quiet. The real case this
        # catches: landing somewhere with no ADS-B ground coverage at all —
        # a small private strip is the usual culprit — sitting there for
        # hours, then taking off again without ever once being confirmed on
        # the ground. Silently drawing that whole layover as "in flight"
        # would be worse than admitting we don't actually know — same
        # philosophy as the NO_SIGNAL case below, just for a leg that
        # clearly did start.
        gap = now - memory.get("last_seen", now)
        had_estimate = bool(memory.get("last_airborne_estimate_icao"))
        if (had_estimate and gap >= SIGNAL_LOST_GRACE_SECONDS) or gap >= SIGNAL_LOST_HARD_CEILING_SECONDS:
            bucket = "SIGNAL_LOST"
        else:
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

    if airborne and estimate is not None:
        # Remembered across passes so a later signal-loss gap (see the
        # `was == "AIRBORNE"` branch above) has something concrete to judge
        # plausibility against instead of guessing blind. Only overwritten
        # on a fresh, real estimate — a pass with no track data briefly
        # available doesn't erase the last good guess.
        memory["last_airborne_estimate_icao"] = estimate
        memory["last_airborne_estimate_at"] = now

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
        # The one exception to "never a coordinate" — see the docstring
        # above. Only when a real fresh ON_GROUND fix matched no known
        # airport; None the rest of the time, including always-None while
        # AIRBORNE.
        "currentLat": lat if (state == "ON_GROUND" and here is None and lat is not None) else None,
        "currentLon": lon if (state == "ON_GROUND" and here is None and lon is not None) else None,
        "generalLocation": (
            general_location(countries, lat, lon)
            if (state == "ON_GROUND" and here is None and lat is not None and lon is not None)
            else None
        ),
        # The bucket this exact pass just assigned above — distinct from
        # `state`, which only ever says AIRBORNE/ON_GROUND/UNKNOWN and can't
        # by itself tell the client whether a current UNKNOWN reading is an
        # ordinary short gap (still shown as "no signal") or a gap that's
        # crossed into SIGNAL_LOST territory (see the `was == "AIRBORNE"`
        # branch above) — the client needs this to pick the right label/
        # color for whatever's happening *right now*, since the timeline
        # strip's confirmed history is deliberately built from recentStops
        # instead (see buildTimelineSegments in PersonDetailScreen.kt).
        "currentBucket": bucket,
        # Only meaningful when currentBucket == "SIGNAL_LOST": the last
        # heading-based guess at a destination before contact was lost, so
        # the client can say *where* it probably landed instead of just
        # "somewhere, we don't know". None when no such guess was ever made
        # (e.g. the hard-ceiling trigger fired with no clear course to go
        # on) — the client should fall back to an unspecified "possibly
        # landed" in that case rather than treating None as an error.
        "probableIcao": memory.get("last_airborne_estimate_icao") if bucket == "SIGNAL_LOST" else None,
    }


# ---------------------------------------------------------------- vessels


def load_ports() -> list[dict]:
    """
    Optional. Drop a ports.csv beside this file to get port names instead of
    bare UN/LOCODE-style identifiers. Expected columns:

        unlocode,name,municipality,iso_country,lat,lon

    The bundled ports.csv (added 2026-09-23) covers all 16,666 UN/LOCODE
    seaports worldwide with usable coordinates — filtered from
    cristan/improved-un-locodes' code-list-improved.csv down to entries
    whose Function code marks a sea/maritime port and whose UN/LOCODE
    status isn't rejected or slated for removal. Same shape and full-
    global-coverage lineage as airports.csv next to it; see DESIGN.md for
    why a hand-curated per-yacht list turned out not to be enough (a real
    AIS catch with no matching port silently read as "no signal").
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
        dlat = row["lat"] - lat
        dlon = _wrapped_dlon(lon, row["lon"]) * math.cos(math.radians(lat))
        d = dlat ** 2 + dlon ** 2
        if d < best_d:
            best, best_d = row["unlocode"], d
    # Coastline is sparser than airports, so a bit more slack than the
    # aircraft version's 0.2 — beyond this we are guessing, so we say nothing.
    return best if best_d < 0.5 else None


# ------------------------------------------------------- general location
#
# The one narrow exception to "port/airport granularity only, never a
# coordinate" (see Models.kt / DESIGN.md for the full privacy rationale and
# scoping). When a real, fresh position doesn't resolve to any known
# port/airport, this gives it a coarse, human place name — a country, a
# named sea, or a broad ocean band — cheap enough to compute (a point-in-
# polygon test against ~177 countries, worst case a few thousand vertex
# comparisons) that it costs nothing meaningful in a once-a-minute worker.
#
# It shares its one data file, world_countries.json, with the Android
# client's own Canvas world map (app/src/main/assets/world_countries.json —
# this is a byte-for-byte copy), so the backend's coarse place names and the
# client's drawn coastlines can never drift into disagreeing with each
# other about where a border actually is.
def load_countries() -> list[dict]:
    """
    Optional. Missing file just means general_location() falls back to the
    named-seas/broad-ocean tiers only (still never returns None) — same
    "optional CSV beside the script" shape as load_airports()/load_ports().
    """
    if not os.path.exists(COUNTRIES_PATH):
        return []
    try:
        with open(COUNTRIES_PATH, encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, json.JSONDecodeError) as exc:
        print(f"[geo] couldn't read {COUNTRIES_PATH}: {exc}", file=sys.stderr)
        return []


def _point_in_ring(lon: float, lat: float, ring: list[float]) -> bool:
    """
    Standard ray-casting point-in-polygon test. `ring` is the flat
    [lon0, lat0, lon1, lat1, ...] encoding world_countries.json uses.

    A handful of real countries' rings straddle the antimeridian outright
    (confirmed in world_countries.json: Russia and Fiji both carry rings
    whose longitudes span the full -180..180 range, not two separate rings
    pre-split at the date line). Ray-casting assumes a flat, continuous x
    axis, so a raw vertex list crossing from +179 to -179 looks like a huge
    ~358-degree jump instead of the real ~2-degree one, and the edge-
    crossing test above silently produces wrong containment results near
    there. Fix: when a ring's own longitude span exceeds 180 degrees (the
    tell that it wraps rather than genuinely spanning that much of the
    globe), remap every negative longitude in it — and the query point, if
    it's also negative — into the equivalent 180..360 value, so the ring
    becomes one continuous span with no fake jump. Rings that don't
    straddle the date line are untouched.
    """
    lons = ring[0::2]
    if max(lons) - min(lons) > 180.0:
        ring = [(v + 360.0 if v < 0 else v) if i % 2 == 0 else v for i, v in enumerate(ring)]
        if lon < 0:
            lon += 360.0

    n = len(ring) // 2
    inside = False
    x, y = lon, lat
    x1, y1 = ring[0], ring[1]
    for i in range(1, n + 1):
        x2, y2 = ring[(2 * i) % len(ring)], ring[(2 * i + 1) % len(ring)]
        if ((y1 > y) != (y2 > y)) and (
            x < (x2 - x1) * (y - y1) / ((y2 - y1) or 1e-12) + x1
        ):
            inside = not inside
        x1, y1 = x2, y2
    return inside


def _country_containing(countries: list[dict], lat: float, lon: float) -> str | None:
    for country in countries:
        for ring in country.get("rings", []):
            if len(ring) >= 6 and _point_in_ring(lon, lat, ring):
                return country["name"]
    return None


# Beyond this, a "nearest country" guess stops being a reasonable "off the
# coast of X" and starts being a guess about a country that's actually
# nowhere near the position — degrees, not a great-circle distance, since
# this is only ever a coarse approximation to begin with.
NEAREST_COUNTRY_MAX_DEG = 3.0


def _nearest_country(countries: list[dict], lat: float, lon: float) -> str | None:
    """
    Nearest-vertex approximation, not true edge distance — good enough for
    "off the coast of X" and much cheaper than real polygon-distance math
    over ~177 countries every pass.
    """
    best, best_d = None, 1e18
    for country in countries:
        for ring in country.get("rings", []):
            for i in range(0, len(ring) - 1, 2):
                dlon = _wrapped_dlon(lon, ring[i]) * math.cos(math.radians(lat))
                dlat = ring[i + 1] - lat
                d = dlon * dlon + dlat * dlat
                if d < best_d:
                    best, best_d = country["name"], d
    if best is None or best_d > NEAREST_COUNTRY_MAX_DEG ** 2:
        return None
    return f"off the coast of {best}"


# Hand-curated, deliberately approximate bounding boxes — good enough to
# name a body of water, not a navigational chart. (lat_min, lat_max,
# lon_min, lon_max); a lon_min > lon_max entry wraps across the antimeridian
# (only the Bering Sea needs this here).
NAMED_SEAS = [
    ("Mediterranean Sea", 30.0, 46.0, -6.0, 36.5),
    ("Black Sea", 40.5, 47.5, 27.0, 42.0),
    ("Red Sea", 12.0, 30.0, 32.0, 44.0),
    ("Persian Gulf", 23.5, 30.5, 47.5, 56.5),
    ("Caribbean Sea", 9.0, 22.0, -87.0, -60.0),
    ("Gulf of Mexico", 18.0, 30.5, -98.0, -80.0),
    ("North Sea", 51.0, 61.5, -4.0, 9.0),
    ("Baltic Sea", 53.0, 66.0, 9.5, 30.5),
    ("Sea of Japan", 34.0, 52.0, 127.0, 142.0),
    ("East China Sea", 23.0, 33.0, 117.0, 131.0),
    ("South China Sea", -3.0, 23.0, 99.0, 121.0),
    ("Yellow Sea", 33.0, 41.0, 119.0, 126.5),
    ("Sea of Okhotsk", 43.0, 62.0, 135.0, 165.0),
    ("Bering Sea", 52.0, 66.0, 162.0, -157.0),
    ("Arabian Sea", 5.0, 25.0, 55.0, 78.0),
    ("Bay of Bengal", 5.0, 23.0, 78.0, 95.0),
    ("Andaman Sea", 5.0, 18.0, 92.0, 99.0),
    ("Adriatic Sea", 39.5, 45.8, 12.0, 20.0),
    ("Aegean Sea", 35.0, 41.0, 23.0, 28.0),
    ("Tasman Sea", -47.0, -30.0, 147.0, 174.0),
    ("Coral Sea", -25.0, -10.0, 145.0, 165.0),
    ("Java Sea", -8.5, -3.0, 105.0, 117.0),
    ("Sea of Marmara", 40.0, 41.2, 26.0, 30.0),
    ("Norwegian Sea", 62.0, 75.0, -5.0, 20.0),
]


def _in_lon_range(lon: float, lo: float, hi: float) -> bool:
    if lo <= hi:
        return lo <= lon <= hi
    return lon >= lo or lon <= hi  # wraps across the antimeridian


def _named_sea(lat: float, lon: float) -> str | None:
    for name, lat_min, lat_max, lon_min, lon_max in NAMED_SEAS:
        if lat_min <= lat <= lat_max and _in_lon_range(lon, lon_min, lon_max):
            return name
    return None


def _broad_ocean(lat: float, lon: float) -> str:
    """
    The guaranteed catch-all — every valid (lat, lon) lands in exactly one
    of these bands, so general_location() never has to return None for a
    real position, even one nowhere near land or a named sea.
    """
    if lat > 66.5:
        return "Arctic Ocean"
    if lat < -60.0:
        return "Southern Ocean"
    if -70.0 <= lon < 20.0:
        return "Atlantic Ocean"
    if 20.0 <= lon < 100.0:
        return "Indian Ocean"
    if 100.0 <= lon < 147.0:
        return "Pacific Ocean" if lat >= 0 else "Indian Ocean"
    return "Pacific Ocean"


def general_location(countries: list[dict], lat: float, lon: float) -> str:
    """
    Always resolves to something — this is the whole point of the fallback
    (see the module comment above): a real fix that reached this function at
    all deserves a real place name, not a second "unknown". Tries country
    containment first (most specific), then "off the coast of X" for a near
    miss, then a named sea, then a broad ocean band that can't fail.
    """
    return (
        _country_containing(countries, lat, lon)
        or _nearest_country(countries, lat, lon)
        or _named_sea(lat, lon)
        or _broad_ocean(lat, lon)
    )


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


def vessel_status(subject: Subject, prev: dict, ports, ais_cache: dict, countries, now: int) -> dict | None:
    """
    The maritime mirror of flight_status() — see VesselStatus in Models.kt
    for the privacy rationale (port granularity only, self-reported
    destination clearly flagged as unverified). The state machine below
    (stop open/close, 7-day pruning) is deliberately the same shape as the
    aircraft version, just swapping "on the ground" for "in port" and
    "airborne" for "underway".

    Same one narrow exception flight_status() documents, applied a bit more
    broadly: a real fresh AIS fix that matches no known port surfaces as
    currentLat/currentLon/generalLocation instead of vanishing, current-pass
    only, never written into recentStops/state.json. Unlike flights this
    applies in BOTH IN_PORT and UNDERWAY states, not just one of them — a
    vessel has no equivalent to LiveFlightTracker's separate live path, so
    an underway fix with no port match is the closest thing to "live" this
    feature can ever show for a boat.
    """
    if not subject.mmsi:
        return None

    entry = ais_cache.get(subject.mmsi)
    fresh = bool(entry) and (now - int(entry.get("timestamp", 0)) < VESSEL_STALE_SECONDS)
    memory = prev.get(subject.mmsi, {})

    lat = entry.get("lat") if fresh else None
    lon = entry.get("lon") if fresh else None
    sog = entry.get("sog") if fresh else None  # knots
    cog = entry.get("cog") if fresh else None  # degrees true, course over ground
    destination = (entry.get("destination") or "").strip() if fresh else None
    eta_raw = entry.get("eta") if fresh else None

    # A moored/anchored vessel still shows a little drift in AIS SOG — 0.5kt
    # is the conventional "not really moving" cutoff most trackers use.
    if not fresh:
        state = "UNKNOWN"
    elif isinstance(sog, (int, float)) and sog < 0.5:
        state = "IN_PORT"
    else:
        state = "UNDERWAY"

    # Short-horizon dead reckoning: AIS coverage between receivers can leave
    # a genuine gap of several minutes even for a vessel that's happily
    # underway the whole time. Rather than let the display fall back to
    # "last known position" (increasingly wrong the longer the gap runs) or
    # to nothing at all, project the last fix forward along its reported
    # course for a short, bounded window. This is deliberately conservative:
    # DEAD_RECKON_MAX_SECONDS caps how stale a fix can be before we stop
    # trusting the projection, and it only ever applies when the vessel was
    # actually moving (sog >= 0.5) with a valid course. Two things this must
    # NEVER touch: `here` (the port match used for the state machine) and
    # anything written into state.json — both keep using the raw, real fix
    # below, so an estimate can never masquerade as a confirmed arrival/
    # departure or leak into the persisted stop history. dr_lat/dr_lon are
    # display-only, current-pass-only, exactly like currentLat/currentLon
    # already are.
    dr_lat, dr_lon = lat, lon
    if (
        fresh
        and lat is not None
        and lon is not None
        and isinstance(sog, (int, float)) and sog >= 0.5
        and isinstance(cog, (int, float))
    ):
        age = now - int(entry.get("timestamp", 0))
        if 0 < age <= DEAD_RECKON_MAX_SECONDS:
            distance_nm = sog * (age / 3600.0)
            dr_lat, dr_lon = _dead_reckon_nm(lat, lon, cog, distance_nm)

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

        # Same generalized condition as flight_status()'s equivalent block
        # above (see its comment) — this is the exact bug that let a real,
        # freshly-caught AIS fix (Ellison's MUSASHI) sit matched to a real
        # port in locationBreakdown/last_port while currentPortUnlocode and
        # the map pin stayed stuck on null, because the vessel had been
        # sitting IN_PORT with no match ever since before ports.csv existed
        # and never once went UNDERWAY to trigger the old arrival check.
        open_stop = stops[-1] if stops and stops[-1].get("departed") is None else None
        if here and (open_stop is None or open_stop.get("unlocode") != here):
            if open_stop is not None:
                open_stop["departed"] = now
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
        # Last confirmed underway with no arrival recorded since. Ordinary
        # AIS coverage gaps here can be long and completely unremarkable —
        # this network is shore-based, so a genuine open-ocean crossing can
        # go days with nothing heard — so this stays UNDERWAY by default,
        # same as before.
        #
        # But it stops being an honest description once the silence outlasts
        # even a generous ocean crossing, or runs well past a course that was
        # clearly pointing at one specific port right before it went quiet —
        # the same two-trigger shape flight_status() uses for SIGNAL_LOST,
        # just with much longer thresholds (see VESSEL_SIGNAL_LOST_GRACE_SECONDS's
        # doc comment for why).
        gap = now - memory.get("last_seen", now)
        had_estimate = bool(memory.get("last_underway_estimate_port"))
        if (had_estimate and gap >= VESSEL_SIGNAL_LOST_GRACE_SECONDS) or gap >= VESSEL_SIGNAL_LOST_HARD_CEILING_SECONDS:
            bucket = "SIGNAL_LOST"
        else:
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

    underway = state == "UNDERWAY"

    # The maritime mirror of flight_status()'s estimate_heading_destination
    # call — see estimate_heading_port's doc comment. Uses the raw fix
    # (lat/lon/cog), not dr_lat/dr_lon, for the same reason `here` above
    # does: this is meant to reflect an actually-confirmed course, not a
    # display-only projection.
    estimate = None
    if underway and ports and lat is not None and lon is not None and isinstance(cog, (int, float)):
        estimate = estimate_heading_port(
            ports, lat, lon, float(cog), exclude_unlocode=memory.get("departed_port")
        )

    if underway and estimate is not None:
        # Remembered across passes so a later signal-loss gap (see the
        # `was == "UNDERWAY"` branch above) has something concrete to judge
        # plausibility against instead of guessing blind — only overwritten
        # on a fresh, real estimate, so a pass with a momentarily missing
        # course doesn't erase the last good guess.
        memory["last_underway_estimate_port"] = estimate
        memory["last_underway_estimate_at"] = now

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
        # Crew-entered, same message (5) as Destination and the same
        # unverified/self-reported caveat — see format_ais_eta's doc
        # comment for why this never carries a year. Left None whenever
        # destination is too (both come off the same fresh ShipStaticData
        # frame), so the client never shows an ETA with no destination to
        # attach it to.
        "selfReportedEta": format_ais_eta(eta_raw, now) if destination else None,
        # The maritime mirror of flight_status()'s "estimatedDestinationIcao"
        # — same live course-based guess, computed on every pass above
        # (`estimate` — see the comment right before it), just not exposed
        # to the client until now. Previously this computation only ever
        # reached the client gated behind SIGNAL_LOST (see
        # "probablePortUnlocode" below), so a boat underway with nothing
        # self-reported showed a flat "no destination broadcast" even on
        # passes where this exact guess existed. Independent of
        # selfReportedDestination — both can be set at once; the client
        # prefers the self-reported one and falls back to this.
        "estimatedDestinationPortUnlocode": estimate,
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
        # The one exception to "never a coordinate" — see the docstring
        # above. Only when a real fresh fix (moored or underway) matched no
        # known port; None otherwise. Uses dr_lat/dr_lon (the short-horizon
        # dead-reckoning projection — see the block above) rather than the
        # raw fix, so a boat mid-gap between AIS receivers still shows a
        # sensible current position instead of one that's silently aging in
        # place; `here` above still used the raw fix, so this never affects
        # the port-match/state-machine logic, only what's displayed.
        "currentLat": dr_lat if (state in ("IN_PORT", "UNDERWAY") and here is None and dr_lat is not None) else None,
        "currentLon": dr_lon if (state in ("IN_PORT", "UNDERWAY") and here is None and dr_lon is not None) else None,
        "generalLocation": (
            general_location(countries, dr_lat, dr_lon)
            if (state in ("IN_PORT", "UNDERWAY") and here is None and dr_lat is not None and dr_lon is not None)
            else None
        ),
        # The maritime mirror of FlightStatus.currentBucket — see its doc
        # comment. Distinct from `state` (UNDERWAY/IN_PORT/UNKNOWN) the same
        # way currentBucket is distinct from a flight's state: `state` alone
        # can't tell an ordinary short AIS gap apart from one that's crossed
        # into SIGNAL_LOST territory (see the `was == "UNDERWAY"` branch
        # above) — the client needs this to pick the right label/color for
        # what's happening right now.
        "currentBucket": bucket,
        # Only meaningful when currentBucket == "SIGNAL_LOST": the last
        # heading-based guess at a port before contact was lost, so the
        # client can say *where* it's possibly near instead of just
        # "somewhere, we don't know". None when no such guess was ever made
        # (e.g. the hard-ceiling trigger fired with no clear course to go
        # on) — the client should fall back to an unspecified "possibly
        # near — unclear" in that case rather than treating None as an error.
        "probablePortUnlocode": memory.get("last_underway_estimate_port") if bucket == "SIGNAL_LOST" else None,
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
                imo=entry.get("imo"),
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
    countries = load_countries()   # see general_location()

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
            flight = flight_status(subject, state.setdefault("flights", {}), airports, countries, now)
            state.setdefault("flight_payload", {})[subject.id] = flight
        else:
            flight = state.get("flight_payload", {}).get(subject.id)

        if passes % VESSEL_EVERY == 0:
            vessel = vessel_status(subject, state.setdefault("vessels", {}), ports, ais_cache, countries, now)
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
