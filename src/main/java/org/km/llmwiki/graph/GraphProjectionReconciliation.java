package org.km.llmwiki.graph;

import java.util.List;

/**
 * Workspace-scoped active set used to remove or supersede stale derived projection state.
 *
 * <p>The active identities are derived from the immutable input. There is intentionally no
 * constructor that accepts a caller-supplied active set: a reconciliation must carry proof that
 * its cleanup set came from the same input fingerprint as its snapshot.
 */
public record GraphProjectionReconciliation(GraphProjectionInput input,
                                            GraphProjectionSnapshot snapshot) {

    public GraphProjectionReconciliation {
        if (input == null || snapshot == null) {
            throw new IllegalArgumentException("Graph reconciliation input and snapshot are required");
        }
        if (!input.workspace().equals(snapshot.workspace())) {
            throw new IllegalArgumentException("Graph reconciliation crosses workspace boundary");
        }
        if (!input.projectionVersion().equals(snapshot.projectionVersion())) {
            throw new IllegalArgumentException("Graph reconciliation projection version differs from snapshot");
        }
        if (!input.sourceFingerprint().equals(snapshot.sourceFingerprint())) {
            throw new IllegalArgumentException("Graph reconciliation source fingerprint differs from snapshot");
        }
    }

    public static GraphProjectionReconciliation from(GraphProjectionInput input,
                                                     GraphProjectionSnapshot snapshot) {
        return new GraphProjectionReconciliation(input, snapshot);
    }

    public GraphWorkspaceScope workspace() {
        return input.workspace();
    }

    public List<GraphEntityIdentity> activeEntities() {
        return input.entities().stream().map(GraphEntity::identity).toList();
    }

    public List<GraphRelationIdentity> activeRelations() {
        return input.relations().stream().map(GraphRelation::identity).toList();
    }

    /** Whether this reconciliation still owns the supplied current snapshot proof. */
    public boolean owns(GraphProjectionSnapshot current) {
        return snapshot.equals(current);
    }

    /** Returns the write context carrying this reconciliation's complete input and snapshot proof. */
    public GraphProjectionWriteContext writeContext() {
        return GraphProjectionWriteContext.of(input, snapshot);
    }

    /** Whether a newer generation has superseded this reconciliation. */
    public boolean isSupersededBy(GraphProjectionSnapshot current) {
        return current != null && workspace().equals(current.workspace())
                && current.generation() > snapshot.generation();
    }

    /** Whether the same generation is represented by a different snapshot proof. */
    public boolean conflictsWith(GraphProjectionSnapshot current) {
        return current != null && workspace().equals(current.workspace())
                && current.generation() == snapshot.generation() && !owns(current);
    }
}
