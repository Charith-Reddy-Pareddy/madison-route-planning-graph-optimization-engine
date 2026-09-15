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
    const offPath = container.querySelector('g.map-node:not(.on-path)');
    expect(offPath.querySelector('text').textContent).toBe('C');
  });

  it('marks the first and last path node as endpoints, not intermediate stops', () => {
    const { container } = render(<NetworkMap nodes={nodes} edges={edges} path={['a', 'b', 'c']} />);
    const endpoints = [...container.querySelectorAll('g.map-node.endpoint')]
      .map((g) => g.querySelector('text').textContent)
      .sort();
    expect(endpoints).toEqual(['A', 'C']);
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
});
