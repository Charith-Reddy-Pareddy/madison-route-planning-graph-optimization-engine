import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Contraction Hierarchies (Geisberger, Sanders, Schultes, Delling 2008): preprocesses the graph
 * once by contracting nodes out one at a time in some order, adding "shortcut" edges so that
 * removing a node from the graph never changes the shortest-path distance between any pair of its
 * still-active neighbors. A shortcut u-&gt;w is only added when no path from u to w already exists
 * (at the point v is contracted, among still-active nodes) that's at least as cheap as going
 * through v -- checked with a bounded witness search, not assumed.
 *
 * <p>This is the Day 3 slice of the contraction-hierarchies work: node ordering and the
 * contraction step itself, plus a correctness check -- running an ordinary Dijkstra over the
 * augmented graph (original edges + shortcuts) must give the exact same shortest-path COST as the
 * original graph, for every query, because a shortcut is only ever added when no cheaper route
 * already exists. Two things are deliberately deferred to Day 4:
 * <ul>
 *   <li>The actual speedup: restricting the query to a bidirectional search over only "upward"
 *       edges (toward higher-ranked/later-contracted nodes), which is the entire point of
 *       contraction hierarchies. Until then, {@link #findPath} runs a plain, unrestricted Dijkstra
 *       over the augmented graph, which is not expected to be faster than {@link
 *       DijkstraAlgorithm} -- it exists to validate the contraction logic, not to be benchmarked.
 *   <li>Shortcut unpacking: a returned path may include a shortcut edge that "jumps" directly
 *       between two nodes, skipping the real intermediate node(s) it stands in for. The cost is
 *       always exactly correct; the path is not yet a real drivable route. Callers that need the
 *       real route should not use this class's path until that's added.
 * </ul>
 *
 * <p>Node ordering here uses a static ascending-degree heuristic (lower-degree nodes contracted
 * first) rather than the full dynamic edge-difference priority real CH implementations use, and
 * the witness search is unbounded (no hop limit) rather than cost/hop-bounded. Neither of those is
 * a correctness issue -- contracting nodes in <em>any</em> order, with a correct witness search,
 * preserves shortest-path distances; only preprocessing speed and shortcut count depend on the
 * ordering and search bound. That's also why this class isn't run against the synthetic
 * benchmark suite yet: an unbounded witness search re-run for every node is far too slow to be
 * practical at benchmark-suite scale (100K-1M nodes) without the real dynamic priority queue and a
 * bounded witness search -- both future work, tracked separately from correctness.
 */
public class ContractionHierarchiesAlgorithm<NodeType, EdgeType extends Number>
    implements ShortestPathAlgorithm<NodeType, EdgeType> {

  private final Map<BaseGraph<NodeType, EdgeType>, Preprocessed<NodeType>> cache = new IdentityHashMap<>();

  private record Preprocessed<NodeType>(
      Map<NodeType, Map<NodeType, Double>> out, Map<NodeType, Map<NodeType, Double>> in, Map<NodeType, Integer> rank) {}

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
      return new PathResult<>(List.of(start), 0.0, 0);
    }

    Preprocessed<NodeType> preprocessed = cache.computeIfAbsent(graph, this::preprocess);

    // Interim Day-3 query: a plain Dijkstra over the augmented (original + shortcut) graph. This
    // is what validates the contraction/shortcut logic; the upward-restricted bidirectional
    // search that makes contraction hierarchies actually fast is Day 4 -- see the class doc.
    Map<NodeType, Double> bestKnown = new HashMap<>();
    Map<NodeType, NodeType> predecessor = new HashMap<>();
    Set<NodeType> visited = new HashSet<>();
    PriorityQueue<Entry<NodeType>> queue = new PriorityQueue<>();
    int nodesExpanded = 0;

    bestKnown.put(start, 0.0);
    queue.add(new Entry<>(start, 0.0));

    while (!queue.isEmpty()) {
      Entry<NodeType> current = queue.poll();
      if (visited.contains(current.node())) {
        continue;
      }
      visited.add(current.node());
      nodesExpanded++;

      if (current.node().equals(end)) {
        return new PathResult<>(
            DijkstraAlgorithm.reconstructPath(predecessor, start, end), current.cost(), nodesExpanded);
      }

      for (Map.Entry<NodeType, Double> edge : preprocessed.out().getOrDefault(current.node(), Map.of()).entrySet()) {
        NodeType next = edge.getKey();
        if (visited.contains(next)) {
          continue;
        }
        double newCost = current.cost() + edge.getValue();
        Double known = bestKnown.get(next);
        if (known == null || newCost < known) {
          bestKnown.put(next, newCost);
          predecessor.put(next, current.node());
          queue.add(new Entry<>(next, newCost));
        }
      }
    }

    throw new NoSuchElementException("no path from " + start + " to " + end);
  }

  private Preprocessed<NodeType> preprocess(BaseGraph<NodeType, EdgeType> graph) {
    List<NodeType> allNodes = graph.getAllNodes();

    // Two separate graphs, not one. `working` shrinks as nodes get contracted -- it exists only
    // to tell the witness search which nodes it's still allowed to route through, mirroring what
    // "not yet contracted" means during preprocessing. `finalGraph` starts as a copy of the
    // original graph and only ever GAINS shortcut edges; it never loses an edge, because a
    // contracted node is still a perfectly valid query endpoint (a real user can start or end a
    // route there) -- only its role as an intermediate hop for OTHER nodes' shortest paths is
    // superseded by shortcuts. Reusing one shrinking graph for both jobs was the Day-3 bug: it
    // deleted the only way to actually reach a contracted node once it got contracted.
    Map<NodeType, Map<NodeType, Double>> workingOut = new HashMap<>();
    Map<NodeType, Map<NodeType, Double>> workingIn = new HashMap<>();
    Map<NodeType, Map<NodeType, Double>> finalOut = new HashMap<>();
    Map<NodeType, Map<NodeType, Double>> finalIn = new HashMap<>();
    for (NodeType node : allNodes) {
      workingOut.put(node, new HashMap<>());
      workingIn.put(node, new HashMap<>());
      finalOut.put(node, new HashMap<>());
      finalIn.put(node, new HashMap<>());
    }
    for (NodeType node : allNodes) {
      for (Neighbor<NodeType, EdgeType> neighbor : graph.neighborsOf(node)) {
        double weight = neighbor.weight().doubleValue();
        addOrUpdateEdge(workingOut, workingIn, node, neighbor.node(), weight);
        addOrUpdateEdge(finalOut, finalIn, node, neighbor.node(), weight);
      }
    }

    // Static ascending-degree contraction order: contract the least-connected nodes first. See
    // the class doc for why this (versus the real dynamic edge-difference priority) only affects
    // preprocessing speed and shortcut count, not correctness.
    List<NodeType> order = new ArrayList<>(allNodes);
    order.sort(Comparator.comparingInt(node -> workingOut.get(node).size() + workingIn.get(node).size()));
    Map<NodeType, Integer> rank = new HashMap<>();
    for (int i = 0; i < order.size(); i++) {
      rank.put(order.get(i), i);
    }

    Set<NodeType> active = new HashSet<>(allNodes);
    for (NodeType v : order) {
      List<Map.Entry<NodeType, Double>> preds = new ArrayList<>(workingIn.get(v).entrySet());
      List<Map.Entry<NodeType, Double>> succs = new ArrayList<>(workingOut.get(v).entrySet());

      for (Map.Entry<NodeType, Double> predEntry : preds) {
        NodeType u = predEntry.getKey();
        if (u.equals(v) || !active.contains(u)) {
          continue;
        }
        double uToV = predEntry.getValue();
        for (Map.Entry<NodeType, Double> succEntry : succs) {
          NodeType w = succEntry.getKey();
          if (w.equals(v) || w.equals(u) || !active.contains(w)) {
            continue;
          }
          double throughV = uToV + succEntry.getValue();
          double witness = witnessDistance(workingOut, active, u, v, w, throughV);
          if (witness > throughV + 1e-9) {
            // No path from u to w avoiding v is cheap enough -- u->v->w becomes the only shortest
            // route once v is gone, so a shortcut is required to preserve that distance. Added to
            // both graphs: `working` so later witness searches can see it, `finalGraph` so queries
            // can actually use it.
            addOrUpdateEdge(workingOut, workingIn, u, w, throughV);
            addOrUpdateEdge(finalOut, finalIn, u, w, throughV);
          }
        }
      }

      active.remove(v);
      for (NodeType pred : new ArrayList<>(workingIn.get(v).keySet())) {
        workingOut.get(pred).remove(v);
      }
      for (NodeType succ : new ArrayList<>(workingOut.get(v).keySet())) {
        workingIn.get(succ).remove(v);
      }
    }

    return new Preprocessed<>(finalOut, finalIn, rank);
  }

  /**
   * A bounded Dijkstra from source, forbidden to pass through {@code forbidden}, restricted to
   * still-{@code active} nodes, stopping as soon as either {@code target} is reached or every
   * remaining frontier node's cost exceeds {@code limit} (safe because the queue is cost-ordered:
   * once the cheapest remaining candidate exceeds the limit, so does everything after it). Returns
   * the real distance to target if a witness path within the limit exists, or +Infinity if not --
   * the caller treats "no witness" as "a shortcut is required."
   */
  private double witnessDistance(
      Map<NodeType, Map<NodeType, Double>> out, Set<NodeType> active, NodeType source, NodeType forbidden,
      NodeType target, double limit) {
    Map<NodeType, Double> dist = new HashMap<>();
    Set<NodeType> visited = new HashSet<>();
    PriorityQueue<Entry<NodeType>> queue = new PriorityQueue<>();
    dist.put(source, 0.0);
    queue.add(new Entry<>(source, 0.0));

    while (!queue.isEmpty()) {
      Entry<NodeType> current = queue.poll();
      if (visited.contains(current.node())) {
        continue;
      }
      if (current.cost() > limit) {
        break;
      }
      visited.add(current.node());
      if (current.node().equals(target)) {
        return current.cost();
      }
      for (Map.Entry<NodeType, Double> edge : out.getOrDefault(current.node(), Map.of()).entrySet()) {
        NodeType next = edge.getKey();
        if (next.equals(forbidden) || !active.contains(next)) {
          continue;
        }
        double newCost = current.cost() + edge.getValue();
        if (newCost > limit) {
          continue;
        }
        Double known = dist.get(next);
        if (known == null || newCost < known) {
          dist.put(next, newCost);
          queue.add(new Entry<>(next, newCost));
        }
      }
    }
    return Double.POSITIVE_INFINITY;
  }

  private void addOrUpdateEdge(
      Map<NodeType, Map<NodeType, Double>> out, Map<NodeType, Map<NodeType, Double>> in,
      NodeType pred, NodeType succ, double weight) {
    Double existing = out.get(pred).get(succ);
    if (existing == null || weight < existing) {
      out.get(pred).put(succ, weight);
      in.get(succ).put(pred, weight);
    }
  }
}
