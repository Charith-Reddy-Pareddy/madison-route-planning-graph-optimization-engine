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

**Bigger known gap -- sparse curation, not a distance-accuracy problem**:
recomputing distances (above) fixes how long each *existing* edge is, but
doesn't fix which edges exist. `kohl_center` had exactly two curated edges
(to `camp_randall` and `x01_apts`), neither anywhere near the
Witte/Sellery/Ogg dorm cluster it's actually a block or two from -- so a
`kohl_center -> sellery_hall` walk route was forced through Camp Randall
Stadium (0.945mi away, in a different direction) instead of a direct
~0.46mi walk. Fixed by adding the missing `kohl_center<->witte_hall` edge
(real computed distance: 0.187mi walk, 0.188mi drive). A systematic sweep
(every pair of locations under 0.35mi apart by real straight-line
distance, compared against their real graph walking distance) found
**255 more pairs with the same shape of problem** -- most traceable to
about 15-20 severely under-connected locations that have only a single
curated edge to the *entire rest of the network* even when a real
neighbor is right next door (e.g. `discovery_building` and
`morgridge_hall`, both degree-1, straight-line 0.061mi apart but 3.1mi by
the current graph; `x01_apts`, `chazen_museum`, `education_building`,
`lucky_apts`, `chadbourne_hall`, `vilas_hall`, `humanities_building`,
`science_hall`, `van_vleck_hall`, and `nicholas_rec` are the other worst
offenders). This needs the same treatment as the fix above -- find the
real missing edge, compute its real distance from the OSM graph, verify
the resulting route -- repeated deliberately rather than rushed, not a
mass find-and-add.

**Progress**: 14 more real edges added the same way (verify what's really
there, compute the real distance from the OSM graph, check the resulting
route makes sense) -- `discovery_building<->morgridge_hall`,
`x01_apts<->chadbourne_hall`, `chadbourne_hall<->humanities_building`,
`humanities_building<->chazen_museum`, `chazen_museum<->lucky_apts`,
`lucky_apts<->vilas_hall`, `education_building<->science_hall`,
`van_vleck_hall<->bascom_hill`, `nicholas_rec<->witte_hall`,
`atmosphere_apts<->kohl_center`, `middleton_building<->union_south` (no
drivable route found for this one, same as the `witte_hall<->ogg_hall`
gap above -- `walkMiles` only), and `grainger_hall<->sellery_hall`. The
flagged-pair count dropped from 255 to 145. One of these new edges
(`discovery_building<->morgridge_hall`) legitimately opened a shorter
real route for `camp_randall -> bascom_hill` too (1.87mi -> 1.35mi),
which is why that number moved in the pinned regression tests -- a real
improvement, not a regression. Remaining ~145 pairs are smaller-magnitude
and lower priority than the ones already fixed; same method applies
whenever this gets picked back up.

**Progress, round 2**: 8 more real edges the same way --
`education_building<->college_library`, `science_hall<->college_library`,
`red_gym<->science_hall`, `grainger_hall<->regent_park`,
`ogg_hall<->regent_park` (no drivable route found, walk only),
`middleton_building<->slichter_hall`, `van_hise_hall<->grainger_hall`,
`x01_apts<->vilas_hall`. Flagged-pair count: 145 -> 100. No pinned
regression values moved this round. Verified live (e.g.
`science_hall -> memorial_union` now correctly routes via College
Library, 0.24mi total, instead of a long detour).

**Progress, rounds 3-5 -- swept to zero**: 27 more real edges the same
way across three more rounds (re-running the sweep fresh each round,
since fixing the worst offenders changes who the next worst offenders
are): `nicholas_rec<->ogg_hall`, `ogg_hall<->sellery_hall`,
`grainger_hall<->x01_apts`, `red_gym<->memorial_library`,
`library_mall<->memorial_library`, `social_sciences<->elizabeth_waters`,
`discovery_building<->union_south`, `middleton_building<->engineering_hall`,
`witte_hall<->lucky_apts`, `bascom_hill<->education_building`,
`elizabeth_waters<->van_hise_hall`, `chazen_museum<->library_mall`,
`kohl_center<->nicholas_rec`, `social_sciences<->van_hise_hall`,
`humanities_building<->science_hall`, `ogg_hall<->vilas_hall`,
`chadbourne_hall<->science_hall`, `bascom_hill<->van_hise_hall`,
`ians_pizza<->state_gilman`, `chipotle_state_st<->lucky_apts`,
`van_hise_hall<->van_vleck_hall`, `engineering_hall<->slichter_hall`,
`atmosphere_apts<->regent_park`, `middleton_building<->morgridge_hall`.
**Flagged-pair count: 100 -> 33 -> 0.**

Two real bugs surfaced by testing along the way, both fixed:

1. A few snapped-point routes came out *shorter* than the straight line
   between the two locations' own stored coordinates -- geometrically
   impossible for a real route, and it broke A*'s admissible-heuristic
   guarantee (`AlgorithmsCorrectnessTest` caught a real mismatch between
   `AStarAlgorithm` and `DijkstraGraph`). Fixed by clamping every stored
   distance up to the straight-line floor, both by hand for the existing
   data and permanently in `scripts/compute_route_distances.py` itself
   (see its docstring) so it can't come back.
2. Several new edges were added by computing one direction and reusing
   the same number for the reverse row -- correct for `walkMiles` (the
   walk graph is genuinely undirected) but wrong for `driveMiles`, which
   respects real one-way streets and isn't always symmetric (e.g.
   `lucky_apts -> chazen_museum` is really 0.59mi to drive, not the 0.11mi
   the reverse direction happened to be). Fixed by re-running
   `scripts/compute_route_distances.py` (now safe to re-run on the file
   as it already exists -- see its docstring) so every directed row gets
   its own independently-computed number.

**Known gap**: a handful of directed rows have no drivable route found
in the fetched OSM extract (`walkMiles` still real and present for all
of them), mostly around the Witte/Sellery/Ogg dorm cluster and Union
South -- real pedestrian-only campus paths where a car genuinely can't
follow the same route, not missing data. `union_south` is not actually
drive-isolated (its outbound directions mostly resolve fine --
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
