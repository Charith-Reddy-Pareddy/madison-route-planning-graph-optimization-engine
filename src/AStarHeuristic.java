/**
 * An estimate of the remaining cost from one node to a goal, used by {@link
 * AStarAlgorithm} and {@link BidirectionalAStarAlgorithm} to prioritize
 * which frontier node to expand next. Must be admissible (never overestimate
 * the true remaining cost) for the search to be guaranteed to find the
 * actual shortest path rather than just *a* path -- {@link
 * RoadNetwork#haversineHeuristic()}'s straight-line distance is admissible
 * because it can never exceed the real road distance between two points.
 */
@FunctionalInterface
public interface AStarHeuristic<NodeType> {
  double estimate(NodeType from, NodeType goal);
}
