import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { getGraph, getRoute } from './api';
import RouteForm from './components/RouteForm';
import RouteResult from './components/RouteResult';
import NetworkMap from './components/NetworkMap';

export default function App() {
  const [graph, setGraph] = useState({ nodes: [], edges: [] });
  const [graphError, setGraphError] = useState(null);
  const [startId, setStartId] = useState('');
  const [endId, setEndId] = useState('');
  const [route, setRoute] = useState(null);
  const [status, setStatus] = useState({ type: 'idle', message: '' });
  // Which field a map click sets next: click one node for start, the next for end.
  const [pickTarget, setPickTarget] = useState('start');

  const nodesById = useMemo(() => new Map(graph.nodes.map((n) => [n.id, n])), [graph.nodes]);

  // Guards against out-of-order responses: if a second search starts before
  // the first one resolves, only the response matching the latest request
  // should ever be applied to state.
  const latestRequestId = useRef(0);

  const findRoute = useCallback(async (start, end) => {
    const requestId = ++latestRequestId.current;
    setStatus({ type: 'loading', message: 'Calculating route...' });
    setRoute(null);
    try {
      const result = await getRoute(start, end);
      if (requestId !== latestRequestId.current) return;
      setRoute(result);
      setStatus({ type: 'success', message: `Shortest route found: ${result.path.length} stop(s).` });
    } catch (err) {
      if (requestId !== latestRequestId.current) return;
      setStatus({ type: 'error', message: err.message });
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    getGraph()
      .then((data) => {
        if (cancelled) return;
        setGraph(data);
        const sorted = [...data.nodes].sort((a, b) => a.name.localeCompare(b.name));
        if (sorted.length > 1) {
          setStartId(sorted[0].id);
          setEndId(sorted[1].id);
          findRoute(sorted[0].id, sorted[1].id);
        }
      })
      .catch((err) => {
        if (!cancelled) setGraphError(err.message);
      });
    return () => {
      cancelled = true;
    };
  }, [findRoute]);

  function handleSubmit(e) {
    e.preventDefault();
    findRoute(startId, endId);
  }

  function handleSwap() {
    setStartId(endId);
    setEndId(startId);
    findRoute(endId, startId);
  }

  function handleNodeClick(id) {
    if (pickTarget === 'start') {
      setStartId(id);
      setPickTarget('end');
    } else {
      setEndId(id);
      setPickTarget('start');
      findRoute(startId, id);
    }
  }

  const networkLoaded = graph.nodes.length > 0;

  return (
    <>
      <header>
        <h1>Path Finder</h1>
        <p className="subtitle">
          Find the shortest route across a Madison, Wisconsin road network, powered by Dijkstra's algorithm.
        </p>
      </header>

      <main>
        <section className="panel controls-panel">
          <h2>Get directions</h2>
          {graphError && <div className="status error">Failed to load network: {graphError}</div>}
          {!graphError && !networkLoaded && <div className="status">Loading network...</div>}
          {!graphError && networkLoaded && (
            <>
              <RouteForm
                nodes={graph.nodes}
                startId={startId}
                endId={endId}
                onStartChange={setStartId}
                onEndChange={setEndId}
                onSubmit={handleSubmit}
                onSwap={handleSwap}
                loading={status.type === 'loading'}
              />
              <RouteResult
                statusType={status.type}
                statusMessage={status.message}
                route={route}
                nodesById={nodesById}
              />
            </>
          )}
        </section>

        <section className="panel map-panel">
          <h2>Network map</h2>
          {networkLoaded && (
            <p className="map-hint">
              Or click a node: set the {pickTarget === 'start' ? 'start' : 'end'}.
            </p>
          )}
          <NetworkMap
            nodes={graph.nodes}
            edges={graph.edges}
            path={route?.path.map((p) => p.id) ?? null}
            onNodeClick={networkLoaded ? handleNodeClick : undefined}
          />
          <ul className="legend">
            <li><span className="legend-swatch road" /> Road</li>
            <li><span className="legend-swatch route" /> Shortest route</li>
            <li><span className="legend-swatch start" /> Start</li>
            <li><span className="legend-swatch end" /> End</li>
            <li><span className="legend-arrow">&#8594;</span> One-way (arrow points the allowed direction)</li>
            <li><span className="legend-step">1</span> Stop order along the route</li>
          </ul>
        </section>
      </main>

      <footer>
        <p>
          Graph algorithm: Dijkstra's shortest path (see <code>src/DijkstraGraph.java</code>). Java backend served
          over plain HTTP.
        </p>
      </footer>
    </>
  );
}
