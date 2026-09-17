# Data sources

`locations.csv` and `roads.csv` are what `RoadNetworkLoader` reads at
startup to build the live app's 57-location Madison/UW-Madison network
(see [RoadNetwork.java](../src/RoadNetwork.java)). This file documents
where that data came from.

## locations.csv

Every `lat,lon` is a real geocoded point, not hand-estimated. Each was
looked up individually via [OpenStreetMap's Nominatim](https://nominatim.openstreetmap.org/)
(free, no API key required) against the location's actual name or street
address.

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

To add or re-verify a location, look up its name or address at
[nominatim.openstreetmap.org](https://nominatim.openstreetmap.org/ui/search.html)
(send requests at most ~1/sec, with a descriptive `User-Agent`, per
Nominatim's usage policy) and append the resulting `lat,lon` as a new row.
A reusable script for this workflow (`scripts/geocode.py`) is planned but
not yet in the repo.
