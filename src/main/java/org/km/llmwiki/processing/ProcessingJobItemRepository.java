package org.km.llmwiki.processing;

import org.km.llmwiki.source.DocumentAnalysisTarget;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

@Repository
public class ProcessingJobItemRepository {

    private final JdbcClient jdbcClient;

    public ProcessingJobItemRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public ProcessingJobItem create(long jobId, DocumentAnalysisTarget document) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql("""
                        INSERT INTO processing_job_item (job_id, document_id, status, current_step)
                        VALUES (:jobId, :documentId, :status, 'ANALYZE')
                        """)
                .paramSource(new MapSqlParameterSource().addValue("jobId", jobId)
                        .addValue("documentId", document.documentId())
                        .addValue("status", ProcessingJobItemStatus.QUEUED.name()))
                .update(keyHolder);
        Number id = keyHolder.getKey();
        if (id == null) {
            throw new IllegalStateException("Processing job item insert did not return a generated id");
        }
        return new ProcessingJobItem(id.longValue(), document);
    }

    public void markRunning(long itemId) {
        jdbcClient.sql("""
                        UPDATE processing_job_item SET status = :status, current_step = 'ANALYZE', started_at = :now
                        WHERE id = :id
                        """).param("status", ProcessingJobItemStatus.RUNNING.name()).param("now", now()).param("id", itemId).update();
    }

    public void markFinished(long itemId, ProcessingJobItemStatus status, String errorCode, String errorMessage) {
        jdbcClient.sql("""
                        UPDATE processing_job_item
                        SET status = :status, current_step = 'ANALYZE', finished_at = :now,
                            error_code = :errorCode, error_message = :errorMessage
                        WHERE id = :id
                        """).param("status", status.name()).param("now", now()).param("errorCode", errorCode)
                .param("errorMessage", errorMessage).param("id", itemId).update();
    }

    private static String now() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }
}
