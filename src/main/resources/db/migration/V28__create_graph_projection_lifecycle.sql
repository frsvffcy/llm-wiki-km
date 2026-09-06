CREATE TABLE graph_projection_lifecycle (
    workspace_id INTEGER PRIMARY KEY,

    provider TEXT NOT NULL CHECK (length(trim(provider)) > 0),
    projection_version TEXT NOT NULL CHECK (length(trim(projection_version)) > 0),
    status TEXT NOT NULL
        CHECK (status IN ('DISABLED', 'NOT_CONFIGURED', 'NOT_READY',
                          'BUILDING', 'REPAIRING', 'CLEARING',
                          'READY', 'FAILED', 'DEGRADED')),

    target_generation INTEGER NOT NULL DEFAULT 0
        CHECK (target_generation >= 0),
    applied_generation INTEGER NOT NULL DEFAULT 0
        CHECK (applied_generation >= 0 AND applied_generation <= target_generation),
    source_fingerprint TEXT,
    snapshot_token TEXT,

    operation_owner TEXT,
    operation_kind TEXT
        CHECK (operation_kind IS NULL OR operation_kind IN ('REBUILD', 'REPAIR', 'CLEAR')),
    operation_source_fingerprint TEXT,
    operation_started_at TEXT,

    last_failure_type TEXT,
    diagnostic_code TEXT,
    last_successful_at TEXT,
    updated_at TEXT NOT NULL,

    FOREIGN KEY (workspace_id) REFERENCES workspace(id),
    CHECK ((status = 'BUILDING' AND operation_kind = 'REBUILD'
            AND operation_owner IS NOT NULL
            AND operation_source_fingerprint IS NOT NULL
            AND operation_started_at IS NOT NULL)
        OR (status = 'REPAIRING' AND operation_kind = 'REPAIR'
            AND operation_owner IS NOT NULL
            AND operation_source_fingerprint IS NOT NULL
            AND operation_started_at IS NOT NULL)
        OR (status = 'CLEARING' AND operation_kind = 'CLEAR'
            AND operation_owner IS NOT NULL
            AND operation_source_fingerprint IS NOT NULL
            AND operation_started_at IS NOT NULL)
        OR (status NOT IN ('BUILDING', 'REPAIRING', 'CLEARING')
            AND operation_owner IS NULL AND operation_kind IS NULL
            AND operation_source_fingerprint IS NULL AND operation_started_at IS NULL)),
    CHECK (operation_owner IS NULL OR length(trim(operation_owner)) > 0),
    CHECK (operation_started_at IS NULL OR length(trim(operation_started_at)) > 0),
    CHECK (operation_source_fingerprint IS NULL
        OR (length(operation_source_fingerprint) = 64
            AND operation_source_fingerprint NOT GLOB '*[^0-9a-f]*')),
    CHECK (status <> 'READY' OR
           (applied_generation > 0 AND source_fingerprint IS NOT NULL
            AND snapshot_token IS NOT NULL)),
    CHECK (status <> 'DEGRADED' OR applied_generation > 0),
    CHECK (status <> 'FAILED' OR applied_generation = 0),
    CHECK ((applied_generation = 0 AND source_fingerprint IS NULL AND snapshot_token IS NULL)
        OR (applied_generation > 0
            AND length(source_fingerprint) = 64
            AND source_fingerprint NOT GLOB '*[^0-9a-f]*'
            AND length(snapshot_token) = 64
            AND snapshot_token NOT GLOB '*[^0-9a-f]*'))
);

CREATE INDEX idx_graph_projection_lifecycle_status
    ON graph_projection_lifecycle(status, updated_at);
