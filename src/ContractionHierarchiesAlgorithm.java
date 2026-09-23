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
 * through v -- checked with a witness search, not assumed.
 *
 * <p>The query exploits the one structural fact contraction guarantees: every shortest path has a
 * highest-ranked "peak" node, and the path is rank-increasing from the source up to the peak and
 * rank-increasing from the target up to the peak (i.e. rank-decreasing from the peak down to the
 * target). So instead of searching the whole graph, {@link #findPath} runs two searches that only
 * ever move to higher-ranked nodes -- forward from the start over {@code out} edges, backward from
 * the end over {@code in} edges -- each one naturally small and quick to exhaust, and takes the
 * cheapest node reached by both.
 *
 * <p>A returned path may contain shortcut edges that "jump" over the real intermediate node(s)
 * they stand in for; each shortcut records which node it was contracted through ({@code
 * shortcutVia}), so the raw search path is unpacked back into real original edges before being
 * returned -- recursively, since a shortcut can itself be built from other shortcuts.
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
      Map<NodeType, Map<NodeType, Double>> out,
      Map<NodeType, Map<NodeType, Double>> in,
      Map<NodeType, Integer> rank,
      Map<EdgeKey<NodeType>, NodeType> shortcutVia) {}

  private record EdgeKey<NodeType>(NodeType from, NodeType to) {}

  private record Entry<NodeType>(NodeType node, double cost) implements Comparable<Entry<NodeType>> {
    @Override
    public int compareTo(Entry<NodeType> other) {
      return Double.compare(cost, other.cost);
    }
  }

  private record UpwardSearchResult<NodeType>(Map<NodeType, Double> dist, Map<NodeType, NodeType> pred, int expanded) {}

  @Override
  public PathResult<NodeType> findPath(BaseGraph<NodeType, EdgeType> graph, NodeType start, NodeType end) {
    if (start == null || end == null || !graph.containsNode(start) || !graph.containsNode(end)) {
      throw new NoSuchElementException("start or end node not in graph");
    }
    if (start.equals(end)) {
      return new PathResult<>(List.of(start), 0.0, 0);
    }

    Preprocessed<NodeType> preprocessed = cache.computeIfAbsent(graph, this::preprocess);
    Map<NodeType, Integer> rank = preprocessed.rank();

    UpwardSearchResult<NodeType> forward = upwardSearch(preprocessed.out(), rank, start);
    UpwardSearchResult<NodeType> backward = upwardSearch(preprocessed.in(), rank, end);

    double bestCost = Double.POSITIVE_INFINITY;
    NodeType meetingNode = null;
    for (Map.Entry<NodeType, Double> entry : forward.dist().entrySet()) {
      Double otherSide = backward.dist().get(entry.getKey());
      if (otherSide != null) {
        double total = entry.getValue() + otherSide;
        if (total < bestCost) {
          bestCost = total;
          meetingNode = entry.getKey();
        }
      }
    }

    if (meetingNode == null) {
      throw new NoSuchElementException("no path from " + start + " to " + end);
    }

    List<NodeType> rawPath =
        BidirectionalDijkstraAlgorithm.stitchPath(forward.pred(), backward.pred(), start, end, meetingNode);
    List<NodeType> realPath = unpack(rawPath, preprocessed.shortcutVia());
    return new PathResult<>(realPath, bestCost, forward.expanded() + backward.expanded());
  }

  /**
   * A Dijkstra from {@code source} restricted to edges that move to a higher-ranked node. Both
   * directions use the same "higher rank only" rule -- the forward search over {@code out} edges,
   * the backward search over {@code in} edges -- because of the peak-node argument in the class
   * doc: ranks increase from the source up to the peak, and ranks *decrease* from the peak down to
   * the target in the forward direction, which means walking backward from the target toward the
   * peak also moves to strictly higher ranks at every step, just via predecessor edges instead of
   * successor edges.
   */
  private UpwardSearchResult<NodeType> upwardSearch(
      Map<NodeType, Map<NodeType, Double>> adjacency, Map<NodeType, Integer> rank, NodeType source) {
    Map<NodeType, Double> dist = new HashMap<>();
    Map<NodeType, NodeType> pred = new HashMap<>();
    Set<NodeType> visited = new HashSet<>();
    PriorityQueue<Entry<NodeType>> queue = new PriorityQueue<>();
    dist.put(source, 0.0);
    queue.add(new Entry<>(source, 0.0));
    int expanded = 0;

    while (!queue.isEmpty()) {
      Entry<NodeType> current = queue.poll();
      if (visited.contains(current.node())) {
        continue;
      }
      visited.add(current.node());
      expanded++;
      int currentRank = rank.get(current.node());

      for (Map.Entry<NodeType, Double> edge : adjacency.getOrDefault(current.node(), Map.of()).entrySet()) {
        NodeType next = edge.getKey();
        if (rank.get(next) <= currentRank) {
          continue;
        }
        double newCost = current.cost() + edge.getValue();
        Double known = dist.get(next);
        if (known == null || newCost < known) {
          dist.put(next, newCost);
          pred.put(next, current.node());
          queue.add(new Entry<>(next, newCost));
        }
      }
    }
    return new UpwardSearchResult<>(dist, pred, expanded);
  }

  /** Expands every shortcut edge in {@code rawPath} back into the real edges it stands in for. */
  private List<NodeType> unpack(List<NodeType> rawPath, Map<EdgeKey<NodeType>, NodeType> shortcutVia) {
    List<NodeType> result = new ArrayList<>();
    result.add(rawPath.get(0));
    for (int i = 0; i < rawPath.size() - 1; i++) {
      appendExpanded(rawPath.get(i), rawPath.get(i + 1), shortcutVia, result);
    }
    return result;
  }

  /** Appends the real node sequence from a to b (exclusive of a) to result, recursively unpacking a into b if that edge is a shortcut. */
  private void appendExpanded(NodeType a, NodeType b, Map<EdgeKey<NodeType>, NodeType> shortcutVia, List<NodeType> result) {
    NodeType via = shortcutVia.get(new EdgeKey<>(a, b));
    if (via == null) {
      result.add(b);
    } else {
      appendExpanded(a, via, shortcutVia, result);
      appendExpanded(via, b, shortcutVia, result);
    }
  }

  private Preprocessed<NodeType> preprocess(BaseGraph<NodeType, EdgeType> graph) {
    List<NodeType> allNodes = graph.getAllNodes();

    // Two separate graphs, not one. `working` shrinks as nodes get contracted -- it exists only
    // to tell the witness search which nodes it's still allowed to route through, mirroring what
    // "not yet contracted" means during preprocessing. `finalGraph` starts as a copy of the
    // original graph and only ever GAINS shortcut edges; it never loses an edge, because a
    // contracted node is still a perfectly valid query endpoint (a real user can start or end a
    // route there) -- only its role as an intermediate hop for OTHER nodes' shortest paths is
    // superseded by shortcuts.
    Map<NodeType, Map<NodeType, Double>> workingOut = new HashMap<>();
    Map<NodeType, Map<NodeType, Double>> workingIn = new HashMap<>();
    Map<NodeType, Map<NodeType, Double>> finalOut = new HashMap<>();
    Map<NodeType, Map<NodeType, Double>> finalIn = new HashMap<>();
    Map<EdgeKey<NodeType>, NodeType> shortcutVia = new HashMap<>();
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
            // can actually use it -- and recorded in shortcutVia (only when it actually wins the
            // graph's currently-cheapest edge for u->w) so the query can unpack it back into v.
            addOrUpdateEdge(workingOut, workingIn, u, w, throughV);
            if (addOrUpdateEdge(finalOut, finalIn, u, w, throughV)) {
              shortcutVia.put(new EdgeKey<>(u, w), v);
            }
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

    return new Preprocessed<>(finalOut, finalIn, rank, shortcutVia);
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

  /** Returns true if this call actually inserted or improved the edge (a strictly cheaper route than any recorded so far). */
  private boolean addOrUpdateEdge(
      Map<NodeType, Map<NodeType, Double>> out, Map<NodeType, Map<NodeType, Double>> in,
      NodeType pred, NodeType succ, double weight) {
    Double existing = out.get(pred).get(succ);
    if (existing == null || weight < existing) {
      out.get(pred).put(succ, weight);
      in.get(succ).put(pred, weight);
      return true;
    }
    return false;
  }
}
