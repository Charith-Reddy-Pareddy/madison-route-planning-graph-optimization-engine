import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;

/**
 * Correctness gate for the new algorithm family (DijkstraAlgorithm,
 * BidirectionalDijkstraAlgorithm, AStarAlgorithm, BidirectionalAStarAlgorithm):
 * each one must match plain Dijkstra's cost on every existing test graph --
 * the lecture-example fixture DijkstraGraphTest already uses, plus every
 * ordered pair of the real 57-location Madison network, cross-checked
 * against the production DijkstraGraph's own answer. This is the actual
 * evidence these algorithms are correct, not just "compiles and runs."
 */
public class AlgorithmsCorrectnessTest {

  // Same fixture as DijkstraGraphTest#lectureExampleGraph, so both test
  // classes are provably checking the same graph.
  private BaseGraph<String, Double> lectureExampleGraph() {
    BaseGraph<String, Double> graph = new BaseGraph<>(new PlaceholderMap<>());
    for (String node : new String[] {"A", "B", "D", "E", "F", "G", "H", "I", "L", "M"}) {
      graph.insertNode(node);
    }
    graph.insertEdge("A", "B", 1.0);
    graph.insertEdge("A", "H", 7.0);
    graph.insertEdge("A", "M", 5.0);
    graph.insertEdge("B", "M", 3.0);
    graph.insertEdge("D", "F", 4.0);
    graph.insertEdge("D", "G", 2.0);
    graph.insertEdge("D", "A", 7.0);
    graph.insertEdge("F", "G", 9.0);
    graph.insertEdge("G", "H", 9.0);
    graph.insertEdge("G", "L", 7.0);
    graph.insertEdge("G", "A", 4.0);
    graph.insertEdge("H", "B", 6.0);
    graph.insertEdge("H", "L", 2.0);
    graph.insertEdge("H", "I", 2.0);
    graph.insertEdge("I", "H", 2.0);
    graph.insertEdge("I", "D", 1.0);
    graph.insertEdge("M", "I", 4.0);
    graph.insertEdge("M", "E", 3.0);
    graph.insertEdge("M", "F", 4.0);
    return graph;
  }

  // A trivial "distance" heuristic for the lecture graph (no real
  // coordinates exist for these letters) -- admissible because it's
  // always zero, which makes A*/bidirectional A* behave like plain
  // Dijkstra on this fixture. That's fine: this fixture is for exercising
  // the search mechanics and edge cases, not the heuristic itself, which
  // gets its own real-data test below via RoadNetwork.haversineHeuristic().
  private static final AStarHeuristic<String> ZERO_HEURISTIC = (from, to) -> 0.0;

  private List<ShortestPathAlgorithm<String, Double>> allAlgorithms() {
    return List.of(
        new DijkstraAlgorithm<>(),
        new BidirectionalDijkstraAlgorithm<>(),
        new AStarAlgorithm<>(ZERO_HEURISTIC),
        new BidirectionalAStarAlgorithm<>(ZERO_HEURISTIC));
  }

  @Test
  public void allAlgorithmsMatchTheLectureExampleCosts() {
    BaseGraph<String, Double> graph = lectureExampleGraph();
    for (ShortestPathAlgorithm<String, Double> algorithm : allAlgorithms()) {
      PathResult<String> result = algorithm.findPath(graph, "D", "I");
      assertEquals(Arrays.asList("D", "G", "H", "I"), result.path(), algorithm.getClass().getSimpleName());
      assertEquals(13.0, result.cost(), 1e-9, algorithm.getClass().getSimpleName());
    }
  }

  @Test
  public void allAlgorithmsMatchOnASecondPairInTheLectureGraph() {
    BaseGraph<String, Double> graph = lectureExampleGraph();
    for (ShortestPathAlgorithm<String, Double> algorithm : allAlgorithms()) {
      PathResult<String> result = algorithm.findPath(graph, "F", "M");
      assertEquals(Arrays.asList("F", "G", "A", "B", "M"), result.path(), algorithm.getClass().getSimpleName());
      assertEquals(17.0, result.cost(), 1e-9, algorithm.getClass().getSimpleName());
    }
  }

  @Test
  public void allAlgorithmsReturnZeroCostSinglePathWhenStartEqualsEnd() {
    BaseGraph<String, Double> graph = lectureExampleGraph();
    for (ShortestPathAlgorithm<String, Double> algorithm : allAlgorithms()) {
      PathResult<String> result = algorithm.findPath(graph, "A", "A");
      assertEquals(List.of("A"), result.path(), algorithm.getClass().getSimpleName());
      assertEquals(0.0, result.cost(), 1e-9, algorithm.getClass().getSimpleName());
    }
  }

  @Test
  public void allAlgorithmsThrowWhenNoPathExists() {
    BaseGraph<String, Double> graph = lectureExampleGraph();
    for (ShortestPathAlgorithm<String, Double> algorithm : allAlgorithms()) {
      // L has no outgoing edges in this graph.
      assertThrows(NoSuchElementException.class, () -> algorithm.findPath(graph, "L", "M"),
          algorithm.getClass().getSimpleName());
    }
  }

  @Test
  public void allAlgorithmsThrowForAnUnknownNode() {
    BaseGraph<String, Double> graph = lectureExampleGraph();
    for (ShortestPathAlgorithm<String, Double> algorithm : allAlgorithms()) {
      assertThrows(NoSuchElementException.class, () -> algorithm.findPath(graph, "X", "A"),
          algorithm.getClass().getSimpleName());
    }
  }

  @Test
  public void allAlgorithmsThrowOnADisconnectedGraph() {
    BaseGraph<String, Double> graph = new BaseGraph<>(new PlaceholderMap<>());
    for (String node : new String[] {"A", "B", "C", "D"}) {
      graph.insertNode(node);
    }
    graph.insertEdge("A", "B", 1.0);
    graph.insertEdge("C", "D", 2.0);
    for (ShortestPathAlgorithm<String, Double> algorithm : allAlgorithms()) {
      assertThrows(NoSuchElementException.class, () -> algorithm.findPath(graph, "A", "D"),
          algorithm.getClass().getSimpleName());
    }
  }

  // --- Real-network exhaustive cross-validation -----------------------

  @Test
  public void allAlgorithmsMatchProductionDijkstraOnEveryOrderedPairOfTheRealNetwork() {
    RoadNetwork network = new RoadNetwork();
    List<RoadNetwork.Intersection> locations = List.copyOf(network.intersections());

    List<ShortestPathAlgorithm<String, Double>> algorithms = List.of(
        new DijkstraAlgorithm<>(),
        new BidirectionalDijkstraAlgorithm<>(),
        new AStarAlgorithm<>(network.haversineHeuristic()),
        new BidirectionalAStarAlgorithm<>(network.haversineHeuristic()));

    int pairsChecked = 0;
    for (RoadNetwork.Intersection startLoc : locations) {
      for (RoadNetwork.Intersection endLoc : locations) {
        if (startLoc.id().equals(endLoc.id())) {
          continue;
        }
        double expected = network.graph().shortestPathCost(startLoc.id(), endLoc.id());
        for (ShortestPathAlgorithm<String, Double> algorithm : algorithms) {
          double actual = algorithm.findPath(network.graph(), startLoc.id(), endLoc.id()).cost();
          assertEquals(expected, actual, 1e-6,
              () -> algorithm.getClass().getSimpleName() + " disagreed with DijkstraGraph on "
                  + startLoc.id() + " -> " + endLoc.id());
        }
        pairsChecked++;
      }
    }

    // 57 locations -> 57*56 ordered pairs. Asserting the real count caught
    // (not just "no mismatches found") guards against this test silently
    // checking nothing if the network ever loaded empty.
    assertEquals(locations.size() * (locations.size() - 1), pairsChecked);
    assertTrue(pairsChecked > 3000, "expected an exhaustive real-network sweep, only checked " + pairsChecked);
  }
}
