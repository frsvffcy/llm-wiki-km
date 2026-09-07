package org.km.llmwiki.graph;

import java.util.List;
import java.util.Set;

/** Materialized bounded result tied to the exact snapshot that produced its topology. */
public record GraphTraversalResult(GraphProjectionSnapshot snapshot,
                                   List<GraphTraversalCandidate> candidates,
                                   int visitedNodeCount, int visitedEdgeCount,
                                   Set<GraphTraversalLimit> reachedLimits) {

    public GraphTraversalResult {
        if (snapshot == null || candidates == null || visitedNodeCount < 1
                || visitedEdgeCount < 0 || reachedLimits == null
                || reachedLimits.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Graph traversal result is incomplete");
        }
        candidates = List.copyOf(candidates);
        reachedLimits = Set.copyOf(reachedLimits);
    }
}
