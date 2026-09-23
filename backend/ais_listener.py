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

Two sources, one cache
-----------------------
Since 2026-09-23 this also runs a second, independent listener side by side
with aisstream.io's: a raw NMEA/AIVDM TCP feed from Kystverket (the
Norwegian Coastal Administration), which is free, needs no API key or
registration, and requires nothing this file didn't already have (stdlib
`asyncio.open_connection` — no new dependency). Its downside is coverage: it
only hears vessels within radio range of Norwegian coastal AIS base
stations, so it's a real but narrow supplement — useful on the (not
uncommon, for this app's tracked yachts) occasions one of them cruises
Scandinavian waters, and silent the rest of the time. It's purely additive:
if it never hears anything for a given run, nothing changes from before it
existed. Both listeners write into the same ais_cache.json through the same
load-modify-save helpers below; see the "no await between load and save"
note on `_kystverket_listen_once` for why running them concurrently in one
asyncio event loop is safe without an explicit lock.

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
confined to this file — snapshot_worker.py still has none, and the
Kystverket listener added below needs nothing beyond stdlib either. A free
API key from https://aisstream.io/authenticate powers the aisstream.io side
specifically; pass it via the AISSTREAM_API_KEY environment variable, never
hardcoded here or committed to the repo. It's no longer strictly required to
run this file at all — see "Two sources, one cache" above — but skipping it
means giving up global coverage for the Kystverket feed's narrower regional
one, so set it if you can.

Untested against the live service
----------------------------------
This was written against aisstream.io's published documentation, not
exercised against the real WebSocket — the sandbox this was built in has no
route to it. The message-parsing logic below is deliberately defensive
(unknown message types and missing fields are skipped, not crashed on), but
watch its logs the first time you run it for real, and report back if the
message shape doesn't match what's coded here.

The Kystverket AIVDM decoder (decode_aivdm_position, below) carries a
stronger, but still not complete, guarantee: it was checked bit-for-bit
against sentences from pyais's own published test suite (github.com/M0r13n/
pyais, tests/test_decode.py — a library whose fixtures are themselves
hand/online-decoder-verified per that file's own comments) and matched
exactly on MMSI, latitude, longitude, speed, and course for every fixture
tried. That confirms the bit-layout math is right; it does not confirm this
sandbox can actually open a TCP connection to Kystverket's server (it
can't — no route out to arbitrary IPs from here), so the socket-handling
code around the decoder is, like the aisstream.io path above, unexercised
against the real feed. Watch its logs on first real use too.
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

# Kystverket's public real-time AIS relay — raw NMEA-0183 AIVDM sentences,
# one per line, no handshake/subscription of any kind: connect and it starts
# streaming immediately. No key, no registration, no rate limit published.
# Source: Norwegian Coastal Administration (Kystverket) open-data AIS page.
# This is a *regional* feed (Norwegian coastal base stations only) used as a
# supplementary source alongside aisstream.io's global-but-gappy coverage —
# see "Two sources, one cache" in the module docstring.
KYSTVERKET_HOST = "153.44.253.27"
KYSTVERKET_PORT = 5631
KYSTVERKET_RECONNECT_BACKOFF_SECONDS = 15
KYSTVERKET_READLINE_MAX_BYTES = 1024  # a real AIVDM line is under 100 bytes;
                                       # this just bounds how much garbage a
                                       # misbehaving/non-AIS connection could
                                       # make readline() buffer before giving up.


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


# ---------------------------------------------------------- kystverket / AIVDM


def _sixbit_decode(payload: str) -> str:
    """
    AIVDM's payload armoring: each character encodes 6 bits, using the ASCII
    range 48-87 for values 0-39 and 96-119 for values 40-63 (the gap over
    48-87 skips punctuation that upsets some NMEA parsers). Concretely:
    subtract 48; if that's over 40, subtract another 8. Returns the decoded
    bits as a flat '0'/'1' string, six per input character.
    """
    bits = []
    for ch in payload:
        v = ord(ch) - 48
        if v > 40:
            v -= 8
        bits.append(format(v, "06b"))
    return "".join(bits)


def _bits_to_uint(bits: str, start: int, length: int) -> int:
    return int(bits[start:start + length], 2)


def _bits_to_int(bits: str, start: int, length: int) -> int:
    """Two's-complement signed field — used for longitude/latitude, which
    can be negative (west/south)."""
    v = _bits_to_uint(bits, start, length)
    if v >= (1 << (length - 1)):
        v -= 1 << length
    return v


def decode_aivdm_position(sentence: str) -> dict | None:
    """
    Decode a single AIVDM sentence carrying an AIS Message Type 1, 2, or 3
    (a Class A position report) into {"mmsi", "lat", "lon", "sog", "cog"},
    or None if this sentence isn't one we can use — wrong talker/type,
    multi-fragment, bad checksum, or missing/out-of-range fields.

    Verified against pyais's own published test fixtures (see module
    docstring) — the bit layout below (ITU-R M.1371's Common Navigation
    Block, ais_types 1/2/3, all exactly 168 bits) is:
        0-5    message type            (must be 1, 2, or 3 — else skip)
        8-37   MMSI                    30-bit unsigned
        50-59  speed over ground       10-bit unsigned, 0.1 knot units,
                                        1023 = not available
        61-88  longitude               28-bit signed, 1/600000 degree units
        89-115 latitude                27-bit signed, 1/600000 degree units
        116-127 course over ground     12-bit unsigned, 0.1 degree units,
                                        3600 = not available
    Every other field in the message (nav status, rate of turn, heading,
    timestamp, ...) isn't something vessel_status() uses today, so this
    intentionally only pulls out the five fields above rather than fully
    modeling the message — see fetch_aircraft()'s equivalent "just enough
    to be useful" philosophy on the flight side.

    Only handles single-fragment sentences (total-fragment count of 1).
    Types 1/2/3 always fit in one 168-bit sentence, so they're never
    fragmented in practice — multi-part reassembly (needed for longer types
    like 5/ShipStaticData) is deliberately not implemented here, since this
    decoder exists only to pull position fixes out of Kystverket's raw feed,
    not to replace aisstream.io's fuller message handling.
    """
    sentence = sentence.strip()
    if not sentence.startswith(("!AIVDM", "!AIVDO")):
        return None
    if "*" not in sentence:
        return None

    body, _, checksum_hex = sentence.rpartition("*")
    checksum = 0
    for ch in body[1:]:   # NMEA checksum covers everything between ! and *
        checksum ^= ord(ch)
    try:
        if checksum != int(checksum_hex.strip()[:2], 16):
            return None
    except ValueError:
        return None

    fields = body.split(",")
    if len(fields) < 7:
        return None
    try:
        total_fragments = int(fields[1])
        fragment_number = int(fields[2])
    except ValueError:
        return None
    if total_fragments != 1 or fragment_number != 1:
        return None  # multi-part message, see docstring

    payload = fields[5]
    try:
        fill_bits = int(fields[6])
    except ValueError:
        fill_bits = 0

    bits = _sixbit_decode(payload)
    if fill_bits:
        bits = bits[:-fill_bits] if fill_bits < len(bits) else bits
    if len(bits) < 168:
        return None

    msg_type = _bits_to_uint(bits, 0, 6)
    if msg_type not in (1, 2, 3):
        return None

    mmsi = _bits_to_uint(bits, 8, 30)
    sog_raw = _bits_to_uint(bits, 50, 10)
    lon_raw = _bits_to_int(bits, 61, 28)
    lat_raw = _bits_to_int(bits, 89, 27)
    cog_raw = _bits_to_uint(bits, 116, 12)

    lon = lon_raw / 600000.0
    lat = lat_raw / 600000.0
    # 181.0/91.0 are the spec's explicit "no fix" sentinels; the range check
    # beyond that is just defensive against a corrupt/garbled decode.
    if lon == 181.0 or lat == 91.0 or not (-180.0 <= lon <= 180.0) or not (-90.0 <= lat <= 90.0):
        return None

    sog = sog_raw / 10.0 if sog_raw != 1023 else None
    cog = cog_raw / 10.0 if cog_raw != 3600 else None

    return {"mmsi": str(mmsi), "lat": lat, "lon": lon, "sog": sog, "cog": cog}


def apply_kystverket_fix(cache: dict, fix: dict) -> bool:
    """
    Merge one decoded Kystverket position fix into the cache. Mirrors
    apply_message()'s PositionReport branch (same field names, so
    vessel_status() reads either source identically) but has no destination/
    IMO equivalent — Kystverket's basic feed carries no ShipStaticData here.
    """
    entry = cache.setdefault(fix["mmsi"], {})
    entry["lat"] = fix["lat"]
    entry["lon"] = fix["lon"]
    entry["sog"] = fix["sog"]
    entry["cog"] = fix["cog"]
    entry["timestamp"] = int(time.time())
    return True


async def _kystverket_listen_once(mmsis: set[str], deadline: float | None) -> None:
    """
    Connect to Kystverket's raw AIS relay, decode each line, and merge any
    fix for a tracked vessel into ais_cache.json, until `deadline` (an
    event-loop time.monotonic() timestamp, or None to run forever) passes.

    No lock around the cache read-modify-write: like _listen_once's
    identical pattern, load_cache()/apply_kystverket_fix()/save_cache() run
    back-to-back with no `await` in between, so even though this coroutine
    runs concurrently with the aisstream.io listener in the same asyncio
    event loop, control can only switch to the other task at an `await`
    point — never mid-way through this three-line sequence. That makes the
    two listeners safe to share one cache file without any extra
    synchronization.
    """
    reader, writer = await asyncio.open_connection(
        KYSTVERKET_HOST, KYSTVERKET_PORT, limit=KYSTVERKET_READLINE_MAX_BYTES
    )
    try:
        while True:
            if deadline is not None:
                remaining = deadline - asyncio.get_running_loop().time()
                if remaining <= 0:
                    return
                try:
                    line = await asyncio.wait_for(
                        reader.readline(), timeout=remaining
                    )
                except asyncio.TimeoutError:
                    return
            else:
                line = await reader.readline()

            if not line:
                return  # connection closed by the remote end

            try:
                sentence = line.decode("ascii", errors="ignore")
            except Exception:  # noqa: BLE001 — a garbled line is just skipped
                continue

            fix = decode_aivdm_position(sentence)
            if fix is None or fix["mmsi"] not in mmsis:
                continue

            cache = load_cache()
            if apply_kystverket_fix(cache, fix):
                save_cache(cache)
    finally:
        writer.close()


async def _kystverket_task(mmsis: list[str], deadline: float | None) -> None:
    """
    Wraps _kystverket_listen_once with the same reconnect-on-drop behavior
    _listen_once's callers give the aisstream.io socket, so the two sources
    fail independently — Kystverket being unreachable (this sandbox has no
    route to it at all, for instance) never takes down the aisstream.io
    listener running alongside it, and vice versa.
    """
    mmsi_set = set(mmsis)
    # This source is purely supplementary (see "Two sources, one cache" in
    # the module docstring) — its whole reason for running alongside
    # aisstream.io is to add coverage without ever being able to subtract
    # from it, so the except clauses below are deliberately broad (not just
    # OSError) rather than risking an edge case here (an oversized line
    # tripping asyncio's readline() length limit, say) propagating up through
    # asyncio.gather() and taking the aisstream.io task down with it.
    if deadline is not None:
        # Burst mode: one connect attempt, whatever it catches in the
        # remaining window. A failure here just means this run got nothing
        # from Kystverket — same "next scheduled run is the retry" shape as
        # the aisstream.io burst path.
        try:
            await _kystverket_listen_once(mmsi_set, deadline)
        except Exception as exc:  # noqa: BLE001 — see comment above
            print(f"[ais/kystverket] unreachable this run ({exc}) — "
                  f"continuing with aisstream.io only", file=sys.stderr)
        return

    # Continuous mode: reconnect forever on drop, same shape as the
    # aisstream.io continuous loop.
    while True:
        try:
            print("[ais/kystverket] connecting…")
            await _kystverket_listen_once(mmsi_set, deadline=None)
            print("[ais/kystverket] connection closed by remote, reconnecting…")
        except Exception as exc:  # noqa: BLE001 — see comment above
            print(f"[ais/kystverket] connection lost ({exc}), retrying in "
                  f"{KYSTVERKET_RECONNECT_BACKOFF_SECONDS}s…", file=sys.stderr)
        await asyncio.sleep(KYSTVERKET_RECONNECT_BACKOFF_SECONDS)


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


async def _aisstream_burst(api_key: str, mmsis: list[str], deadline: float) -> None:
    async with websockets.connect(STREAM_URL) as ws:
        await ws.send(json.dumps({
            "APIKey": api_key,
            "BoundingBoxes": WORLD_BOUNDING_BOX,
            "FiltersShipMMSI": mmsis,
            "FilterMessageTypes": SUBSCRIBED_MESSAGE_TYPES,
        }))
        try:
            await _listen_once(ws, mmsis, deadline)
        except (websockets.exceptions.WebSocketException, OSError) as exc:
            print(f"[ais/aisstream] connection lost mid-burst ({exc}) — keeping "
                  f"whatever was already cached", file=sys.stderr)


async def _aisstream_continuous(api_key: str, mmsis: list[str]) -> None:
    while True:
        try:
            async with websockets.connect(STREAM_URL) as ws:
                await ws.send(json.dumps({
                    "APIKey": api_key,
                    "BoundingBoxes": WORLD_BOUNDING_BOX,
                    "FiltersShipMMSI": mmsis,
                    "FilterMessageTypes": SUBSCRIBED_MESSAGE_TYPES,
                }))
                print("[ais/aisstream] subscribed, listening…")
                await _listen_once(ws, mmsis, deadline=None)

        except (websockets.exceptions.WebSocketException, OSError) as exc:
            print(f"[ais/aisstream] connection lost ({exc}), retrying in "
                  f"{RECONNECT_BACKOFF_SECONDS}s…", file=sys.stderr)
            await asyncio.sleep(RECONNECT_BACKOFF_SECONDS)


async def run() -> None:
    api_key = os.environ.get("AISSTREAM_API_KEY")
    if not api_key:
        # No longer fatal on its own: Kystverket needs no key at all, so a
        # missing AISSTREAM_API_KEY now means "aisstream.io is skipped this
        # run, Kystverket still runs" rather than "nothing runs". Global
        # aisstream.io coverage is still by far the more valuable of the two
        # sources, so this is loud about what's missing, not silent.
        print("AISSTREAM_API_KEY not set — skipping aisstream.io (get a free "
              "key at https://aisstream.io/authenticate). Continuing with "
              "the Kystverket feed alone.", file=sys.stderr)

    mmsis = tracked_mmsis()
    if not mmsis:
        print("No 'mmsi' entries in holdings.json — nothing to track from "
              "either AIS source. Add one per person with a tracked vessel "
              "and re-run.", file=sys.stderr)
        raise SystemExit(1)

    print(f"[ais] tracking {len(mmsis)} vessel(s): {', '.join(mmsis)}")

    if BURST_SECONDS > 0:
        # One connect/listen/exit cycle per source, run concurrently — see
        # "Two ways to run it" and "Two sources, one cache" in the module
        # docstring. Any connection failure in either is non-fatal to the
        # other (each swallows its own errors internally); the next
        # scheduled Actions run is the retry for whichever source missed
        # this window.
        print(f"[ais] burst mode: listening for {BURST_SECONDS}s then exiting")
        deadline = asyncio.get_running_loop().time() + BURST_SECONDS
        tasks = [_kystverket_task(mmsis, deadline)]
        if api_key:
            tasks.append(_aisstream_burst(api_key, mmsis, deadline))
        await asyncio.gather(*tasks)

        cache = load_cache()
        if prune_cache(cache, mmsis):
            save_cache(cache)
        print("[ais] burst complete")
        return

    # Continuous mode: both sources stay connected and reconnect forever on
    # drop, independently of each other.
    tasks = [_kystverket_task(mmsis, deadline=None)]
    if api_key:
        tasks.append(_aisstream_continuous(api_key, mmsis))
    await asyncio.gather(*tasks)


if __name__ == "__main__":
    asyncio.run(run())
