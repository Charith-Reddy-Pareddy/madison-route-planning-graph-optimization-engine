#!/usr/bin/env python3
"""Formalizes the ad-hoc Nominatim geocoding workflow used throughout this
project's history (see data/sources.md) into a real, reusable script, so
adding or re-verifying a location doesn't mean hand-typing a curl command.

Usage (single lookup, prints the result):
    python3 geocode.py "Capitol Square, Madison, WI"

Usage (verify/refresh data/locations.csv against a queries file):
    python3 geocode.py --batch queries.csv --out locations.csv

queries.csv columns: id,name,query
Output locations.csv columns: id,name,lat,lon,query,source,retrieved_at

For each row, if the existing lat/lon (when --existing is given) is within
--tolerance-m of the freshly geocoded point, the *existing* coordinate is
kept (it's already been through however many rounds of manual
verification produced it) and only the query/source/retrieved_at
provenance columns are filled in from this run. If it's further than
that, the row is flagged in the printed report rather than silently
overwritten -- a real discrepancy needs a human look, not an automatic
fix.

Respects Nominatim's usage policy: max ~1 request/second, a descriptive
User-Agent, results cached to --cache-dir so re-running doesn't re-hit the
API for queries already answered.
"""
import argparse
import csv
import hashlib
import json
import math
import subprocess
import sys
import time
import urllib.parse
from datetime import datetime, timezone
from pathlib import Path

NOMINATIM_URL = "https://nominatim.openstreetmap.org/search"
USER_AGENT = "MadisonRoutePlanningResearch/1.0 (charithpareddy@gmail.com)"
RATE_LIMIT_SECONDS = 1.1
DEFAULT_TOLERANCE_M = 30

SOURCE = "Nominatim (OpenStreetMap)"


def haversine_m(lat1, lon1, lat2, lon2):
    R = 6371000.0
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2) ** 2
    return 2 * R * math.asin(min(1.0, math.sqrt(a)))


def cache_path(cache_dir: Path, query: str) -> Path:
    digest = hashlib.sha256(query.encode("utf-8")).hexdigest()[:16]
    return cache_dir / f"{digest}.json"


def geocode(query: str, cache_dir: Path = None):
    """Returns (lat, lon, retrieved_at_iso) for the top Nominatim match, or
    None if nothing matched. retrieved_at is real: either the timestamp of
    this actual request, or of the cached request it's reusing."""
    if cache_dir:
        cache_dir.mkdir(parents=True, exist_ok=True)
        cached = cache_path(cache_dir, query)
        if cached.exists():
            data = json.loads(cached.read_text())
            return data["lat"], data["lon"], data["retrieved_at"]

    url = NOMINATIM_URL + "?" + urllib.parse.urlencode({"q": query, "format": "json", "limit": 1})
    try:
        # Shells out to curl rather than urllib.request: on a stock
        # python.org macOS install, urllib's SSL verification fails
        # against a CA bundle that install just doesn't ship with (a
        # well-known gotcha -- see python.org's "Install Certificates"
        # step), while curl uses the OS's own, already-populated trust
        # store. curl is as close to a universal dependency as anything
        # not in the stdlib, and this keeps the script working the same
        # way regardless of which Python happens to be on PATH.
        proc = subprocess.run(
            ["curl", "-s", "-G", url, "-A", USER_AGENT],
            capture_output=True, text=True, timeout=15,
        )
        results = json.loads(proc.stdout)
    except (subprocess.SubprocessError, json.JSONDecodeError) as e:
        print(f"  geocoding failed for {query!r}: {e}", file=sys.stderr)
        return None
    finally:
        time.sleep(RATE_LIMIT_SECONDS)

    if not results:
        return None

    lat, lon = float(results[0]["lat"]), float(results[0]["lon"])
    retrieved_at = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    if cache_dir:
        cache_path(cache_dir, query).write_text(json.dumps({"lat": lat, "lon": lon, "retrieved_at": retrieved_at}))

    return lat, lon, retrieved_at


def run_batch(queries_path: Path, out_path: Path, existing_path: Path, tolerance_m: float, cache_dir: Path):
    with open(queries_path) as f:
        queries = list(csv.DictReader(f))

    existing = {}
    if existing_path and existing_path.exists():
        with open(existing_path) as f:
            for row in csv.DictReader(f):
                existing[row["id"]] = (float(row["lat"]), float(row["lon"]))

    out_rows = []
    flagged = []
    for i, row in enumerate(queries):
        query = row["query"]
        print(f"[{i + 1}/{len(queries)}] {row['id']}: {query!r}")
        result = geocode(query, cache_dir=cache_dir)
        if result is None:
            print(f"  NO RESULT -- keeping existing coordinate if any, flagging for review")
            flagged.append((row["id"], row["name"], "no geocode result"))
            if row["id"] in existing:
                lat, lon = existing[row["id"]]
                out_rows.append({"id": row["id"], "name": row["name"], "lat": lat, "lon": lon,
                                  "query": query, "source": SOURCE, "retrieved_at": ""})
            continue

        fresh_lat, fresh_lon, retrieved_at = result
        if row["id"] in existing:
            existing_lat, existing_lon = existing[row["id"]]
            dist = haversine_m(existing_lat, existing_lon, fresh_lat, fresh_lon)
            if dist > tolerance_m:
                flagged.append((row["id"], row["name"], f"existing coordinate is {dist:.0f}m from fresh geocode"))
            # Keep the already-vetted coordinate either way; this run's job
            # is to attach real provenance, not silently overwrite a point
            # that's already been through manual verification.
            lat, lon = existing_lat, existing_lon
        else:
            lat, lon = fresh_lat, fresh_lon

        out_rows.append({"id": row["id"], "name": row["name"], "lat": lat, "lon": lon,
                          "query": query, "source": SOURCE, "retrieved_at": retrieved_at})

    with open(out_path, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=["id", "name", "lat", "lon", "query", "source", "retrieved_at"])
        writer.writeheader()
        writer.writerows(out_rows)

    print(f"\nWrote {len(out_rows)} rows to {out_path}")
    if flagged:
        print(f"\n{len(flagged)} row(s) flagged for manual review:")
        for fid, fname, reason in flagged:
            print(f"  {fid} ({fname}): {reason}")
    return len(flagged) == 0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("query", nargs="?", help="A single query to geocode and print, e.g. 'Capitol Square, Madison, WI'")
    parser.add_argument("--batch", type=Path, help="CSV file with id,name,query columns")
    parser.add_argument("--out", type=Path, help="Output CSV path for --batch mode")
    parser.add_argument("--existing", type=Path, help="Existing locations.csv to preserve vetted coordinates from")
    parser.add_argument("--tolerance-m", type=float, default=DEFAULT_TOLERANCE_M,
                         help=f"Flag (don't overwrite) if fresh geocode differs from existing by more than this many meters (default {DEFAULT_TOLERANCE_M})")
    parser.add_argument("--cache-dir", type=Path, default=Path(__file__).parent / ".geocode_cache",
                         help="Cache geocode results here so re-runs don't re-hit the API")
    args = parser.parse_args()

    if args.batch:
        if not args.out:
            parser.error("--batch requires --out")
        ok = run_batch(args.batch, args.out, args.existing, args.tolerance_m, args.cache_dir)
        sys.exit(0 if ok else 1)

    if not args.query:
        parser.error("provide a query, or use --batch")

    result = geocode(args.query, cache_dir=args.cache_dir)
    if result is None:
        print(f"No result for {args.query!r}", file=sys.stderr)
        sys.exit(1)
    lat, lon, retrieved_at = result
    print(f"{lat},{lon}  (retrieved {retrieved_at} via {SOURCE})")


if __name__ == "__main__":
    main()
