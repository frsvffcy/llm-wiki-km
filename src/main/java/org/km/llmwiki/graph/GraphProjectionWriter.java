package org.km.llmwiki.graph;

/** Write/reconciliation port implemented by a replaceable Graph projection adapter. */
public interface GraphProjectionWriter {

    /**
     * Upserts one immutable entity projection in the supplied workspace.
     * Implementations must reject a mismatched entity workspace and must not mutate canonical
     * authority.
     */
    void upsertEntity(GraphWorkspaceScope workspace, GraphEntity entity);

    /**
     * Upserts one immutable relation projection in the supplied workspace.
     * Implementations must reject a mismatched relation workspace and must not mutate canonical
     * authority.
     */
    void upsertRelation(GraphWorkspaceScope workspace, GraphRelation relation);

    /**
     * Conditionally removes or supersedes projection rows absent from the active set.
     *
     * <p>The implementation must atomically compare the workspace's current snapshot proof with
     * {@code reconciliation.snapshot()} before mutating derived state. If a newer generation is
     * current, it must return {@link GraphProjectionCleanupStatus#SUPERSEDED} without deleting or
     * updating any row. A same-generation proof mismatch must fail with
     * {@link GraphProjectionFailureType#INVALID_PROJECTION_INPUT}; an older current generation
     * must fail with {@link GraphProjectionFailureType#PROJECTION_STALE}. The compare-and-set may
     * use any provider-neutral equivalent mechanism; vendor transaction or query primitives must
     * not cross this boundary.
     */
    GraphProjectionCleanupResult removeStale(GraphProjectionReconciliation reconciliation);

    /** Deletes all derived graph state for one workspace without touching canonical authority. */
    void clearWorkspace(GraphWorkspaceScope workspace);
}
