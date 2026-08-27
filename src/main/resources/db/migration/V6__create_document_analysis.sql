CREATE TABLE document_analysis (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    job_item_id INTEGER NOT NULL,
    document_id INTEGER NOT NULL,

    status TEXT NOT NULL,

    prompt_identifier TEXT,
    prompt_version TEXT,
    prompt_content_hash TEXT,

    provider TEXT,
    model TEXT,
    contract_version TEXT,

    result_json TEXT,

    error_code TEXT,
    error_message TEXT,

    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,

    FOREIGN KEY (job_item_id)
        REFERENCES processing_job_item(id)
        ON DELETE CASCADE,

    FOREIGN KEY (document_id)
        REFERENCES document(id),

    UNIQUE(job_item_id)
);

CREATE INDEX idx_document_analysis_document
    ON document_analysis(document_id);
