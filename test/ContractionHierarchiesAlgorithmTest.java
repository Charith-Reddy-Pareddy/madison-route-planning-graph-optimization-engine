import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Correctness check for {@link ContractionHierarchiesAlgorithm}: its cost must match {@link
 * DijkstraGraph}'s own answer, and its returned path (after shortcut unpacking) must be a real,
 * walkable path in the original graph whose edge weights sum to that same cost -- not just a
 * cost-only check, since a shortcut that isn't unpacked correctly would still report the right
 * cost while returning a path with edges that don't actually exist.
 */
public class ContractionHierarchiesAlgorithmTest {

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

  private void assertRealPath(BaseGraph<String, Double> graph, String start, String end, PathResult<String> result) {
    List<String> path = result.path();
    assertEquals(start, path.get(0));
    assertEquals(end, path.get(path.size() - 1));
    double sum = 0.0;
    for (int i = 0; i < path.size() - 1; i++) {
      String a = path.get(i);
      String b = path.get(i + 1);
      assertTrue(graph.containsEdge(a, b), "no real edge " + a + " -> " + b + " in path " + path);
      sum += graph.getEdge(a, b);
    }
    assertEquals(result.cost(), sum, 1e-9, "path edge weights don't sum to the reported cost");
  }

  @Test
  public void matchesDijkstraOnTheLectureExampleGraph() {
    BaseGraph<String, Double> graph = lectureExampleGraph();
    var dijkstra = new DijkstraAlgorithm<String, Double>();
    var ch = new ContractionHierarchiesAlgorithm<String, Double>();

    for (String[] pair : new String[][] {{"D", "I"}, {"F", "M"}, {"A", "L"}, {"G", "E"}}) {
      var expected = dijkstra.findPath(graph, pair[0], pair[1]);
      var actual = ch.findPath(graph, pair[0], pair[1]);
      assertEquals(expected.cost(), actual.cost(), 1e-9, pair[0] + " -> " + pair[1]);
      assertRealPath(graph, pair[0], pair[1], actual);
    }

    assertEquals(0.0, ch.findPath(graph, "A", "A").cost(), 1e-9);
  }

  @Test
  public void throwsWhenNoPathExists() {
    BaseGraph<String, Double> graph = new BaseGraph<>(new PlaceholderMap<>());
    for (String node : new String[] {"A", "B", "C", "D"}) {
      graph.insertNode(node);
    }
    graph.insertEdge("A", "B", 1.0);
    graph.insertEdge("C", "D", 2.0);
    var ch = new ContractionHierarchiesAlgorithm<String, Double>();
    org.junit.jupiter.api.Assertions.assertThrows(
        java.util.NoSuchElementException.class, () -> ch.findPath(graph, "A", "D"));
  }

  @Test
  public void matchesProductionDijkstraOnEveryOrderedPairOfTheRealNetwork() {
    RoadNetwork network = new RoadNetwork();
    List<RoadNetwork.Intersection> locations = List.copyOf(network.intersections());
    var ch = new ContractionHierarchiesAlgorithm<String, Double>();

    int pairsChecked = 0;
    for (RoadNetwork.Intersection startLoc : locations) {
      for (RoadNetwork.Intersection endLoc : locations) {
        if (startLoc.id().equals(endLoc.id())) {
          continue;
        }
        double expected = network.graph().shortestPathCost(startLoc.id(), endLoc.id());
        var actual = ch.findPath(network.graph(), startLoc.id(), endLoc.id());
        assertEquals(expected, actual.cost(), 1e-6,
            () -> "ContractionHierarchiesAlgorithm disagreed with DijkstraGraph on "
                + startLoc.id() + " -> " + endLoc.id());
        assertRealPath(network.graph(), startLoc.id(), endLoc.id(), actual);
        pairsChecked++;
      }
    }
    assertEquals(locations.size() * (locations.size() - 1), pairsChecked);
    assertTrue(pairsChecked > 3000, "expected an exhaustive real-network sweep, only checked " + pairsChecked);
  }
}
