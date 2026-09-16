import { useEffect, useMemo, useRef, useState } from 'react';

const VIEW_WIDTH = 800;
const VIEW_HEIGHT = 640;
const PADDING = 46;

const MIN_ZOOM = 1;
const MAX_ZOOM = 6;
// Rough px-per-character at the label font size, for estimating how wide a
// name's bounding box is without a real DOM measurement.
const CHAR_WIDTH = 6.3;
const LABEL_HEIGHT = 13;

function clamp(value, min, max) {
  return Math.min(Math.max(value, min), max);
}

function rectsOverlap(a, b, pad) {
  return !(a.x1 + pad < b.x0 || a.x0 - pad > b.x1 || a.y1 + pad < b.y0 || a.y0 - pad > b.y1);
}

/**
 * Greedy label placement: on-path stops always get a label (those are what
 * the user is actually looking at); everything else gets one only if it
 * doesn't collide with an already-placed label. This is why isolated nodes
 * (most of the map) stay labeled at any zoom, while only the genuinely
 * crowded campus-core cluster thins out -- a flat "hide until zoomed in"
 * rule would've hidden far-apart names too, for no reason.
 */
function layoutLabels(nodes, positions, toScreen, pathSet, viewWidth, viewHeight) {
  const ordered = [...nodes].sort((a, b) => {
    const aFirst = pathSet.has(a.id) ? 0 : 1;
    const bFirst = pathSet.has(b.id) ? 0 : 1;
    return aFirst - bFirst;
  });

  const placedRects = [];
  const labels = [];
  for (const node of ordered) {
    const pos = positions.get(node.id);
    if (!pos) continue;
    const screen = toScreen(pos);
    if (screen.x < -20 || screen.x > viewWidth + 20 || screen.y < -20 || screen.y > viewHeight + 20) continue;

    const isOnPath = pathSet.has(node.id);
    const nearRightEdge = screen.x > viewWidth - 120;
    const anchorX = screen.x + (nearRightEdge ? -10 : 10);
    const y = clamp(screen.y + 4, 12, viewHeight - 6);
    const width = node.name.length * CHAR_WIDTH;
    const rect = nearRightEdge
      ? { x0: anchorX - width, x1: anchorX, y0: y - LABEL_HEIGHT, y1: y + 3 }
      : { x0: anchorX, x1: anchorX + width, y0: y - LABEL_HEIGHT, y1: y + 3 };

    if (!isOnPath && placedRects.some((r) => rectsOverlap(rect, r, 2))) continue;

    placedRects.push(rect);
    labels.push({ id: node.id, name: node.name, x: anchorX, y, textAnchor: nearRightEdge ? 'end' : 'start' });
  }
  return labels;
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

// A straight lat/lon projection squashes a dense real-world cluster (~20
// campus buildings within a few blocks of each other) into a handful of
// screen pixels -- no amount of label cleverness fixes circles that
// literally overlap. This nudges any two nodes closer than MIN_SEPARATION
// apart away from each other, a few passes at a time, while leaving nodes
// that are already far apart untouched (like real force-directed graph
// layouts do for decluttering, e.g. subway-map-style distortion).
const MIN_SEPARATION = 46;
const REPULSION_PASSES = 200;

function declump(rawPositions) {
  const ids = [...rawPositions.keys()];
  const pos = new Map(ids.map((id) => [id, { ...rawPositions.get(id) }]));
  for (let pass = 0; pass < REPULSION_PASSES; pass++) {
    let moved = false;
    for (let i = 0; i < ids.length; i++) {
      for (let j = i + 1; j < ids.length; j++) {
        const a = pos.get(ids[i]);
        const b = pos.get(ids[j]);
        const dx = b.x - a.x;
        const dy = b.y - a.y;
        const dist = Math.hypot(dx, dy) || 0.01;
        if (dist >= MIN_SEPARATION) continue;
        moved = true;
        const push = (MIN_SEPARATION - dist) / 2;
        const ux = dx / dist;
        const uy = dy / dist;
        a.x -= ux * push;
        a.y -= uy * push;
        b.x += ux * push;
        b.y += uy * push;
      }
    }
    if (!moved) break;
  }
  return pos;
}

// Rough outlines (not survey-accurate, just recognizable) of the two lakes
// that frame the isthmus Madison and this network sit on -- Mendota to the
// north (its south shore runs along the Memorial Union / lakeshore dorms
// path), Monona to the southeast (John Nolen Dr runs along its shore).
// Drawn from the same lat/lon projection as everything else so they sit in
// the right place relative to the real streets, even though node positions
// themselves get decluttered afterward.
const LAKE_MENDOTA = [
  [43.101, -89.462], [43.118, -89.448], [43.127, -89.42], [43.125, -89.39],
  [43.112, -89.368], [43.094, -89.36], [43.079, -89.368], [43.073, -89.392],
  [43.078, -89.42], [43.086, -89.445],
];
const LAKE_MONONA = [
  [43.077, -89.366], [43.075, -89.345], [43.062, -89.325], [43.043, -89.322],
  [43.033, -89.338], [43.035, -89.362], [43.05, -89.378], [43.066, -89.378],
];

function screenTextProps(screen) {
  return { x: screen.x, y: screen.y, textAnchor: 'middle' };
}

function polygonPath(points, project, bounds) {
  return points.map(([lat, lon], i) => {
    const { x, y } = project(lat, lon, bounds);
    return `${i === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`;
  }).join(' ') + ' Z';
}

/** Renders the road network as an SVG map, highlighting `path` (a list of node ids) if given. */
export default function NetworkMap({ nodes, edges, path, onNodeClick }) {
  const svgRef = useRef(null);
  const dragState = useRef(null);
  // Active touches, by pointer id -> last known SVG-unit position. Two of
  // these at once means a pinch, not a drag.
  const activePointers = useRef(new Map());
  const pinchState = useRef(null);
  const [view, setView] = useState({ zoom: 1, pan: { x: 0, y: 0 } });

  const bounds = useMemo(() => (nodes.length === 0 ? null : computeBounds(nodes)), [nodes]);

  const positions = useMemo(() => {
    if (nodes.length === 0) return new Map();
    const raw = new Map(nodes.map((n) => [n.id, project(n.lat, n.lon, bounds)]));
    return declump(raw);
  }, [nodes, bounds]);

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

  // Client (browser) pixels -> SVG viewBox units, accounting for the SVG
  // being scaled down to fit its container (width: 100% in CSS).
  function toSvgUnits(clientX, clientY) {
    const rect = svgRef.current.getBoundingClientRect();
    const scale = VIEW_WIDTH / rect.width;
    return { x: (clientX - rect.left) * scale, y: (clientY - rect.top) * scale };
  }

  function pinchMidpointAndDistance() {
    const pts = [...activePointers.current.values()];
    const [a, b] = pts;
    return {
      mid: { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 },
      dist: Math.hypot(b.x - a.x, b.y - a.y) || 0.01,
    };
  }

  function handlePointerDown(e) {
    // Panning/pinching starts only from empty map background, so node
    // clicks keep working normally.
    if (e.target.closest('.map-node')) return;
    svgRef.current?.setPointerCapture?.(e.pointerId);
    activePointers.current.set(e.pointerId, toSvgUnits(e.clientX, e.clientY));

    if (activePointers.current.size === 2) {
      dragState.current = null;
      pinchState.current = { lastDist: pinchMidpointAndDistance().dist };
    } else if (activePointers.current.size === 1) {
      dragState.current = { pointerId: e.pointerId, startX: e.clientX, startY: e.clientY, pan: view.pan };
    }
  }

  function handlePointerMove(e) {
    if (activePointers.current.has(e.pointerId)) {
      activePointers.current.set(e.pointerId, toSvgUnits(e.clientX, e.clientY));
    }

    if (activePointers.current.size === 2 && pinchState.current) {
      const { mid, dist } = pinchMidpointAndDistance();
      zoomAt(mid.x, mid.y, dist / pinchState.current.lastDist);
      pinchState.current.lastDist = dist;
      return;
    }

    // Only the single finger that actually started this drag should move it
    // -- a second, unrelated pointer (e.g. tapping a node) must not.
    if (!dragState.current || dragState.current.pointerId !== e.pointerId) return;
    const rect = svgRef.current.getBoundingClientRect();
    const scale = VIEW_WIDTH / rect.width;
    const dx = (e.clientX - dragState.current.startX) * scale;
    const dy = (e.clientY - dragState.current.startY) * scale;
    setView((v) => ({ ...v, pan: { x: dragState.current.pan.x + dx, y: dragState.current.pan.y + dy } }));
  }

  function handlePointerUp(e) {
    activePointers.current.delete(e.pointerId);
    pinchState.current = null;
    // If one finger lifts out of a pinch, resume panning with whichever
    // finger is still down, from its own last known (not the lifted
    // finger's) position.
    if (activePointers.current.size === 1) {
      const [[remainingId, remainingPos]] = activePointers.current.entries();
      const rect = svgRef.current.getBoundingClientRect();
      const scale = rect.width / VIEW_WIDTH;
      dragState.current = {
        pointerId: remainingId,
        startX: rect.left + remainingPos.x * scale,
        startY: rect.top + remainingPos.y * scale,
        pan: view.pan,
      };
    } else {
      dragState.current = null;
    }
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

  const labels = layoutLabels(nodes, positions, toScreen, pathSet, VIEW_WIDTH, VIEW_HEIGHT);

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
          {bounds && (
            <g className="map-water">
              <path d={polygonPath(LAKE_MENDOTA, project, bounds)} />
              <path d={polygonPath(LAKE_MONONA, project, bounds)} />
            </g>
          )}
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
            zooming spreads the nodes apart -- growing that spacing is what lets
            layoutLabels place more of them without collisions. */}
        <g className="map-labels">
          {bounds && (
            <>
              <text className="map-water-label" {...screenTextProps(toScreen(project(43.1, -89.405, bounds)))}>
                Lake Mendota
              </text>
              <text className="map-water-label" {...screenTextProps(toScreen(project(43.052, -89.35, bounds)))}>
                Lake Monona
              </text>
            </>
          )}
          {labels.map((label) => (
            <text key={label.id} className="map-node-label" x={label.x} y={label.y} textAnchor={label.textAnchor}>
              {label.name}
            </text>
          ))}
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
        Scroll, pinch, or use the +/&minus; buttons to zoom, drag to pan &mdash; labels appear as you zoom in.
      </p>
    </div>
  );
}
