ALTER TABLE source_chunk
    ADD COLUMN chunk_policy_version TEXT NOT NULL DEFAULT 'chunk-policy-v1-current';

CREATE INDEX idx_source_chunk_policy_version
    ON source_chunk(chunk_policy_version);
