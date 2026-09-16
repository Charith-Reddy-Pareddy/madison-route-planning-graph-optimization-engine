import { useEffect, useMemo, useRef, useState } from 'react';

const VIEW_WIDTH = 800;
const VIEW_HEIGHT = 640;
const PADDING = 46;

const MIN_ZOOM = 1;
const MAX_ZOOM = 6;
// Below this zoom, off-path labels are hidden entirely -- with 40+ campus
// buildings clustered close together, showing every name at once is just
// unreadable overlap. Zooming in (wheel/pinch or the +/- buttons) reveals
// them, since node spacing grows with zoom while label text does not.
const LABEL_REVEAL_ZOOM = 1.6;

function clamp(value, min, max) {
  return Math.min(Math.max(value, min), max);
}

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
  const svgRef = useRef(null);
  const dragState = useRef(null);
  const [view, setView] = useState({ zoom: 1, pan: { x: 0, y: 0 } });

  const positions = useMemo(() => {
    if (nodes.length === 0) return new Map();
    const bounds = computeBounds(nodes);
    return new Map(nodes.map((n) => [n.id, project(n.lat, n.lon, bounds)]));
  }, [nodes]);

  // Zooms so the world point under (screenX, screenY) stays fixed on screen --
  // otherwise every zoom step would recenter on the viewBox origin instead of
  // wherever the cursor (or a zoom button, using the view's center) is.
  function zoomAt(screenX, screenY, factor) {
    setView((v) => {
      const newZoom = clamp(v.zoom * factor, MIN_ZOOM, MAX_ZOOM);
      const worldX = (screenX - v.pan.x) / v.zoom;
      const worldY = (screenY - v.pan.y) / v.zoom;
      return { zoom: newZoom, pan: { x: screenX - worldX * newZoom, y: screenY - worldY * newZoom } };
    });
  }

  function resetView() {
    setView({ zoom: 1, pan: { x: 0, y: 0 } });
  }

  // React's onWheel is passive by default (can't preventDefault there), so
  // the page would scroll along with the map -- attach a real listener.
  useEffect(() => {
    const svg = svgRef.current;
    if (!svg) return undefined;
    const handleWheel = (e) => {
      e.preventDefault();
      const rect = svg.getBoundingClientRect();
      const scale = VIEW_WIDTH / rect.width;
      const x = (e.clientX - rect.left) * scale;
      const y = (e.clientY - rect.top) * scale;
      zoomAt(x, y, e.deltaY < 0 ? 1.2 : 1 / 1.2);
    };
    svg.addEventListener('wheel', handleWheel, { passive: false });
    return () => svg.removeEventListener('wheel', handleWheel);
  }, []);

  function toScreen(pos) {
    return { x: view.pan.x + pos.x * view.zoom, y: view.pan.y + pos.y * view.zoom };
  }

  function handlePointerDown(e) {
    // Panning starts only from empty map background, so node clicks (and
    // their own drag-to-scroll on touch) keep working normally.
    if (e.target.closest('.map-node')) return;
    dragState.current = { startX: e.clientX, startY: e.clientY, pan: view.pan };
    svgRef.current?.setPointerCapture(e.pointerId);
  }

  function handlePointerMove(e) {
    if (!dragState.current) return;
    const rect = svgRef.current.getBoundingClientRect();
    const scale = VIEW_WIDTH / rect.width;
    const dx = (e.clientX - dragState.current.startX) * scale;
    const dy = (e.clientY - dragState.current.startY) * scale;
    setView((v) => ({ ...v, pan: { x: dragState.current.pan.x + dx, y: dragState.current.pan.y + dy } }));
  }

  function handlePointerUp() {
    dragState.current = null;
  }

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
    <div className="map-wrap">
      <svg
        ref={svgRef}
        className="map"
        viewBox={`0 0 ${VIEW_WIDTH} ${VIEW_HEIGHT}`}
        role="img"
        aria-label="Road network map"
        onPointerDown={handlePointerDown}
        onPointerMove={handlePointerMove}
        onPointerUp={handlePointerUp}
        onPointerLeave={handlePointerUp}
      >
        <defs>
          <marker id="arrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="5" markerHeight="5" orient="auto-start-reverse">
            <path d="M0,0 L10,5 L0,10 z" className="map-arrow" />
          </marker>
          <marker id="arrow-on-path" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="5" markerHeight="5" orient="auto-start-reverse">
            <path d="M0,0 L10,5 L0,10 z" className="map-arrow on-path" />
          </marker>
        </defs>
        <g transform={`translate(${view.pan.x} ${view.pan.y}) scale(${view.zoom})`}>
          {roadSegments.map((edge) => {
            const from = positions.get(edge.from);
            const to = positions.get(edge.to);
            if (!from || !to) return null;
            const onPath =
              pathEdgeSet.has(`${edge.from}->${edge.to}`) ||
              (!edge.oneWay && pathEdgeSet.has(`${edge.to}->${edge.from}`));
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
                <title>{stepNumber ? `${node.name} — stop ${stepNumber} of ${pathIds.length}` : node.name}</title>
              </g>
            );
          })}
        </g>
        {/* Labels live outside the pan/zoom group and are positioned by hand from
            the same transform, so their text stays a constant on-screen size while
            zooming spreads the nodes apart -- that's what actually declutters
            crowded areas, not just making everything bigger together. */}
        <g className="map-labels">
          {nodes.map((node) => {
            const pos = positions.get(node.id);
            if (!pos) return null;
            const isOnPath = pathSet.has(node.id);
            if (!isOnPath && view.zoom < LABEL_REVEAL_ZOOM) return null;
            const screen = toScreen(pos);
            if (screen.x < -20 || screen.x > VIEW_WIDTH + 20 || screen.y < -20 || screen.y > VIEW_HEIGHT + 20) {
              return null;
            }
            const nearRightEdge = screen.x > VIEW_WIDTH - 110;
            const labelY = clamp(screen.y + 4, 12, VIEW_HEIGHT - 6);
            return (
              <text
                key={node.id}
                className="map-node-label"
                x={screen.x + (nearRightEdge ? -10 : 10)}
                y={labelY}
                textAnchor={nearRightEdge ? 'end' : 'start'}
              >
                {node.name}
              </text>
            );
          })}
        </g>
      </svg>
      <div className="map-controls">
        <button type="button" onClick={() => zoomAt(VIEW_WIDTH / 2, VIEW_HEIGHT / 2, 1.4)} aria-label="Zoom in">
          +
        </button>
        <button type="button" onClick={() => zoomAt(VIEW_WIDTH / 2, VIEW_HEIGHT / 2, 1 / 1.4)} aria-label="Zoom out">
          &minus;
        </button>
        <button type="button" onClick={resetView} aria-label="Reset map view">
          Reset
        </button>
      </div>
      <p className="map-zoom-hint">
        Scroll to zoom (or use the +/&minus; buttons), drag to pan &mdash; labels appear as you zoom in.
      </p>
    </div>
  );
}
