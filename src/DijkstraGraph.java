// === CS400 File Header Information ===
// Name: Charith Reddy Pareddy
// Email: cpareddy@wisc.edu
// Group and Team: P2.2602
// Lecturer: Gary Dahl
//Lecture Number:002
//Assigned Role:Frontend Developer
// Notes to Grader: I implemented DijkstraGraph i.e. the computeShortestPath based on the pseudocode
// in Florian's lecture. Unit tests for this class live in test/DijkstraGraphTest.java.
import java.util.*;

/**
 * This class extends the BaseGraph data structure with additional methods for computing the total
 * cost and list of node data along the shortest path connecting a provided starting to ending
 * nodes. This class makes use of Dijkstra's shortest path algorithm.
 *
 * <p>The original CS400 submission (header above) enqueued a new SearchNode for every edge out of
 * a visited node unconditionally, even when a cheaper path to that same node was already known --
 * relying on the later visitedSet check to discard the loser once popped. computeShortestPath now
 * tracks a best-known distance per node and only enqueues strict improvements, so an inferior
 * candidate is never added to the queue in the first place; see lastEdgesConsidered()/
 * lastQueueInsertions() and DijkstraGraphTest for a demonstration on a graph with redundant paths.
 */
public class DijkstraGraph<NodeType, EdgeType extends Number> extends BaseGraph<NodeType, EdgeType>
    implements GraphADT<NodeType, EdgeType> {

  /**
   * While searching for the shortest path between two nodes, a SearchNode contains data about one
   * specific path between the start node and another node in the graph. The final node in this path
   * is stored in its node field. The total cost of this path is stored in its cost field. And the
   * predecessor SearchNode within this path is referened by the predecessor field (this field is
   * null within the SearchNode containing the starting node in its node field).
   *
   * SearchNodes are Comparable and are sorted by cost so that the lowest cost SearchNode has the
   * highest priority within a java.util.PriorityQueue.
   */
  protected class SearchNode implements Comparable<SearchNode> {
    public Node node;
    public double cost;
    public SearchNode predecessor;

    public SearchNode(Node node, double cost, SearchNode predecessor) {
      this.node = node;
      this.cost = cost;
      this.predecessor = predecessor;
    }

    public int compareTo(SearchNode other) {
      if (cost > other.cost)
        return +1;
      if (cost < other.cost)
        return -1;
      return 0;
    }
  }

  /**
   * Constructor that sets the map that the graph uses.
   */
  public DijkstraGraph() {
    super(new PlaceholderMap<>());
  }

  // Diagnostics from the most recent computeShortestPath call: how many
  // edges were looked at vs. how many actually produced a queue insertion.
  // Exposed for tests/benchmarks to demonstrate that tracking a best-known
  // distance per node (below) avoids enqueuing SearchNodes that can never
  // win -- e.g. a later, more expensive path to a node already reached
  // more cheaply. Without that check, every edge out of every visited node
  // becomes a queue insertion regardless of whether it's an improvement.
  private int lastEdgesConsidered;
  private int lastQueueInsertions;

  public int lastEdgesConsidered() {
    return lastEdgesConsidered;
  }

  public int lastQueueInsertions() {
    return lastQueueInsertions;
  }

  /**
   * This helper method creates a network of SearchNodes while computing the shortest path between
   * the provided start and end locations. The SearchNode that is returned by this method is
   * represents the end of the shortest path that is found: it's cost is the cost of that shortest
   * path, and the nodes linked together through predecessor references represent all of the nodes
   * along that shortest path (ordered from end to start).
   *
   * @param start the data item in the starting node for the path
   * @param end   the data item in the destination node for the path
   * @return SearchNode for the final end node within the shortest path
   * @throws NoSuchElementException when no path from start to end is found or when either start or
   *                                end data do not correspond to a graph node Just the node,the
   *                                previous,and cost to compare it with.
   */
  protected SearchNode computeShortestPath(NodeType start, NodeType end) {
    if (start == null || end == null || !nodes.containsKey(start) || !nodes.containsKey(end)) {
      throw new NoSuchElementException("Either start or end node does not exist in the graph.");
    }
    lastEdgesConsidered = 0;
    lastQueueInsertions = 0;

    // S1: Initialize priority queue to store nodes based on their cost
    PriorityQueue<SearchNode> priorityQueue = new PriorityQueue<>();
    // Use PlaceholderMap to track visited (finalized) nodes
    PlaceholderMap<NodeType, SearchNode> visitedSet = new PlaceholderMap<>();
    // Best path cost found so far to a not-yet-finalized node, so a worse
    // duplicate SearchNode for the same node never gets enqueued in the
    // first place, instead of being enqueued and later skipped via
    // visitedSet once it's popped.
    Map<NodeType, Double> bestKnownCost = new HashMap<>();

    // S2: Initialize the start node with cost 0 and no predecessor, then add to queue
    SearchNode startNode = new SearchNode(nodes.get(start), 0.0, null);
    priorityQueue.add(startNode);
    lastQueueInsertions++;
    bestKnownCost.put(start, 0.0);

    // S3: Continue processing nodes in the priority queue until it's empty
    while (!priorityQueue.isEmpty()) {
      // S4: Remove node with the lowest cost from the queue
      SearchNode currentNode = priorityQueue.remove();
      NodeType currentData = currentNode.node.data;

      // S5: Skip nodes that have already been visited
      if (visitedSet.containsKey(currentData)) {
        continue;
      }

      // S6: If current node is the destination, return it as the shortest path end node
      if (currentData.equals(end)) {
        return currentNode;
      }

      // S7.1 & S7.2: Mark the current node as visited by adding it to the visited set
      visitedSet.put(currentData, currentNode);

      // S8: Explore neighboring nodes (successors)
      Node graphNode = currentNode.node;
      for (Edge edge : graphNode.edgesLeaving) {
        Node successorNode = edge.successor;
        NodeType successorData = successorNode.data;
        if (visitedSet.containsKey(successorData)) {
          continue;
        }
        lastEdgesConsidered++;

        double newCost = currentNode.cost + edge.data.doubleValue();
        Double knownCost = bestKnownCost.get(successorData);

        // Only enqueue this path if it's the first one found to this node,
        // or strictly better than the best one found so far.
        if (knownCost == null || newCost < knownCost) {
          bestKnownCost.put(successorData, newCost);
          priorityQueue.add(new SearchNode(successorNode, newCost, currentNode));
          lastQueueInsertions++;
        }
      }
    }

    // S9: If no path from start to end is found, throw exception
    throw new NoSuchElementException("No path found from start to end");
  }

  /**
   * Returns the list of data values from nodes along the shortest path from the node with the
   * provided start value through the node with the provided end value. This list of data values
   * starts with the start value, ends with the end value, and contains intermediary values in the
   * order they are encountered while traversing this shorteset path. This method uses Dijkstra's
   * shortest path algorithm to find this solution.
   *
   * @param start the data item in the starting node for the path
   * @param end   the data item in the destination node for the path
   * @return list of data item from node along this shortest path
   */
  public List<NodeType> shortestPathData(NodeType start, NodeType end) {
    // Compute the shortest path from start to end
    SearchNode endNode = computeShortestPath(start, end);
    LinkedList<NodeType> path = new LinkedList<>();
    // Trace back from the end node to the start node to form the path
    for (SearchNode node = endNode; node != null; node = node.predecessor) {
      path.addFirst(node.node.data);
    }
    return path;
  }


  /**
   * Returns the cost of the path (sum over edge weights) of the shortest path freom the node
   * containing the start data to the node containing the end data. This method uses Dijkstra's
   * shortest path algorithm to find this solution.
   *
   * @param start the data item in the starting node for the path
   * @param end   the data item in the destination node for the path
   * @return the cost of the shortest path between these nodes
   */
  public double shortestPathCost(NodeType start, NodeType end) {
    // Compute the shortest path and return the cost associated with the end node
    SearchNode endNode = computeShortestPath(start, end);
    return endNode.cost;
  }

}


