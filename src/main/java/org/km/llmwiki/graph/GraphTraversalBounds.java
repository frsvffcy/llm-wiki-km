package org.km.llmwiki.graph;

/** Caller-selected traversal limits constrained by non-bypassable application hard caps. */
public record GraphTraversalBounds(int maxDepth, int perNodeFanOut, int perHopFanOut,
                                   int maxVisitedNodes, int maxVisitedEdges,
                                   int candidateLimit) {

    public static final int HARD_MAX_DEPTH = 4;
    public static final int HARD_MAX_PER_NODE_FAN_OUT = 32;
    public static final int HARD_MAX_PER_HOP_FAN_OUT = 128;
    public static final int HARD_MAX_VISITED_NODES = 512;
    public static final int HARD_MAX_VISITED_EDGES = 1_024;
    public static final int HARD_MAX_CANDIDATES = 200;

    public GraphTraversalBounds {
        requireBound(maxDepth, HARD_MAX_DEPTH);
        requireBound(perNodeFanOut, HARD_MAX_PER_NODE_FAN_OUT);
        requireBound(perHopFanOut, HARD_MAX_PER_HOP_FAN_OUT);
        requireBound(maxVisitedNodes, HARD_MAX_VISITED_NODES);
        requireBound(maxVisitedEdges, HARD_MAX_VISITED_EDGES);
        requireBound(candidateLimit, HARD_MAX_CANDIDATES);
    }

    private static void requireBound(int value, int hardMaximum) {
        if (value < 1 || value > hardMaximum) {
            throw new GraphProjectionException(
                    GraphProjectionFailureType.INVALID_TRAVERSAL_BOUNDS);
        }
    }
}
