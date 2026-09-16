# Madison Route Planning frontend

React (Vite) frontend for Madison Route Planning. Fetches the road network and
shortest-route results from the Java backend's JSON API and renders an
interactive SVG map.

See the [top-level README](../README.md) for the full project overview.
`make run` (from the repo root) builds this and starts the whole app;
the commands below are for frontend-only development.

## Development

```bash
npm install
npm run dev
```

Runs the Vite dev server with hot reload at http://localhost:5173,
proxying `/api/*` requests to the Java backend (start that separately
with `make run` from the repo root, or `java -cp out/classes Main`).

## Production build

```bash
npm run build
```

Builds into `../web`, which `PathFinderServer` serves as static files —
this is what `make run` uses at the repo root.

## Structure

```
src/
  main.jsx              React entry point
  App.jsx                top-level layout + data fetching
  api.js                  fetch helpers for /api/graph and /api/route
  index.css               global styles
  components/
    RouteForm.jsx          start/end selects + submit
    RouteResult.jsx         status message + route steps
    NetworkMap.jsx           SVG map rendering, path highlighting
```
