package org.km.llmwiki.graph;

/** Observable application-owned bound reached while materializing a deterministic traversal. */
public enum GraphTraversalLimit {
    DEPTH,
    PER_NODE_FAN_OUT,
    PER_HOP_FAN_OUT,
    VISITED_NODES,
    VISITED_EDGES,
    CANDIDATES
}
