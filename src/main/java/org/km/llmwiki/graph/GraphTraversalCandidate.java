package org.km.llmwiki.graph;

import java.util.List;

/** One reachable entity and its deterministic provider-neutral relation path. */
public record GraphTraversalCandidate(GraphEntityIdentity seed, GraphEntity entity,
                                      int depth, List<GraphRelation> path) {

    public GraphTraversalCandidate {
        if (seed == null || entity == null || depth < 1 || path == null || path.size() != depth) {
            throw new IllegalArgumentException("Graph traversal candidate is incomplete");
        }
        path = List.copyOf(path);
        GraphEntityIdentity expectedSource = seed;
        for (GraphRelation relation : path) {
            if (relation == null || !expectedSource.equals(relation.source())) {
                throw new IllegalArgumentException("Graph traversal path is not contiguous");
            }
            expectedSource = relation.target();
        }
        if (!expectedSource.equals(entity.identity())
                || !seed.workspace().equals(entity.identity().workspace())) {
            throw new IllegalArgumentException("Graph traversal path does not reach its candidate");
        }
    }
}
