/**
 * One end of a directed edge as seen from the other end: the node reached
 * (or, from {@link BaseGraph#predecessorsOf}, the node an edge comes from)
 * and that edge's weight. The minimum a shortest-path algorithm needs to
 * traverse a graph without depending on {@link BaseGraph}'s internal
 * {@code Node}/{@code Edge} representation.
 */
public record Neighbor<NodeType, EdgeType extends Number>(NodeType node, EdgeType weight) {}
