# Data sources

`locations.csv` and `roads.csv` are what `RoadNetworkLoader` reads at
startup to build the live app's 57-location Madison/UW-Madison network
(see [RoadNetwork.java](../src/RoadNetwork.java)). This file documents
where that data came from.

## locations.csv

Columns: `id,name,lat,lon,query,source,retrieved_at`.

Every `lat,lon` is a real geocoded point, not hand-estimated. `query` is
the exact string sent to the geocoder for that row (via
[`scripts/geocode.py`](../scripts/geocode.py)); `source` is which service
answered it (currently always Nominatim); `retrieved_at` is a real UTC
timestamp from when that query actually ran -- not backfilled or
estimated. A handful of rows (see "Locations that couldn't be
independently re-confirmed by name" below) have a `query` but a blank
`retrieved_at`: that query was attempted and returned no result, so
there's no real retrieval event to timestamp -- the existing coordinate
was kept rather than left blank or guessed at.

An earlier pass of this data was hand-estimated from memory and got some
relative positions wrong -- e.g. placing Dejope Residence Hall (actually
out near Eagle Heights, on the far west side) close to X01 near the Kohl
Center on the other side of campus, and assuming Witte/Sellery/Ogg were
Lakeshore dorms when they're actually the Southeast dorms on W Johnson/
Dayton St. Geocoding against Nominatim caught both errors; the real
Lakeshore corridor along Observatory Dr that Route 80 runs is Elizabeth
Waters / Slichter / Kronshage / Bradley / Dejope.

Nominatim doesn't always resolve a street *intersection* precisely (e.g.
"State St & Gilman St" can land at some other point along State St rather
than exactly that corner), so a handful of points are approximate to a
block or so -- but every point is real geocoded data, not invented.

A later check caught a duplicate: "Grainger Hall" and "Wisconsin School
of Business" were two separate entries only ~40m apart. Re-geocoding
confirmed they're the same building -- the Wisconsin School of Business
has no independent OSM entry, and its real address (975 University
Avenue) matches Grainger Hall's exactly. Merged into one entry,
`grainger_hall`, named "Grainger Hall (Wisconsin School of Business)".

A full re-verification pass (every location re-geocoded independently,
every result cross-checked, `scripts/geocode.py` built specifically to
make this repeatable) caught two real, meaningful errors, both fixed:

- **Education Building** was 515m off -- the stored point actually landed
  on Steenbock's, a restaurant in a different building on North Orchard
  Street. Corrected to the real Education Building at 1000 Observatory
  Drive.
- **Olbrich Gardens** was 178m off, on Sugar Avenue instead of the real
  entrance on Atwood Avenue. Corrected.

Both roads connected to these points in `roads.csv` were recomputed from
the corrected coordinates (real distances, not the old numbers left in
place): `morgridge_hall<->education_building` 0.05mi -> 0.31mi,
`atwood_schenks<->olbrich_gardens` 1.03mi -> 0.93mi,
`olbrich_gardens<->tenney_park` 1.77mi -> 1.67mi.

### Locations that couldn't be independently re-confirmed by name

Two different reasons, neither a known error:

- **Street-level and district-level names** (`willy_st` "Williamson St",
  `atwood_schenks` "Atwood Ave & Schenk's Corners") describe a stretch of
  street or a named commercial district, not a single address -- Nominatim
  returns a real but different point each time depending on which segment
  or landmark it happens to match, and 5 plain street-intersection queries
  (`king_st`, `john_nolen`, `state_gilman`, `monroe_edgewood`, `east_wash`)
  don't parse as "X & Y" at all. The stored points all fall in the
  geographically correct area; there's no single "more correct" point to
  move them to.
- **`social_sciences` and `kronshage_halls`** have no distinct point-of-
  interest node in OSM under any name variant tried, so a forward (name ->
  coordinate) query can't confirm them independently. Both were already
  confirmed a different way, in an earlier full audit: reverse-geocoding
  (coordinate -> address) their exact stored points returned real, plausible
  Madison addresses in the correct part of campus.

## roads.csv

Each road's `miles` is the real great-circle (haversine) distance between
its two endpoints' geocoded coordinates, not a guess. `busRoute` is the
real Madison Metro Transit route (per [cityofmadison.com/metro](https://www.cityofmadison.com/metro/))
that covers that corridor, or blank for a walk-only segment.

This is a hand-curated subset of the real street network -- not every
real street or intersection between two points is included, only enough
to connect the named locations plausibly. A separate research track
([pipeline/](../pipeline/)) ingests the full real OpenStreetMap street
graph for the same area instead of this curated subset -- see the
top-level README's "Research track" section.

## Reproducing this data

**Single lookup** -- geocode one query and print the result:

```bash
python3 scripts/geocode.py "Capitol Square, Madison, WI"
```

**Re-verify the whole network** -- build a `queries.csv` (`id,name,query`
columns) and run:

```bash
python3 scripts/geocode.py --batch queries.csv --out locations.csv --existing data/locations.csv
```

For each row, this re-geocodes `query` fresh, and:
- if there's no existing coordinate for that `id`, uses the fresh result;
- if there is, and it's within `--tolerance-m` (default 30m) of the fresh
  result, **keeps the existing coordinate** (it may already reflect manual
  correction beyond what a single query can capture) and just attaches
  real `query`/`source`/`retrieved_at` provenance;
- if it's further than that, keeps the existing coordinate but flags the
  row in the printed report -- a real discrepancy gets a human look
  (exactly how the Education Building and Olbrich Gardens errors above
  were caught), not a silent overwrite either direction.

Respects Nominatim's usage policy (max ~1 request/second, a descriptive
`User-Agent`) and caches results in `scripts/.geocode_cache/` (gitignored)
so re-running doesn't re-hit the API for queries already answered.
