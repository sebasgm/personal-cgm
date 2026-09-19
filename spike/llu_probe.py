#!/usr/bin/env python3
"""Stage 1 probe: find out how LibreLinkUp actually behaves for *your* account.

    export LLU_EMAIL=you@example.com
    read -rs LLU_PASSWORD && export LLU_PASSWORD

    ./spike/llu_probe.py login     # region, patient id, sensor, units
    ./spike/llu_probe.py probe     # one-shot current value + latency
    ./spike/llu_probe.py record    # poll and record; run for a few hours
    ./spike/llu_probe.py stats     # analyse what record collected

The recorded file is the offline fixture the Android app is developed against,
so we never debug UI against the live API.
"""

from __future__ import annotations

import argparse
import json
import os
import statistics
import sys
import time
from datetime import datetime

from llu import (
    LibreLinkUp,
    LibreLinkUpError,
    RateLimited,
    Reading,
    _utcnow,
    load_client,
    save_cache,
)

FIXTURE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixtures")


def mask(value: str | None, keep: int = 4) -> str:
    """Hide most of an identifier.

    The probe's output often gets pasted into issues, chat logs and AI sessions.
    Patient ids and sensor serials identify a person's medical device, so they are
    masked unless explicitly asked for with --show-ids.
    """
    if not value:
        return "?"
    if len(value) <= keep:
        return "*" * len(value)
    return value[:keep] + "\u2026" + "*" * 4


def _pick_patient(client: LibreLinkUp, patient_id: str | None) -> dict:
    conns = client.connections()
    if not conns:
        raise SystemExit(
            "No connections on this account.\n"
            "The sensor wearer must invite this email as a follower in the LibreLink\n"
            "app, and the invitation must be accepted."
        )
    if patient_id:
        for c in conns:
            if c.get("patientId") == patient_id:
                return c
        raise SystemExit(f"patient {patient_id} not found in this account")
    if len(conns) > 1:
        print(f"note: {len(conns)} connections, using the first", file=sys.stderr)
    return conns[0]


def cmd_login(args) -> int:
    client = load_client()
    client.login()
    save_cache(client)

    conn = _pick_patient(client, args.patient)
    current, history = client.graph(conn["patientId"])
    sensor = conn.get("sensor") or {}

    expiry = (
        datetime.fromtimestamp(client.token_expires).isoformat(timespec="seconds")
        if client.token_expires
        else "unknown"
    )

    print("Authenticated.\n")
    print(f"  region          {client.region}   (set LLU_REGION={client.region} to skip the redirect)")
    print(f"  token expires   {expiry}")
    show = args.show_ids
    patient_id = conn["patientId"]
    serial = sensor.get("sn")
    print(f"  patient id      {patient_id if show else mask(patient_id, 8)}")
    if show:
        print(f"  patient         {conn.get('firstName','?')} {conn.get('lastName','')}".rstrip())
    print(f"  sensor serial   {serial if show else mask(serial)}")
    if sensor.get("a"):
        started = datetime.fromtimestamp(sensor["a"])
        age_days = (datetime.now() - started).total_seconds() / 86400
        print(f"  sensor started  {started.isoformat(timespec='minutes')}  ({age_days:.1f} days ago)")
    if current:
        print(f"  display unit    {current.unit}")
        print(f"  target range    {conn.get('targetLow','?')}-{conn.get('targetHigh','?')}")
    print(f"  graph points    {len(history)}")
    print("\nPut this in your environment to skip the region redirect on every call:")
    print(f"  export LLU_REGION={client.region}")
    return 0


def cmd_probe(args) -> int:
    client = load_client()
    conn = _pick_patient(client, args.patient)
    save_cache(client)
    current, history = client.graph(conn["patientId"])

    if not current:
        print("No current measurement. Sensor may be warming up or out of range.")
        return 1

    print(f"  glucose   {current.value_display:g} {current.unit}   {current.trend_label()}")
    print(f"  measured  {current.timestamp_utc}")
    print(f"  latency   {current.age_seconds():.0f}s behind the sensor")
    if current.is_low or current.is_high:
        print(f"  flags     {'LOW' if current.is_low else ''}{'HIGH' if current.is_high else ''}")

    if history:
        spacing = _spacings(history)
        print(f"\n  graph     {len(history)} points, "
              f"{history[0].timestamp_utc} .. {history[-1].timestamp_utc}")
        if spacing:
            print(f"  spacing   median {statistics.median(spacing)/60:.1f} min")
    return 0


def _spacings(readings: list[Reading]) -> list[float]:
    ordered = sorted(readings, key=lambda r: r.dt)
    return [
        (b.dt - a.dt).total_seconds() for a, b in zip(ordered, ordered[1:])
    ]


def cmd_record(args) -> int:
    client = load_client()
    conn = _pick_patient(client, args.patient)
    save_cache(client)
    patient_id = conn["patientId"]

    os.makedirs(FIXTURE_DIR, exist_ok=True)
    path = args.out or os.path.join(
        FIXTURE_DIR, f"readings-{datetime.now():%Y%m%d-%H%M%S}.jsonl"
    )

    print(f"Recording to {path}")
    print(f"Polling every {args.interval}s. Ctrl-C to stop.\n")

    seen: set[str] = set()
    deadline = time.time() + args.duration * 60 if args.duration else None
    polls = 0
    errors = 0

    try:
        with open(path, "a", buffering=1) as f:
            while deadline is None or time.time() < deadline:
                try:
                    current, history = client.graph(patient_id)
                    polls += 1

                    # Backfill history once, then only append genuinely new points.
                    batch = ([*history, current] if polls == 1 else [current])
                    fresh = 0
                    for r in batch:
                        if r is None or r.timestamp_utc in seen:
                            continue
                        seen.add(r.timestamp_utc)
                        f.write(json.dumps(
                            {**r.to_json(), "observed_utc": _utcnow().isoformat()}
                        ) + "\n")
                        fresh += 1

                    if current:
                        marker = "NEW " if fresh else "    "
                        print(f"{marker}{datetime.now():%H:%M:%S}  "
                              f"{current.value_display:6g} {current.unit}  "
                              f"{current.trend_label():<18}  "
                              f"age {current.age_seconds():4.0f}s  "
                              f"({len(seen)} stored)")

                except RateLimited as e:
                    errors += 1
                    print(f"    rate limited, sleeping {e.retry_after:.0f}s", file=sys.stderr)
                    time.sleep(e.retry_after)
                    continue
                except LibreLinkUpError as e:
                    errors += 1
                    print(f"    error: {e}", file=sys.stderr)

                time.sleep(args.interval)
    except KeyboardInterrupt:
        print("\nstopped")

    save_cache(client)
    print(f"\n{len(seen)} readings, {polls} polls, {errors} errors -> {path}")
    print(f"Now run:  ./spike/llu_probe.py stats {path}")
    return 0


def cmd_stats(args) -> int:
    path = args.file or _latest_fixture()
    if not path:
        raise SystemExit("no fixture found; run `record` first")

    rows = [json.loads(line) for line in open(path) if line.strip()]
    if not rows:
        raise SystemExit(f"{path} is empty")

    readings = [
        Reading(**{k: v for k, v in r.items() if k != "observed_utc"}) for r in rows
    ]
    readings.sort(key=lambda r: r.dt)
    values = [r.value_display for r in readings]

    print(f"{path}\n")
    print(f"  readings    {len(readings)}")
    print(f"  span        {readings[0].timestamp_utc}")
    print(f"              {readings[-1].timestamp_utc}")
    print(f"  unit        {readings[0].unit}")
    print(f"  range       {min(values):g} .. {max(values):g}  (median {statistics.median(values):g})")

    spacing = _spacings(readings)
    if spacing:
        print("\n  cadence between readings:")
        print(f"    median    {statistics.median(spacing)/60:.2f} min")
        print(f"    min/max   {min(spacing)/60:.2f} / {max(spacing)/60:.2f} min")
        gaps = [s for s in spacing if s > 900]
        if gaps:
            print(f"    gaps >15m {len(gaps)}  (largest {max(gaps)/60:.0f} min)")

    # Latency = how far behind the sensor a reading was when we first saw it.
    lags = [
        (datetime.fromisoformat(r["observed_utc"])
         - datetime.fromisoformat(r["timestamp_utc"])).total_seconds()
        for r in rows if "observed_utc" in r
    ]
    if lags:
        lags.sort()
        print("\n  cloud latency at first observation:")
        print(f"    median    {statistics.median(lags)/60:.2f} min")
        print(f"    p90       {lags[int(len(lags)*0.9)]/60:.2f} min")
        print(f"    max       {max(lags)/60:.2f} min")
        print("\n  -> this is the floor for how fresh the watch can ever be.")
    return 0


def _latest_fixture() -> str | None:
    if not os.path.isdir(FIXTURE_DIR):
        return None
    files = sorted(
        (os.path.join(FIXTURE_DIR, f) for f in os.listdir(FIXTURE_DIR) if f.endswith(".jsonl")),
        key=os.path.getmtime,
    )
    return files[-1] if files else None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--patient", help="patient id (default: first connection)")
    sub = parser.add_subparsers(dest="cmd", required=True)

    login = sub.add_parser("login", help="authenticate and describe the account")
    login.add_argument(
        "--show-ids",
        action="store_true",
        help="print patient id, name and sensor serial in full (masked by default)",
    )
    sub.add_parser("probe", help="one-shot current reading")

    rec = sub.add_parser("record", help="poll and record readings to a fixture")
    rec.add_argument("--interval", type=int, default=60, help="seconds between polls (default 60)")
    rec.add_argument("--duration", type=int, default=0, help="minutes to run (default: until Ctrl-C)")
    rec.add_argument("--out", help="output path")

    st = sub.add_parser("stats", help="analyse a recorded fixture")
    st.add_argument("file", nargs="?", help="fixture path (default: most recent)")

    args = parser.parse_args()
    if not hasattr(args, "show_ids"):
        args.show_ids = False
    handler = {"login": cmd_login, "probe": cmd_probe, "record": cmd_record, "stats": cmd_stats}[args.cmd]
    try:
        return handler(args)
    except LibreLinkUpError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
