#!/usr/bin/env python3
"""Computes real walking and driving distances for every road in data/roads.csv.

The existing `miles` column is the straight-line (haversine) distance between two
locations' geocoded coordinates -- not a real route. This script replaces it with two
real numbers per edge, `walkMiles` and `driveMiles`, computed by running Dijkstra over
the real Madison street graph pipeline/ already fetched from OpenStreetMap
(pipeline/data/parsed/{nodes,edges}.json -- see pipeline/README.md), filtered per mode:

- walk graph: every edge except motorway/motorway_link/trunk/trunk_link (no legal
  pedestrian access), treated as undirected (a one-way *street* doesn't stop a
  pedestrian using its sidewalk in either direction).
- drive graph: every edge except footway/pedestrian/path/steps/cycleway/track/
  corridor/platform/bus_stop, kept directed exactly as parsed (parse_osm.py already
  resolved real oneway restrictions into which directed edges exist at all).

Each of the 57 curated locations is snapped to its nearest real OSM node in each
filtered graph (independently -- the nearest walkable node isn't always the nearest
drivable one), then Dijkstra runs from that snapped node to find the real route.

Usage:
    python3 scripts/compute_route_distances.py
Writes data/roads.csv in place (columns: from,to,walkMiles,driveMiles,busRoute) and
prints a report of the largest changes versus the old straight-line numbers, and any
edge that came back unreachable in either mode.
"""
import csv
import heapq
import json
import math
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LOCATIONS_CSV = ROOT / "data" / "locations.csv"
ROADS_CSV = ROOT / "data" / "roads.csv"
NODES_JSON = ROOT / "pipeline" / "data" / "parsed" / "nodes.json"
EDGES_JSON = ROOT / "pipeline" / "data" / "parsed" / "edges.json"

METERS_PER_MILE = 1609.344
DRIVE_EXCLUDED_HIGHWAYS = {
    "footway", "pedestrian", "path", "steps", "cycleway", "track", "corridor", "platform", "bus_stop",
}
WALK_EXCLUDED_HIGHWAYS = {"motorway", "motorway_link", "trunk", "trunk_link"}

# Safety cap so a disconnected pair can't turn into an unbounded full-graph scan --
# Madison campus/downtown pairs settle in a few thousand nodes at most when reachable.
MAX_EXPLORED_NODES = 45_000

# Snapping a location to the nearest OSM node is wrong if the nearest node is more than
# this far away (e.g. a location that's genuinely outside the fetched bounding box) --
# better to report it than silently snap to something implausible.
MAX_SNAP_METERS = 400


def haversine_meters(lat1, lon1, lat2, lon2):
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def load_locations():
    with open(LOCATIONS_CSV, newline="", encoding="utf-8") as f:
        return {row["id"]: (float(row["lat"]), float(row["lon"])) for row in csv.DictReader(f)}


def load_road_rows():
    with open(ROADS_CSV, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def build_graphs():
    with open(NODES_JSON, encoding="utf-8") as f:
        nodes = json.load(f)
    with open(EDGES_JSON, encoding="utf-8") as f:
        edges = json.load(f)

    node_coord = {n["id"]: (n["lat"], n["lon"]) for n in nodes}
    walk_adj = {}
    drive_adj = {}

    def add(adj, a, b, meters):
        adj.setdefault(a, []).append((b, meters))

    for e in edges:
        highway = e.get("highway")
        a, b, meters = e["from"], e["to"], e["meters"]
        if highway not in DRIVE_EXCLUDED_HIGHWAYS:
            add(drive_adj, a, b, meters)
        if highway not in WALK_EXCLUDED_HIGHWAYS:
            add(walk_adj, a, b, meters)
            add(walk_adj, b, a, meters)  # walk graph is undirected

    return node_coord, walk_adj, drive_adj


def nearest_node(lat, lon, node_coord, adjacency):
    best_id, best_dist = None, math.inf
    for node_id in adjacency.keys():
        nlat, nlon = node_coord[node_id]
        # Cheap pre-filter before the real haversine call.
        if abs(nlat - lat) > 0.01 or abs(nlon - lon) > 0.01:
            continue
        d = haversine_meters(lat, lon, nlat, nlon)
        if d < best_dist:
            best_dist, best_id = d, node_id
    return best_id, best_dist


def dijkstra(adjacency, source, target):
    dist = {source: 0.0}
    visited = set()
    heap = [(0.0, source)]
    explored = 0
    while heap:
        d, node = heapq.heappop(heap)
        if node in visited:
            continue
        visited.add(node)
        explored += 1
        if node == target:
            return d
        if explored > MAX_EXPLORED_NODES:
            return None
        for neighbor, weight in adjacency.get(node, ()):
            if neighbor in visited:
                continue
            nd = d + weight
            if nd < dist.get(neighbor, math.inf):
                dist[neighbor] = nd
                heapq.heappush(heap, (nd, neighbor))
    return None


def main():
    locations = load_locations()
    rows = load_road_rows()
    print(f"Loaded {len(locations)} locations, {len(rows)} road rows.")

    print("Loading real OSM street graph...")
    node_coord, walk_adj, drive_adj = build_graphs()
    print(f"  walk graph: {sum(len(v) for v in walk_adj.values())} directed edges over {len(walk_adj)} nodes")
    print(f"  drive graph: {sum(len(v) for v in drive_adj.values())} directed edges over {len(drive_adj)} nodes")

    print("Snapping locations to nearest real street-graph node (per mode)...")
    walk_snap, drive_snap = {}, {}
    for loc_id, (lat, lon) in locations.items():
        wid, wdist = nearest_node(lat, lon, node_coord, walk_adj)
        did, ddist = nearest_node(lat, lon, node_coord, drive_adj)
        walk_snap[loc_id] = wid
        drive_snap[loc_id] = did
        if wdist > MAX_SNAP_METERS:
            print(f"  WARNING: {loc_id} nearest walk node is {wdist:.0f}m away (>{MAX_SNAP_METERS}m)")
        if ddist > MAX_SNAP_METERS:
            print(f"  WARNING: {loc_id} nearest drive node is {ddist:.0f}m away (>{MAX_SNAP_METERS}m)")

    print(f"Computing real routes for {len(rows)} road rows...")
    unreachable = []
    biggest_changes = []
    for row in rows:
        a, b = row["from"], row["to"]
        old_miles = float(row["miles"])

        walk_m = dijkstra(walk_adj, walk_snap[a], walk_snap[b])
        drive_m = dijkstra(drive_adj, drive_snap[a], drive_snap[b])

        walk_miles = round(walk_m / METERS_PER_MILE, 3) if walk_m is not None else None
        drive_miles = round(drive_m / METERS_PER_MILE, 3) if drive_m is not None else None

        if walk_miles is None:
            unreachable.append((a, b, "walk"))
        if drive_miles is None:
            unreachable.append((a, b, "drive"))

        row["walkMiles"] = "" if walk_miles is None else str(walk_miles)
        row["driveMiles"] = "" if drive_miles is None else str(drive_miles)

        reference = walk_miles if walk_miles is not None else drive_miles
        if reference is not None:
            biggest_changes.append((abs(reference - old_miles), a, b, old_miles, walk_miles, drive_miles))

    with open(ROADS_CSV, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=["from", "to", "walkMiles", "driveMiles", "busRoute"])
        writer.writeheader()
        for row in rows:
            writer.writerow(
                {
                    "from": row["from"],
                    "to": row["to"],
                    "walkMiles": row["walkMiles"],
                    "driveMiles": row["driveMiles"],
                    "busRoute": row["busRoute"],
                }
            )

    biggest_changes.sort(reverse=True)
    print("\nLargest changes vs. the old straight-line distance:")
    for delta, a, b, old, walk, drive in biggest_changes[:15]:
        print(f"  {a} -> {b}: old(straight-line)={old}mi  walk={walk}mi  drive={drive}mi")

    if unreachable:
        print(f"\n{len(unreachable)} (edge, mode) pairs had no route within the real street graph:")
        for a, b, mode in unreachable:
            print(f"  {a} -> {b} ({mode})")
    else:
        print("\nEvery road was reachable in both walk and drive graphs.")


if __name__ == "__main__":
    sys.exit(main())
