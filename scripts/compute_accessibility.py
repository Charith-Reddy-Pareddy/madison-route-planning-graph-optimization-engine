#!/usr/bin/env python3
"""Computes real accessibility attributes for every road in data/roads.csv, using the same real
OSM street graph scripts/compute_route_distances.py already draws from
(pipeline/data/parsed/{nodes,edges}.json). For each curated edge's real walking route (same
snapped endpoints and same walk-graph filtering as that script), walks the actual path and checks
every real OSM segment along it for two things:

- `hasSteps`: true if any segment of the real route is tagged `highway=steps` -- a route no
  wheelchair or stroller can use, regardless of how short it is.
- `maxInclinePercent`: the steepest real `incline` tag on any segment of the route (0 if none of
  the segments carry a real incline tag -- not "level", just "unmeasured"; see data/sources.md for
  how sparse this OSM tag actually is, 848 of 120,370 edges in the fetched extract).

Both are real, per-edge facts about the route actually taken to compute walkMiles for that road,
not guessed or estimated from the endpoints alone -- a road can be short in total distance and
still cross a single step or a steep ramp partway along it.

Usage:
    python3 scripts/compute_accessibility.py
Writes data/roads.csv in place, adding `hasSteps` and `maxInclinePercent` columns (after
`driveMiles`, before `busRoute`) if they don't already exist, or refreshing them if they do.
"""
import csv
import heapq
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from compute_route_distances import (
    ROOT, LOCATIONS_CSV, ROADS_CSV, NODES_JSON, EDGES_JSON, WALK_EXCLUDED_HIGHWAYS,
    load_locations, nearest_node, MAX_EXPLORED_NODES,
)
import json

FIELDNAMES = [
    "from", "to", "walkMiles", "driveMiles", "hasSteps", "maxInclinePercent", "accessibleMiles", "busRoute",
]

# A common ADA ramp-slope threshold; used only to decide which edges the *accessible* graph
# excludes, not to invent a distance -- accessibleMiles is always a real route, just one that never
# uses a real highway=steps segment or a real incline tag steeper than this.
MAX_ACCESSIBLE_INCLINE_PERCENT = 8.0


def build_walk_graphs_with_tags():
    """Returns (node_coord, walk_adj, accessible_adj) -- `walk_adj` is exactly what
    compute_route_distances.py uses (every edge except the excluded highway classes, tagged with
    steps/incline info too), `accessible_adj` is the same minus any edge that's real steps or has a
    real incline tag over the ADA threshold."""
    with open(NODES_JSON, encoding="utf-8") as f:
        nodes = json.load(f)
    with open(EDGES_JSON, encoding="utf-8") as f:
        edges = json.load(f)
    node_coord = {n["id"]: (n["lat"], n["lon"]) for n in nodes}
    walk_adj = {}
    accessible_adj = {}

    def incline_percent(tag):
        if not tag:
            return 0.0
        match = re.search(r"-?\d+(\.\d+)?", tag)
        return abs(float(match.group())) if match else 0.0

    def add(adj, a, b, e, has_steps, incline):
        adj.setdefault(a, []).append((b, e["meters"], has_steps, incline))

    for e in edges:
        if e.get("highway") not in WALK_EXCLUDED_HIGHWAYS:
            has_steps = e.get("highway") == "steps"
            incline = incline_percent(e.get("incline"))
            add(walk_adj, e["from"], e["to"], e, has_steps, incline)
            add(walk_adj, e["to"], e["from"], e, has_steps, incline)  # walk graph is undirected
            if not has_steps and incline <= MAX_ACCESSIBLE_INCLINE_PERCENT:
                add(accessible_adj, e["from"], e["to"], e, has_steps, incline)
                add(accessible_adj, e["to"], e["from"], e, has_steps, incline)

    return node_coord, walk_adj, accessible_adj


def dijkstra_with_path(adjacency, source, target):
    """Like compute_route_distances.dijkstra, but also returns the real sequence of edges taken
    (each (has_steps, incline_percent) pair), not just the total distance."""
    dist = {source: 0.0}
    pred_edge = {}  # node -> (predecessor, has_steps, incline_percent) of the edge that reached it
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
            path_edges = []
            cur = target
            while cur in pred_edge:
                prev, has_steps, incline = pred_edge[cur]
                path_edges.append((has_steps, incline))
                cur = prev
            return path_edges
        if explored > MAX_EXPLORED_NODES:
            return None
        for neighbor, weight, has_steps, incline in adjacency.get(node, ()):
            if neighbor in visited:
                continue
            nd = d + weight
            if nd < dist.get(neighbor, float("inf")):
                dist[neighbor] = nd
                pred_edge[neighbor] = (node, has_steps, incline)
                heapq.heappush(heap, (nd, neighbor))
    return None


def distance_only(adjacency, source, target):
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
        for neighbor, weight, _has_steps, _incline in adjacency.get(node, ()):
            if neighbor in visited:
                continue
            nd = d + weight
            if nd < dist.get(neighbor, float("inf")):
                dist[neighbor] = nd
                heapq.heappush(heap, (nd, neighbor))
    return None


def main():
    from compute_route_distances import METERS_PER_MILE

    locations = load_locations()
    with open(ROADS_CSV, newline="", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    print(f"Loaded {len(locations)} locations, {len(rows)} road rows.")

    print("Loading real OSM street graph (with accessibility tags)...")
    node_coord, walk_adj, accessible_adj = build_walk_graphs_with_tags()
    print(f"  walk graph: {sum(len(v) for v in walk_adj.values())} directed edges")
    print(f"  accessible graph (no steps, no incline over {MAX_ACCESSIBLE_INCLINE_PERCENT}%): "
          f"{sum(len(v) for v in accessible_adj.values())} directed edges")

    print("Snapping locations to nearest real graph node (walk and accessible separately)...")
    walk_snap, accessible_snap = {}, {}
    for loc_id, (lat, lon) in locations.items():
        walk_snap[loc_id], _d = nearest_node(lat, lon, node_coord, walk_adj)
        accessible_snap[loc_id], _d = nearest_node(lat, lon, node_coord, accessible_adj)

    print(f"Computing real accessibility for {len(rows)} road rows...")
    steps_count = 0
    steep_count = 0
    accessible_unreachable = []
    for row in rows:
        a, b = row["from"], row["to"]
        path_edges = dijkstra_with_path(walk_adj, walk_snap[a], walk_snap[b])
        if path_edges is None:
            row["hasSteps"] = ""
            row["maxInclinePercent"] = ""
        else:
            has_steps = any(steps for steps, _incline in path_edges)
            max_incline = max((incline for _steps, incline in path_edges), default=0.0)
            row["hasSteps"] = "true" if has_steps else "false"
            row["maxInclinePercent"] = str(round(max_incline, 1))
            if has_steps:
                steps_count += 1
            if max_incline > MAX_ACCESSIBLE_INCLINE_PERCENT:
                steep_count += 1

        accessible_m = distance_only(accessible_adj, accessible_snap[a], accessible_snap[b])
        if accessible_m is None:
            row["accessibleMiles"] = ""
            accessible_unreachable.append((a, b))
        else:
            row["accessibleMiles"] = str(round(accessible_m / METERS_PER_MILE, 3))

    with open(ROADS_CSV, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=FIELDNAMES)
        writer.writeheader()
        for row in rows:
            writer.writerow({k: row.get(k, "") for k in FIELDNAMES})

    print(f"\n{steps_count} of {len(rows)} roads have real steps somewhere along their default walking route.")
    print(f"{steep_count} of {len(rows)} roads have a real incline over {MAX_ACCESSIBLE_INCLINE_PERCENT}% "
          "somewhere along the default route.")
    if accessible_unreachable:
        print(f"\n{len(accessible_unreachable)} roads have NO accessible route at all "
              "(every real path requires steps or a steep incline):")
        for a, b in accessible_unreachable:
            print(f"  {a} -> {b}")
    else:
        print("\nEvery road has some real accessible route (may be a real detour vs. the default).")


if __name__ == "__main__":
    sys.exit(main())
