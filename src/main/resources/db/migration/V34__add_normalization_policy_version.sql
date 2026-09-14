-- #412：versioned selected-Cf normalization policy 的 lineage 辨識。
-- normalization 與 chunking 為正交維度，不得重用 chunk_policy_version 窗口混淆語意；
-- historical rows 以 v1 預設值保留 baseline 身份，僅新 extraction 寫入 active version，
-- 升級一律經既有 re-extraction 路徑（含 FTS/Embedding/Graph 既有 invalidation/rebuild）。

ALTER TABLE source_chunk
    ADD COLUMN normalization_policy_version TEXT NOT NULL DEFAULT 'normalization-policy-v1-current';

CREATE INDEX idx_source_chunk_normalization_policy_version
    ON source_chunk(normalization_policy_version);

ALTER TABLE document_extracted_content
    ADD COLUMN normalization_policy_version TEXT NOT NULL DEFAULT 'normalization-policy-v1-current';
