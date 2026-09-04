package org.km.llmwiki.graph;

/** Safe result for one generation-guarded graph projection cleanup. */
public record GraphProjectionCleanupResult(GraphProjectionCleanupStatus status,
                                           GraphWorkspaceScope workspace,
                                           long generation,
                                           int removedEntities,
                                           int removedRelations) {

    public GraphProjectionCleanupResult {
        if (status == null || workspace == null || generation < 1
                || removedEntities < 0 || removedRelations < 0
                || (status == GraphProjectionCleanupStatus.NO_OP
                && (removedEntities != 0 || removedRelations != 0))
                || (status == GraphProjectionCleanupStatus.SUPERSEDED
                && (removedEntities != 0 || removedRelations != 0))
                || (status == GraphProjectionCleanupStatus.APPLIED
                && removedEntities == 0 && removedRelations == 0)) {
            throw new IllegalArgumentException("Graph cleanup result is invalid");
        }
    }

    public static GraphProjectionCleanupResult applied(GraphProjectionReconciliation reconciliation,
                                                       int removedEntities, int removedRelations) {
        requireReconciliation(reconciliation);
        GraphProjectionCleanupStatus status = removedEntities == 0 && removedRelations == 0
                ? GraphProjectionCleanupStatus.NO_OP : GraphProjectionCleanupStatus.APPLIED;
        return new GraphProjectionCleanupResult(status, reconciliation.workspace(),
                reconciliation.snapshot().generation(), removedEntities, removedRelations);
    }

    public static GraphProjectionCleanupResult superseded(GraphProjectionReconciliation reconciliation) {
        requireReconciliation(reconciliation);
        return new GraphProjectionCleanupResult(GraphProjectionCleanupStatus.SUPERSEDED,
                reconciliation.workspace(), reconciliation.snapshot().generation(), 0, 0);
    }

    private static void requireReconciliation(GraphProjectionReconciliation reconciliation) {
        if (reconciliation == null) {
            throw new IllegalArgumentException("Graph reconciliation is required");
        }
    }

    public boolean mutated() {
        return status == GraphProjectionCleanupStatus.APPLIED;
    }
}
