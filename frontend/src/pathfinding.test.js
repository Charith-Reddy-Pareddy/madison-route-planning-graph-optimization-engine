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
