package org.km.llmwiki.processing;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

@Repository
public class ProcessingLogRepository {

    private final JdbcClient jdbcClient;

    public ProcessingLogRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void append(long jobId, Long jobItemId, Long documentId, ProcessingJobItemStatus status,
                       String message, String metadataJson) {
        jdbcClient.sql("""
                        INSERT INTO processing_log (job_id, job_item_id, document_id, step, status, message, metadata_json, created_at)
                        VALUES (:jobId, :jobItemId, :documentId, 'ANALYZE', :status, :message, :metadataJson, :now)
                        """).param("jobId", jobId).param("jobItemId", jobItemId).param("documentId", documentId)
                .param("status", status.name()).param("message", message).param("metadataJson", metadataJson)
                .param("now", DateTimeFormatter.ISO_INSTANT.format(Instant.now())).update();
    }
}
