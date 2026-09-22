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
    N ADS-B requests      (N = tracked aircraft, only when due)
    0 AIS requests        (vessel positions are read from ais_cache.json,
                           written by the separate ais_listener.py process —
                           see that file. This worker never talks to AIS.)
    M RSS requests        (M = tracked people, only every NEWS_EVERY passes)
    <=M Wikipedia requests (only every WIKI_EVERY_DAYS days per person — a
                            photo and article link barely ever change)

Stdlib only. No API keys. (ais_listener.py is the one exception — see its
own docstring — it's a separate, optional, long-running process, not
something this script imports or calls.)
"""

from __future__ import annotations

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


def fetch_quotes(tickers: list[str]) -> dict[str, float]:
    """
    One request per ticker against Yahoo Finance's unofficial chart
    endpoint (see the module-level comment above this function for why
    Stooq's old batched CSV call was replaced). A plain custom User-Agent
    gets blocked here in practice — Yahoo's anti-bot layer treats it like
    any other scraper request — so this one call uses a real browser UA
    ([YAHOO_QUOTE_UA]) instead of this module's usual [USER_AGENT]. Swap
    this function for a paid provider (Finnhub, Twelve Data) if you ever
    want a real SLA instead of an unofficial endpoint; nothing else changes.
    """
    if not tickers:
        return {}

    out: dict[str, float] = {}
    for ticker in tickers:
        url = (
            "https://query2.finance.yahoo.com/v8/finance/chart/"
            f"{urllib.parse.quote(ticker)}?range=1d&interval=1d"
        )
        try:
            payload = get_json(url, headers={"User-Agent": YAHOO_QUOTE_UA})
            price = payload["chart"]["result"][0]["meta"]["regularMarketPrice"]
            out[ticker.upper()] = float(price)
        except Exception as exc:                      # noqa: BLE001
            print(f"[quotes] {ticker}: failed: {exc}", file=sys.stderr)
            continue

    missing = [t for t in tickers if t.upper() not in out]
    if missing:
        print(f"[quotes] no price for {missing}", file=sys.stderr)
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
    # Officially-announced public appearances only — a keynote, an earnings
    # call. No live feed exists for this (nothing broadcasts a person's
    # calendar the way ADS-B/AIS do a plane/boat), so this is hand-curated
    # here from holdings.json, the same static-fact pattern as birth_date/
    # residence above — see holdings.example.json for the field shape and
    # the sourcing bar ("confirmed by the organizer's own page", never a
    # third-party forecast). Passed straight through to the client, which
    # filters to "starts in the future" itself, same idea as age.
    upcoming_events: list[dict] = field(default_factory=list)


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


def fetch_aircraft(icao_hex: str) -> dict | None:
    """
    adsb.lol — community-fed, unfiltered, free. Be a good citizen: one request
    per aircraft per pass, never a tight loop. If you scale past a handful of
    tails, feed a receiver back to the network or move to a paid feed.
    """
    try:
        data = get_json(f"https://api.adsb.lol/v2/hex/{icao_hex.lower()}")
    except Exception as exc:                      # noqa: BLE001
        print(f"[adsb] {icao_hex}: {exc}", file=sys.stderr)
        return None
    ac = data.get("ac") or []
    return ac[0] if ac else None


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
    cutoff = now - 7 * 86_400
    memory["stops"] = [
        s for s in stops if s.get("departed") is None or s["departed"] >= cutoff
    ]

    # --- time-share breakdown -------------------------------------------
    # One sample per pass, no extra network call — this is bookkeeping on
    # data already fetched above. Every second in the tracked window gets
    # attributed to exactly one bucket: an airport ICAO, IN_FLIGHT, or
    # NO_SIGNAL (no ADS-B return at all that pass).
    if state == "ON_GROUND":
        bucket = here or "UNKNOWN_AIRPORT"
    elif state == "AIRBORNE":
        bucket = "IN_FLIGHT"
    else:
        bucket = "NO_SIGNAL"

    samples: list[dict] = memory.setdefault("samples", [])
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
        "lastSeenEpoch": memory.get("last_seen", now),
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

    cutoff = now - 7 * 86_400
    memory["stops"] = [
        s for s in stops if s.get("departed") is None or s["departed"] >= cutoff
    ]

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
        "lastSeenEpoch": memory.get("last_seen", now),
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
    if thumb and "/wikipedia/commons/" in thumb:
        photo_url = thumb
    elif thumb:
        print(f"[wikipedia] {title}: thumbnail not on Commons, dropping image", file=sys.stderr)

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
                upcoming_events=entry.get("upcomingEvents", []),
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


CROSSING_HISTORY_LIMIT = 200   # exposed-in-snapshot cap; the internal list is never trimmed


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
    prices = fetch_quotes(tickers)

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

        series = history.get(subject.id, [])
        series.append(value)
        history[subject.id] = series[-48:]

        prev_value = series[-2] if len(series) > 1 else value
        opens.setdefault(subject.id, value)
        day_change = value - opens[subject.id]
        drift = (value - prev_value) / float(POLL_SECONDS)

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
        wiki_stale = not wiki_entry or (now - wiki_entry.get("fetchedAt", 0)) >= WIKI_REFRESH_SECONDS
        if subject.wikipedia_title and wiki_stale:
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
                "netWorthUsd": value,
                "driftPerSecondUsd": drift,
                "dayChangeUsd": day_change,
                "history": history[subject.id],
                "flight": flight,
                "vessel": vessel,
                "news": news,
                "upcomingEvents": subject.upcoming_events,
                "socialUrl": subject.social_url,
                "wikipediaUrl": wiki_entry.get("wikipediaUrl"),
                "photoUrl": wiki_entry.get("photoUrl"),
            }
        )

    people.sort(key=lambda p: p["netWorthUsd"], reverse=True)
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
