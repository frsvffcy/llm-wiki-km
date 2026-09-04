package org.km.llmwiki.graph;

/** Write/reconciliation port implemented by a replaceable Graph projection adapter. */
public interface GraphProjectionWriter {

    /**
     * Upserts one immutable entity projection under an application-owned snapshot proof.
     *
     * <p>A context for a generation newer than the current snapshot may be staged, but staged
     * rows must not be current-visible until {@link #publish(GraphProjectionWriteContext)}
     * succeeds. If a newer generation is already current, implementations must return
     * {@link GraphProjectionWriteStatus#SUPERSEDED} without mutating any row. A same-generation
     * proof mismatch must fail with
     * {@link GraphProjectionFailureType#INVALID_PROJECTION_INPUT}; workspace and projection
     * version mismatches must fail closed as typed projection failures. Implementations must not
     * mutate canonical authority.
     */
    GraphProjectionWriteResult upsertEntity(GraphProjectionWriteContext context, GraphEntity entity);

    /**
     * Upserts one immutable relation projection under the same generation ownership rules as an
     * entity write. Entity and relation writes must have identical stale, conflict, workspace,
     * version, and idempotency semantics.
     */
    GraphProjectionWriteResult upsertRelation(GraphProjectionWriteContext context,
                                               GraphRelation relation);

    /**
     * Atomically publishes the context's staged rows as the workspace's current projection.
     *
     * <p>Publication is the visibility boundary: all rows written with the matching context are
     * current-visible only after this operation succeeds. Publication does not infer missing
     * rows; callers must complete the context's entity/relation writes before publishing. A newer
     * current generation wins the compare-and-set and causes
     * {@link GraphProjectionWriteStatus#SUPERSEDED}; an incompatible same-generation proof fails
     * closed. A matching current proof is idempotent.
     */
    GraphProjectionWriteResult publish(GraphProjectionWriteContext context);

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

    /**
     * Conditionally clears the derived projection state owned by one generation.
     *
     * <p>The target workspace and the complete application-owned context proof must be supplied
     * together. Implementations must compare the current snapshot with the context and perform
     * the destructive clear as one atomic state transition, or a provider-neutral equivalent;
     * a caller must not implement this as a read-then-clear sequence. A newer current generation
     * must return {@link GraphProjectionWriteStatus#SUPERSEDED} without mutation. A same-generation
     * proof mismatch must fail with {@link GraphProjectionFailureType#INVALID_PROJECTION_INPUT},
     * a cross-workspace target must fail with {@link GraphProjectionFailureType#CROSS_WORKSPACE},
     * and an incompatible projection version must fail with
     * {@link GraphProjectionFailureType#PROJECTION_INCOMPATIBLE}. A clear may remove rows owned
     * by the expected generation and older generations, but must preserve rows staged for newer
     * generations so they can still be published. Repeating an already applied clear with the
     * same proof is an idempotent {@link GraphProjectionWriteStatus#NO_OP}. Canonical authority
     * must never be modified.
     */
    GraphProjectionWriteResult clearWorkspace(GraphWorkspaceScope workspace,
                                               GraphProjectionWriteContext context);
}
