CREATE TABLE embedding_projection_readiness (
    workspace_id INTEGER NOT NULL,
    corpus TEXT NOT NULL CHECK (corpus IN ('WIKI', 'SOURCE')),
    status TEXT NOT NULL CHECK (status IN ('NOT_BUILT', 'QUEUED', 'REBUILDING', 'PARTIAL', 'STALE', 'FAILED', 'READY')),
    processing_job_id INTEGER,
    indexed_count INTEGER NOT NULL DEFAULT 0,
    expected_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,
    embedding_provider TEXT,
    embedding_model TEXT,
    dimension INTEGER,
    projection_version TEXT,
    failure_detail TEXT,
    started_at TEXT,
    completed_at TEXT,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (workspace_id, corpus),
    FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
    FOREIGN KEY (processing_job_id) REFERENCES processing_job(id) ON DELETE SET NULL
);

CREATE INDEX idx_embedding_projection_readiness_status
    ON embedding_projection_readiness(workspace_id, status, corpus);
