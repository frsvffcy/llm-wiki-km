CREATE TABLE wiki_draft (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    workspace_id INTEGER NOT NULL,
    proposal_id INTEGER NOT NULL,
    action TEXT NOT NULL CHECK (action IN ('CREATE', 'MERGE')),
    page_type TEXT NOT NULL CHECK (page_type IN (
        'CONCEPT', 'TECHNOLOGY', 'TROUBLESHOOTING', 'DECISION', 'PROJECT',
        'REFERENCE', 'HOWTO', 'PERSON', 'ORGANIZATION'
    )),
    title TEXT NOT NULL,
    target_title TEXT NOT NULL,
    target_page_type TEXT NOT NULL CHECK (target_page_type IN (
        'CONCEPT', 'TECHNOLOGY', 'TROUBLESHOOTING', 'DECISION', 'PROJECT',
        'REFERENCE', 'HOWTO', 'PERSON', 'ORGANIZATION'
    )),
    target_knowledge_id TEXT,
    target_path TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('DRAFT', 'READY', 'PUBLISHED', 'INVALIDATED')),
    expected_content_hash TEXT,
    base_content_hash TEXT NOT NULL,
    rendered_content_hash TEXT NOT NULL,
    input_hash TEXT NOT NULL,
    structured_draft_json TEXT NOT NULL,
    base_content TEXT NOT NULL,
    rendered_content TEXT NOT NULL,
    invalidated_reason TEXT CHECK (invalidated_reason IS NULL OR invalidated_reason IN (
        'MANUAL', 'SUPERSEDED_BY_REGENERATION', 'SOURCE_PROPOSAL_INVALID', 'TARGET_CHANGED'
    )),
    regenerated_from_draft_id INTEGER,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,

    FOREIGN KEY (workspace_id) REFERENCES workspace(id),
    FOREIGN KEY (proposal_id) REFERENCES knowledge_proposal(id),
    FOREIGN KEY (regenerated_from_draft_id) REFERENCES wiki_draft(id),
    CHECK (
        (action = 'CREATE' AND target_knowledge_id IS NULL AND expected_content_hash IS NULL)
        OR
        (action = 'MERGE' AND target_knowledge_id IS NOT NULL AND expected_content_hash IS NOT NULL)
    ),
    CHECK (
        (status = 'INVALIDATED' AND invalidated_reason IS NOT NULL)
        OR
        (status <> 'INVALIDATED' AND invalidated_reason IS NULL)
    )
);

CREATE INDEX idx_wiki_draft_workspace_proposal
    ON wiki_draft(workspace_id, proposal_id, id);

CREATE INDEX idx_wiki_draft_workspace_status
    ON wiki_draft(workspace_id, status);
