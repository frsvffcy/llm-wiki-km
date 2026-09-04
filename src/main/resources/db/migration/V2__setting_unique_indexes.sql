CREATE TABLE setting_duplicate_backup (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    workspace_id INTEGER,

    setting_group TEXT NOT NULL,
    setting_key TEXT NOT NULL,

    setting_value TEXT,

    value_type TEXT NOT NULL DEFAULT 'STRING',

    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

INSERT INTO setting_duplicate_backup
SELECT s.*
FROM setting s
WHERE s.workspace_id IS NULL
  AND EXISTS (
      SELECT 1 FROM setting t
      WHERE t.workspace_id IS NULL
        AND t.setting_group = s.setting_group
        AND t.setting_key = s.setting_key
        AND t.id < s.id
  );

DELETE FROM setting AS s
WHERE s.workspace_id IS NULL
  AND EXISTS (
      SELECT 1 FROM setting t
      WHERE t.workspace_id IS NULL
        AND t.setting_group = s.setting_group
        AND t.setting_key = s.setting_key
        AND t.id < s.id
  );

CREATE UNIQUE INDEX uq_setting_global_group_key
    ON setting (setting_group, setting_key)
    WHERE workspace_id IS NULL;

CREATE UNIQUE INDEX uq_setting_workspace_group_key
    ON setting (workspace_id, setting_group, setting_key)
    WHERE workspace_id IS NOT NULL;
