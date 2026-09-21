import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

/**
 * Times {@link DijkstraAlgorithm}, {@link AStarAlgorithm}, {@link
 * BidirectionalDijkstraAlgorithm}, and {@link BidirectionalAStarAlgorithm}
 * against each other on synthetic graphs of increasing size (see {@link
 * BenchmarkGraphGenerator}), and writes one CSV row per (algorithm, size)
 * pair to results/benchmark.csv: average query latency, average nodes
 * expanded, and approximate heap usage for building the graph itself.
 *
 * <p>None of these algorithms need a preprocessing step (unlike, say,
 * contraction hierarchies), so there's no separate preprocessing-time
 * column yet -- see the "Additional pathfinding algorithms" section of the
 * top-level README for why only these four exist so far.
 *
 * <p>Run with: java -cp out/classes:out/benchmarks BenchmarkRunner
 */
public class BenchmarkRunner {

  private static final int[] SIZES = {100, 1_000, 10_000, 100_000};
  private static final int AVG_DEGREE = 4;
  private static final int QUERIES_PER_SIZE = 30;
  private static final long GRAPH_SEED = 42;
  private static final long QUERY_SEED = 7;

  private record Row(String algorithm, int nodeCount, double avgLatencyMs, double avgNodesExpanded, long graphHeapBytes) {}

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
    }

    Path outDir = Path.of("benchmarks", "results");
    Files.createDirectories(outDir);
    Path outFile = outDir.resolve("benchmark.csv");
    try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(outFile))) {
      writer.println("algorithm,node_count,avg_latency_ms,avg_nodes_expanded,graph_heap_bytes");
      for (Row row : rows) {
        writer.printf(
            "%s,%d,%.4f,%.1f,%d%n",
            row.algorithm(), row.nodeCount(), row.avgLatencyMs(), row.avgNodesExpanded(), row.graphHeapBytes());
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
    long totalNanos = 0;
    long totalNodesExpanded = 0;
    int successfulQueries = 0;

    for (String[] query : queries) {
      long start = System.nanoTime();
      try {
        PathResult<String> result = algorithm.findPath(generated.graph(), query[0], query[1]);
        totalNanos += System.nanoTime() - start;
        totalNodesExpanded += result.nodesExpanded();
        successfulQueries++;
      } catch (java.util.NoSuchElementException e) {
        // The synthetic graph is strongly connected by construction (see
        // BenchmarkGraphGenerator), so this shouldn't happen -- but if start == end validation
        // ever throws for some other reason, skip that one query rather than aborting the run.
      }
    }

    double avgLatencyMs = successfulQueries == 0 ? 0.0 : (totalNanos / 1e6) / successfulQueries;
    double avgNodesExpanded = successfulQueries == 0 ? 0.0 : (double) totalNodesExpanded / successfulQueries;
    System.out.printf(
        "  %-24s n=%-8d avg latency %.3fms  avg nodes expanded %.1f%n",
        name, nodeCount, avgLatencyMs, avgNodesExpanded);
    return new Row(name, nodeCount, avgLatencyMs, avgNodesExpanded, graphHeapBytes);
  }

  /** A rough, best-effort heap snapshot -- run with -Xmx set and treat this as approximate, not exact. */
  private static long usedHeapBytes() {
    Runtime runtime = Runtime.getRuntime();
    System.gc();
    return runtime.totalMemory() - runtime.freeMemory();
  }
}
