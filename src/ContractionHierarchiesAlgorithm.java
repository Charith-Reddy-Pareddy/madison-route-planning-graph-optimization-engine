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
 * <p>Node ordering uses the real dynamic edge-difference priority (replacing the static
 * ascending-degree order this class used to use): each node's priority is (real shortcuts it
 * would need if contracted right now) minus (its current active degree), so nodes that are cheap
 * to remove (few shortcuts, well-connected) go first. Since contracting one node changes its
 * neighbors' priorities, the priority queue uses lazy re-validation (Java's {@link PriorityQueue}
 * has no efficient decrease-key): pop the best-looking candidate, recompute its priority fresh,
 * and give it exactly one chance to be pushed back and replaced by a better candidate if that
 * fresh value is worse than the new best -- capped at one retry, not chased indefinitely (see
 * {@link #preprocess}'s comment for why an uncapped chase was itself a real, measured performance
 * bug: a node waiting its turn near a growing hub got fully re-validated -- a complete witness
 * search pass -- every time anything near it was contracted).
 *
 * <p>The witness search deciding whether a shortcut is actually necessary is bounded only by cost
 * (never explore a candidate path already more expensive than going through the node being
 * contracted), not by a node-count or hop cap. That was tried and made things *worse*: capping the
 * search early makes it miss real witnesses, which adds shortcuts that weren't actually needed,
 * which grows the degree of whichever nodes end up in the "core" of the hierarchy, which makes
 * every later witness search on those nodes more expensive -- a real, measured vicious cycle
 * (settling only 15 nodes per witness search made 500 synthetic nodes slower to preprocess than
 * settling unboundedly did). Dynamic ordering plus an accurate, unbounded-by-count witness search
 * is what actually keeps hub growth in check.
 *
 * <p>Real, measured result of both fixes together: 1,000 synthetic nodes preprocess in ~11
 * seconds (previously didn't finish even 500 nodes in any reasonable time). That is a genuine
 * improvement over the old static-order implementation, verified correct at that scale -- but it
 * is not yet 100K-1M-node scale like the other algorithms in this benchmark suite; hub-degree
 * growth still compounds faster than linearly past a few thousand nodes on this synthetic graph
 * shape, and closing that gap needs a fundamentally different technique (e.g. a proper
 * local/hop-bounded witness search tuned against shortcut count, rather than a blanket cap), left
 * as tracked future work rather than rushed.
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

  /** A shortcut u-&gt;w (cost throughV) that contracting some node would require. */
  private record ShortcutCandidate<NodeType>(NodeType u, NodeType w, double throughV) {}

  /** A node's current contraction priority: lower edgeDifference contracts first. */
  private record NodePriority<NodeType>(NodeType node, int edgeDifference) implements Comparable<NodePriority<NodeType>> {
    @Override
    public int compareTo(NodePriority<NodeType> other) {
      return Integer.compare(edgeDifference, other.edgeDifference);
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

    Set<NodeType> active = new HashSet<>(allNodes);

    // Dynamic edge-difference ordering with lazy re-validation -- see the class doc. Seed every
    // node's initial priority once; from then on, a popped candidate's priority is recomputed
    // fresh before it's trusted, since contracting earlier nodes can have changed it.
    PriorityQueue<NodePriority<NodeType>> queue = new PriorityQueue<>();
    for (NodeType node : allNodes) {
      List<ShortcutCandidate<NodeType>> candidates = shortcutsNeededFor(node, workingOut, workingIn, active);
      queue.add(new NodePriority<>(node, edgeDifference(node, candidates, workingOut, workingIn)));
    }

    Map<NodeType, Integer> rank = new HashMap<>();
    int nextRank = 0;
    while (!queue.isEmpty()) {
      NodePriority<NodeType> top = queue.poll();
      NodeType v = top.node();
      List<ShortcutCandidate<NodeType>> candidates = shortcutsNeededFor(v, workingOut, workingIn, active);
      int fresh = edgeDifference(v, candidates, workingOut, workingIn);
      // At most one re-validation retry per contraction, not an unbounded chase for the true
      // global minimum: a node whose priority went stale can be pushed back and re-tried against
      // the new best candidate exactly once, then whatever comes up next is contracted
      // unconditionally. See the class doc for why an uncapped chase was itself a real,
      // measured performance bug. Correctness never depends on this either way -- only
      // preprocessing speed and shortcut count do.
      if (!queue.isEmpty() && fresh > queue.peek().edgeDifference()) {
        queue.add(new NodePriority<>(v, fresh));
        top = queue.poll();
        v = top.node();
        candidates = shortcutsNeededFor(v, workingOut, workingIn, active);
      }

      for (ShortcutCandidate<NodeType> candidate : candidates) {
        // Added to both graphs: `working` so later witness searches can see it, `finalGraph` so
        // queries can actually use it -- and recorded in shortcutVia (only when it actually wins
        // the graph's currently-cheapest edge for u->w) so the query can unpack it back into v.
        addOrUpdateEdge(workingOut, workingIn, candidate.u(), candidate.w(), candidate.throughV());
        if (addOrUpdateEdge(finalOut, finalIn, candidate.u(), candidate.w(), candidate.throughV())) {
          shortcutVia.put(new EdgeKey<>(candidate.u(), candidate.w()), v);
        }
      }

      active.remove(v);
      for (NodeType pred : new ArrayList<>(workingIn.get(v).keySet())) {
        workingOut.get(pred).remove(v);
      }
      for (NodeType succ : new ArrayList<>(workingOut.get(v).keySet())) {
        workingIn.get(succ).remove(v);
      }
      rank.put(v, nextRank++);
    }

    return new Preprocessed<>(finalOut, finalIn, rank, shortcutVia);
  }

  /** shortcuts needed minus current active degree -- lower contracts first (see class doc). */
  private int edgeDifference(
      NodeType v, List<ShortcutCandidate<NodeType>> candidates,
      Map<NodeType, Map<NodeType, Double>> workingOut, Map<NodeType, Map<NodeType, Double>> workingIn) {
    int degree = workingOut.get(v).size() + workingIn.get(v).size();
    return candidates.size() - degree;
  }

  /**
   * Every shortcut that contracting {@code v} right now would require -- checked with a witness
   * search per predecessor/successor pair, not assumed. Used both to score a node's contraction
   * priority (without actually contracting it) and, once a node is chosen, to apply those exact
   * shortcuts -- computing this once and reusing it for both avoids paying for the witness
   * searches twice.
   */
  private List<ShortcutCandidate<NodeType>> shortcutsNeededFor(
      NodeType v, Map<NodeType, Map<NodeType, Double>> workingOut, Map<NodeType, Map<NodeType, Double>> workingIn,
      Set<NodeType> active) {
    List<ShortcutCandidate<NodeType>> needed = new ArrayList<>();
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
          // No path from u to w avoiding v is cheap enough (within the witness search's budget)
          // -- u->v->w becomes the only shortest route once v is gone, so a shortcut is required
          // to preserve that distance.
          needed.add(new ShortcutCandidate<>(u, w, throughV));
        }
      }
    }
    return needed;
  }

  /**
   * A Dijkstra from source, forbidden to pass through {@code forbidden}, restricted to
   * still-{@code active} nodes, stopping as soon as either {@code target} is reached or every
   * remaining frontier node's cost exceeds {@code limit} (safe because the queue is cost-ordered:
   * once the cheapest remaining candidate exceeds the limit, so does everything after it) --
   * deliberately not also capped by a node-settled or hop count; see the class doc for why that
   * was tried and made preprocessing slower overall, not faster. Returns the real distance to
   * target if a witness path within the limit exists, or +Infinity if not -- the caller treats
   * "no witness" as "a shortcut is required."
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
