#!/usr/bin/env python3
"""Turns the raw Overpass OSM response (fetch_osm.py's output) into a
routable directed graph: real intersections as nodes, real street segments
as edges, with real distances and real OSM tags carried through.

An OSM way is a polyline through many nodes, most of which are just shape
points, not intersections -- routing over every one of them would produce
a graph with a huge number of degree-2 nodes that add nothing. This
collapses each way down to edges between actual graph nodes (an
intersection, i.e. a node shared by 2+ ways, or a way's endpoint), summing
the real haversine distance of every constituent segment in between so an
edge's length is the real path length, not a straight-line shortcut.

Usage (standalone, for inspecting the parse step on its own):
    python3 parse_osm.py

Importable as a module: parse(raw_data) -> (nodes, edges).
"""
import json
import math
import sys
from pathlib import Path

import fetch_osm

# Meta/non-routable highway values to exclude even though they carry a
# highway=* tag (matches fetch_osm's own exclusion list).
EXCLUDED_HIGHWAY_VALUES = fetch_osm.EXCLUDED_HIGHWAY_VALUES

# Tags worth carrying onto each edge -- surface/incline/sidewalk feed the
# accessibility-aware routing work planned for later; maxspeed and name
# are useful for display and sanity-checking.
CARRIED_TAGS = ["highway", "surface", "incline", "sidewalk", "maxspeed", "name", "lanes"]

EARTH_RADIUS_M = 6371000.0


def haversine_m(lat1, lon1, lat2, lon2):
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(min(1.0, math.sqrt(a)))


def is_oneway_forward_only(tags):
    return tags.get("oneway") in ("yes", "1", "true")


def is_oneway_reversed(tags):
    return tags.get("oneway") == "-1"


def parse(raw_data):
    elements = raw_data["elements"]
    node_coords = {}
    ways = []
    for el in elements:
        if el["type"] == "node":
            node_coords[el["id"]] = (el["lat"], el["lon"])
        elif el["type"] == "way":
            tags = el.get("tags", {})
            if tags.get("highway") in EXCLUDED_HIGHWAY_VALUES:
                continue
            if len(el.get("nodes", [])) < 2:
                continue
            ways.append(el)

    # A node is a real graph node (an intersection or a dead end) if more
    # than one way passes through it, or if it's the first/last node of
    # any way -- everything else is just a shape point along a segment.
    node_way_count = {}
    for way in ways:
        seen_in_this_way = set(way["nodes"])
        for node_id in seen_in_this_way:
            node_way_count[node_id] = node_way_count.get(node_id, 0) + 1

    graph_node_ids = set()
    for way in ways:
        way_nodes = way["nodes"]
        graph_node_ids.add(way_nodes[0])
        graph_node_ids.add(way_nodes[-1])
        for node_id in way_nodes:
            if node_way_count.get(node_id, 0) > 1:
                graph_node_ids.add(node_id)

    graph_nodes = {}
    for node_id in graph_node_ids:
        if node_id not in node_coords:
            continue  # referenced but not returned by Overpass (bbox edge) -- skip
        lat, lon = node_coords[node_id]
        graph_nodes[node_id] = {"id": f"osm{node_id}", "lat": lat, "lon": lon}

    edges = []
    for way in ways:
        tags = way.get("tags", {})
        way_nodes = way["nodes"]

        segment_start_idx = 0
        accumulated_m = 0.0
        for i in range(1, len(way_nodes)):
            prev_id, cur_id = way_nodes[i - 1], way_nodes[i]
            if prev_id not in node_coords or cur_id not in node_coords:
                continue
            lat1, lon1 = node_coords[prev_id]
            lat2, lon2 = node_coords[cur_id]
            accumulated_m += haversine_m(lat1, lon1, lat2, lon2)

            if cur_id in graph_node_ids:
                start_id = way_nodes[segment_start_idx]
                if start_id in graph_nodes and cur_id in graph_nodes and accumulated_m > 0:
                    edge_tags = {k: tags[k] for k in CARRIED_TAGS if k in tags}
                    from_node = graph_nodes[start_id]["id"]
                    to_node = graph_nodes[cur_id]["id"]
                    forward_only = is_oneway_forward_only(tags)
                    reversed_only = is_oneway_reversed(tags)
                    if reversed_only:
                        edges.append({"from": to_node, "to": from_node, "meters": accumulated_m, **edge_tags})
                    elif forward_only:
                        edges.append({"from": from_node, "to": to_node, "meters": accumulated_m, **edge_tags})
                    else:
                        edges.append({"from": from_node, "to": to_node, "meters": accumulated_m, **edge_tags})
                        edges.append({"from": to_node, "to": from_node, "meters": accumulated_m, **edge_tags})
                segment_start_idx = i
                accumulated_m = 0.0

    return list(graph_nodes.values()), edges


def main():
    raw = fetch_osm.fetch(force=False)
    nodes, edges = parse(raw)
    print(f"Parsed {len(nodes)} graph nodes and {len(edges)} directed edges.")

    out_dir = Path(__file__).parent / "data" / "parsed"
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "nodes.json").write_text(json.dumps(nodes))
    (out_dir / "edges.json").write_text(json.dumps(edges))
    print(f"Wrote {out_dir}/nodes.json and {out_dir}/edges.json")


if __name__ == "__main__":
    sys.exit(main())
