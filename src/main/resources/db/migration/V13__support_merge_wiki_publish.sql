CREATE TABLE wiki_publish_operation_v13 (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    workspace_id INTEGER NOT NULL,
    draft_id INTEGER NOT NULL,
    proposal_id INTEGER NOT NULL,
    action TEXT NOT NULL CHECK (action IN ('CREATE', 'MERGE')),
    knowledge_id TEXT NOT NULL,
    target_path TEXT NOT NULL,
    before_content_hash TEXT,
    content_hash TEXT NOT NULL,
    revision INTEGER NOT NULL CHECK (revision >= 1),
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
    CHECK (
        (action = 'CREATE' AND before_content_hash IS NULL AND revision = 1)
        OR
        (action = 'MERGE' AND before_content_hash IS NOT NULL AND revision >= 2)
    ),
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

INSERT INTO wiki_publish_operation_v13 (
    id, workspace_id, draft_id, proposal_id, action, knowledge_id, target_path,
    before_content_hash, content_hash, revision, status, knowledge_page_id,
    failure_detail, created_at, updated_at, completed_at
)
SELECT id, workspace_id, draft_id, proposal_id, action, knowledge_id, target_path,
       NULL, content_hash, revision, status, knowledge_page_id,
       failure_detail, created_at, updated_at, completed_at
FROM wiki_publish_operation;

DROP TABLE wiki_publish_operation;
ALTER TABLE wiki_publish_operation_v13 RENAME TO wiki_publish_operation;

CREATE INDEX idx_wiki_publish_operation_status
    ON wiki_publish_operation(workspace_id, status, id);

CREATE INDEX idx_wiki_publish_operation_target
    ON wiki_publish_operation(workspace_id, target_path, id);

CREATE UNIQUE INDEX uq_wiki_publish_operation_in_flight_target
    ON wiki_publish_operation(workspace_id, target_path)
    WHERE status IN ('PREPARED', 'FILE_COMMITTED');
