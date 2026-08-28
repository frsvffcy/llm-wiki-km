CREATE TABLE knowledge_page (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    workspace_id INTEGER NOT NULL,
    knowledge_id TEXT NOT NULL,
    title TEXT NOT NULL,
    normalized_title TEXT NOT NULL,
    type TEXT NOT NULL CHECK (type IN (
        'CONCEPT', 'TECHNOLOGY', 'TROUBLESHOOTING', 'DECISION', 'PROJECT',
        'REFERENCE', 'HOWTO', 'PERSON', 'ORGANIZATION'
    )),
    markdown_path TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED', 'DELETED')),
    content_hash TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,

    FOREIGN KEY (workspace_id) REFERENCES workspace(id),
    UNIQUE (workspace_id, knowledge_id),
    UNIQUE (workspace_id, markdown_path)
);

CREATE INDEX idx_knowledge_page_title
    ON knowledge_page(workspace_id, normalized_title);

CREATE INDEX idx_knowledge_page_status
    ON knowledge_page(workspace_id, status);
