import { afterEach, describe, expect, it, vi } from 'vitest';
import { getGraph, getRoute } from './api';

function mockFetch(status, body) {
  global.fetch = vi.fn().mockResolvedValue({
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(body),
  });
}

describe('api', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('getGraph resolves with the parsed body on success', async () => {
    mockFetch(200, { nodes: [], edges: [] });
    await expect(getGraph()).resolves.toEqual({ nodes: [], edges: [] });
    expect(fetch).toHaveBeenCalledWith('/api/graph');
  });

  it('getRoute URL-encodes start/end into the query string', async () => {
    mockFetch(200, { path: [], segments: [], totalMiles: 0 });
    await getRoute('state st & gilman', 'b');
    expect(fetch).toHaveBeenCalledWith('/api/route?start=state+st+%26+gilman&end=b');
  });

  it('rejects with the server-provided error message on a non-2xx response', async () => {
    mockFetch(404, { error: 'unknown intersection id' });
    await expect(getRoute('a', 'nowhere')).rejects.toThrow('unknown intersection id');
  });

  it('falls back to a generic message if the error response has no error field', async () => {
    mockFetch(500, {});
    await expect(getGraph()).rejects.toThrow('request failed (500)');
  });

  it('falls back to the bundled network and local Dijkstra when there is no backend (GitHub Pages)', async () => {
    vi.resetModules();
    global.fetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));
    const { getGraph: freshGetGraph, getRoute: freshGetRoute } = await import('./api');

    const graph = await freshGetGraph();
    expect(graph.nodes.length).toBeGreaterThan(0);
    // Only the health probe should have hit the network -- getGraph and
    // getRoute below must both resolve locally without calling fetch again.
    expect(fetch).toHaveBeenCalledTimes(1);

    const route = await freshGetRoute(graph.nodes[0].id, graph.nodes[0].id);
    expect(route.totalMiles).toBe(0);
    expect(fetch).toHaveBeenCalledTimes(1);
  });
});
