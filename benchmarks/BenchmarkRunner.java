import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

/**
 * Times {@link DijkstraAlgorithm}, {@link AStarAlgorithm}, {@link
 * BidirectionalDijkstraAlgorithm}, {@link BidirectionalAStarAlgorithm}, and
 * {@link ALTAlgorithm} against each other on synthetic graphs of increasing
 * size (see {@link BenchmarkGraphGenerator}), and writes one CSV row per
 * (algorithm, size) pair to results/benchmark.csv: first-query latency
 * (includes any lazy preprocessing, e.g. ALT's landmark selection), average
 * steady-state query latency, average nodes expanded, and approximate heap
 * usage for building the graph itself.
 *
 * <p>The first-query-vs-steady-state split is what surfaces preprocessing
 * cost without needing a special case: the four non-preprocessing
 * algorithms have first-query and steady-state latency close together,
 * while ALT's first query is where its one-time landmark Dijkstra runs
 * actually happen (see {@link ALTAlgorithm}'s per-graph cache).
 *
 * <p>Run with: java -cp out/classes:out/benchmarks BenchmarkRunner
 */
public class BenchmarkRunner {

  private static final int[] SIZES = {100, 1_000, 10_000, 100_000, 1_000_000};
  private static final int AVG_DEGREE = 4;
  private static final int QUERIES_PER_SIZE = 30;
  private static final long GRAPH_SEED = 42;
  private static final long QUERY_SEED = 7;

  private record Row(
      String algorithm,
      int nodeCount,
      double firstQueryMs,
      double avgLatencyMs,
      double avgNodesExpanded,
      long graphHeapBytes) {}

  public static void main(String[] args) throws IOException {
    List<Row> rows = new java.util.ArrayList<>();

    for (int size : SIZES) {
      System.out.println("Generating graph: " + size + " nodes...");
      long beforeHeap = usedHeapBytes();
      BenchmarkGraphGenerator.GeneratedGraph generated =
          BenchmarkGraphGenerator.generate(size, AVG_DEGREE, GRAPH_SEED);
      long graphHeapBytes = Math.max(0, usedHeapBytes() - beforeHeap);

      List<String> nodeIds = new java.util.ArrayList<>();
      for (int i = 0; i < size; i++) {
        nodeIds.add("n" + i);
      }
      Random queryRandom = new Random(QUERY_SEED);
      List<String[]> queries = new java.util.ArrayList<>();
      for (int i = 0; i < QUERIES_PER_SIZE; i++) {
        String start = nodeIds.get(queryRandom.nextInt(size));
        String end = nodeIds.get(queryRandom.nextInt(size));
        queries.add(new String[] {start, end});
      }

      var heuristic = generated.euclideanHeuristic();
      rows.add(benchmark("Dijkstra", new DijkstraAlgorithm<>(), generated, queries, size, graphHeapBytes));
      rows.add(benchmark("AStar", new AStarAlgorithm<>(heuristic), generated, queries, size, graphHeapBytes));
      rows.add(
          benchmark(
              "BidirectionalDijkstra",
              new BidirectionalDijkstraAlgorithm<>(),
              generated,
              queries,
              size,
              graphHeapBytes));
      rows.add(
          benchmark(
              "BidirectionalAStar",
              new BidirectionalAStarAlgorithm<>(heuristic),
              generated,
              queries,
              size,
              graphHeapBytes));
      rows.add(benchmark("ALT", new ALTAlgorithm<>(8), generated, queries, size, graphHeapBytes));
    }

    Path outDir = Path.of("benchmarks", "results");
    Files.createDirectories(outDir);
    Path outFile = outDir.resolve("benchmark.csv");
    try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(outFile))) {
      writer.println("algorithm,node_count,first_query_ms,avg_latency_ms,avg_nodes_expanded,graph_heap_bytes");
      for (Row row : rows) {
        writer.printf(
            "%s,%d,%.4f,%.4f,%.1f,%d%n",
            row.algorithm(),
            row.nodeCount(),
            row.firstQueryMs(),
            row.avgLatencyMs(),
            row.avgNodesExpanded(),
            row.graphHeapBytes());
      }
    }
    System.out.println("Wrote " + rows.size() + " rows to " + outFile);
  }

  private static Row benchmark(
      String name,
      ShortestPathAlgorithm<String, Double> algorithm,
      BenchmarkGraphGenerator.GeneratedGraph generated,
      List<String[]> queries,
      int nodeCount,
      long graphHeapBytes) {
    double firstQueryMs = 0.0;
    long totalNanos = 0;
    long totalNodesExpanded = 0;
    int successfulQueries = 0;
    int steadyStateQueries = 0;

    for (int i = 0; i < queries.size(); i++) {
      String[] query = queries.get(i);
      long start = System.nanoTime();
      try {
        PathResult<String> result = algorithm.findPath(generated.graph(), query[0], query[1]);
        long elapsedNanos = System.nanoTime() - start;
        totalNodesExpanded += result.nodesExpanded();
        successfulQueries++;
        if (i == 0) {
          firstQueryMs = elapsedNanos / 1e6;
        } else {
          totalNanos += elapsedNanos;
          steadyStateQueries++;
        }
      } catch (java.util.NoSuchElementException e) {
        // The synthetic graph is strongly connected by construction (see
        // BenchmarkGraphGenerator), so this shouldn't happen -- but if start == end validation
        // ever throws for some other reason, skip that one query rather than aborting the run.
      }
    }

    double avgLatencyMs = steadyStateQueries == 0 ? 0.0 : (totalNanos / 1e6) / steadyStateQueries;
    double avgNodesExpanded = successfulQueries == 0 ? 0.0 : (double) totalNodesExpanded / successfulQueries;
    System.out.printf(
        "  %-24s n=%-8d first query %.3fms  avg steady-state latency %.3fms  avg nodes expanded %.1f%n",
        name, nodeCount, firstQueryMs, avgLatencyMs, avgNodesExpanded);
    return new Row(name, nodeCount, firstQueryMs, avgLatencyMs, avgNodesExpanded, graphHeapBytes);
  }

  /** A rough, best-effort heap snapshot -- run with -Xmx set and treat this as approximate, not exact. */
  private static long usedHeapBytes() {
    Runtime runtime = Runtime.getRuntime();
    System.gc();
    return runtime.totalMemory() - runtime.freeMemory();
  }
}
