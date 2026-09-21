# Algorithm benchmark suite

Times `DijkstraAlgorithm`, `AStarAlgorithm`, `BidirectionalDijkstraAlgorithm`,
`BidirectionalAStarAlgorithm`, and `ALTAlgorithm` (see `src/`) against each
other on synthetic graphs of increasing size, since the live app's real
network only has 57 locations -- nowhere near enough to see these
algorithms' complexity differences show up in wall-clock time. Kept
separate from `src/`, the same way `pipeline/` and `scripts/` are, since
none of this ships in the deployed app.

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

Steady-state query latency / average nodes expanded (excludes each algorithm's first query, so
preprocessing-heavy algorithms aren't penalized in this row -- see the preprocessing row below):

| Algorithm | 100 | 1,000 | 10,000 | 100,000 | 1,000,000 |
|---|---|---|---|---|---|
| Dijkstra | 0.17ms / 47 nodes | 0.91ms / 507 | 6.24ms / 5,542 | 114ms / 47,594 | 1,850ms / 431,177 |
| A* | 0.06ms / 21 | 0.56ms / 239 | 3.84ms / 2,923 | 70ms / 22,187 | 1,030ms / 199,645 |
| Bidirectional Dijkstra | 0.05ms / 14 | 0.18ms / 59 | 0.74ms / 240 | 1.45ms / 574 | 9.34ms / 1,865 |
| Bidirectional A* | 0.07ms / 16 | 0.26ms / 67 | 0.91ms / 261 | 2.32ms / 674 | 10.86ms / 1,944 |
| ALT (8 landmarks) | 0.08ms / 8 | 0.33ms / 63 | 2.62ms / 676 | 61ms / 4,950 | 692ms / 43,594 |

First-query latency, i.e. including one-time preprocessing where an algorithm has any (only ALT
does, in this suite -- landmark selection plus one forward and one backward Dijkstra per landmark):

| Algorithm | 100 | 1,000 | 10,000 | 100,000 | 1,000,000 |
|---|---|---|---|---|---|
| Dijkstra / A* / Bidirectional Dijkstra / Bidirectional A* | <2.2ms | <1.3ms | <14ms | <66ms | <1.5s |
| ALT (8 landmarks) | 5.7ms | 30ms | 248ms | 5.6s | 98.6s |

(see `results/benchmark.csv` for the exact numbers, including the approximate graph heap size at
each node count.)

The gap that matters between the first four: on this graph shape (uniformly random points, degree
4), a bidirectional search's two half-radius frontiers cover quadratically less area than one
full-radius search, so the bidirectional variants pull dramatically ahead as node count grows -- at
1,000,000 nodes, plain Dijkstra expands ~431K nodes and takes ~1.85s per query; bidirectional
Dijkstra expands ~1.9K nodes and takes ~9.3ms, roughly a 200x reduction in work for the same
answer. A*'s heuristic helps (it consistently expands 40-55% fewer nodes than plain Dijkstra at
every size) but a one-directional search still can't match cutting the search radius in half on
each side.

ALT tells a real, two-sided story rather than a clean win. Its landmark-based heuristic is
noticeably stronger than plain A*'s straight-line one -- at 1,000,000 nodes it expands ~43.6K nodes
versus A*'s ~199.6K (a real ~4.6x reduction) and its steady-state query latency (~692ms) beats
plain A* (~1,030ms). But it's still a one-directional search, so it doesn't get anywhere near the
bidirectional variants' ~9-11ms, and its one-time preprocessing is genuinely expensive: ~98.6
seconds at 1,000,000 nodes for 8 landmarks (8 farthest-point-selection Dijkstra runs, plus one
forward and one backward Dijkstra per chosen landmark -- 24 full-graph Dijkstra runs total).
Whether that tradeoff is worth it depends entirely on the deployment: amortized over millions of
queries against a graph that barely ever changes, 98.6s of one-time preprocessing for meaningfully
fewer expanded nodes per query is cheap; for a graph that's rebuilt often, it isn't.

## Known limitations

- The heap measurement (`graph_heap_bytes`) is a best-effort
  before/after-`System.gc()` snapshot of the whole JVM heap around graph
  construction, not a precise per-object measurement -- treat it as
  approximate.
- Results are from a single machine, single run per size -- not averaged
  across repeated runs, so treat the exact numbers as illustrative of the
  scaling trend rather than precise benchmarks.
- ALT's landmark count (8) and selection strategy (farthest-point) aren't
  swept here -- fewer landmarks would cut preprocessing time at some cost to
  heuristic quality, which this suite doesn't yet explore.
