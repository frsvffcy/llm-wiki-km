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

    /** Removes or supersedes projection rows absent from the workspace-scoped active set. */
    void removeStale(GraphProjectionReconciliation reconciliation);

    /** Deletes all derived graph state for one workspace without touching canonical authority. */
    void clearWorkspace(GraphWorkspaceScope workspace);
}
