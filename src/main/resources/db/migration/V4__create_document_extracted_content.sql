CREATE TABLE document_extracted_content (
    document_id INTEGER PRIMARY KEY,

    content TEXT NOT NULL,
    chunk_count INTEGER NOT NULL,

    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,

    FOREIGN KEY (document_id)
        REFERENCES document(id)
        ON DELETE CASCADE
);
