-- Rebuild execution state is a durable health gate for the operational FTS projection.
-- Canonical Wiki, vault and Source Chunk content remain untouched by this table.
CREATE TABLE search_index_rebuild_state (
    workspace_id INTEGER NOT NULL,
    corpus TEXT NOT NULL CHECK (corpus IN ('WIKI', 'SOURCE')),
    status TEXT NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED')),
    processing_job_id INTEGER NOT NULL,
    indexed_count INTEGER NOT NULL DEFAULT 0 CHECK (indexed_count >= 0),
    failed_count INTEGER NOT NULL DEFAULT 0 CHECK (failed_count >= 0),
    failure_detail TEXT,
    started_at TEXT,
    completed_at TEXT,
    updated_at TEXT NOT NULL,

    PRIMARY KEY (workspace_id, corpus),
    FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
    FOREIGN KEY (processing_job_id) REFERENCES processing_job(id) ON DELETE CASCADE
);

CREATE INDEX idx_search_index_rebuild_state_status
    ON search_index_rebuild_state(workspace_id, status, corpus);

-- Wiki projection freshness includes both immutable content bytes and the canonical revision.
ALTER TABLE knowledge_search_index_sync ADD COLUMN indexed_revision INTEGER;
