# Algorithm benchmark suite

Times `DijkstraAlgorithm`, `AStarAlgorithm`, `BidirectionalDijkstraAlgorithm`,
and `BidirectionalAStarAlgorithm` (see `src/`) against each other on
synthetic graphs of increasing size, since the live app's real network only
has 57 locations -- nowhere near enough to see these algorithms' complexity
differences show up in wall-clock time. Kept separate from `src/`, the same
way `pipeline/` and `scripts/` are, since none of this ships in the deployed
app.

## Running it

```bash
javac -cp out/classes -d out/benchmarks benchmarks/*.java
java -cp out/classes:out/benchmarks -Xmx6g BenchmarkRunner
```

(`out/classes` must already have the main `src/` build in it -- run `make
build` first if it doesn't.) Writes `results/benchmark.csv`.

## How the graphs are built

`BenchmarkGraphGenerator` gives every node a random 2D coordinate and sets
each edge's weight to the real Euclidean distance between its endpoints
(not an arbitrary random number), so straight-line distance to the goal is
a valid admissible heuristic for A* -- the same relationship
`RoadNetwork.haversineHeuristic()` has to real road distances, just on a
synthetic plane instead of the globe. Connectivity is guaranteed by a
random permutation chain wired in both directions before any extra random
edges are scattered in, so every generated graph is strongly connected and
every benchmark query is guaranteed reachable. A fixed seed makes a given
(node count, average degree) pair reproducible across runs.

## Real results (100 to 1,000,000 nodes, average out-degree 4, 30 random queries per size)

| Algorithm | 100 | 1,000 | 10,000 | 100,000 | 1,000,000 |
|---|---|---|---|---|---|
| Dijkstra | 0.22ms / 47 nodes | 0.95ms / 507 | 5.74ms / 5,542 | 112ms / 47,594 | 1,566ms / 431,177 |
| A* | 0.08ms / 21 | 0.57ms / 239 | 4.78ms / 2,923 | 64ms / 22,187 | 1,097ms / 199,645 |
| Bidirectional Dijkstra | 0.06ms / 14 | 0.18ms / 59 | 0.84ms / 240 | 1.41ms / 574 | 5.52ms / 1,865 |
| Bidirectional A* | 0.12ms / 16 | 0.27ms / 67 | 1.03ms / 261 | 1.99ms / 674 | 8.99ms / 1,944 |

(latency is average query wall-clock time; "nodes" is average nodes
expanded per query -- see `results/benchmark.csv` for the exact numbers,
including the approximate graph heap size at each node count.)

The gap that matters: on this graph shape (uniformly random points, degree
4), a bidirectional search's two half-radius frontiers cover quadratically
less area than one full-radius search, so the bidirectional variants pull
dramatically ahead as node count grows -- at 1,000,000 nodes, plain
Dijkstra expands ~431K nodes and takes ~1.57s per query; bidirectional
Dijkstra expands ~1.9K nodes and takes ~5.5ms, roughly a 280x reduction in
work for the same answer. A*'s heuristic helps (it consistently expands
40-55% fewer nodes than plain Dijkstra at every size) but a one-directional
search still can't match cutting the search radius in half on each side.

## Known limitations

- No preprocessing-time column yet -- none of these four algorithms need a
  preprocessing step. That column becomes relevant once a preprocessing-based
  algorithm (contraction hierarchies, ALT) is added to this suite.
- The heap measurement (`graph_heap_bytes`) is a best-effort
  before/after-`System.gc()` snapshot of the whole JVM heap around graph
  construction, not a precise per-object measurement -- treat it as
  approximate.
- Results are from a single machine, single run per size -- not averaged
  across repeated runs, so treat the exact numbers as illustrative of the
  scaling trend rather than precise benchmarks.
