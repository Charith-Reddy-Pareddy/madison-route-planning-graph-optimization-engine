import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * ALT (A*, Landmarks, Triangle inequality; Goldberg &amp; Harrelson 2004/2005): precomputes
 * shortest-path distances from a handful of landmark nodes to (and from) every other node once,
 * then uses the triangle inequality on those precomputed distances as an admissible A* heuristic
 * at query time -- unlike {@link AStarAlgorithm}, no real coordinates are required (compare {@link
 * RoadNetwork#haversineHeuristic()}). This is the first algorithm in this family with a real
 * preprocessing cost: selecting landmarks and running one Dijkstra from each of them.
 *
 * <p>Landmark selection uses the standard farthest-point heuristic: start from an arbitrary node,
 * then repeatedly add whichever remaining node is farthest (by real shortest-path distance) from
 * every landmark chosen so far, so landmarks end up spread across the graph instead of clustered
 * together.
 *
 * <p>The graph is directed, so one direction of distance isn't enough: d(L, v) (landmark to v, a
 * forward Dijkstra from L) says nothing admissible about a target that lies behind v relative to
 * L. ALT also precomputes d(v, L) (v to landmark, a Dijkstra from L over reversed edges, via
 * {@link BaseGraph#predecessorsOf}) and combines both directions per landmark:
 * <pre>  h_L(v) = max(d(L, target) - d(L, v),  d(v, L) - d(target, L))</pre>
 * and takes the max of that over every landmark, which stays admissible (never overestimates the
 * true remaining cost) by the triangle inequality -- see Goldberg &amp; Harrelson for the
 * correctness argument.
 *
 * <p>Preprocessing is cached per graph instance (by reference identity) and reused across
 * queries, the way a real deployment would precompute once and serve many queries -- calling
 * findPath again on the same graph object does not redo it.
 */
public class ALTAlgorithm<NodeType, EdgeType extends Number> implements ShortestPathAlgorithm<NodeType, EdgeType> {

  private final int landmarkCount;
  private final Map<BaseGraph<NodeType, EdgeType>, Preprocessed<NodeType>> cache = new IdentityHashMap<>();

  public ALTAlgorithm(int landmarkCount) {
    if (landmarkCount < 1) {
      throw new IllegalArgumentException("landmarkCount must be >= 1");
    }
    this.landmarkCount = landmarkCount;
  }

  private record Preprocessed<NodeType>(
      List<Map<NodeType, Double>> forward, List<Map<NodeType, Double>> backward) {}

  private record Entry<NodeType>(NodeType node, double gCost, double fCost) implements Comparable<Entry<NodeType>> {
    @Override
    public int compareTo(Entry<NodeType> other) {
      return Double.compare(fCost, other.fCost);
    }
  }

  @Override
  public PathResult<NodeType> findPath(BaseGraph<NodeType, EdgeType> graph, NodeType start, NodeType end) {
    if (start == null || end == null || !graph.containsNode(start) || !graph.containsNode(end)) {
      throw new NoSuchElementException("start or end node not in graph");
    }
    if (start.equals(end)) {
      return new PathResult<>(List.of(start), 0.0, 0);
    }

    Preprocessed<NodeType> preprocessed = cache.computeIfAbsent(graph, this::preprocess);

    Map<NodeType, Double> bestKnown = new HashMap<>();
    Map<NodeType, NodeType> predecessor = new HashMap<>();
    Set<NodeType> visited = new HashSet<>();
    PriorityQueue<Entry<NodeType>> queue = new PriorityQueue<>();
    int nodesExpanded = 0;

    bestKnown.put(start, 0.0);
    queue.add(new Entry<>(start, 0.0, heuristic(preprocessed, start, end)));

    while (!queue.isEmpty()) {
      Entry<NodeType> current = queue.poll();
      if (visited.contains(current.node())) {
        continue;
      }
      visited.add(current.node());
      nodesExpanded++;

      if (current.node().equals(end)) {
        return new PathResult<>(
            DijkstraAlgorithm.reconstructPath(predecessor, start, end), current.gCost(), nodesExpanded);
      }

      for (Neighbor<NodeType, EdgeType> neighbor : graph.neighborsOf(current.node())) {
        if (visited.contains(neighbor.node())) {
          continue;
        }
        double newG = current.gCost() + neighbor.weight().doubleValue();
        Double known = bestKnown.get(neighbor.node());
        if (known == null || newG < known) {
          bestKnown.put(neighbor.node(), newG);
          predecessor.put(neighbor.node(), current.node());
          queue.add(new Entry<>(neighbor.node(), newG, newG + heuristic(preprocessed, neighbor.node(), end)));
        }
      }
    }

    throw new NoSuchElementException("no path from " + start + " to " + end);
  }

  private double heuristic(Preprocessed<NodeType> preprocessed, NodeType from, NodeType to) {
    double best = 0.0;
    for (int i = 0; i < preprocessed.forward().size(); i++) {
      Map<NodeType, Double> dFromLandmark = preprocessed.forward().get(i);
      Map<NodeType, Double> dToLandmark = preprocessed.backward().get(i);
      Double dLFrom = dFromLandmark.get(from);
      Double dLTo = dFromLandmark.get(to);
      Double dFromL = dToLandmark.get(from);
      Double dToL = dToLandmark.get(to);
      if (dLFrom != null && dLTo != null) {
        best = Math.max(best, dLTo - dLFrom);
      }
      if (dFromL != null && dToL != null) {
        best = Math.max(best, dFromL - dToL);
      }
    }
    return best;
  }

  private Preprocessed<NodeType> preprocess(BaseGraph<NodeType, EdgeType> graph) {
    List<NodeType> allNodes = graph.getAllNodes();
    List<NodeType> landmarks = selectLandmarks(graph, allNodes);

    List<Map<NodeType, Double>> forward = new ArrayList<>();
    List<Map<NodeType, Double>> backward = new ArrayList<>();
    for (NodeType landmark : landmarks) {
      forward.add(dijkstraDistances(graph, landmark, false));
      backward.add(dijkstraDistances(graph, landmark, true));
    }
    return new Preprocessed<>(forward, backward);
  }

  /**
   * Farthest-point selection: each new landmark is whichever remaining node is farthest (by real
   * shortest-path distance) from every landmark chosen so far, so landmarks end up spread across
   * the graph rather than clustered in one corner of it.
   */
  private List<NodeType> selectLandmarks(BaseGraph<NodeType, EdgeType> graph, List<NodeType> allNodes) {
    List<NodeType> landmarks = new ArrayList<>();
    if (allNodes.isEmpty()) {
      return landmarks;
    }

    Map<NodeType, Double> minDistanceToAnyLandmark = new HashMap<>();
    for (NodeType node : allNodes) {
      minDistanceToAnyLandmark.put(node, Double.POSITIVE_INFINITY);
    }

    NodeType next = allNodes.get(0);
    int count = Math.min(landmarkCount, allNodes.size());
    for (int i = 0; i < count; i++) {
      landmarks.add(next);
      Map<NodeType, Double> distancesFromNext = dijkstraDistances(graph, next, false);
      for (NodeType node : allNodes) {
        double distanceFromNext = distancesFromNext.getOrDefault(node, Double.POSITIVE_INFINITY);
        if (distanceFromNext < minDistanceToAnyLandmark.get(node)) {
          minDistanceToAnyLandmark.put(node, distanceFromNext);
        }
      }

      NodeType farthest = null;
      double farthestDistance = -1.0;
      for (NodeType node : allNodes) {
        if (landmarks.contains(node)) {
          continue;
        }
        double distance = minDistanceToAnyLandmark.get(node);
        if (distance > farthestDistance) {
          farthestDistance = distance;
          farthest = node;
        }
      }
      if (farthest == null) {
        break; // fewer distinct reachable nodes than requested landmarks
      }
      next = farthest;
    }
    return landmarks;
  }

  private Map<NodeType, Double> dijkstraDistances(BaseGraph<NodeType, EdgeType> graph, NodeType source, boolean reversed) {
    Map<NodeType, Double> distances = new HashMap<>();
    Set<NodeType> visited = new HashSet<>();
    PriorityQueue<Entry<NodeType>> queue = new PriorityQueue<>();
    distances.put(source, 0.0);
    queue.add(new Entry<>(source, 0.0, 0.0));

    while (!queue.isEmpty()) {
      Entry<NodeType> current = queue.poll();
      if (visited.contains(current.node())) {
        continue;
      }
      visited.add(current.node());
      List<Neighbor<NodeType, EdgeType>> edges =
          reversed ? graph.predecessorsOf(current.node()) : graph.neighborsOf(current.node());
      for (Neighbor<NodeType, EdgeType> neighbor : edges) {
        if (visited.contains(neighbor.node())) {
          continue;
        }
        double newCost = current.gCost() + neighbor.weight().doubleValue();
        Double known = distances.get(neighbor.node());
        if (known == null || newCost < known) {
          distances.put(neighbor.node(), newCost);
          queue.add(new Entry<>(neighbor.node(), newCost, newCost));
        }
      }
    }
    return distances;
  }
}
