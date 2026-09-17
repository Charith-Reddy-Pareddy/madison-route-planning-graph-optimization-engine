#!/usr/bin/env python3
"""Runs the full OSM ingestion pipeline (fetch -> parse -> emit) and
writes the resulting real Madison street graph to experiments/data/, as
its own dataset separate from the live app's data/locations.csv and
data/roads.csv -- see the "Structural decision" section of the project
plan for why the two are kept apart.

Usage:
    python3 build_graph.py [--force]

--force re-fetches from Overpass instead of using the cached raw response.
"""
import csv
import sys
from pathlib import Path

import fetch_osm
import parse_osm

OUT_DIR = Path(__file__).parent.parent / "experiments" / "data"
LOCATIONS_CSV = OUT_DIR / "osm_locations.csv"
ROADS_CSV = OUT_DIR / "osm_roads.csv"


def write_locations_csv(nodes):
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    with open(LOCATIONS_CSV, "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["id", "lat", "lon"])
        for n in nodes:
            writer.writerow([n["id"], n["lat"], n["lon"]])


def write_roads_csv(edges):
    with open(ROADS_CSV, "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["from", "to", "meters", "highway", "surface", "incline", "sidewalk", "maxspeed", "name"])
        for e in edges:
            writer.writerow(
                [
                    e["from"],
                    e["to"],
                    round(e["meters"], 2),
                    e.get("highway", ""),
                    e.get("surface", ""),
                    e.get("incline", ""),
                    e.get("sidewalk", ""),
                    e.get("maxspeed", ""),
                    e.get("name", ""),
                ]
            )


def main():
    force = "--force" in sys.argv
    raw = fetch_osm.fetch(force=force)
    nodes, edges = parse_osm.parse(raw)

    write_locations_csv(nodes)
    write_roads_csv(edges)

    print(f"Wrote {len(nodes)} locations to {LOCATIONS_CSV}")
    print(f"Wrote {len(edges)} roads to {ROADS_CSV}")


if __name__ == "__main__":
    sys.exit(main())
