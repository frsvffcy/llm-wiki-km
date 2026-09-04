-- sqlite-vec reads FLOAT32 little-endian values. The existing FLOAT64_LE blob remains the
-- provider-neutral projection representation; this second blob is rebuildable query storage.
ALTER TABLE embedding_projection ADD COLUMN vector_search_blob BLOB;

CREATE INDEX idx_embedding_projection_knn_filter
    ON embedding_projection(workspace_id, evidence_kind, embedding_provider,
                             embedding_model, dimension, projection_version,
                             generation_status, stable_id);

-- Existing projections cannot be converted safely in SQL because SQLite has no portable,
-- lossless FLOAT64-to-FLOAT32 blob conversion. Force the normal rebuild path to populate the
-- native search representation before semantic readiness is restored.
UPDATE embedding_projection_readiness
SET status = 'STALE',
    failure_detail = 'Native KNN representation requires embedding projection rebuild',
    updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
WHERE status = 'READY';
