import { describe, expect, it, vi } from 'vitest';
import { render, fireEvent } from '@testing-library/react';
import NetworkMap from './NetworkMap';
import network from '../data/network.json';

const nodes = [
  { id: 'a', name: 'A', lat: 43.07, lon: -89.4 },
  { id: 'b', name: 'B', lat: 43.08, lon: -89.39 },
  { id: 'c', name: 'C', lat: 43.06, lon: -89.41 },
];
const edges = [
  { from: 'a', to: 'b', miles: 1 },
  { from: 'b', to: 'c', miles: 2 },
];

describe('NetworkMap', () => {
  it('renders one line per edge and one group per node', () => {
    const { container } = render(<NetworkMap nodes={nodes} edges={edges} path={null} />);
    expect(container.querySelectorAll('line')).toHaveLength(edges.length);
    expect(container.querySelectorAll('g.map-node')).toHaveLength(nodes.length);
  });

  it('marks only the edges and nodes on the given path as on-path', () => {
    const { container } = render(<NetworkMap nodes={nodes} edges={edges} path={['a', 'b']} />);
    expect(container.querySelectorAll('line.on-path')).toHaveLength(1);
    expect(container.querySelectorAll('g.map-node.on-path')).toHaveLength(2);
    // b->c isn't part of the path, so it should render as a plain road.
    expect(container.querySelector('g.map-node:not(.on-path)')).not.toBeNull();
  });

  it('marks the first and last path node as endpoints, not intermediate stops', () => {
    const { container } = render(<NetworkMap nodes={nodes} edges={edges} path={['a', 'b', 'c']} />);
    const endpointTitles = [...container.querySelectorAll('g.map-node.endpoint title')]
      .map((t) => t.textContent)
      .sort();
    expect(endpointTitles[0]).toContain('A');
    expect(endpointTitles[1]).toContain('C');
  });

  it('shows route junctions as route points, not numbered stops', () => {
    const { container } = render(<NetworkMap nodes={nodes} edges={edges} path={['a', 'b', 'c']} />);
    expect(container.querySelectorAll('.map-step')).toHaveLength(0);
    expect(container.querySelector('g.map-node:not(.endpoint) title').textContent).toBe('B — along route');
    expect(container.querySelector('g.map-node.start title').textContent).toBe('A — start');
    expect(container.querySelector('g.map-node.end title').textContent).toBe('C — destination');
  });

  it('labels every node when none of them are close enough to overlap', () => {
    // a/b/c are spread across the whole viewBox in this fixture, so nothing
    // collides and every label shows -- this is the common case for the real
    // map too: most of its 40+ locations are far enough apart to always show.
    const { container } = render(<NetworkMap nodes={nodes} edges={edges} path={null} />);
    const labels = [...container.querySelectorAll('.map-labels text.map-node-label')].map((t) => t.textContent).sort();
    expect(labels).toEqual(['A', 'B', 'C']);
  });

  it('places nearby labels on different sides instead of overlapping them', () => {
    // Two coincident nodes compete for the same label space. The layout
    // moves the labels to different sides so both remain readable.
    const overlapping = [
      { id: 'northwest', name: 'Northwest', lat: 43.08, lon: -89.41 },
      { id: 'southeast', name: 'Southeast', lat: 43.06, lon: -89.39 },
      { id: 'x', name: 'Off-path Building', lat: 43.07, lon: -89.4 },
      { id: 'y', name: 'On-path Building', lat: 43.07, lon: -89.4 },
    ];
    const { container } = render(<NetworkMap nodes={overlapping} edges={[]} path={['y']} />);
    const labelElements = [...container.querySelectorAll('.map-labels text.map-node-label')];
    const buildingLabels = labelElements.filter((label) => label.textContent.endsWith('Building'));
    expect(buildingLabels.map((label) => label.textContent)).toEqual(['On-path Building', 'Off-path Building']);
    expect(buildingLabels[0].getAttribute('text-anchor')).not.toBe(buildingLabels[1].getAttribute('text-anchor'));
  });

  it('renders nothing crash-worthy with an empty network', () => {
    const { container } = render(<NetworkMap nodes={[]} edges={[]} path={null} />);
    expect(container.querySelector('svg.map')).toBeInTheDocument();
  });

  it('calls onNodeClick with the node id when a node is clicked, not otherwise', () => {
    const onNodeClick = vi.fn();
    const { container } = render(<NetworkMap nodes={nodes} edges={edges} path={null} onNodeClick={onNodeClick} />);
    expect(container.querySelectorAll('g.map-node.clickable')).toHaveLength(nodes.length);
    fireEvent.click(container.querySelector('g.map-node'));
    expect(onNodeClick).toHaveBeenCalledWith('a');
  });

  it('is not clickable when no onNodeClick handler is given', () => {
    const { container } = render(<NetworkMap nodes={nodes} edges={edges} path={null} />);
    expect(container.querySelectorAll('g.map-node.clickable')).toHaveLength(0);
    expect(container.querySelector('g.map-node[role="button"]')).toBeNull();
  });

  it('triggers onNodeClick on Enter/Space for keyboard users', () => {
    const onNodeClick = vi.fn();
    const { container } = render(<NetworkMap nodes={nodes} edges={edges} path={null} onNodeClick={onNodeClick} />);
    const firstNode = container.querySelector('g.map-node');
    fireEvent.keyDown(firstNode, { key: 'Enter' });
    expect(onNodeClick).toHaveBeenCalledWith('a');
  });

  it('draws a two-way road once, not as two overlapping lines', () => {
    const twoWayEdges = [
      { from: 'a', to: 'b', miles: 1 },
      { from: 'b', to: 'a', miles: 1 },
    ];
    const { container } = render(<NetworkMap nodes={nodes} edges={twoWayEdges} path={null} />);
    expect(container.querySelectorAll('line')).toHaveLength(1);
    expect(container.querySelector('line').getAttribute('marker-end')).toBeNull();
  });

  it('marks a one-way road with a direction arrow', () => {
    const oneWayEdges = [{ from: 'a', to: 'b', miles: 1 }];
    const { container } = render(<NetworkMap nodes={nodes} edges={oneWayEdges} path={null} />);
    const line = container.querySelector('line');
    expect(line.getAttribute('marker-end')).toBe('url(#arrow)');
  });

  // jsdom doesn't lay elements out, so getBoundingClientRect/setPointerCapture
  // need stubbing for these two to exercise the real client-pixel math.
  function stubSvgGeometry(svg) {
    svg.getBoundingClientRect = () => ({ left: 0, top: 0, right: 800, bottom: 640, width: 800, height: 640 });
    svg.setPointerCapture = vi.fn();
  }

  function zoomOf(container) {
    const scaled = container.querySelector('g[transform*="scale"]');
    return Number(scaled.getAttribute('transform').match(/scale\(([\d.]+)\)/)[1]);
  }

  it('zooms in when two touches spread apart (pinch)', () => {
    const { container } = render(<NetworkMap nodes={nodes} edges={edges} path={null} />);
    const svg = container.querySelector('svg.map');
    stubSvgGeometry(svg);

    fireEvent.pointerDown(svg, { pointerId: 1, clientX: 380, clientY: 320 });
    fireEvent.pointerDown(svg, { pointerId: 2, clientX: 420, clientY: 320 });
    expect(zoomOf(container)).toBe(1);

    fireEvent.pointerMove(svg, { pointerId: 1, clientX: 340, clientY: 320 });
    fireEvent.pointerMove(svg, { pointerId: 2, clientX: 460, clientY: 320 });

    expect(zoomOf(container)).toBeGreaterThan(1);
  });

  it('pans with a single finger without changing zoom', () => {
    const { container } = render(<NetworkMap nodes={nodes} edges={edges} path={null} />);
    const svg = container.querySelector('svg.map');
    stubSvgGeometry(svg);

    const before = container.querySelector('g[transform*="scale"]').getAttribute('transform');
    fireEvent.pointerDown(svg, { pointerId: 1, clientX: 400, clientY: 300 });
    fireEvent.pointerMove(svg, { pointerId: 1, clientX: 450, clientY: 320 });
    const after = container.querySelector('g[transform*="scale"]').getAttribute('transform');

    expect(after).not.toBe(before);
    expect(zoomOf(container)).toBe(1);
  });

  // Regression test: a real 58-location run once placed "Social Science
  // Building" ~200px away from its true position, near Lake Monona, because
  // declump()'s pairwise repulsion has no restoring force -- a node whose
  // close neighbors sit mostly on one side gets nudged the same direction
  // over and over (18 different neighbors, all pushing it further south, in
  // that real case), with nothing pulling it back. This reproduces that
  // shape -- one node with many close neighbors clustered almost entirely
  // to its north -- and checks the fix directly: the node's final position
  // should stay within a bounded distance of where it actually projects to,
  // not just "somewhere in a spread-out cluster."
  function project(lat, lon, bounds) {
    const x = 46 + ((lon - bounds.lonMin) / (bounds.lonMax - bounds.lonMin || 1)) * (800 - 92);
    const y = 46 + ((bounds.latMax - lat) / (bounds.latMax - bounds.latMin || 1)) * (640 - 92);
    return { x, y };
  }
  function computeBounds(allNodes) {
    const lats = allNodes.map((n) => n.lat);
    const lons = allNodes.map((n) => n.lon);
    return {
      latMin: Math.min(...lats) - 0.033,
      latMax: Math.max(...lats) + 0.022,
      lonMin: Math.min(...lons),
      lonMax: Math.max(...lons),
    };
  }

  it('keeps Nicholas Recreation Center near its geographic center relative to Ogg', () => {
    const { container } = render(<NetworkMap nodes={network.nodes} edges={network.edges} path={null} />);
    const nodeById = new Map(network.nodes.map((node) => [node.id, node]));
    const bounds = computeBounds(network.nodes);
    const rawNicholas = project(nodeById.get('nicholas_rec').lat, nodeById.get('nicholas_rec').lon, bounds);
    const rawOgg = project(nodeById.get('ogg_hall').lat, nodeById.get('ogg_hall').lon, bounds);
    const circleFor = (id) => [...container.querySelectorAll('g.map-node')].find((g) => g.querySelector('title').textContent.startsWith(nodeById.get(id).name)).querySelector('circle');
    const nicholas = circleFor('nicholas_rec');
    const ogg = circleFor('ogg_hall');
    const drift = Math.hypot(Number(nicholas.getAttribute('cx')) - rawNicholas.x, Number(nicholas.getAttribute('cy')) - rawNicholas.y);
    const rawGap = Math.hypot(rawNicholas.x - rawOgg.x, rawNicholas.y - rawOgg.y);
    const renderedGap = Math.hypot(Number(nicholas.getAttribute('cx')) - Number(ogg.getAttribute('cx')), Number(nicholas.getAttribute('cy')) - Number(ogg.getAttribute('cy')));

    expect(drift).toBeLessThan(15);
    expect(renderedGap).toBeLessThan(rawGap + 25);
  });

  it('never drifts a node far from its true position, even with many neighbors on one side', () => {
    // Two far-apart anchors give computeBounds() a realistic overall span
    // (like the real ~0.09-degree-tall network), so the tight cluster below
    // only occupies a small fraction of the canvas -- matching the real
    // campus-core density bug, not an artificially magnified one where the
    // cluster alone would define (and fill) the whole viewBox.
    const anchors = [
      { id: 'nw_anchor', name: 'NW Anchor', lat: 43.12, lon: -89.45 },
      { id: 'se_anchor', name: 'SE Anchor', lat: 43.03, lon: -89.34 },
    ];
    const target = { id: 'target', name: 'Target', lat: 43.075, lon: -89.4 };
    // 80 neighbors, all almost coincident with each other and just north of
    // `target` -- an exaggerated version of real UW campus-core density
    // (dozens of buildings within a few blocks, overwhelmingly to one
    // side), enough to reliably trigger the pre-fix runaway in a small
    // synthetic fixture that doesn't have the real network's 58 locations.
    const neighbors = Array.from({ length: 80 }, (_, i) => ({
      id: `n${i}`,
      name: `Neighbor ${i}`,
      lat: 43.0754 + i * 0.0003,
      lon: -89.4 + (i % 2 === 0 ? 1 : -1) * 0.00005,
    }));
    const clusterNodes = [...anchors, target, ...neighbors];

    const { container } = render(<NetworkMap nodes={clusterNodes} edges={[]} path={null} />);
    const byName = new Map(
      [...container.querySelectorAll('g.map-node')].map((g) => [
        g.querySelector('title').textContent,
        { x: Number(g.querySelector('circle').getAttribute('cx')), y: Number(g.querySelector('circle').getAttribute('cy')) },
      ]),
    );

    const bounds = computeBounds(clusterNodes);
    const targetRaw = project(target.lat, target.lon, bounds);
    const targetFinal = byName.get(target.name);
    const drift = Math.hypot(targetFinal.x - targetRaw.x, targetFinal.y - targetRaw.y);

    // The layout may space points apart modestly, but should preserve their
    // real locations within a small map-scale tolerance.
    expect(drift).toBeLessThan(15);
  });
});
