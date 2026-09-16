// Benchmarks the heap-based min-selection in ../src/pathfinding.js against
// the plain linear-scan it replaced, on random synthetic graphs of
// increasing size, to find the crossover point where the heap actually
// wins. Run with: node scripts/benchmark-pathfinding.mjs
//
// Real numbers from real runs on whatever machine this executes on --
// nothing here is precomputed or hardcoded.
import { MinHeap } from '../src/pathfinding.js';

function makeRandomGraph(nodeCount, avgDegree, seed) {
  // Small deterministic PRNG (mulberry32) so a given seed always produces
  // the same graph -- makes a run reproducible without a real dependency.
  let state = seed >>> 0;
  function rand() {
    state |= 0;
    state = (state + 0x6d2b79f5) | 0;
    let t = Math.imul(state ^ (state >>> 15), 1 | state);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  }

  const nodeIds = Array.from({ length: nodeCount }, (_, i) => `n${i}`);
  const edges = [];
  // Guarantee connectivity first: a random permutation chain from n0.
  const order = [...nodeIds];
  for (let i = order.length - 1; i > 0; i--) {
    const j = Math.floor(rand() * (i + 1));
    [order[i], order[j]] = [order[j], order[i]];
  }
  for (let i = 1; i < order.length; i++) {
    const from = order[Math.floor(rand() * i)];
    const miles = 0.1 + rand() * 2;
    edges.push({ from, to: order[i], miles, busRoute: null });
    edges.push({ from: order[i], to: from, miles, busRoute: null });
  }
  // Then scatter extra random edges up to the target average degree.
  const extraEdges = Math.max(0, nodeCount * avgDegree - edges.length);
  for (let i = 0; i < extraEdges; i++) {
    const from = nodeIds[Math.floor(rand() * nodeCount)];
    const to = nodeIds[Math.floor(rand() * nodeCount)];
    if (from === to) continue;
    edges.push({ from, to, miles: 0.1 + rand() * 2, busRoute: null });
  }

  return { nodes: nodeIds.map((id) => ({ id, name: id })), edges };
}

function buildAdjacency(network) {
  const adjacency = new Map(network.nodes.map((n) => [n.id, []]));
  for (const edge of network.edges) adjacency.get(edge.from).push(edge);
  return adjacency;
}

// The O(V^2 + E) approach this replaced: scan every unvisited node each
// round to find the minimum, instead of a heap.
function shortestPathCostLinearScan(network, startId, endId) {
  const nodeIds = network.nodes.map((n) => n.id);
  const adjacency = buildAdjacency(network);
  const dist = new Map(nodeIds.map((id) => [id, Infinity]));
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
      if (alt < dist.get(edge.to)) dist.set(edge.to, alt);
    }
  }
  return dist.get(endId);
}

// Mirrors computeRoute's algorithm in pathfinding.js (heap-based), but
// returns just the cost -- this benchmark only cares about the min-
// selection strategy, not response shaping.
function shortestPathCostHeap(network, startId, endId) {
  const nodeIds = network.nodes.map((n) => n.id);
  const adjacency = buildAdjacency(network);
  const dist = new Map(nodeIds.map((id) => [id, Infinity]));
  const visited = new Set();
  dist.set(startId, 0);

  const queue = new MinHeap();
  queue.push(startId, 0);
  while (queue.size > 0) {
    const { value: current, priority: currentDist } = queue.pop();
    if (visited.has(current)) continue;
    if (current === endId) break;
    visited.add(current);
    for (const edge of adjacency.get(current)) {
      const alt = currentDist + edge.miles;
      if (alt < dist.get(edge.to)) {
        dist.set(edge.to, alt);
        queue.push(edge.to, alt);
      }
    }
  }
  return dist.get(endId);
}

function timeMs(fn) {
  const start = performance.now();
  fn();
  return performance.now() - start;
}

const AVG_DEGREE = 4;
const SIZES = [50, 200, 1000, 5000, 20000, 100000];
const TRIALS_PER_SIZE = 3;

console.log(`nodes\tavgDegree\tlinearScanMs\theapMs\tspeedup\tcostsMatch`);
for (const size of SIZES) {
  const linearTimes = [];
  const heapTimes = [];
  let allMatch = true;
  for (let trial = 0; trial < TRIALS_PER_SIZE; trial++) {
    const network = makeRandomGraph(size, AVG_DEGREE, size * 1000 + trial);
    const startId = 'n0';
    const endId = `n${size - 1}`;

    // Skip the linear scan above a size where it takes too long to be a
    // useful data point rather than just a very long wait.
    let linearCost;
    if (size <= 5000) {
      linearTimes.push(timeMs(() => (linearCost = shortestPathCostLinearScan(network, startId, endId))));
    }
    let heapCost;
    heapTimes.push(timeMs(() => (heapCost = shortestPathCostHeap(network, startId, endId))));

    if (size <= 5000 && Math.abs(linearCost - heapCost) > 1e-9) {
      allMatch = false;
    }
  }

  const avg = (arr) => (arr.length ? arr.reduce((a, b) => a + b, 0) / arr.length : null);
  const linearAvg = avg(linearTimes);
  const heapAvg = avg(heapTimes);
  const speedup = linearAvg !== null ? (linearAvg / heapAvg).toFixed(2) + 'x' : 'n/a (skipped)';
  console.log(
    `${size}\t${AVG_DEGREE}\t${linearAvg !== null ? linearAvg.toFixed(2) : 'skipped'}\t${heapAvg.toFixed(2)}\t${speedup}\t${size <= 5000 ? allMatch : 'n/a'}`
  );
}
