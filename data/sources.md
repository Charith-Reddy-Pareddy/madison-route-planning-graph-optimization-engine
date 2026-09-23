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

Each road has two real, independently-computed distances -- `walkMiles`
and `driveMiles` -- not one straight-line number. An earlier version of
this file had a single `miles` column that was the great-circle
(haversine) distance between the two endpoints' geocoded coordinates:
real coordinates, but a straight line through buildings and around the
actual street grid, not a route anyone could walk or drive. That
consistently understated real distance (a straight line is a lower bound
on any real route) and couldn't distinguish walking from driving at all.

Both columns now come from [`scripts/compute_route_distances.py`](../scripts/compute_route_distances.py):
real Dijkstra shortest-path search over the real Madison street graph
already fetched from OpenStreetMap for the research track
([pipeline/data/parsed/{nodes,edges}.json](../pipeline/README.md)),
filtered per mode --

- **walk**: every real street/path/sidewalk edge except
  motorway/motorway_link/trunk/trunk_link (no legal pedestrian access),
  treated as undirected -- a one-way *street* doesn't stop a pedestrian
  using its sidewalk against traffic.
- **drive**: every real edge except footway/pedestrian/path/steps/
  cycleway/track/corridor/platform/bus_stop, kept directed exactly as
  parsed (`parse_osm.py` already resolves real `oneway` tags into which
  directed edges exist at all, so this respects real one-way streets).

Each of the 57 curated locations is snapped independently to its nearest
real graph node in each filtered mode -- the nearest walkable point isn't
always the nearest drivable one. Real effect of switching from
straight-line to a real route: every corrected distance got *longer*
(as expected -- a straight line is a lower bound), by as much as 2x for
routes that have to go around a lake or follow a road grid instead of
cutting a diagonal (e.g. `monroe_edgewood <-> arboretum` went from a
straight-line 1.07mi to a real 2.15mi walk).

`busRoute` is still the real Madison Metro Transit route (per
[cityofmadison.com/metro](https://www.cityofmadison.com/metro/)) that
covers that corridor, or blank for a walk-only segment.

**Known gap**: 6 of the 122 directed rows came back with no drivable
route found in the fetched OSM extract (`walkMiles` still real and
present for all of them) -- `witte_hall<->ogg_hall` in both directions,
plus one direction each for `camp_randall->union_south`,
`engineering_hall->union_south`, `nicholas_rec->union_south`, and
`morgridge_hall->education_building`. `union_south` is not actually
drive-isolated (all three outbound directions from it resolved fine --
only the return trip didn't, plausibly a real one-way access loop near
its entrance); `witte_hall<->ogg_hall` is the one genuine gap worth
flagging, since it's Ogg Hall's *only* curated edge, which would leave it
completely unreachable in drive mode -- despite a real bus physically
covering that corridor (`busRoute=Route B`), which means the real street
does exist and this is very likely a bounding-box edge effect (see
`pipeline/README.md`'s note that ~0.74% of the fetched extract lands in
small disconnected fragments) or a node-snapping artifact, not a real-
world fact. Left blank rather than papered over with a guess; worth a
follow-up re-fetch with a wider bounding box.

This is a hand-curated subset of the real street network -- not every
real street or intersection between two points is included, only enough
to connect the named locations plausibly. The same research-track OSM
graph this script draws from ([pipeline/](../pipeline/)) also exists on
its own, at full scale, kept separate from what the deployed app serves --
see the top-level README's "Research track" section.

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
