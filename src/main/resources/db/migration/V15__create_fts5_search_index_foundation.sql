-- FTS5 is an operational projection. The relational Wiki and Source Chunk tables remain
-- authoritative and this migration deliberately creates no triggers that could make FTS
-- writes part of the source-of-truth transaction.
CREATE TABLE search_index_contract (
    corpus TEXT PRIMARY KEY CHECK (corpus IN ('KNOWLEDGE', 'SOURCE')),
    schema_version INTEGER NOT NULL CHECK (schema_version = 1),
    stable_identity TEXT NOT NULL,
    indexed_fields TEXT NOT NULL,
    tokenizer TEXT NOT NULL,
    unicode_normalization TEXT NOT NULL,
    source_of_truth TEXT NOT NULL,
    rebuild_strategy TEXT NOT NULL,
    rebuildable INTEGER NOT NULL CHECK (rebuildable = 1)
);

INSERT INTO search_index_contract (
    corpus, schema_version, stable_identity, indexed_fields, tokenizer,
    unicode_normalization, source_of_truth, rebuild_strategy, rebuildable
)
VALUES
    ('KNOWLEDGE', 1, 'workspace_id + knowledge_id', 'title, content',
     'unicode61 remove_diacritics 2', 'NFC before indexing',
     'knowledge_page (PUBLISHED) and published vault Markdown',
     'clear-and-repopulate from published Wiki', 1),
    ('SOURCE', 1, 'workspace_id + source_chunk_id', 'normalized_content',
     'unicode61 remove_diacritics 2', 'NFC supplied by source_chunk.normalized_content',
     'source_chunk.normalized_content; raw content remains evidence-only',
     'clear-and-repopulate from source_chunk', 1);

-- FTS virtual tables cannot enforce a composite identity. The sidecar table below does so and
-- maps each stable identity to the FTS rowid used by the repository adapter.
CREATE TABLE search_index_identity (
    corpus TEXT NOT NULL CHECK (corpus IN ('KNOWLEDGE', 'SOURCE')),
    workspace_id INTEGER NOT NULL,
    stable_id TEXT NOT NULL,
    fts_rowid INTEGER NOT NULL,
    indexed_at TEXT NOT NULL,

    PRIMARY KEY (corpus, workspace_id, stable_id),
    UNIQUE (corpus, fts_rowid),
    FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
);

CREATE INDEX idx_search_index_identity_workspace
    ON search_index_identity(workspace_id, corpus, stable_id);

-- Only indexed columns participate in MATCH. Identity, workspace and provenance fields are
-- UNINDEXED metadata returned by the repository and never replace canonical source records.
CREATE VIRTUAL TABLE knowledge_fts USING fts5(
    workspace_id UNINDEXED,
    knowledge_id UNINDEXED,
    title,
    content,
    normalized_title UNINDEXED,
    markdown_path UNINDEXED,
    page_type UNINDEXED,
    page_status UNINDEXED,
    content_hash UNINDEXED,
    tokenize = 'unicode61 remove_diacritics 2'
);

CREATE VIRTUAL TABLE source_fts USING fts5(
    workspace_id UNINDEXED,
    source_chunk_id UNINDEXED,
    document_id UNINDEXED,
    chunk_no UNINDEXED,
    normalized_content,
    section UNINDEXED,
    heading_path UNINDEXED,
    content_hash UNINDEXED,
    tokenize = 'unicode61 remove_diacritics 2'
);
