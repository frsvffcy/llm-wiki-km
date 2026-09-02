-- Embeddings are a rebuildable operational projection.  The canonical Wiki, vault and Source
-- Chunk authorities remain independent from this table and can always recreate it.
CREATE TABLE embedding_projection (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    workspace_id INTEGER NOT NULL,
    evidence_kind TEXT NOT NULL CHECK (evidence_kind IN ('WIKI', 'SOURCE_CHUNK')),
    stable_id TEXT NOT NULL,
    canonical_content_hash TEXT NOT NULL,
    embedding_provider TEXT,
    embedding_model TEXT,
    dimension INTEGER,
    projection_version TEXT NOT NULL,
    vector_encoding TEXT,
    vector_blob BLOB,
    generation_status TEXT NOT NULL CHECK (generation_status IN ('FRESH', 'FAILED')),
    generation_attempt INTEGER NOT NULL DEFAULT 1 CHECK (generation_attempt > 0),
    generated_at TEXT,
    last_attempt_at TEXT NOT NULL,
    failure_type TEXT,
    failure_detail TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,

    UNIQUE (workspace_id, evidence_kind, stable_id),
    FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
    CHECK (
        (generation_status = 'FRESH'
            AND embedding_provider IS NOT NULL
            AND embedding_model IS NOT NULL
            AND dimension > 0
            AND vector_encoding = 'FLOAT64_LE'
            AND vector_blob IS NOT NULL
            AND generated_at IS NOT NULL
            AND failure_type IS NULL
            AND failure_detail IS NULL)
        OR
        (generation_status = 'FAILED'
            AND embedding_provider IS NULL
            AND embedding_model IS NULL
            AND dimension IS NULL
            AND vector_encoding IS NULL
            AND vector_blob IS NULL
            AND generated_at IS NULL
            AND failure_type IS NOT NULL)
    )
);

CREATE INDEX idx_embedding_projection_workspace_status
    ON embedding_projection(workspace_id, generation_status, evidence_kind);

CREATE INDEX idx_embedding_projection_freshness
    ON embedding_projection(workspace_id, evidence_kind, stable_id,
                            canonical_content_hash, projection_version);
