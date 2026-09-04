CREATE TABLE knowledge_candidate (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    document_analysis_id INTEGER NOT NULL,
    document_id INTEGER NOT NULL,
    candidate_no INTEGER NOT NULL,

    title TEXT NOT NULL,
    candidate_type TEXT NOT NULL,
    summary TEXT NOT NULL,
    confidence REAL NOT NULL,
    rationale TEXT NOT NULL,

    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,

    FOREIGN KEY (document_analysis_id)
        REFERENCES document_analysis(id)
        ON DELETE CASCADE,

    FOREIGN KEY (document_id)
        REFERENCES document(id)
        ON DELETE CASCADE,

    UNIQUE(document_analysis_id, candidate_no)
);

CREATE INDEX idx_knowledge_candidate_document
    ON knowledge_candidate(document_id);

CREATE TABLE knowledge_candidate_evidence (
    knowledge_candidate_id INTEGER NOT NULL,
    source_chunk_id INTEGER NOT NULL,

    PRIMARY KEY (knowledge_candidate_id, source_chunk_id),

    FOREIGN KEY (knowledge_candidate_id)
        REFERENCES knowledge_candidate(id)
        ON DELETE CASCADE,

    FOREIGN KEY (source_chunk_id)
        REFERENCES source_chunk(id)
        ON DELETE CASCADE
);

CREATE INDEX idx_knowledge_candidate_evidence_source_chunk
    ON knowledge_candidate_evidence(source_chunk_id);
