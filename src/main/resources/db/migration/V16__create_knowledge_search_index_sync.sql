-- FTS is rebuildable and must not participate in the durable Wiki publish transaction.
-- This ledger records the repair state when the operational projection cannot be refreshed.
CREATE TABLE knowledge_search_index_sync (
    workspace_id INTEGER NOT NULL,
    knowledge_page_id INTEGER NOT NULL,
    knowledge_id TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('SYNCED', 'INDEX_PENDING', 'DRIFT')),
    content_hash TEXT NOT NULL,
    indexed_content_hash TEXT,
    failure_detail TEXT,
    updated_at TEXT NOT NULL,

    PRIMARY KEY (workspace_id, knowledge_page_id),
    UNIQUE (workspace_id, knowledge_id),
    FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
    FOREIGN KEY (knowledge_page_id) REFERENCES knowledge_page(id) ON DELETE CASCADE
);

CREATE INDEX idx_knowledge_search_index_sync_status
    ON knowledge_search_index_sync(workspace_id, status, updated_at);
