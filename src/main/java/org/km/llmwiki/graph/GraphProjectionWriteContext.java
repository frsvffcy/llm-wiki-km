package org.km.llmwiki.graph;

/**
 * Application-owned proof carried by every Graph projection write operation.
 *
 * <p>The context is derived from a complete projection input and its snapshot. It is deliberately
 * provider-neutral: the proof contains no backend transaction, record identifier, session, or
 * query language. Adapters must compare the complete snapshot proof, not only the generation,
 * before accepting a mutation.
 */
public record GraphProjectionWriteContext(GraphProjectionInput input,
                                          GraphProjectionSnapshot snapshot) {

    public GraphProjectionWriteContext {
        if (input == null || snapshot == null) {
            throw new IllegalArgumentException("Graph projection write input and snapshot are required");
        }
        GraphProjectionReconciliation.from(input, snapshot);
    }

    public static GraphProjectionWriteContext of(GraphProjectionInput input, long generation) {
        return new GraphProjectionWriteContext(input, GraphProjectionSnapshot.of(input, generation));
    }

    public static GraphProjectionWriteContext of(GraphProjectionInput input,
                                                 GraphProjectionSnapshot snapshot) {
        return new GraphProjectionWriteContext(input, snapshot);
    }

    public static GraphProjectionWriteContext of(GraphProjectionReconciliation reconciliation) {
        if (reconciliation == null) {
            throw new IllegalArgumentException("Graph reconciliation is required");
        }
        return of(reconciliation.input(), reconciliation.snapshot());
    }

    public GraphWorkspaceScope workspace() {
        return snapshot.workspace();
    }

    public GraphProjectionVersion projectionVersion() {
        return snapshot.projectionVersion();
    }

    public long generation() {
        return snapshot.generation();
    }

    public String sourceFingerprint() {
        return snapshot.sourceFingerprint();
    }

    public String snapshotToken() {
        return snapshot.snapshotToken();
    }

    public boolean owns(GraphProjectionSnapshot current) {
        return current != null && snapshot.equals(current);
    }

    public boolean isSupersededBy(GraphProjectionSnapshot current) {
        return current != null && workspace().equals(current.workspace())
                && current.generation() > generation();
    }

    public boolean conflictsWith(GraphProjectionSnapshot current) {
        return current != null && workspace().equals(current.workspace())
                && current.generation() == generation() && !owns(current);
    }

    public boolean matches(GraphEntity entity) {
        return entity != null && workspace().equals(entity.identity().workspace())
                && projectionVersion().equals(entity.projectionVersion())
                && input.entities().contains(entity);
    }

    public boolean matches(GraphRelation relation) {
        return relation != null && workspace().equals(relation.identity().workspace())
                && projectionVersion().equals(relation.projectionVersion())
                && input.relations().contains(relation);
    }
}
