# OSM ingestion pipeline

Ingests the real OpenStreetMap street network for Madison, WI / UW-Madison
into a routable graph -- the research-track counterpart to the live app's
hand-curated 58-location `data/locations.csv`/`data/roads.csv` (see the
top-level README's "Structural decision" for why the two stay separate:
this doesn't touch what the deployed app serves).

## Running it

Python 3 standard library only, no dependencies to install.

```bash
cd pipeline
python3 build_graph.py
```

This runs all three stages and writes the final dataset to
`../experiments/data/osm_locations.csv` and `osm_roads.csv`. Add `--force`
to re-fetch from Overpass instead of using the cached raw response.

Each stage can also be run and inspected on its own:

```bash
python3 fetch_osm.py    # -> data/raw/madison_overpass.json (real Overpass response)
python3 parse_osm.py    # -> data/parsed/nodes.json, edges.json (routable graph)
python3 build_graph.py  # -> ../experiments/data/osm_locations.csv, osm_roads.csv
```

`data/` (raw + parsed intermediates) is gitignored -- it's fully
regenerable and the raw response alone is ~14MB. Only the final CSVs in
`experiments/data/` are committed.

## What each stage does

- **`fetch_osm.py`**: queries the Overpass API (`overpass.kumi.systems` --
  the default `overpass-api.de` either 406s without a descriptive
  User-Agent or times out for a bounding box this size) for every
  `highway=*` way inside a bbox sized to match the live network's own
  lat/lon extent (`data/locations.csv`), plus the nodes those ways
  reference.
- **`parse_osm.py`**: an OSM way is a polyline through many nodes, most of
  which are just shape points, not real intersections. This collapses
  each way down to edges between actual graph nodes (a node shared by 2+
  ways, or a way's endpoint), summing the real haversine distance of
  every constituent segment in between. Carries over `highway`,
  `surface`, `incline`, `sidewalk`, `maxspeed`, `name`, `lanes` from each
  way's tags, and respects `oneway` (including `oneway=-1`, reversed).
- **`build_graph.py`**: runs both stages and writes the final CSVs.

## Validating the result

```bash
python3 ../scripts/validate_network.py
```

Checks dangling edges (a real bug if any exist -- the script exits
non-zero) and reports weakly-connected component sizes, since a graph
this size can't practically be checked with an every-pair-reachable test
like the live app's `RoadNetworkTest.java` (58 locations, is O(V^2) and
sized to match). A real bounding-box extract cuts through some roads at
the edge, so 100% single-component coverage isn't expected -- see the
run's actual output for the real number, not an assumed one.
