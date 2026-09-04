-- Incremental jobs must remember whether the serving corpus was complete before
-- the canonical mutation invalidated it. This is internal lifecycle state and is
-- cleared when the job reaches a terminal state.
ALTER TABLE embedding_projection_readiness
    ADD COLUMN incremental_prior_ready INTEGER NOT NULL DEFAULT 0
        CHECK (incremental_prior_ready IN (0, 1));
