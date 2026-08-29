ALTER TABLE knowledge_page ADD COLUMN revision INTEGER NOT NULL DEFAULT 1;
ALTER TABLE knowledge_page ADD COLUMN proposal_id INTEGER REFERENCES knowledge_proposal(id);
ALTER TABLE knowledge_page ADD COLUMN draft_id INTEGER REFERENCES wiki_draft(id);
ALTER TABLE knowledge_page ADD COLUMN published_at TEXT;

CREATE UNIQUE INDEX uq_knowledge_page_draft
    ON knowledge_page(workspace_id, draft_id)
    WHERE draft_id IS NOT NULL;

ALTER TABLE wiki_draft ADD COLUMN published_path TEXT;
ALTER TABLE wiki_draft ADD COLUMN published_content_hash TEXT;
ALTER TABLE wiki_draft ADD COLUMN published_revision INTEGER;
ALTER TABLE wiki_draft ADD COLUMN published_at TEXT;

CREATE TABLE wiki_publish_operation (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    workspace_id INTEGER NOT NULL,
    draft_id INTEGER NOT NULL,
    proposal_id INTEGER NOT NULL,
    action TEXT NOT NULL CHECK (action = 'CREATE'),
    knowledge_id TEXT NOT NULL,
    target_path TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    revision INTEGER NOT NULL CHECK (revision = 1),
    status TEXT NOT NULL CHECK (status IN (
        'PREPARED', 'FILE_COMMITTED', 'COMPLETED', 'ROLLED_BACK', 'RECONCILIATION_REQUIRED'
    )),
    knowledge_page_id INTEGER,
    failure_detail TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    completed_at TEXT,

    FOREIGN KEY (workspace_id) REFERENCES workspace(id),
    FOREIGN KEY (draft_id) REFERENCES wiki_draft(id),
    FOREIGN KEY (proposal_id) REFERENCES knowledge_proposal(id),
    FOREIGN KEY (knowledge_page_id) REFERENCES knowledge_page(id),
    UNIQUE (draft_id),
    UNIQUE (workspace_id, target_path),
    CHECK (
        (status = 'COMPLETED' AND knowledge_page_id IS NOT NULL AND completed_at IS NOT NULL
            AND failure_detail IS NULL)
        OR
        (status <> 'COMPLETED' AND knowledge_page_id IS NULL AND completed_at IS NULL)
    ),
    CHECK (
        (status IN ('ROLLED_BACK', 'RECONCILIATION_REQUIRED') AND failure_detail IS NOT NULL)
        OR
        (status NOT IN ('ROLLED_BACK', 'RECONCILIATION_REQUIRED') AND failure_detail IS NULL)
    )
);

CREATE INDEX idx_wiki_publish_operation_status
    ON wiki_publish_operation(workspace_id, status, id);
