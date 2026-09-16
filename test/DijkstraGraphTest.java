import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for DijkstraGraph's shortest-path computation, covering the
 * worked lecture example plus edge cases: disconnected graphs, unknown
 * nodes, ties, and single-node graphs.
 */
public class DijkstraGraphTest {

  /**
   * Builds the graph used in the course's Dijkstra lecture example so
   * multiple tests can reuse the same fixture.
   */
  private DijkstraGraph<String, Double> lectureExampleGraph() {
    DijkstraGraph<String, Double> graph = new DijkstraGraph<>();
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

  @Test
  public void testShortestPathFromLecture() {
    DijkstraGraph<String, Double> graph = lectureExampleGraph();
    List<String> path = graph.shortestPathData("D", "I");
    Assertions.assertEquals(Arrays.asList("D", "G", "H", "I"), path);
    Assertions.assertEquals(13.0, graph.shortestPathCost("D", "I"));
  }

  @Test
  public void testShortestPathAndCostBetweenOtherNodes() {
    DijkstraGraph<String, Double> graph = lectureExampleGraph();
    List<String> path = graph.shortestPathData("F", "M");
    double cost = graph.shortestPathCost("F", "M");
    Assertions.assertEquals(Arrays.asList("F", "G", "A", "B", "M"), path);
    Assertions.assertEquals(17.0, cost);
  }

  @Test
  public void testNoPathBetweenReachableAndUnreachableNode() {
    DijkstraGraph<String, Double> graph = lectureExampleGraph();
    // L has no outgoing edges in this graph, so nothing is reachable from it.
    Assertions.assertThrows(NoSuchElementException.class, () -> graph.shortestPathData("L", "M"));
  }

  @Test
  public void testStartNodeNotInGraph() {
    DijkstraGraph<String, Integer> graph = new DijkstraGraph<>();
    graph.insertNode("A");
    graph.insertNode("B");
    graph.insertNode("C");
    graph.insertEdge("A", "B", 2);
    graph.insertEdge("B", "C", 3);

    Assertions.assertThrows(NoSuchElementException.class, () -> graph.shortestPathData("X", "C"));
  }

  @Test
  public void testSameStartAndEndNode() {
    DijkstraGraph<String, Double> graph = new DijkstraGraph<>();
    graph.insertNode("A");

    Assertions.assertEquals(Arrays.asList("A"), graph.shortestPathData("A", "A"));
    Assertions.assertEquals(0.0, graph.shortestPathCost("A", "A"));
  }

  @Test
  public void testDisconnectedGraphHasNoPath() {
    DijkstraGraph<String, Double> graph = new DijkstraGraph<>();
    for (String node : new String[] {"A", "B", "C", "D"}) {
      graph.insertNode(node);
    }
    graph.insertEdge("A", "B", 1.0);
    graph.insertEdge("C", "D", 2.0);

    Assertions.assertThrows(NoSuchElementException.class, () -> graph.shortestPathData("A", "D"));
  }

  @Test
  public void testTiedShortestPathsPicksOneWithCorrectCost() {
    DijkstraGraph<String, Double> graph = new DijkstraGraph<>();
    for (String node : new String[] {"A", "B", "C", "D"}) {
      graph.insertNode(node);
    }
    graph.insertEdge("A", "B", 2.0);
    graph.insertEdge("A", "C", 2.0);
    graph.insertEdge("B", "D", 2.0);
    graph.insertEdge("C", "D", 2.0);

    List<String> path = graph.shortestPathData("A", "D");
    double cost = graph.shortestPathCost("A", "D");

    boolean viaB = path.equals(Arrays.asList("A", "B", "D"));
    boolean viaC = path.equals(Arrays.asList("A", "C", "D"));
    Assertions.assertTrue(viaB || viaC, "expected a valid shortest path, got " + path);
    Assertions.assertEquals(4.0, cost);
  }

  @Test
  public void testSingleNodeGraphHasNoPathToUnknownNode() {
    DijkstraGraph<String, Double> graph = new DijkstraGraph<>();
    graph.insertNode("A");

    Assertions.assertEquals(Arrays.asList("A"), graph.shortestPathData("A", "A"));
    Assertions.assertEquals(0.0, graph.shortestPathCost("A", "A"));
    Assertions.assertThrows(NoSuchElementException.class, () -> graph.shortestPathData("A", "B"));
  }

  /**
   * A layered graph (S -> {A1,A2,A3} -> {B1,B2,B3} -> T, every node in one
   * layer connected to every node in the next, at increasing weights) where
   * most of those edges arrive at a node someone already reached more
   * cheaply. S->A1 is strictly the cheapest way into layer A (1 < 2 < 3),
   * so A1 is always processed before A2/A3 regardless of priority-queue tie
   * -breaking, and once it establishes B1/B2/B3's best-known costs, every
   * edge A2/A3 send to those same nodes is strictly worse and should never
   * be queued. Likewise B1 always reaches T first and cheapest.
   */
  private DijkstraGraph<String, Double> layeredGraphWithRedundantPaths() {
    DijkstraGraph<String, Double> graph = new DijkstraGraph<>();
    String[][] layers = {{"S"}, {"A1", "A2", "A3"}, {"B1", "B2", "B3"}, {"T"}};
    for (String[] layer : layers) {
      for (String node : layer) {
        graph.insertNode(node);
      }
    }
    for (int l = 0; l < layers.length - 1; l++) {
      double weight = 1.0;
      for (String from : layers[l]) {
        for (String to : layers[l + 1]) {
          graph.insertEdge(from, to, weight);
          weight += 1.0;
        }
      }
    }
    return graph;
  }

  @Test
  public void trackingBestKnownDistancePrunesInferiorQueueInsertions() {
    DijkstraGraph<String, Double> graph = layeredGraphWithRedundantPaths();

    graph.shortestPathData("S", "T");

    // Deterministic regardless of how the priority queue breaks cost ties:
    // S (1) + S's 3 edges to layer A (4) + A1's 3 edges to layer B (7, A1 is
    // strictly cheapest into layer A so it's always processed first and
    // always wins) + B1's 1 edge to T (8, same reasoning one layer down).
    // A2/A3/B2/B3 never contribute an insertion because every path through
    // them is strictly more expensive than one already recorded.
    Assertions.assertEquals(8, graph.lastQueueInsertions());
    // Every visited node's full edge list gets examined regardless of
    // whether it leads to an insertion, so edges considered is always
    // higher than actual insertions here -- that gap is the pruning.
    Assertions.assertTrue(graph.lastEdgesConsidered() > graph.lastQueueInsertions(),
        "expected pruning to skip inferior candidates, but edges considered ("
            + graph.lastEdgesConsidered() + ") was not greater than insertions ("
            + graph.lastQueueInsertions() + ")");
  }
}
