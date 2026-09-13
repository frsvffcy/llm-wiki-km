-- #374: Governed Ask -> Proposal ingress. Ask-sourced proposals carry no document
-- analysis chain, so the three analysis-chain foreign keys become nullable and each
-- proposal records its source kind plus the minimal Ask provenance (question, answer,
-- citation identities). A per-workspace dedup hash gives the ingress a testable
-- idempotency contract. The table is rebuilt (SQLite cannot relax NOT NULL in place);
-- descendant tables are explicitly backed up and restored in dependency order so the
-- rebuild is self-healing regardless of implicit-cascade behavior: descendant rows are
-- explicitly backed up, deleted child-first, and restored parent-first inside the same
-- transaction, so no RESTRICT child or implicit cascade can silently lose data.

CREATE TABLE knowledge_proposal__v30_new (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    workspace_id INTEGER NOT NULL,
    document_analysis_id INTEGER,
    document_id INTEGER,
    knowledge_candidate_id INTEGER,

    action TEXT NOT NULL CHECK (action IN ('CREATE', 'MERGE', 'LINK_ONLY', 'IGNORE', 'REVIEW')),
    status TEXT NOT NULL CHECK (status IN ('DRAFT', 'REVIEW', 'APPROVED', 'REJECTED')),
    merge_target_reference TEXT,

    provider TEXT NOT NULL,
    model TEXT NOT NULL,
    prompt_identifier TEXT NOT NULL,
    prompt_version TEXT NOT NULL,
    contract_version TEXT NOT NULL,
    validated_payload_json TEXT,
    normalized_data_json TEXT NOT NULL,

    source_kind TEXT NOT NULL DEFAULT 'DOCUMENT_ANALYSIS'
        CHECK (source_kind IN ('DOCUMENT_ANALYSIS', 'ASK')),
    ask_question TEXT,
    ask_answer_text TEXT,
    ask_citations_json TEXT,
    source_dedup_hash TEXT,

    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,

    FOREIGN KEY (workspace_id) REFERENCES workspace(id),
    FOREIGN KEY (document_analysis_id) REFERENCES document_analysis(id) ON DELETE CASCADE,
    FOREIGN KEY (document_id) REFERENCES document(id),
    FOREIGN KEY (knowledge_candidate_id) REFERENCES knowledge_candidate(id) ON DELETE CASCADE
);

INSERT INTO knowledge_proposal__v30_new (
    id, workspace_id, document_analysis_id, document_id, knowledge_candidate_id,
    action, status, merge_target_reference, provider, model, prompt_identifier,
    prompt_version, contract_version, validated_payload_json, normalized_data_json,
    source_kind, created_at, updated_at
)
SELECT
    id, workspace_id, document_analysis_id, document_id, knowledge_candidate_id,
    action, status, merge_target_reference, provider, model, prompt_identifier,
    prompt_version, contract_version, validated_payload_json, normalized_data_json,
    'DOCUMENT_ANALYSIS', created_at, updated_at
FROM knowledge_proposal;

-- Explicit ordered child backup/delete/restore: the parent DROP must never trip
-- RESTRICT children or run implicit cascades that silently lose descendant rows.
CREATE TABLE wiki_publish_attempt__v30_backup AS SELECT * FROM wiki_publish_attempt;
CREATE TABLE wiki_publish_operation__v30_backup AS SELECT * FROM wiki_publish_operation;
CREATE TABLE wiki_draft__v30_backup AS SELECT * FROM wiki_draft;
CREATE TABLE knowledge_proposal_evidence__v30_backup AS SELECT * FROM knowledge_proposal_evidence;

DELETE FROM wiki_publish_attempt;
DELETE FROM wiki_publish_operation;
DELETE FROM wiki_draft;
DELETE FROM knowledge_proposal_evidence;
DELETE FROM knowledge_proposal;

DROP TABLE knowledge_proposal;
ALTER TABLE knowledge_proposal__v30_new RENAME TO knowledge_proposal;

CREATE INDEX idx_knowledge_proposal_workspace_status ON knowledge_proposal(workspace_id, status);
CREATE INDEX idx_knowledge_proposal_document ON knowledge_proposal(document_id);
CREATE INDEX idx_knowledge_proposal_candidate ON knowledge_proposal(knowledge_candidate_id);
CREATE INDEX idx_knowledge_proposal_source_kind ON knowledge_proposal(workspace_id, source_kind);
CREATE UNIQUE INDEX idx_knowledge_proposal_ask_dedup
    ON knowledge_proposal(workspace_id, source_dedup_hash) WHERE source_kind = 'ASK';

INSERT INTO knowledge_proposal_evidence SELECT * FROM knowledge_proposal_evidence__v30_backup;
INSERT INTO wiki_draft SELECT * FROM wiki_draft__v30_backup;
INSERT INTO wiki_publish_operation SELECT * FROM wiki_publish_operation__v30_backup;
INSERT INTO wiki_publish_attempt SELECT * FROM wiki_publish_attempt__v30_backup;

DROP TABLE knowledge_proposal_evidence__v30_backup;
DROP TABLE wiki_draft__v30_backup;
DROP TABLE wiki_publish_operation__v30_backup;
DROP TABLE wiki_publish_attempt__v30_backup;
