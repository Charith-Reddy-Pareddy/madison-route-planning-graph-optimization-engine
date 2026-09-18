import java.util.List;

/**
 * The result of a {@link ShortestPathAlgorithm} run: the sequence of nodes
 * from start to end (inclusive of both), and the total cost of that path.
 */
public record PathResult<NodeType>(List<NodeType> path, double cost) {}
