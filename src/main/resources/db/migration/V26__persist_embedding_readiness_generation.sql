-- target_generation advances at canonical invalidation/enqueue time. applied_generation
-- records the greatest terminal generation whose callback has been observed. Neither field
-- is a serving claim by itself; READY is derived from the operation ledger and corpus proof.
ALTER TABLE embedding_projection_readiness
    ADD COLUMN target_generation INTEGER NOT NULL DEFAULT 0
        CHECK (target_generation >= 0);

ALTER TABLE embedding_projection_readiness
    ADD COLUMN applied_generation INTEGER NOT NULL DEFAULT 0
        CHECK (applied_generation >= 0);

ALTER TABLE embedding_projection_readiness
    ADD COLUMN projection_snapshot_token TEXT;

-- Historical READY rows remain a valid legacy baseline for a future incremental repair, but
-- receive no invented operation history or snapshot token. A full rebuild creates the first
-- generation proof; a legacy incremental can become READY only after a fresh whole-corpus proof.
