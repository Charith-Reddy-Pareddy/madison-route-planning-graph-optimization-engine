import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Builds synthetic weighted directed graphs to benchmark {@link
 * ShortestPathAlgorithm} implementations at sizes far beyond the live app's
 * real 57-location network. Each node gets a random 2D coordinate and each
 * edge's weight is the real Euclidean distance between its two endpoints
 * (not an arbitrary random number), so straight-line distance to the goal
 * is a valid admissible heuristic for A* here -- the same relationship
 * {@link RoadNetwork#haversineHeuristic()} has to real road distances, just
 * on a synthetic plane instead of the globe.
 */
public class BenchmarkGraphGenerator {

  public record GeneratedGraph(BaseGraph<String, Double> graph, Map<String, double[]> coordinates) {
    public AStarHeuristic<String> euclideanHeuristic() {
      return (from, goal) -> distance(coordinates.get(from), coordinates.get(goal));
    }
  }

  /**
   * @param nodeCount how many nodes to generate
   * @param avgDegree target average out-degree once extra random edges are scattered in, on top
   *     of the connectivity-guaranteeing chain
   * @param seed makes a given (nodeCount, avgDegree, seed) triple reproducible across runs
   */
  public static GeneratedGraph generate(int nodeCount, int avgDegree, long seed) {
    Random random = new Random(seed);
    DijkstraGraph<String, Double> graph = new DijkstraGraph<>();
    Map<String, double[]> coordinates = new HashMap<>();
    List<String> nodeIds = new ArrayList<>(nodeCount);

    for (int i = 0; i < nodeCount; i++) {
      String id = "n" + i;
      nodeIds.add(id);
      graph.insertNode(id);
      coordinates.put(id, new double[] {random.nextDouble() * 1000.0, random.nextDouble() * 1000.0});
    }

    // Guarantee strong connectivity first: a random permutation chain, wired in both directions,
    // so every node is reachable from every other node before any random edges are added.
    List<String> order = new ArrayList<>(nodeIds);
    Collections.shuffle(order, random);
    for (int i = 1; i < order.size(); i++) {
      String from = order.get(random.nextInt(i));
      String to = order.get(i);
      addEdge(graph, coordinates, from, to);
      addEdge(graph, coordinates, to, from);
    }

    // Scatter extra one-directional edges up to the target average out-degree, so the graph has
    // real branching for the search algorithms to navigate instead of just the bare chain.
    long targetEdges = (long) nodeCount * avgDegree;
    long extraEdges = Math.max(0, targetEdges - graph.getEdgeCount());
    for (long i = 0; i < extraEdges; i++) {
      String from = nodeIds.get(random.nextInt(nodeCount));
      String to = nodeIds.get(random.nextInt(nodeCount));
      if (from.equals(to)) {
        continue;
      }
      addEdge(graph, coordinates, from, to);
    }

    return new GeneratedGraph(graph, coordinates);
  }

  private static void addEdge(
      BaseGraph<String, Double> graph, Map<String, double[]> coordinates, String from, String to) {
    graph.insertEdge(from, to, distance(coordinates.get(from), coordinates.get(to)));
  }

  private static double distance(double[] a, double[] b) {
    double dx = a[0] - b[0];
    double dy = a[1] - b[1];
    return Math.sqrt(dx * dx + dy * dy);
  }
}
