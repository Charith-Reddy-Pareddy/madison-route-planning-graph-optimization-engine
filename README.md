# Path Finder

[![CI](https://github.com/Charith-Reddy-Pareddy/path-finder/actions/workflows/ci.yml/badge.svg)](https://github.com/Charith-Reddy-Pareddy/path-finder/actions/workflows/ci.yml)

**Live:** [GitHub Pages](https://charith-reddy-pareddy.github.io/path-finder/) · [Render](https://path-finder-nbjl.onrender.com) (full Java backend; free-tier instance may take ~30-50s to wake up if idle)

A route-planning web app: pick a start and end location around UW-Madison
and downtown Madison, WI (dorms, academic buildings, popular apartments,
State St. food spots, and regular intersections — 58 in all, each at its
real, geocoded lat/lon — see "Where the coordinates come from" below) and
get the shortest route, computed with Dijkstra's algorithm, with estimated
walk time and which Madison Metro Transit bus (if any) covers each leg.
Originally a UW-Madison CS400 (data structures) assignment implementing a
generic weighted directed graph — this repo wraps that graph engine in a
real Java HTTP backend and a browser frontend so it actually behaves like
a route planner instead of just a test fixture.

## Tech stack

| Layer | Tech |
|---|---|
| Graph engine | Java 21 — generic `DijkstraGraph<NodeType, EdgeType>` over a custom hash-map ADT |
| Backend | Java's built-in `com.sun.net.httpserver` (no framework), plain JSON over HTTP |
| Frontend | React 19 + Vite — no map library; the network map is a hand-built inline SVG component ([frontend/src/components/NetworkMap.jsx](frontend/src/components/NetworkMap.jsx), see below) |
| Testing | JUnit 5 (backend) + Vitest/React Testing Library (frontend) |
| Build | `make` — compiles Java, builds the React frontend, downloads the JUnit console launcher, runs tests, runs the app |

## Where the coordinates come from

Every location's lat/lon is a real geocoded point, not hand-estimated --
each was looked up individually via [OpenStreetMap's Nominatim](https://nominatim.openstreetmap.org/)
(free, no API key) against its actual name or street address, and each
road's `miles` is the real great-circle distance between those two points,
not a guess. An earlier pass of this data was hand-estimated from memory
and got some relative distances wrong (e.g. placing Dejope Residence Hall,
which is actually out near Eagle Heights on the far west side, close to
X01 near Kohl Center on the other side of campus) -- geocoding caught and
fixed that, and also caught a wrong assumption that Witte/Sellery/Ogg were
Lakeshore dorms (they're actually the Southeast dorms, on W Johnson/Dayton
St; the real Lakeshore corridor along Observatory Dr that Route 80 runs is
Elizabeth Waters/Slichter/Kronshage/Bradley/Dejope). Nominatim doesn't
always resolve a street *intersection* precisely (e.g. "State St & Gilman
St" can land at some other point along State St rather than exactly that
corner), so a few points are approximate to a block or so -- but every
point is real data, not invented, and every distance is computed from it.

## The network map

No mapping library (no Leaflet/Mapbox/Google Maps) — `NetworkMap.jsx` is
plain SVG, built up in a few layers:

- **Projection**: each location's real lat/lon is linearly projected onto
  the SVG viewBox (`project()`).
- **Decluttering**: real campus buildings sit only blocks apart, which a
  straight projection crushes together. `declump()` runs an iterative
  repulsion pass that nudges any two nodes closer than a minimum pixel
  distance apart, leaving already-separated nodes untouched — a
  simplified force-directed layout, computed once and memoized.
- **Label placement**: `layoutLabels()` greedily places each name, skipping
  it if its estimated bounding box would collide with an already-placed
  label (the current route's stops always win). Isolated locations stay
  labeled at any zoom; only genuinely crowded clusters thin out.
- **Pan/zoom**: an SVG `<g transform="translate(...) scale(...)">` driven
  by hand-wired wheel, pointer-drag, and two-finger pinch handlers (a real
  `wheel` listener via `useEffect`, since React's synthetic one is passive
  and can't call `preventDefault`).
- **Lakes**: Mendota and Monona are hand-picked lat/lon polygons run
  through the same projection, sized to fit inside canvas margin reserved
  specifically for them (`NORTH_WATER_MARGIN`/`SOUTH_WATER_MARGIN`), and
  rendered as filled `<path>`s behind everything else.
- **Routes**: drawn as SVG `<line>`s between projected positions; one-way
  streets get an arrowhead via an SVG `<marker>`; the current route
  animates in with a CSS `stroke-dashoffset` transition.

## Deployment

Ships two ways, from the same frontend build:

- **Render** ([render.yaml](render.yaml), a Blueprint): the full app —
  Docker image (multi-stage: builds the React frontend, compiles the Java
  backend, then a slim JRE runtime) reading its port from `PORT`, so it
  also runs as-is on Cloud Run, Fly, or any container platform. Redeploys
  on every push to `main`.
- **GitHub Pages** ([.github/workflows/pages.yml](.github/workflows/pages.yml)):
  the frontend alone, with no Java backend at all. `frontend/src/api.js`
  probes for a live backend on load and, when there isn't one, falls back
  to a bundled snapshot of the network ([frontend/src/data/network.json](frontend/src/data/network.json))
  plus a client-side Dijkstra port ([frontend/src/pathfinding.js](frontend/src/pathfinding.js)) —
  same shortest-path results, same bus/time estimates, entirely in the
  browser. Also redeploys on every push to `main`.

`network.json` is a point-in-time snapshot of `/api/graph`, not generated
at build time — if `RoadNetwork.java` changes, re-export it (`curl
localhost:8080/api/graph | python3 -m json.tool > frontend/src/data/network.json`
with `make run` going) so the two stay in sync. A test
(`pathfinding.test.js`) pins one known route's cost against the snapshot
to catch drift.

## Running it

Requires a JDK (21+), Node.js, and `make`.

```bash
make run
```

Then open [http://localhost:8080](http://localhost:8080). Pick a start
and end intersection and click **Find shortest route** — the map
highlights the path and the panel lists each leg's distance.

Port is configurable via `PORT` (defaults to 8080):

```bash
PORT=9000 make run
```

For frontend-only work with hot reload (proxying API calls to a
separately-running backend), see [frontend/README.md](frontend/README.md).

## Testing

```bash
make test
```

Runs both suites (55+ tests). Backend: `javac`s the Java sources,
downloads the JUnit Platform Console Standalone launcher into `lib/` on
first run (cached after that), and runs unit tests for `DijkstraGraph`,
a `RoadNetworkTest` that checks every location can reach every other one
(catches a one-way street accidentally stranding a node), plus
integration tests that start the real server on an ephemeral port and
drive it over real HTTP with `java.net.http.HttpClient` — status codes,
response shape, cache headers, that a burst of concurrent requests
doesn't serialize, and that the HTTP layer's answer matches calling the
graph directly. Frontend: Vitest + React Testing Library, covering
`RouteForm`, `NetworkMap` (including the label-collision layout and
pinch/drag pan-zoom math), `api.js` (including its no-backend fallback),
`pathfinding.js` (the client-side Dijkstra port, incl. a parity check
against the backend's pinned test case), and the `ErrorBoundary`.

## Project layout

```
src/
  MapADT.java, PlaceholderMap.java   generic key/value map ADT (hash map backed)
  GraphADT.java, BaseGraph.java      generic directed weighted graph
  DijkstraGraph.java                 shortest-path algorithm (priority-queue Dijkstra)
  RoadNetwork.java                   the 58-location network: intersections, roads, bus routes, one-ways
  PathFinderServer.java              HTTP API + static file server
  Json.java                          minimal hand-rolled JSON response writer
  Main.java                          entry point
test/
  DijkstraGraphTest.java             algorithm unit tests
  RoadNetworkTest.java               network sanity checks (every location reachable, no dangling roads)
  PathFinderServerIntegrationTest.java  end-to-end HTTP integration tests
  JsonTest.java                      JSON writer unit tests
frontend/
  src/
    components/NetworkMap.jsx         the hand-built SVG map (see "The network map" above)
    components/RouteForm.jsx, RouteResult.jsx, ErrorBoundary.jsx
    api.js                            fetches the live backend, or falls back to client-side computation
    pathfinding.js                    client-side Dijkstra port, used when there's no backend (GitHub Pages)
    data/network.json                 point-in-time snapshot of /api/graph, powers that fallback
  (see frontend/README.md for frontend-only dev setup)
web/                                  generated by `make frontend` (gitignored) — Vite's build output, served by PathFinderServer
.github/workflows/
  ci.yml                              runs `make test` on push/PR
  pages.yml                           builds the frontend and deploys it to GitHub Pages on push to main
Makefile
```

## API

- `GET /api/graph` — all locations (id, name, lat/lon) and roads (from,
  to, miles, and `busRoute` — the real Metro Transit route that road is
  on, or `null` for a walk-only segment), for rendering the map.
- `GET /api/route?start=<id>&end=<id>` — shortest path, with each leg's
  distance, walking-pace minutes, and `busRoute`, plus totals. Returns
  `404` if either id is unknown or no path exists, `400` if a parameter
  is missing. (Bus route legs get grouped into ride-able trips and their
  own time estimate client-side — see `RouteResult.jsx`.)
- `GET /api/health` — liveness check, used by Render's health check.

The server handles each request on its own virtual thread
(`Executors.newVirtualThreadPerTaskExecutor()`) rather than the JDK
default of one request at a time.

## The algorithm

`DijkstraGraph.computeShortestPath` runs Dijkstra's algorithm with a
`java.util.PriorityQueue` of partial paths ordered by cost so far,
expanding the lowest-cost frontier node first and stopping as soon as
the destination is popped. `RoadNetwork` includes a few one-way streets
specifically so the shortest path can differ depending on direction of
travel — a plain undirected shortest-path search wouldn't reproduce it.

