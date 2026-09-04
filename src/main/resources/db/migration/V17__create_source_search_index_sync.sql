-- Source FTS remains a rebuildable projection. Recreate only the SOURCE corpus virtual
-- table to add evidence provenance introduced by STORY-503; canonical source_chunk rows
-- are never changed by this migration.
DROP TABLE source_fts;

DELETE FROM search_index_identity
 WHERE corpus = 'SOURCE';

CREATE VIRTUAL TABLE source_fts USING fts5(
    workspace_id UNINDEXED,
    source_chunk_id UNINDEXED,
    document_id UNINDEXED,
    chunk_no UNINDEXED,
    page_no UNINDEXED,
    normalized_content,
    section UNINDEXED,
    heading_path UNINDEXED,
    content_hash UNINDEXED,
    tokenize = 'unicode61 remove_diacritics 2'
);

-- Document is the atomic synchronization unit because extraction replaces every Source
-- Chunk for a document. INDEX_PENDING is a durable repair signal and does not participate
-- in the canonical Source Chunk transaction.
CREATE TABLE source_search_index_sync (
    workspace_id INTEGER NOT NULL,
    document_id INTEGER NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('SYNCED', 'INELIGIBLE', 'INDEX_PENDING')),
    eligible_chunk_count INTEGER NOT NULL CHECK (eligible_chunk_count >= 0),
    indexed_chunk_count INTEGER NOT NULL CHECK (indexed_chunk_count >= 0),
    canonical_fingerprint TEXT,
    indexed_fingerprint TEXT,
    failure_detail TEXT,
    updated_at TEXT NOT NULL,

    PRIMARY KEY (workspace_id, document_id),
    FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
    FOREIGN KEY (document_id) REFERENCES document(id) ON DELETE CASCADE
);

CREATE INDEX idx_source_search_index_sync_status
    ON source_search_index_sync(workspace_id, status, updated_at);
