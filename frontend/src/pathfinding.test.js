import { describe, expect, it } from 'vitest';
import { computeRoute } from './pathfinding';
import network from './data/network.json';

const nodes = [
  { id: 'a', name: 'A' },
  { id: 'b', name: 'B' },
  { id: 'c', name: 'C' },
];
const edges = [
  { from: 'a', to: 'b', miles: 1, busRoute: null },
  { from: 'b', to: 'c', miles: 2, busRoute: 'Route A' },
  { from: 'a', to: 'c', miles: 5, busRoute: null },
];
const small = { nodes, edges };

describe('computeRoute', () => {
  it('picks the shorter multi-hop path over a longer direct edge', () => {
    const result = computeRoute(small, 'a', 'c');
    expect(result.path.map((p) => p.id)).toEqual(['a', 'b', 'c']);
    expect(result.totalMiles).toBe(3);
    expect(result.segments[1].busRoute).toBe('Route A');
  });

  it('returns a zero-cost, single-node path when start equals end', () => {
    const result = computeRoute(small, 'a', 'a');
    expect(result.path.map((p) => p.id)).toEqual(['a']);
    expect(result.segments).toEqual([]);
    expect(result.totalMiles).toBe(0);
  });

  it('throws for an unknown intersection id', () => {
    expect(() => computeRoute(small, 'a', 'nowhere')).toThrow('unknown intersection id');
  });

  it('throws when no path exists between two real nodes', () => {
    const disconnected = { nodes, edges: [{ from: 'a', to: 'b', miles: 1, busRoute: null }] };
    expect(() => computeRoute(disconnected, 'a', 'c')).toThrow('no route found');
  });

  // Regression guard matching the backend's own pinned case
  // (PathFinderServerIntegrationTest#floatingPointSummationNoiseIsRoundedAway):
  // camp_randall -> bascom_hill is one-way, so the reverse trip is forced
  // onto a different, longer path than the direct one. If this ever drifts,
  // the bundled network.json is out of sync with the real RoadNetwork.java.
  it('matches the backend exactly for the pinned camp_randall -> bascom_hill case', () => {
    const result = computeRoute(network, 'camp_randall', 'bascom_hill');
    expect(result.totalMiles).toBe(1.4);
  });
});

// computeRoute's min-selection used to be a linear scan over every
// unvisited node each round (O(V^2 + E)); it's now a binary min-heap (see
// MinHeap in pathfinding.js). This reference implementation is the old
// approach, kept here only to prove the rewrite didn't change results --
// see scripts/benchmark-pathfinding.mjs for the actual performance
// comparison between the two.
function shortestPathCostLinearScan(network, startId, endId) {
  const nodeIds = network.nodes.map((n) => n.id);
  const adjacency = new Map(nodeIds.map((id) => [id, []]));
  for (const edge of network.edges) adjacency.get(edge.from).push(edge);

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

// Deterministic PRNG (mulberry32) so a seeded random graph is reproducible.
function mulberry32(seed) {
  let state = seed >>> 0;
  return () => {
    state |= 0;
    state = (state + 0x6d2b79f5) | 0;
    let t = Math.imul(state ^ (state >>> 15), 1 | state);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function randomConnectedGraph(nodeCount, extraEdgeCount, seed) {
  const rand = mulberry32(seed);
  const nodeIds = Array.from({ length: nodeCount }, (_, i) => `n${i}`);
  const order = [...nodeIds];
  for (let i = order.length - 1; i > 0; i--) {
    const j = Math.floor(rand() * (i + 1));
    [order[i], order[j]] = [order[j], order[i]];
  }
  const edges = [];
  for (let i = 1; i < order.length; i++) {
    const from = order[Math.floor(rand() * i)];
    const miles = Math.round((0.1 + rand() * 2) * 100) / 100;
    edges.push({ from, to: order[i], miles, busRoute: null });
    edges.push({ from: order[i], to: from, miles, busRoute: null });
  }
  for (let i = 0; i < extraEdgeCount; i++) {
    const from = nodeIds[Math.floor(rand() * nodeCount)];
    const to = nodeIds[Math.floor(rand() * nodeCount)];
    if (from === to) continue;
    edges.push({ from, to, miles: Math.round((0.1 + rand() * 2) * 100) / 100, busRoute: null });
  }
  return { nodes: nodeIds.map((id) => ({ id, name: id })), edges };
}

describe('heap-based min-selection vs. the linear-scan it replaced', () => {
  it('produce identical shortest-path costs across randomized graphs', () => {
    const sizes = [5, 20, 60, 150];
    let comparisons = 0;
    for (const size of sizes) {
      for (let seed = 0; seed < 10; seed++) {
        const graph = randomConnectedGraph(size, size * 2, size * 100 + seed);
        const startId = 'n0';
        const endId = `n${size - 1}`;
        const heapCost = computeRoute(graph, startId, endId).totalMiles;
        const linearCost = Math.round(shortestPathCostLinearScan(graph, startId, endId) * 100) / 100;
        expect(heapCost).toBeCloseTo(linearCost, 6);
        comparisons++;
      }
    }
    expect(comparisons).toBe(40);
  });
});
