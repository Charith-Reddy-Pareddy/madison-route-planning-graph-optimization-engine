import java.util.List;

/**
 * The result of a {@link ShortestPathAlgorithm} run: the sequence of nodes
 * from start to end (inclusive of both), the total cost of that path, and
 * how many nodes the search settled (popped off its queue and expanded)
 * before finishing -- the benchmark suite's main efficiency signal
 * alongside wall-clock latency.
 */
public record PathResult<NodeType>(List<NodeType> path, double cost, int nodesExpanded) {}
