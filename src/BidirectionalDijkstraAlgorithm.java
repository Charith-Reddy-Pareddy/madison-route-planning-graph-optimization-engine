import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Bidirectional Dijkstra: runs Dijkstra forward from {@code start} and, at
 * the same time, backward from {@code end} along reversed edges (via
 * {@link BaseGraph#predecessorsOf}, which is why this needs a real
 * predecessor-edge traversal rather than treating the graph as
 * undirected -- this network has one-way streets). Each step expands
 * whichever frontier's next node is currently cheaper. The two searches
 * meet somewhere in the middle instead of either one exploring the whole
 * graph, which is the point: for a roughly circular search area, two
 * half-radius searches cover a lot less area in total than one full-radius
 * search.
 *
 * <p>Stopping condition: once the sum of the two frontiers' next minimum
 * costs is >= the best start-to-end path found through any node settled by
 * both searches so far, no node still unexplored could possibly improve on
 * it -- this is the standard correctness argument for bidirectional
 * Dijkstra (naively stopping "as soon as the two searches touch" is a real,
 * well-known bug: the first shared node isn't guaranteed to lie on the
 * actual shortest path).
 */
public class BidirectionalDijkstraAlgorithm<NodeType, EdgeType extends Number>
    implements ShortestPathAlgorithm<NodeType, EdgeType> {

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
    if (start.equals(end)) {
      return new PathResult<>(List.of(start), 0.0);
    }

    Map<NodeType, Double> distF = new HashMap<>();
    Map<NodeType, Double> distB = new HashMap<>();
    Map<NodeType, NodeType> predF = new HashMap<>();
    Map<NodeType, NodeType> predB = new HashMap<>();
    Set<NodeType> settledF = new HashSet<>();
    Set<NodeType> settledB = new HashSet<>();
    PriorityQueue<Entry<NodeType>> queueF = new PriorityQueue<>();
    PriorityQueue<Entry<NodeType>> queueB = new PriorityQueue<>();

    distF.put(start, 0.0);
    distB.put(end, 0.0);
    queueF.add(new Entry<>(start, 0.0));
    queueB.add(new Entry<>(end, 0.0));

    double bestCost = Double.POSITIVE_INFINITY;
    NodeType meetingNode = null;

    while (!queueF.isEmpty() && !queueB.isEmpty()) {
      if (queueF.peek().cost() + queueB.peek().cost() >= bestCost) {
        break;
      }

      boolean stepForward = queueF.peek().cost() <= queueB.peek().cost();
      Entry<NodeType> current = stepForward ? queueF.poll() : queueB.poll();
      Set<NodeType> settledThis = stepForward ? settledF : settledB;
      Set<NodeType> settledOther = stepForward ? settledB : settledF;
      Map<NodeType, Double> distOther = stepForward ? distB : distF;

      if (settledThis.contains(current.node())) {
        continue;
      }
      settledThis.add(current.node());

      if (settledOther.contains(current.node())) {
        double total = current.cost() + distOther.get(current.node());
        if (total < bestCost) {
          bestCost = total;
          meetingNode = current.node();
        }
      }

      List<Neighbor<NodeType, EdgeType>> edges = stepForward ? graph.neighborsOf(current.node()) : graph.predecessorsOf(current.node());
      Map<NodeType, Double> distThis = stepForward ? distF : distB;
      Map<NodeType, NodeType> predThis = stepForward ? predF : predB;
      PriorityQueue<Entry<NodeType>> queueThis = stepForward ? queueF : queueB;

      for (Neighbor<NodeType, EdgeType> neighbor : edges) {
        if (settledThis.contains(neighbor.node())) {
          continue;
        }
        double newCost = current.cost() + neighbor.weight().doubleValue();
        Double known = distThis.get(neighbor.node());
        if (known == null || newCost < known) {
          distThis.put(neighbor.node(), newCost);
          predThis.put(neighbor.node(), current.node());
          queueThis.add(new Entry<>(neighbor.node(), newCost));
          if (settledOther.contains(neighbor.node())) {
            double total = newCost + distOther.get(neighbor.node());
            if (total < bestCost) {
              bestCost = total;
              meetingNode = neighbor.node();
            }
          }
        }
      }
    }

    if (meetingNode == null) {
      throw new NoSuchElementException("no path from " + start + " to " + end);
    }
    return new PathResult<>(stitchPath(predF, predB, start, end, meetingNode), bestCost);
  }

  /**
   * predF maps a forward-settled node to the node the forward search
   * reached it from, so walking predF from meetingNode back to start (then
   * reversing) gives the start-to-meeting half. predB maps a
   * backward-settled node P to the node the *real* forward edge P->(that
   * node) leads to, so walking predB forward from meetingNode gives the
   * meeting-to-end half directly, without needing to reverse it.
   */
  static <NodeType> List<NodeType> stitchPath(
      Map<NodeType, NodeType> predF, Map<NodeType, NodeType> predB, NodeType start, NodeType end, NodeType meeting) {
    LinkedList<NodeType> path = new LinkedList<>();
    NodeType current = meeting;
    while (current != null) {
      path.addFirst(current);
      if (current.equals(start)) {
        break;
      }
      current = predF.get(current);
    }
    current = predB.get(meeting);
    while (current != null) {
      path.addLast(current);
      if (current.equals(end)) {
        break;
      }
      current = predB.get(current);
    }
    return path;
  }
}
