CREATE TABLE source_chunk (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    document_id INTEGER NOT NULL,
    chunk_no INTEGER NOT NULL,

    page_no INTEGER,
    section TEXT,
    heading_path TEXT,

    content TEXT NOT NULL,
    normalized_content TEXT NOT NULL,
    content_hash TEXT NOT NULL,

    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,

    FOREIGN KEY (document_id)
        REFERENCES document(id)
        ON DELETE CASCADE,

    UNIQUE(document_id, chunk_no)
);

CREATE INDEX idx_source_chunk_document_chunk_no
    ON source_chunk(document_id, chunk_no);
