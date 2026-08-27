package org.km.llmwiki.processing;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

@Repository
public class ProcessingJobRepository {

    private final JdbcClient jdbcClient;

    public ProcessingJobRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public ProcessingJob create(long workspaceId, String jobId, int totalCount) {
        String now = now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql("""
                        INSERT INTO processing_job (workspace_id, job_id, job_type, status, total_count, created_at, updated_at)
                        VALUES (:workspaceId, :jobId, 'ANALYZE', :status, :totalCount, :now, :now)
                        """)
                .paramSource(new MapSqlParameterSource()
                        .addValue("workspaceId", workspaceId).addValue("jobId", jobId)
                        .addValue("status", ProcessingJobStatus.QUEUED.name()).addValue("totalCount", totalCount)
                        .addValue("now", now))
                .update(keyHolder);
        Number id = keyHolder.getKey();
        if (id == null) {
            throw new IllegalStateException("Processing job insert did not return a generated id");
        }
        return new ProcessingJob(id.longValue(), jobId, totalCount);
    }

    public void markRunning(long jobId) {
        jdbcClient.sql("""
                        UPDATE processing_job SET status = :status, started_at = :now, updated_at = :now
                        WHERE id = :id
                        """)
                .param("status", ProcessingJobStatus.RUNNING.name()).param("now", now()).param("id", jobId).update();
    }

    public void markCompleted(long jobId) {
        String now = now();
        jdbcClient.sql("""
                        UPDATE processing_job
                        SET status = :status,
                            processed_count = (SELECT COUNT(*) FROM processing_job_item WHERE job_id = :id
                                               AND status IN ('SUCCEEDED', 'FAILED', 'SKIPPED')),
                            success_count = (SELECT COUNT(*) FROM processing_job_item WHERE job_id = :id AND status = 'SUCCEEDED'),
                            failed_count = (SELECT COUNT(*) FROM processing_job_item WHERE job_id = :id AND status = 'FAILED'),
                            skipped_count = (SELECT COUNT(*) FROM processing_job_item WHERE job_id = :id AND status = 'SKIPPED'),
                            finished_at = :now, updated_at = :now
                        WHERE id = :id
                        """)
                .param("status", ProcessingJobStatus.COMPLETED.name()).param("id", jobId).param("now", now).update();
    }

    private static String now() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }
}
