CREATE TABLE knowledge_proposal (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    workspace_id INTEGER NOT NULL,
    document_analysis_id INTEGER NOT NULL,
    document_id INTEGER NOT NULL,
    knowledge_candidate_id INTEGER NOT NULL,

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

    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,

    FOREIGN KEY (workspace_id) REFERENCES workspace(id),
    FOREIGN KEY (document_analysis_id) REFERENCES document_analysis(id) ON DELETE CASCADE,
    FOREIGN KEY (document_id) REFERENCES document(id),
    FOREIGN KEY (knowledge_candidate_id) REFERENCES knowledge_candidate(id) ON DELETE CASCADE
);

CREATE INDEX idx_knowledge_proposal_workspace_status ON knowledge_proposal(workspace_id, status);
CREATE INDEX idx_knowledge_proposal_document ON knowledge_proposal(document_id);
CREATE INDEX idx_knowledge_proposal_candidate ON knowledge_proposal(knowledge_candidate_id);

CREATE TABLE knowledge_proposal_evidence (
    knowledge_proposal_id INTEGER NOT NULL,
    source_chunk_id INTEGER NOT NULL,

    PRIMARY KEY (knowledge_proposal_id, source_chunk_id),

    FOREIGN KEY (knowledge_proposal_id) REFERENCES knowledge_proposal(id) ON DELETE CASCADE,
    FOREIGN KEY (source_chunk_id) REFERENCES source_chunk(id) ON DELETE CASCADE
);

CREATE INDEX idx_knowledge_proposal_evidence_source_chunk
    ON knowledge_proposal_evidence(source_chunk_id);
