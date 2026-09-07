package org.km.llmwiki.graph;

import java.util.Comparator;

/** Application-owned ordering contract; backend record order never participates. */
public enum GraphTraversalOrdering {
    DEPTH_SEED_ENTITY_PATH_V1;

    /**
     * Defines which outgoing relations survive fan-out truncation. Infrastructure indexes may
     * implement this order efficiently, but they are not its authority.
     */
    public Comparator<GraphRelation> outgoingRelationComparator() {
        return Comparator.comparing((GraphRelation relation) -> relation.type().name())
                .thenComparing(relation -> relation.target().stableId())
                .thenComparing(relation -> relation.identity().stableId());
    }

    public Comparator<GraphRelationType> relationTypeComparator() {
        return Comparator.comparing(Enum::name);
    }

    public Comparator<GraphTraversalCandidate> candidateComparator() {
        return Comparator.comparingInt(GraphTraversalCandidate::depth)
                .thenComparing(candidate -> candidate.seed().stableId())
                .thenComparing(candidate -> candidate.entity().identity().stableId())
                .thenComparing(GraphTraversalOrdering::pathKey);
    }

    private static String pathKey(GraphTraversalCandidate candidate) {
        return candidate.path().stream()
                .map(relation -> relation.identity().stableId())
                .reduce("", (left, right) -> left + "|" + right);
    }
}
