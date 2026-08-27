ALTER TABLE processing_job_item ADD COLUMN retry_eligible INTEGER NOT NULL DEFAULT 0
    CHECK (retry_eligible IN (0, 1));
