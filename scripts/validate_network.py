#!/usr/bin/env python3
"""Structural sanity checks on a locations/roads CSV pair, for any graph
this size -- unlike RoadNetworkTest.java's every-pair-reachable check
(fine for the live app's 58 hand-curated locations), a graph with tens of
thousands of real OSM nodes needs a check that's actually O(V+E): this
computes weakly-connected components via union-find and reports how much
of the graph is one connected blob, rather than testing every pair.

Usage:
    python3 validate_network.py [locations.csv] [roads.csv]

Defaults to experiments/data/osm_locations.csv and osm_roads.csv (the
OSM-ingested research-track graph). Exits non-zero if any dangling edge
references an unknown node -- that's a real bug in the ingestion pipeline,
not just a data-quality note.
"""
import csv
import sys
from pathlib import Path

DEFAULT_LOCATIONS = Path(__file__).parent.parent / "experiments" / "data" / "osm_locations.csv"
DEFAULT_ROADS = Path(__file__).parent.parent / "experiments" / "data" / "osm_roads.csv"


class UnionFind:
    def __init__(self, items):
        self.parent = {item: item for item in items}
        self.rank = {item: 0 for item in items}

    def find(self, x):
        root = x
        while self.parent[root] != root:
            root = self.parent[root]
        while self.parent[x] != root:
            self.parent[x], x = root, self.parent[x]
        return root

    def union(self, a, b):
        ra, rb = self.find(a), self.find(b)
        if ra == rb:
            return
        if self.rank[ra] < self.rank[rb]:
            ra, rb = rb, ra
        self.parent[rb] = ra
        if self.rank[ra] == self.rank[rb]:
            self.rank[ra] += 1


def load_locations(path):
    with open(path) as f:
        return [row["id"] for row in csv.DictReader(f)]


def load_roads(path):
    with open(path) as f:
        return [(row["from"], row["to"]) for row in csv.DictReader(f)]


def validate(locations_path, roads_path):
    ids = load_locations(locations_path)
    id_set = set(ids)
    roads = load_roads(roads_path)

    duplicate_ids = len(ids) - len(id_set)

    dangling = [(f, t) for f, t in roads if f not in id_set or t not in id_set]
    self_loops = [(f, t) for f, t in roads if f == t]

    uf = UnionFind(id_set)
    for f, t in roads:
        if f in id_set and t in id_set:
            uf.union(f, t)

    components = {}
    for node_id in id_set:
        root = uf.find(node_id)
        components.setdefault(root, []).append(node_id)
    component_sizes = sorted((len(members) for members in components.values()), reverse=True)

    largest = component_sizes[0] if component_sizes else 0
    coverage_pct = (100.0 * largest / len(id_set)) if id_set else 0.0

    print(f"Locations: {len(ids)} ({duplicate_ids} duplicate ids)")
    print(f"Roads: {len(roads)}")
    # Not a bug: an OSM way whose first and last node are the same real
    # intersection (a parking-lot loop, a closed loop street) parses to a
    # from==to edge with a real, nonzero length -- Dijkstra never selects
    # it (there's always a free 0-cost "already there" alternative), so
    # it's inert rather than wrong.
    print(f"Self-loop edges (from == to, e.g. closed loop roads): {len(self_loops)}")
    print(f"Dangling edges (reference an unknown node): {len(dangling)}")
    print(f"Weakly-connected components: {len(component_sizes)}")
    print(f"Largest component: {largest} nodes ({coverage_pct:.2f}% of all nodes)")
    if len(component_sizes) > 1:
        print(f"Next 5 largest components: {component_sizes[1:6]}")

    if dangling:
        print(f"\nFAIL: {len(dangling)} edges reference a node not present in {locations_path}", file=sys.stderr)
        return False
    return True


if __name__ == "__main__":
    locations_path = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_LOCATIONS
    roads_path = Path(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_ROADS
    ok = validate(locations_path, roads_path)
    sys.exit(0 if ok else 1)
