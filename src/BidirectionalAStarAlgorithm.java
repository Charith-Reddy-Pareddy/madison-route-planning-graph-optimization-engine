import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * Bidirectional A*, using symmetric potentials (Ikeda, Hsu, Imai et al.
 * 1994) so the forward and backward searches' priorities stay correctly
 * comparable -- a real, discovered-by-testing subtlety, not a theoretical
 * nicety: an earlier version of this class ordered each side's queue by
 * plain {@code g + h} (cost-so-far plus that side's own one-directional
 * heuristic) and stopped once the two queues' peeked {@code g}-values
 * summed past the best meeting cost found. That's the correct bidirectional
 * -Dijkstra termination rule, but it silently assumes the queue is ordered
 * by {@code g} -- true for plain Dijkstra, false for A*, where the top of
 * an {@code f}-ordered queue is a lower bound on remaining {@code f}
 * -values, not remaining {@code g}-values. AlgorithmsCorrectnessTest's
 * exhaustive real-network sweep caught it directly: a genuinely suboptimal
 * path on a real query (2.99mi returned where the true shortest was
 * 2.37mi), not just a theoretical gap.
 *
 * <p>The fix: define
 * <pre>  pf(v) = (h(v, end) - h(v, start)) / 2
 *  pb(v) = -pf(v) = (h(v, start) - h(v, end)) / 2</pre>
 * and prioritize the forward search by {@code g_f(v) + pf(v)} and the
 * backward search by {@code g_b(v) + pb(v)}, instead of each side's raw
 * one-directional heuristic. Because {@code h} is a true (symmetric,
 * triangle-inequality-respecting) metric here -- see {@link
 * RoadNetwork#haversineHeuristic()} -- {@code pf} is itself a consistent
 * heuristic for the forward search (and {@code pb} for the backward one on
 * the reversed graph), which is what makes each side's priority
 * nondecreasing across pops, the same property plain Dijkstra's raw
 * {@code g} has. And since {@code pf(v) + pb(v) = 0} for every {@code v},
 * the two sides' priorities still sum to the real combined cost
 * {@code g_f(v) + g_b(v)} at whatever node they meet at, so the
 * bidirectional-Dijkstra-style stopping rule (sum of the two queues' top
 * priorities >= the best meeting cost found) is valid again.
 */
public class BidirectionalAStarAlgorithm<NodeType, EdgeType extends Number>
    implements ShortestPathAlgorithm<NodeType, EdgeType> {

  private final AStarHeuristic<NodeType> heuristic;

  public BidirectionalAStarAlgorithm(AStarHeuristic<NodeType> heuristic) {
    this.heuristic = heuristic;
  }

  private record Entry<NodeType>(NodeType node, double gCost, double priority) implements Comparable<Entry<NodeType>> {
    @Override
    public int compareTo(Entry<NodeType> other) {
      return Double.compare(priority, other.priority);
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

    ToDoubleFunction<NodeType> pf = v -> (heuristic.estimate(v, end) - heuristic.estimate(v, start)) / 2.0;
    ToDoubleFunction<NodeType> pb = v -> -pf.applyAsDouble(v);

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
    queueF.add(new Entry<>(start, 0.0, pf.applyAsDouble(start)));
    queueB.add(new Entry<>(end, 0.0, pb.applyAsDouble(end)));

    double bestCost = Double.POSITIVE_INFINITY;
    NodeType meetingNode = null;

    while (!queueF.isEmpty() && !queueB.isEmpty()) {
      // Priority (g + potential), not raw g -- see class doc for why that
      // distinction is the whole fix.
      if (queueF.peek().priority() + queueB.peek().priority() >= bestCost) {
        break;
      }

      boolean stepForward = queueF.peek().priority() <= queueB.peek().priority();
      Entry<NodeType> current = stepForward ? queueF.poll() : queueB.poll();
      Set<NodeType> settledThis = stepForward ? settledF : settledB;
      Set<NodeType> settledOther = stepForward ? settledB : settledF;
      Map<NodeType, Double> distOther = stepForward ? distB : distF;

      if (settledThis.contains(current.node())) {
        continue;
      }
      settledThis.add(current.node());

      if (settledOther.contains(current.node())) {
        double total = current.gCost() + distOther.get(current.node());
        if (total < bestCost) {
          bestCost = total;
          meetingNode = current.node();
        }
      }

      List<Neighbor<NodeType, EdgeType>> edges =
          stepForward ? graph.neighborsOf(current.node()) : graph.predecessorsOf(current.node());
      Map<NodeType, Double> distThis = stepForward ? distF : distB;
      Map<NodeType, NodeType> predThis = stepForward ? predF : predB;
      PriorityQueue<Entry<NodeType>> queueThis = stepForward ? queueF : queueB;
      ToDoubleFunction<NodeType> potentialThis = stepForward ? pf : pb;

      for (Neighbor<NodeType, EdgeType> neighbor : edges) {
        if (settledThis.contains(neighbor.node())) {
          continue;
        }
        double newG = current.gCost() + neighbor.weight().doubleValue();
        Double known = distThis.get(neighbor.node());
        if (known == null || newG < known) {
          distThis.put(neighbor.node(), newG);
          predThis.put(neighbor.node(), current.node());
          queueThis.add(new Entry<>(neighbor.node(), newG, newG + potentialThis.applyAsDouble(neighbor.node())));
          if (settledOther.contains(neighbor.node())) {
            double total = newG + distOther.get(neighbor.node());
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
    return new PathResult<>(BidirectionalDijkstraAlgorithm.stitchPath(predF, predB, start, end, meetingNode), bestCost);
  }
}
