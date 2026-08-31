-- Issue #129: switch the rebuildable FTS projections to the versioned application
-- projector.  V1..V18 are immutable; this migration only changes operational data.
ALTER TABLE search_index_contract
    ADD COLUMN projection_algorithm TEXT NOT NULL DEFAULT 'cjk-bigram';
ALTER TABLE search_index_contract
    ADD COLUMN projection_version TEXT NOT NULL DEFAULT 'cjk-bigram-v1';

-- Keep the historical unicode61 tokenizer declaration: SQLite still tokenizes the
-- application projection.  The application-side algorithm/version is the durable
-- contract that determines whether an index is serveable.
UPDATE search_index_contract
   SET projection_algorithm = 'cjk-bigram',
       projection_version = 'cjk-bigram-v1',
       indexed_fields = CASE corpus
           WHEN 'SOURCE' THEN 'projected_content'
           ELSE indexed_fields
       END;

ALTER TABLE knowledge_search_index_sync
    ADD COLUMN projection_version TEXT NOT NULL DEFAULT 'cjk-bigram-v1';
ALTER TABLE source_search_index_sync
    ADD COLUMN projection_version TEXT NOT NULL DEFAULT 'cjk-bigram-v1';
ALTER TABLE search_index_rebuild_state
    ADD COLUMN projection_version TEXT NOT NULL DEFAULT 'cjk-bigram-v1';

-- FTS5 virtual tables do not support adding UNINDEXED columns portably.  Recreate
-- only these disposable projections and leave all canonical tables untouched.  Any
-- pre-existing unicode61 rows therefore require a deterministic full rebuild.
DROP TABLE knowledge_fts;
DROP TABLE source_fts;
DELETE FROM search_index_identity;

CREATE VIRTUAL TABLE knowledge_fts USING fts5(
    workspace_id UNINDEXED,
    knowledge_id UNINDEXED,
    title,
    content,
    normalized_title UNINDEXED,
    canonical_title UNINDEXED,
    canonical_content UNINDEXED,
    markdown_path UNINDEXED,
    page_type UNINDEXED,
    page_status UNINDEXED,
    content_hash UNINDEXED,
    projection_version UNINDEXED,
    tokenize = 'unicode61 remove_diacritics 2'
);

CREATE VIRTUAL TABLE source_fts USING fts5(
    workspace_id UNINDEXED,
    source_chunk_id UNINDEXED,
    document_id UNINDEXED,
    chunk_no UNINDEXED,
    page_no UNINDEXED,
    projected_content,
    normalized_content UNINDEXED,
    section UNINDEXED,
    heading_path UNINDEXED,
    content_hash UNINDEXED,
    projection_version UNINDEXED,
    tokenize = 'unicode61 remove_diacritics 2'
);
