#!/usr/bin/env python3
"""
ais_listener.py — the one piece of this backend that isn't stdlib-only, and
the one piece that isn't a request/response poll like everything in
snapshot_worker.py.

Why this is a separate process
-------------------------------
ADS-B (planes) is a simple "ask, get an answer" HTTP API, so snapshot_worker.py
just calls it once a minute like everything else. AIS (vessels) has no free
global equivalent of that shape — the free tier that actually covers open
water (aisstream.io) is a persistent WebSocket subscription: you connect
once, say which MMSIs you care about, and it pushes position reports at you
as they arrive. That doesn't fit inside a script that runs for a few seconds
once a minute and exits.

So this script is a small, separate, long-running process: it opens the
WebSocket once, keeps it open, and every time a position or destination
update arrives for a tracked vessel it writes the latest known state to
ais_cache.json. snapshot_worker.py just reads that file — zero network
calls of its own for AIS, same cost profile as everything else in this
product regardless of how many app users there are.

Two ways to run it
------------------
1. Continuously (the original design), under whatever process supervisor
   you have — systemd, supervisord, a tmux pane if you're just trying it
   out. It stays connected and reconnects on its own if the connection
   drops. This is the most real-time option, but it needs *somewhere* that
   stays on 24/7, which is the one piece of this project's architecture
   that isn't free-by-default the way GitHub Actions makes flights/quotes/
   news — a persistent WebSocket doesn't fit inside a scheduled job that
   runs for a few seconds and exits, so this always needed a small
   always-on box of your own (a spare machine, a $0-tier VPS, etc.) —
   aisstream.io itself is free either way, it's the *hosting* that isn't.

2. In a time-boxed burst — set AIS_BURST_SECONDS (e.g. "45") and this
   process connects, subscribes, listens for that many seconds, saves
   whatever it caught, and exits cleanly. That fits inside a GitHub
   Actions job on the same free cron pattern as snapshot_worker.py (see
   .github/workflows/ais.yml) — no server of your own required at all.
   It'll catch fewer updates than a socket that's open around the clock
   (only whatever those vessels transmit during the window), but it's
   real live AIS, it's free, and it matches every other part of this
   product's "no server to run yourself" design. Use mode 1 instead if you
   already have somewhere to run it continuously and want tighter freshness.

Requires
--------
    pip install websockets

This is the one non-stdlib dependency anywhere in this backend. It's
confined to this file — snapshot_worker.py still has none. A free API key
from https://aisstream.io/authenticate is required; pass it via the
AISSTREAM_API_KEY environment variable, never hardcoded here or committed
to the repo.

Untested against the live service
----------------------------------
This was written against aisstream.io's published documentation, not
exercised against the real WebSocket — the sandbox this was built in has no
route to it. The message-parsing logic below is deliberately defensive
(unknown message types and missing fields are skipped, not crashed on), but
watch its logs the first time you run it for real, and report back if the
message shape doesn't match what's coded here.
"""

from __future__ import annotations

import asyncio
import json
import os
import sys
import time

try:
    import websockets
except ImportError:
    print(
        "ais_listener.py needs the 'websockets' package: pip install websockets\n"
        "(this is the only non-stdlib dependency in this backend, and it's\n"
        "confined to this one file — see the module docstring for why.)",
        file=sys.stderr,
    )
    raise SystemExit(1)

HERE = os.path.dirname(os.path.abspath(__file__))
HOLDINGS_PATH = os.path.join(HERE, "holdings.json")
AIS_CACHE_PATH = os.path.join(HERE, "ais_cache.json")

STREAM_URL = "wss://stream.aisstream.io/v0/stream"
RECONNECT_BACKOFF_SECONDS = 10

# aisstream.io's subscription message requires BoundingBoxes — it's not
# optional, unlike FiltersShipMMSI (see their docs' subscription example).
# This script went without one for its entire life, which is almost
# certainly why every burst has come back with "no traffic heard" even for
# vessels confirmed live elsewhere (VesselFinder/MarineTraffic) at the exact
# same moment: an invalid/incomplete subscription talks to the socket fine
# but the server has nothing to match messages against, so nothing is ever
# sent back, silently. The tracked yachts roam globally (Mediterranean, US
# East Coast, Pacific, wherever), so one box spanning the whole planet is
# the right shape here — FiltersShipMMSI below still does the real
# narrowing down to just the handful of MMSIs this app cares about, so a
# world-sized box doesn't turn this into a firehose.
WORLD_BOUNDING_BOX = [[[-90, -180], [90, 180]]]

# Both message types apply_message() actually knows how to handle - not just
# "PositionReport". The subscription used to filter to PositionReport alone,
# which meant aisstream.io never sent a ShipStaticData (Message 5) frame at
# all, so the `elif msg_type == "ShipStaticData"` branch in apply_message()
# below was unreachable and selfReportedDestination could never be populated
# from a live run - a documented feature that silently never worked because
# the one message type it depends on was filtered out at the subscription
# itself, not in this file's own parsing.
SUBSCRIBED_MESSAGE_TYPES = ["PositionReport", "ShipStaticData"]

# Set AIS_BURST_SECONDS to run this as a one-shot burst instead of a
# forever-running listener — see "Two ways to run it" above. 0 (unset)
# keeps the original continuous behavior.
BURST_SECONDS = int(os.environ.get("AIS_BURST_SECONDS", "0") or "0")


def tracked_mmsis() -> list[str]:
    """Only subscribe to the vessels this app actually tracks — never the
    whole ocean. aisstream.io allows up to 200 MMSIs per subscription."""
    with open(HOLDINGS_PATH, encoding="utf-8") as fh:
        raw = json.load(fh)
    return [str(p["mmsi"]) for p in raw["people"] if p.get("mmsi")]


def load_cache() -> dict:
    if os.path.exists(AIS_CACHE_PATH):
        try:
            with open(AIS_CACHE_PATH, encoding="utf-8") as fh:
                return json.load(fh)
        except (OSError, json.JSONDecodeError):
            pass
    return {}


def save_cache(cache: dict) -> None:
    tmp = AIS_CACHE_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(cache, fh)
    os.replace(tmp, AIS_CACHE_PATH)


def prune_cache(cache: dict, mmsis: list[str]) -> bool:
    """
    Self-managing, same spirit as snapshot_worker.py's 7-day trims: drop any
    cached MMSI that's no longer in holdings.json (someone removed, or a
    typo got fixed) so this file never carries entries for vessels the app
    doesn't track anymore. Returns True if anything was actually dropped.
    """
    stale = [mmsi for mmsi in cache if mmsi not in mmsis]
    for mmsi in stale:
        del cache[mmsi]
    return bool(stale)


def apply_message(cache: dict, raw: dict) -> bool:
    """
    Merge one aisstream.io message into the cache. Returns True if it
    changed anything worth persisting.

    Two message types matter here:
      PositionReport   -> lat/lon/speed/course (Message 1/2/3)
      ShipStaticData    -> the crew-entered Destination field, and the
                           vessel's IMO number (Message 5)
    Everything else (and anything with an unexpected shape) is ignored
    rather than raising — a stream client should degrade quietly, not crash
    the whole listener over one odd frame.

    IMO number: unlike Destination (self-reported, changes every voyage),
    ShipStaticData's ImoNumber is the vessel's permanent hull identifier —
    it's what holdings.json's optional `imo` field is meant to eventually
    hold (see that file's 2026-09-23 note), so capturing it here means the
    cache carries it independent of whether it's been manually filled in
    yet. aisstream.io returns 0 for vessels with no assigned IMO (common
    for smaller/non-SOLAS craft, which some tracked yachts are) — that's
    not a real identifier, so it's dropped rather than cached as "0".
    """
    msg_type = raw.get("MessageType")
    meta = raw.get("MetaData") or {}
    mmsi = meta.get("MMSI")
    if not mmsi:
        # A malformed/rejected subscription (the exact bug this file just
        # had — a missing required BoundingBoxes field) shows up here as a
        # frame with no MessageType/MetaData at all, which used to be
        # swallowed completely silently, identical to any other harmless
        # unrecognized frame. Surfacing it — once it's clearly not a normal
        # position/static-data message — means a future protocol problem
        # shows up in the Actions log instead of just quietly producing zero
        # traffic forever with no clue why.
        if msg_type is None:
            print(f"[ais] unrecognized frame (no MessageType): {raw}", file=sys.stderr)
        return False
    mmsi = str(mmsi)

    entry = cache.setdefault(mmsi, {})
    changed = False

    if msg_type == "PositionReport":
        body = (raw.get("Message") or {}).get("PositionReport") or {}
        lat, lon = body.get("Latitude"), body.get("Longitude")
        if lat is None or lon is None:
            return False
        entry["lat"] = lat
        entry["lon"] = lon
        entry["sog"] = body.get("Sog")
        entry["cog"] = body.get("Cog")
        entry["navStatus"] = body.get("NavigationalStatus")
        entry["timestamp"] = int(time.time())
        changed = True

    elif msg_type == "ShipStaticData":
        body = (raw.get("Message") or {}).get("ShipStaticData") or {}
        dest = body.get("Destination")
        if dest is not None:
            entry["destination"] = dest.strip()
            changed = True

        imo = body.get("ImoNumber")
        # 0 means "no IMO assigned" per aisstream.io's docs, not a real
        # identifier — see the docstring above for why that's dropped
        # rather than stored.
        if isinstance(imo, int) and imo > 0:
            entry["imo"] = str(imo)
            changed = True

    return changed


async def _listen_once(ws, mmsis: list[str], deadline: float | None) -> None:
    """
    Read messages off an already-subscribed socket, applying each one to the
    cache as it arrives, until either the socket closes or `deadline`
    (an event-loop `time.monotonic()` timestamp, or None for "forever")
    passes. Shared by both run modes so the message-handling logic is
    identical in burst and continuous mode — only how long this loop runs
    differs.
    """
    while True:
        if deadline is not None:
            remaining = deadline - asyncio.get_running_loop().time()
            if remaining <= 0:
                return
            try:
                raw_message = await asyncio.wait_for(ws.recv(), timeout=remaining)
            except asyncio.TimeoutError:
                return
        else:
            raw_message = await ws.recv()

        try:
            parsed = json.loads(raw_message)
        except json.JSONDecodeError:
            continue
        cache = load_cache()
        if apply_message(cache, parsed):
            save_cache(cache)


async def run() -> None:
    api_key = os.environ.get("AISSTREAM_API_KEY")
    if not api_key:
        print("Set AISSTREAM_API_KEY in the environment (get one free at "
              "https://aisstream.io/authenticate) — refusing to start without it.",
              file=sys.stderr)
        raise SystemExit(1)

    mmsis = tracked_mmsis()
    if not mmsis:
        print("No 'mmsi' entries in holdings.json — nothing to subscribe to. "
              "Add one per person with a tracked vessel and re-run.", file=sys.stderr)
        raise SystemExit(1)

    print(f"[ais] tracking {len(mmsis)} vessel(s): {', '.join(mmsis)}")

    if BURST_SECONDS > 0:
        # One connect/listen/exit cycle — see "Two ways to run it" in the
        # module docstring. Any connection failure here is fatal for this
        # run (not retried): the next scheduled Actions run is the retry.
        print(f"[ais] burst mode: listening for {BURST_SECONDS}s then exiting")
        async with websockets.connect(STREAM_URL) as ws:
            await ws.send(json.dumps({
                "APIKey": api_key,
                "BoundingBoxes": WORLD_BOUNDING_BOX,
                "FiltersShipMMSI": mmsis,
                "FilterMessageTypes": SUBSCRIBED_MESSAGE_TYPES,
            }))
            deadline = asyncio.get_running_loop().time() + BURST_SECONDS
            try:
                await _listen_once(ws, mmsis, deadline)
            except (websockets.exceptions.WebSocketException, OSError) as exc:
                print(f"[ais] connection lost mid-burst ({exc}) — keeping "
                      f"whatever was already cached", file=sys.stderr)

        cache = load_cache()
        if prune_cache(cache, mmsis):
            save_cache(cache)
        print("[ais] burst complete")
        return

    # Continuous mode: stay connected, reconnect forever on drop.
    while True:
        try:
            async with websockets.connect(STREAM_URL) as ws:
                await ws.send(json.dumps({
                    "APIKey": api_key,
                    "BoundingBoxes": WORLD_BOUNDING_BOX,
                    "FiltersShipMMSI": mmsis,
                    "FilterMessageTypes": SUBSCRIBED_MESSAGE_TYPES,
                }))
                print("[ais] subscribed, listening…")
                await _listen_once(ws, mmsis, deadline=None)

        except (websockets.exceptions.WebSocketException, OSError) as exc:
            print(f"[ais] connection lost ({exc}), retrying in "
                  f"{RECONNECT_BACKOFF_SECONDS}s…", file=sys.stderr)
            await asyncio.sleep(RECONNECT_BACKOFF_SECONDS)


if __name__ == "__main__":
    asyncio.run(run())
