-- A readiness row is current serving state; this ledger preserves the immutable
-- corpus generation represented by every embedding operation.
CREATE TABLE embedding_projection_operation (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    workspace_id INTEGER NOT NULL,
    corpus TEXT NOT NULL CHECK (corpus IN ('WIKI', 'SOURCE')),
    processing_job_id INTEGER NOT NULL,
    generation INTEGER NOT NULL CHECK (generation > 0),
    operation_kind TEXT NOT NULL CHECK (operation_kind IN ('INCREMENTAL', 'FULL')),
    status TEXT NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED')),
    failure_detail TEXT,
    created_at TEXT NOT NULL,
    started_at TEXT,
    completed_at TEXT,
    updated_at TEXT NOT NULL,
    UNIQUE (workspace_id, corpus, generation),
    UNIQUE (processing_job_id, corpus),
    FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
    FOREIGN KEY (processing_job_id) REFERENCES processing_job(id) ON DELETE CASCADE
);

CREATE INDEX idx_embedding_projection_operation_job
    ON embedding_projection_operation(processing_job_id, status);

CREATE INDEX idx_embedding_projection_operation_current
    ON embedding_projection_operation(workspace_id, corpus, generation, status);
