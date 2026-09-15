import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Structural sanity checks on the sample RoadNetwork itself, separate from
 * the Dijkstra algorithm tests -- these catch a bad edit to the fixture data
 * (e.g. a one-way street that strands an intersection with no way back to
 * the rest of the network) rather than a bug in the algorithm.
 */
public class RoadNetworkTest {

  @Test
  public void everyIntersectionCanReachEveryOtherIntersection() {
    RoadNetwork network = new RoadNetwork();
    var ids = network.intersections().stream().map(RoadNetwork.Intersection::id).toList();

    for (String start : ids) {
      for (String end : ids) {
        if (start.equals(end)) continue;
        assertDoesNotThrow(
            () -> network.graph().shortestPathData(start, end),
            () -> start + " cannot reach " + end + " -- likely a one-way street with no alternate return route");
      }
    }
  }

  @Test
  public void everyRoadConnectsTwoRealIntersections() {
    RoadNetwork network = new RoadNetwork();
    for (RoadNetwork.Road road : network.roads()) {
      if (!network.hasIntersection(road.from()) || !network.hasIntersection(road.to())) {
        fail("Road references an unknown intersection: " + road);
      }
    }
  }
}
