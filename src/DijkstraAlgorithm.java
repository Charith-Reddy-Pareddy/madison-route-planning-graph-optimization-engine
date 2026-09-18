import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Standard priority-queue Dijkstra, conforming to {@link
 * ShortestPathAlgorithm} so it's directly comparable against the other
 * algorithms in this family. Tracks a best-known distance per node so it
 * only enqueues strict improvements -- the same optimization {@link
 * DijkstraGraph} (the production implementation this reimplements, kept
 * separate) got in its own history, applied here from the start so a
 * benchmark comparison against the newer algorithms is measuring real
 * algorithmic differences rather than one side carrying an avoidable
 * inefficiency.
 */
public class DijkstraAlgorithm<NodeType, EdgeType extends Number> implements ShortestPathAlgorithm<NodeType, EdgeType> {

  private record Entry<NodeType>(NodeType node, double cost) implements Comparable<Entry<NodeType>> {
    @Override
    public int compareTo(Entry<NodeType> other) {
      return Double.compare(cost, other.cost);
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
    queue.add(new Entry<>(start, 0.0));

    while (!queue.isEmpty()) {
      Entry<NodeType> current = queue.poll();
      if (visited.contains(current.node())) {
        continue;
      }
      visited.add(current.node());

      if (current.node().equals(end)) {
        return new PathResult<>(reconstructPath(predecessor, start, end), current.cost());
      }

      for (Neighbor<NodeType, EdgeType> neighbor : graph.neighborsOf(current.node())) {
        if (visited.contains(neighbor.node())) {
          continue;
        }
        double newCost = current.cost() + neighbor.weight().doubleValue();
        Double known = bestKnown.get(neighbor.node());
        if (known == null || newCost < known) {
          bestKnown.put(neighbor.node(), newCost);
          predecessor.put(neighbor.node(), current.node());
          queue.add(new Entry<>(neighbor.node(), newCost));
        }
      }
    }

    throw new NoSuchElementException("no path from " + start + " to " + end);
  }

  /** Walks a predecessor map from end back to start, reversing it into start-to-end order. */
  static <NodeType> List<NodeType> reconstructPath(Map<NodeType, NodeType> predecessor, NodeType start, NodeType end) {
    LinkedList<NodeType> path = new LinkedList<>();
    NodeType current = end;
    while (current != null) {
      path.addFirst(current);
      if (current.equals(start)) {
        break;
      }
      current = predecessor.get(current);
    }
    return path;
  }
}
