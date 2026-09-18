import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * A* search: like {@link DijkstraAlgorithm}, but the priority queue orders
 * by cost-so-far plus an admissible heuristic estimate of the remaining
 * cost (see {@link AStarHeuristic}), so it expands nodes in the general
 * direction of the goal instead of growing a uniform frontier in every
 * direction the way plain Dijkstra does.
 */
public class AStarAlgorithm<NodeType, EdgeType extends Number> implements ShortestPathAlgorithm<NodeType, EdgeType> {

  private final AStarHeuristic<NodeType> heuristic;

  public AStarAlgorithm(AStarHeuristic<NodeType> heuristic) {
    this.heuristic = heuristic;
  }

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

    Map<NodeType, Double> bestKnown = new HashMap<>();
    Map<NodeType, NodeType> predecessor = new HashMap<>();
    Set<NodeType> visited = new HashSet<>();
    PriorityQueue<Entry<NodeType>> queue = new PriorityQueue<>();

    bestKnown.put(start, 0.0);
    queue.add(new Entry<>(start, 0.0, heuristic.estimate(start, end)));

    while (!queue.isEmpty()) {
      Entry<NodeType> current = queue.poll();
      if (visited.contains(current.node())) {
        continue;
      }
      visited.add(current.node());

      if (current.node().equals(end)) {
        return new PathResult<>(DijkstraAlgorithm.reconstructPath(predecessor, start, end), current.gCost());
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
          queue.add(new Entry<>(neighbor.node(), newG, newG + heuristic.estimate(neighbor.node(), end)));
        }
      }
    }

    throw new NoSuchElementException("no path from " + start + " to " + end);
  }
}
