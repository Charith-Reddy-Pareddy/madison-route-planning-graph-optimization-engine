import java.util.NoSuchElementException;

/**
 * A pluggable shortest-path search strategy, decoupled from any one graph
 * implementation via {@link BaseGraph#neighborsOf} / {@link
 * BaseGraph#predecessorsOf} rather than a subclass relationship -- so
 * {@link DijkstraAlgorithm}, {@link BidirectionalDijkstraAlgorithm}, {@link
 * AStarAlgorithm}, and {@link BidirectionalAStarAlgorithm} are directly
 * swappable and comparable (same input, same {@link PathResult} shape),
 * which the benchmark suite and cross-validation tests both depend on.
 *
 * <p>This is deliberately separate from {@link DijkstraGraph}, which stays
 * as-is (including its diagnostics counters and its own history) as the
 * implementation the live app actually runs in production.
 */
public interface ShortestPathAlgorithm<NodeType, EdgeType extends Number> {

  /**
   * @throws NoSuchElementException if start or end isn't a node in the
   *     graph, or if end isn't reachable from start
   */
  PathResult<NodeType> findPath(BaseGraph<NodeType, EdgeType> graph, NodeType start, NodeType end);
}
