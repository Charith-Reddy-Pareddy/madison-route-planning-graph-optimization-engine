// Client-side Dijkstra + response shaping, mirroring PathFinderServer's
// /api/route exactly (same field names, same rounding, same error
// messages) so this can stand in for the Java backend when there isn't
// one to call -- e.g. a static GitHub Pages deploy. Ports the walking-pace
// estimate from RoadNetwork.estimatedMinutes (WALK_MPH = 3.5).
const WALK_MPH = 3.5;

function round2(d) {
  return Math.round(d * 100) / 100;
}

function estimatedMinutes(miles) {
  return Math.max(1, Math.round((miles / WALK_MPH) * 60));
}

// Binary min-heap keyed by priority (path cost), used as computeRoute's
// priority queue below instead of a linear scan over every unvisited node.
// Ties are broken by insertion order, which doesn't affect correctness.
// Decrease-key is skipped in favor of lazy deletion: a since-improved
// entry is just left in the heap and dropped when popped, once `visited`
// already covers that node -- cheaper to implement than an indexed heap
// and, since every push is an already-verified improvement, only adds
// O(1) amortized wasted pops rather than changing the result.
export class MinHeap {
  constructor() {
    this.items = [];
  }

  get size() {
    return this.items.length;
  }

  push(value, priority) {
    this.items.push({ value, priority });
    this.#siftUp(this.items.length - 1);
  }

  pop() {
    if (this.items.length === 0) return undefined;
    const top = this.items[0];
    const last = this.items.pop();
    if (this.items.length > 0) {
      this.items[0] = last;
      this.#siftDown(0);
    }
    return top;
  }

  #siftUp(i) {
    while (i > 0) {
      const parent = (i - 1) >> 1;
      if (this.items[parent].priority <= this.items[i].priority) break;
      [this.items[parent], this.items[i]] = [this.items[i], this.items[parent]];
      i = parent;
    }
  }

  #siftDown(i) {
    const n = this.items.length;
    for (;;) {
      const left = 2 * i + 1;
      const right = 2 * i + 2;
      let smallest = i;
      if (left < n && this.items[left].priority < this.items[smallest].priority) smallest = left;
      if (right < n && this.items[right].priority < this.items[smallest].priority) smallest = right;
      if (smallest === i) break;
      [this.items[smallest], this.items[i]] = [this.items[i], this.items[smallest]];
      i = smallest;
    }
  }
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

  const queue = new MinHeap();
  queue.push(startId, 0);

  while (queue.size > 0) {
    const { value: current, priority: currentDist } = queue.pop();
    if (visited.has(current)) continue; // stale entry made obsolete by a cheaper path found since
    if (current === endId) break;
    visited.add(current);
    for (const edge of adjacency.get(current)) {
      const alt = currentDist + edge.miles;
      if (alt < dist.get(edge.to)) {
        dist.set(edge.to, alt);
        cameVia.set(edge.to, edge);
        queue.push(edge.to, alt);
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
