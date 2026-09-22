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

Run this once, continuously, under whatever process supervisor you use
(systemd, supervisord, a tmux pane if you're just trying it out) — NOT on
the same one-minute cron as snapshot_worker.py. It reconnects on its own if
the connection drops.

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


def apply_message(cache: dict, raw: dict) -> bool:
    """
    Merge one aisstream.io message into the cache. Returns True if it
    changed anything worth persisting.

    Two message types matter here:
      PositionReport   -> lat/lon/speed/course (Message 1/2/3)
      ShipStaticData    -> the crew-entered Destination field (Message 5)
    Everything else (and anything with an unexpected shape) is ignored
    rather than raising — a stream client should degrade quietly, not crash
    the whole listener over one odd frame.
    """
    msg_type = raw.get("MessageType")
    meta = raw.get("MetaData") or {}
    mmsi = meta.get("MMSI")
    if not mmsi:
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

    return changed


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

    while True:
        try:
            async with websockets.connect(STREAM_URL) as ws:
                subscribe = {
                    "APIKey": api_key,
                    "FiltersShipMMSI": mmsis,
                }
                await ws.send(json.dumps(subscribe))
                print("[ais] subscribed, listening…")

                async for raw_message in ws:
                    try:
                        parsed = json.loads(raw_message)
                    except json.JSONDecodeError:
                        continue
                    cache = load_cache()
                    if apply_message(cache, parsed):
                        save_cache(cache)

        except (websockets.exceptions.WebSocketException, OSError) as exc:
            print(f"[ais] connection lost ({exc}), retrying in "
                  f"{RECONNECT_BACKOFF_SECONDS}s…", file=sys.stderr)
            await asyncio.sleep(RECONNECT_BACKOFF_SECONDS)


if __name__ == "__main__":
    asyncio.run(run())
