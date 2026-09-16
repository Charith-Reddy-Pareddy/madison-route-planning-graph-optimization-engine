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

  it('labels every node when none of them are close enough to overlap', () => {
    // a/b/c are spread across the whole viewBox in this fixture, so nothing
    // collides and every label shows -- this is the common case for the real
    // map too: most of its 40+ locations are far enough apart to always show.
    const { container } = render(<NetworkMap nodes={nodes} edges={edges} path={null} />);
    const labels = [...container.querySelectorAll('.map-labels text.map-node-label')].map((t) => t.textContent).sort();
    expect(labels).toEqual(['A', 'B', 'C']);
  });

  it('drops an overlapping label in favor of the on-path node sharing its spot', () => {
    // Two nodes placed on top of each other guarantee a label collision --
    // the on-path one should win and the other should be dropped, not just
    // whichever happened to be processed first.
    const overlapping = [
      { id: 'x', name: 'Off-path Building', lat: 43.07, lon: -89.4 },
      { id: 'y', name: 'On-path Building', lat: 43.07, lon: -89.4 },
    ];
    const { container } = render(<NetworkMap nodes={overlapping} edges={[]} path={['y']} />);
    const labels = [...container.querySelectorAll('.map-labels text.map-node-label')].map((t) => t.textContent);
    expect(labels).toEqual(['On-path Building']);
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
