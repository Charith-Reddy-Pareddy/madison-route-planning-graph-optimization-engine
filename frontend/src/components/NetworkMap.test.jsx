import { describe, expect, it, vi } from 'vitest';
import { render, fireEvent } from '@testing-library/react';
import NetworkMap from './NetworkMap';

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

  it('only shows labels for on-path nodes at the default zoom level, not the whole crowded map', () => {
    const { container } = render(<NetworkMap nodes={nodes} edges={edges} path={['a', 'b']} />);
    const labels = [...container.querySelectorAll('.map-labels text')].map((t) => t.textContent).sort();
    // C is off-path and stays hidden until the user zooms in.
    expect(labels).toEqual(['A', 'B']);
  });

  it('hides all labels at the default zoom when no route is highlighted', () => {
    // The real map has 40+ locations, so with nothing "on path" to force-show,
    // every label stays hidden until the user zooms in -- the app always has
    // a default route active on load, so this is the pre-route/zoomed-out case.
    const { container } = render(<NetworkMap nodes={nodes} edges={edges} path={null} />);
    expect(container.querySelectorAll('.map-labels text')).toHaveLength(0);
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
});
