import { useMemo } from 'react';

const VIEW_WIDTH = 640;
const VIEW_HEIGHT = 520;
const PADDING = 40;

function computeBounds(nodes) {
  const lats = nodes.map((n) => n.lat);
  const lons = nodes.map((n) => n.lon);
  return {
    latMin: Math.min(...lats),
    latMax: Math.max(...lats),
    lonMin: Math.min(...lons),
    lonMax: Math.max(...lons),
  };
}

function project(lat, lon, bounds) {
  const { latMin, latMax, lonMin, lonMax } = bounds;
  const x = PADDING + ((lon - lonMin) / (lonMax - lonMin || 1)) * (VIEW_WIDTH - 2 * PADDING);
  // Higher latitude is north; SVG y grows downward, so invert.
  const y = PADDING + ((latMax - lat) / (latMax - latMin || 1)) * (VIEW_HEIGHT - 2 * PADDING);
  return { x, y };
}

/** Renders the road network as an SVG map, highlighting `path` (a list of node ids) if given. */
export default function NetworkMap({ nodes, edges, path, onNodeClick }) {
  const positions = useMemo(() => {
    if (nodes.length === 0) return new Map();
    const bounds = computeBounds(nodes);
    return new Map(nodes.map((n) => [n.id, project(n.lat, n.lon, bounds)]));
  }, [nodes]);

  const pathIds = path ?? [];
  const pathSet = new Set(pathIds);
  const pathEdgeSet = new Set();
  for (let i = 0; i < pathIds.length - 1; i++) {
    pathEdgeSet.add(`${pathIds[i]}->${pathIds[i + 1]}`);
  }

  return (
    <svg className="map" viewBox={`0 0 ${VIEW_WIDTH} ${VIEW_HEIGHT}`} role="img" aria-label="Road network map">
      {edges.map((edge) => {
        const from = positions.get(edge.from);
        const to = positions.get(edge.to);
        if (!from || !to) return null;
        const onPath = pathEdgeSet.has(`${edge.from}->${edge.to}`);
        return (
          <line
            key={`${edge.from}->${edge.to}`}
            x1={from.x}
            y1={from.y}
            x2={to.x}
            y2={to.y}
            className={onPath ? 'map-edge on-path' : 'map-edge'}
          />
        );
      })}
      {nodes.map((node) => {
        const pos = positions.get(node.id);
        if (!pos) return null;
        const isOnPath = pathSet.has(node.id);
        const isEndpoint = pathIds.length > 0 && (node.id === pathIds[0] || node.id === pathIds[pathIds.length - 1]);
        const nearRightEdge = pos.x > VIEW_WIDTH - 110;
        return (
          <g
            key={node.id}
            className={`map-node${isOnPath ? ' on-path' : ''}${isEndpoint ? ' endpoint' : ''}${onNodeClick ? ' clickable' : ''}`}
            onClick={onNodeClick ? () => onNodeClick(node.id) : undefined}
            role={onNodeClick ? 'button' : undefined}
            tabIndex={onNodeClick ? 0 : undefined}
            onKeyDown={
              onNodeClick
                ? (e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      onNodeClick(node.id);
                    }
                  }
                : undefined
            }
          >
            <circle cx={pos.x} cy={pos.y} r={isEndpoint ? 8 : 6} />
            <text x={pos.x + (nearRightEdge ? -10 : 10)} y={pos.y + 4} textAnchor={nearRightEdge ? 'end' : 'start'}>
              {node.name}
            </text>
            <title>{node.name}</title>
          </g>
        );
      })}
    </svg>
  );
}
