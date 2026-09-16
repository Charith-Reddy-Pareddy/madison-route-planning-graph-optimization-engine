// Client-side Dijkstra + response shaping, mirroring PathFinderServer's
// /api/route exactly (same field names, same rounding, same error
// messages) so this can stand in for the Java backend when there isn't
// one to call -- e.g. a static GitHub Pages deploy. Ports the walking-pace
// estimate from RoadNetwork.estimatedMinutes (WALK_MPH = 3).
const WALK_MPH = 3;

function round2(d) {
  return Math.round(d * 100) / 100;
}

function estimatedMinutes(miles) {
  return Math.max(1, Math.round((miles / WALK_MPH) * 60));
}

/**
 * Computes the shortest path between two node ids in `network` ({nodes, edges}),
 * returning the same shape PathFinderServer's /api/route does. Throws an Error
 * with the same messages the HTTP API uses for unknown ids / no path found.
 */
export function computeRoute(network, startId, endId) {
  const nodeIds = network.nodes.map((n) => n.id);
  if (!nodeIds.includes(startId) || !nodeIds.includes(endId)) {
    throw new Error('unknown intersection id');
  }

  const adjacency = new Map(nodeIds.map((id) => [id, []]));
  for (const edge of network.edges) {
    adjacency.get(edge.from).push(edge);
  }

  const dist = new Map(nodeIds.map((id) => [id, Infinity]));
  const cameVia = new Map(); // nodeId -> edge used to reach it on the shortest path
  const visited = new Set();
  dist.set(startId, 0);

  while (visited.size < nodeIds.length) {
    let current = null;
    let currentDist = Infinity;
    for (const id of nodeIds) {
      if (!visited.has(id) && dist.get(id) < currentDist) {
        current = id;
        currentDist = dist.get(id);
      }
    }
    if (current === null || current === endId) break;
    visited.add(current);
    for (const edge of adjacency.get(current)) {
      const alt = currentDist + edge.miles;
      if (alt < dist.get(edge.to)) {
        dist.set(edge.to, alt);
        cameVia.set(edge.to, edge);
      }
    }
  }

  if (dist.get(endId) === Infinity) {
    throw new Error('no route found between those intersections');
  }

  const pathIds = [endId];
  while (pathIds[0] !== startId) {
    pathIds.unshift(cameVia.get(pathIds[0]).from);
  }

  const nodesById = new Map(network.nodes.map((n) => [n.id, n]));
  const path = pathIds.map((id) => ({ id, name: nodesById.get(id)?.name ?? id }));

  const segments = [];
  let totalMinutes = 0;
  for (let i = 0; i < pathIds.length - 1; i++) {
    const edge = cameVia.get(pathIds[i + 1]);
    const miles = round2(edge.miles);
    const minutes = estimatedMinutes(edge.miles);
    totalMinutes += minutes;
    segments.push({ from: edge.from, to: edge.to, miles, minutes, busRoute: edge.busRoute ?? null });
  }

  return { path, segments, totalMiles: round2(dist.get(endId)), totalMinutes };
}
