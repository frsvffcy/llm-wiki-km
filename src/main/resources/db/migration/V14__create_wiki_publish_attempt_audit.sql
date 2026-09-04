CREATE TABLE wiki_publish_attempt (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    workspace_id INTEGER NOT NULL,
    draft_id INTEGER NOT NULL,
    proposal_id INTEGER NOT NULL,
    operation_id INTEGER,
    action TEXT NOT NULL CHECK (action IN ('CREATE', 'MERGE')),
    idempotency_key TEXT NOT NULL,
    target_path TEXT NOT NULL,
    before_content_hash TEXT,
    after_content_hash TEXT,
    revision INTEGER,
    result TEXT CHECK (result IN ('PUBLISHED', 'CONFLICT', 'FAILED', 'NO_OP')),
    failure_category TEXT CHECK (failure_category IN (
        'VALIDATION', 'CONFLICT', 'FILESYSTEM', 'DATABASE', 'RECONCILIATION'
    )),
    failure_code TEXT,
    failure_stage TEXT CHECK (failure_stage IN (
        'VALIDATION', 'TARGET_CHECK', 'OPERATION_RESERVATION', 'FILESYSTEM',
        'DATABASE_FINALIZATION', 'RECONCILIATION'
    )),
    error_detail TEXT,
    started_at TEXT NOT NULL,
    finished_at TEXT,

    FOREIGN KEY (workspace_id) REFERENCES workspace(id),
    FOREIGN KEY (draft_id) REFERENCES wiki_draft(id),
    FOREIGN KEY (proposal_id) REFERENCES knowledge_proposal(id),
    FOREIGN KEY (operation_id) REFERENCES wiki_publish_operation(id),
    CHECK (
        (result IS NULL AND finished_at IS NULL AND failure_category IS NULL
            AND failure_code IS NULL AND failure_stage IS NULL AND error_detail IS NULL)
        OR
        (result IN ('PUBLISHED', 'NO_OP') AND finished_at IS NOT NULL
            AND failure_category IS NULL AND failure_code IS NULL
            AND failure_stage IS NULL AND error_detail IS NULL)
        OR
        (result IN ('CONFLICT', 'FAILED') AND finished_at IS NOT NULL
            AND failure_category IS NOT NULL AND failure_code IS NOT NULL
            AND failure_stage IS NOT NULL AND error_detail IS NOT NULL)
    )
);

CREATE INDEX idx_wiki_publish_attempt_draft
    ON wiki_publish_attempt(workspace_id, draft_id, id);

CREATE INDEX idx_wiki_publish_attempt_result
    ON wiki_publish_attempt(workspace_id, result, failure_category, id);

CREATE INDEX idx_wiki_publish_attempt_idempotency
    ON wiki_publish_attempt(workspace_id, idempotency_key, id);
