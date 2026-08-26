CREATE TABLE workspace (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    name TEXT NOT NULL,

    root_path TEXT NOT NULL,
    inbox_path TEXT NOT NULL,
    archive_path TEXT NOT NULL,
    vault_path TEXT NOT NULL,
    data_path TEXT NOT NULL,
    config_path TEXT,

    status TEXT NOT NULL DEFAULT 'ACTIVE',

    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    last_opened_at TEXT,

    UNIQUE(root_path)
);

CREATE TABLE setting (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    workspace_id INTEGER,

    setting_group TEXT NOT NULL,
    setting_key TEXT NOT NULL,

    setting_value TEXT,

    value_type TEXT NOT NULL DEFAULT 'STRING',

    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,

    FOREIGN KEY (workspace_id)
        REFERENCES workspace(id),

    UNIQUE(workspace_id, setting_group, setting_key)
);

CREATE TABLE document (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    workspace_id INTEGER NOT NULL,

    file_name TEXT NOT NULL,
    original_file_name TEXT,

    extension TEXT,
    mime_type TEXT,

    source_path TEXT NOT NULL,
    archive_path TEXT,

    sha256 TEXT NOT NULL,
    file_size INTEGER,

    document_type TEXT,

    source_created_at TEXT,
    source_modified_at TEXT,

    status TEXT NOT NULL DEFAULT 'PENDING',

    parse_status TEXT,
    processing_status TEXT,

    duplicate_of_document_id INTEGER,
    parent_version_document_id INTEGER,

    extracted_text_hash TEXT,

    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    processed_at TEXT,
    archived_at TEXT,

    error_code TEXT,
    error_message TEXT,

    FOREIGN KEY (workspace_id)
        REFERENCES workspace(id),

    FOREIGN KEY (duplicate_of_document_id)
        REFERENCES document(id),

    FOREIGN KEY (parent_version_document_id)
        REFERENCES document(id)
);

CREATE INDEX idx_document_workspace
    ON document(workspace_id);

CREATE INDEX idx_document_status
    ON document(status);

CREATE INDEX idx_document_sha256
    ON document(sha256);

CREATE INDEX idx_document_source_path
    ON document(source_path);

CREATE INDEX idx_document_processed_at
    ON document(processed_at);

CREATE TABLE processing_job (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    workspace_id INTEGER NOT NULL,

    job_id TEXT NOT NULL,

    job_type TEXT NOT NULL,

    status TEXT NOT NULL DEFAULT 'QUEUED',

    total_count INTEGER NOT NULL DEFAULT 0,

    processed_count INTEGER NOT NULL DEFAULT 0,
    success_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,
    skipped_count INTEGER NOT NULL DEFAULT 0,

    estimated_tokens INTEGER,
    estimated_cost REAL,

    started_at TEXT,
    finished_at TEXT,

    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,

    FOREIGN KEY (workspace_id)
        REFERENCES workspace(id),

    UNIQUE(job_id)
);

CREATE TABLE processing_job_item (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    job_id INTEGER NOT NULL,
    document_id INTEGER NOT NULL,

    status TEXT NOT NULL DEFAULT 'QUEUED',

    current_step TEXT,

    retry_count INTEGER NOT NULL DEFAULT 0,

    started_at TEXT,
    finished_at TEXT,

    error_code TEXT,
    error_message TEXT,

    FOREIGN KEY (job_id)
        REFERENCES processing_job(id)
        ON DELETE CASCADE,

    FOREIGN KEY (document_id)
        REFERENCES document(id),

    UNIQUE(job_id, document_id)
);

CREATE INDEX idx_job_item_job
    ON processing_job_item(job_id);

CREATE INDEX idx_job_item_status
    ON processing_job_item(job_id, status);

CREATE TABLE processing_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    job_id INTEGER,
    job_item_id INTEGER,
    document_id INTEGER,

    step TEXT NOT NULL,

    status TEXT NOT NULL,

    message TEXT,

    duration_ms INTEGER,

    metadata_json TEXT,

    created_at TEXT NOT NULL,

    FOREIGN KEY (job_id)
        REFERENCES processing_job(id)
        ON DELETE CASCADE,

    FOREIGN KEY (job_item_id)
        REFERENCES processing_job_item(id)
        ON DELETE CASCADE,

    FOREIGN KEY (document_id)
        REFERENCES document(id)
);
