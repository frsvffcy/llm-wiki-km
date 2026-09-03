-- Generic immutable operation metadata captured when a processing job is created.
-- Feature-specific codecs own the schema and validation of the JSON value.
ALTER TABLE processing_job
    ADD COLUMN operation_metadata_json TEXT;
