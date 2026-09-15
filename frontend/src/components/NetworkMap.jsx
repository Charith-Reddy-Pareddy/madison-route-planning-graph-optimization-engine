import { useMemo } from 'react';

const VIEW_WIDTH = 720;
const VIEW_HEIGHT = 580;
const PADDING = 46;

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

  // A road with no return leg in the opposite direction is one-way; draw it
  // once with a direction arrow instead of twice (there's only one edge
  // object for it anyway) or as an overlapping duplicate pair (two-way
  // roads have both directions as separate edges covering the same line).
  const edgeKeys = new Set(edges.map((e) => `${e.from}->${e.to}`));
  const seenPairs = new Set();
  const roadSegments = [];
  for (const edge of edges) {
    const pairKey = [edge.from, edge.to].sort().join('|');
    if (seenPairs.has(pairKey)) continue;
    seenPairs.add(pairKey);
    const oneWay = !edgeKeys.has(`${edge.to}->${edge.from}`);
    roadSegments.push({ ...edge, oneWay });
  }

  return (
    <svg className="map" viewBox={`0 0 ${VIEW_WIDTH} ${VIEW_HEIGHT}`} role="img" aria-label="Road network map">
      <defs>
        <marker id="arrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="5" markerHeight="5" orient="auto-start-reverse">
          <path d="M0,0 L10,5 L0,10 z" className="map-arrow" />
        </marker>
        <marker id="arrow-on-path" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="5" markerHeight="5" orient="auto-start-reverse">
          <path d="M0,0 L10,5 L0,10 z" className="map-arrow on-path" />
        </marker>
      </defs>
      {roadSegments.map((edge) => {
        const from = positions.get(edge.from);
        const to = positions.get(edge.to);
        if (!from || !to) return null;
        const onPath =
          pathEdgeSet.has(`${edge.from}->${edge.to}`) || (!edge.oneWay && pathEdgeSet.has(`${edge.to}->${edge.from}`));
        // Drives the "draw the route" animation: a straight line's own length,
        // used as its dash length so a full dash-offset hides it and animating
        // the offset to 0 reveals it end-to-end (see .map-edge.on-path in CSS).
        const length = Math.hypot(to.x - from.x, to.y - from.y);
        return (
          <line
            key={`${edge.from}->${edge.to}`}
            x1={from.x}
            y1={from.y}
            x2={to.x}
            y2={to.y}
            className={onPath ? 'map-edge on-path' : 'map-edge'}
            markerEnd={edge.oneWay ? `url(#${onPath ? 'arrow-on-path' : 'arrow'})` : undefined}
            style={onPath ? { '--road-length': length } : undefined}
          />
        );
      })}
      {nodes.map((node) => {
        const pos = positions.get(node.id);
        if (!pos) return null;
        const isOnPath = pathSet.has(node.id);
        // Origin and destination are marked distinctly (start red, end green)
        // rather than as one generic "endpoint" color, so the map reads like
        // a trip -- not just a highlighted subgraph.
        const isStart = pathIds.length > 0 && node.id === pathIds[0];
        const isEnd = pathIds.length > 0 && node.id === pathIds[pathIds.length - 1];
        const isEndpoint = isStart || isEnd;
        // 1-based position of this stop along the route, so the map itself
        // shows the same step order as the turn-by-turn list beside it.
        const stepNumber = isOnPath ? pathIds.indexOf(node.id) + 1 : null;
        const radius = isEndpoint ? 10 : isOnPath ? 9 : 6;
        const nearRightEdge = pos.x > VIEW_WIDTH - 110;
        // Keep labels for nodes near the top/bottom edge from clipping out of the viewBox.
        const labelY = Math.min(Math.max(pos.y + 4, 12), VIEW_HEIGHT - 6);
        return (
          <g
            key={node.id}
            className={`map-node${isOnPath ? ' on-path' : ''}${isEndpoint ? ' endpoint' : ''}${isStart ? ' start' : ''}${isEnd ? ' end' : ''}${onNodeClick ? ' clickable' : ''}`}
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
            <circle cx={pos.x} cy={pos.y} r={radius} />
            {stepNumber && (
              <text x={pos.x} y={pos.y} textAnchor="middle" dominantBaseline="central" className="map-step">
                {stepNumber}
              </text>
            )}
            <text
              className="map-node-label"
              x={pos.x + (nearRightEdge ? -10 : 10)}
              y={labelY}
              textAnchor={nearRightEdge ? 'end' : 'start'}
            >
              {node.name}
            </text>
            <title>{stepNumber ? `${node.name} — stop ${stepNumber} of ${pathIds.length}` : node.name}</title>
          </g>
        );
      })}
    </svg>
  );
}
