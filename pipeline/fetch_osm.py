#!/usr/bin/env python3
"""Fetches real OpenStreetMap road data for the Madison, WI / UW-Madison
area from the Overpass API, for the research-track OSM ingestion pipeline
(see ../README.md and ../data/sources.md for how this differs from the
live app's hand-curated 58-location network).

Uses the overpass.kumi.systems mirror rather than the default
overpass-api.de instance: the default instance either 406s without a
descriptive User-Agent or times out under load for a bbox this size, while
kumi.systems has been reliable for this same query in practice.

Usage:
    python3 fetch_osm.py [--force]

Writes the raw Overpass JSON response to data/raw/madison_overpass.json.
Without --force, an existing cached file is reused instead of re-fetching
(be polite to a free public API -- don't refetch identical data on every
pipeline run).
"""
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

# Covers downtown Madison + the UW-Madison campus + near east/west sides --
# sized to match the real 58-location network's own lat/lon extent (see
# data/locations.csv), with a small margin, rather than all of Dane County.
BBOX = {"south": 43.03, "west": -89.47, "north": 43.10, "east": -89.32}

OVERPASS_URL = "https://overpass.kumi.systems/api/interpreter"
USER_AGENT = "MadisonRoutePlanningResearch/1.0 (charithpareddy@gmail.com)"

OUTPUT_PATH = Path(__file__).parent / "data" / "raw" / "madison_overpass.json"

# highway=* selects anything routable on foot or by road (residential
# streets, footways, paths, service roads, ...); excluding a handful of
# non-routable/meta values keeps out things like highway=street_lamp.
EXCLUDED_HIGHWAY_VALUES = {"proposed", "construction", "street_lamp", "elevator"}

QUERY_TEMPLATE = """
[out:json][timeout:120];
(
  way["highway"]({south},{west},{north},{east});
);
out body;
>;
out skel qt;
"""


def build_query() -> str:
    return QUERY_TEMPLATE.format(**BBOX)


def fetch(force: bool = False) -> dict:
    if OUTPUT_PATH.exists() and not force:
        print(f"Using cached Overpass response at {OUTPUT_PATH} (pass --force to re-fetch)")
        return json.loads(OUTPUT_PATH.read_text())

    query = build_query()
    print(f"Querying {OVERPASS_URL} for bbox {BBOX} ...")
    request = urllib.request.Request(
        OVERPASS_URL,
        data=query.encode("utf-8"),
        headers={"User-Agent": USER_AGENT, "Content-Type": "text/plain"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=150) as response:
            body = response.read()
    except urllib.error.HTTPError as e:
        print(f"Overpass request failed: HTTP {e.code} {e.reason}", file=sys.stderr)
        raise
    except urllib.error.URLError as e:
        print(f"Overpass request failed: {e.reason}", file=sys.stderr)
        raise

    data = json.loads(body)
    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT_PATH.write_text(json.dumps(data))
    print(f"Wrote raw Overpass response to {OUTPUT_PATH}")
    return data


def summarize(data: dict) -> None:
    elements = data.get("elements", [])
    nodes = [e for e in elements if e["type"] == "node"]
    ways = [e for e in elements if e["type"] == "way"]
    routable_ways = [
        w for w in ways if w.get("tags", {}).get("highway") not in EXCLUDED_HIGHWAY_VALUES
    ]
    print(f"Fetched {len(nodes)} nodes, {len(ways)} ways ({len(routable_ways)} routable).")


if __name__ == "__main__":
    force = "--force" in sys.argv
    result = fetch(force=force)
    summarize(result)
