-- Projection mutations carry the operation generation so a delayed older worker cannot
-- overwrite or delete rows belonging to a newer operation.
ALTER TABLE embedding_projection
    ADD COLUMN projection_generation INTEGER NOT NULL DEFAULT 0
        CHECK (projection_generation >= 0);

CREATE INDEX idx_embedding_projection_generation
    ON embedding_projection(workspace_id, evidence_kind, projection_generation);
