import network from './data/network.json';
import { computeRoute } from './pathfinding';

async function getJSON(url) {
  const resp = await fetch(url);
  const body = await resp.json();
  if (!resp.ok) {
    throw new Error(body.error || `request failed (${resp.status})`);
  }
  return body;
}

// This same build runs two ways: served by the Java backend (Render), where
// /api/* is real, or as static files with no backend at all (GitHub Pages).
// Probe once for a live backend and fall back to the bundled network.json +
// client-side Dijkstra (pathfinding.js) when there isn't one, so the app
// works identically either way -- bus info included.
let backendAvailable = null;

async function hasBackend() {
  if (backendAvailable !== null) return backendAvailable;
  try {
    const resp = await fetch('/api/health', { signal: AbortSignal.timeout(2000) });
    backendAvailable = resp.ok;
  } catch {
    backendAvailable = false;
  }
  return backendAvailable;
}

/** Fetches (or computes locally) all intersections and roads, for the map and selects. */
export async function getGraph() {
  if (await hasBackend()) return getJSON('/api/graph');
  return network;
}

/**
 * Fetches (or computes locally) the shortest route between two intersection ids, in 'walk' or
 * 'drive' mode. `live=true` (drive only) asks the backend to refresh each leg's distance from a
 * real external routing API instead of the precomputed graph -- only the real Java backend can do
 * that (see LiveRoutingClient.java), so it's silently ignored in the no-backend fallback.
 */
export async function getRoute(startId, endId, mode = 'walk', live = false) {
  if (await hasBackend()) {
    const params = new URLSearchParams({ start: startId, end: endId, mode, live: String(live) });
    return getJSON(`/api/route?${params}`);
  }
  return computeRoute(network, startId, endId, mode);
}
