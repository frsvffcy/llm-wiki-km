package org.km.llmwiki.processing;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import static org.km.llmwiki.persistence.jooq.generated.Tables.PROCESSING_LOG;

@Repository
public class ProcessingLogRepository {

    private final DSLContext dsl;

    public ProcessingLogRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void append(long jobId, Long jobItemId, Long documentId, ProcessingJobItemStatus status,
                       String message, String metadataJson) {
        append(jobId, jobItemId, documentId, "ANALYZE", status.name(), message, metadataJson);
    }

    public void append(long jobId, Long jobItemId, Long documentId, String step, String status,
                       String message, String metadataJson) {
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        dsl.insertInto(PROCESSING_LOG)
                .columns(
                        PROCESSING_LOG.JOB_ID,
                        PROCESSING_LOG.JOB_ITEM_ID,
                        PROCESSING_LOG.DOCUMENT_ID,
                        PROCESSING_LOG.STEP,
                        PROCESSING_LOG.STATUS,
                        PROCESSING_LOG.MESSAGE,
                        PROCESSING_LOG.METADATA_JSON,
                        PROCESSING_LOG.CREATED_AT
                )
                .values(
                        (int) jobId,
                        jobItemId == null ? null : jobItemId.intValue(),
                        documentId == null ? null : documentId.intValue(),
                        step,
                        status,
                        message,
                        metadataJson,
                        now
                )
                .execute();
    }

    /** Returns only the metadata envelope; callers must apply an allow-list before exposing it. */
    public Optional<String> findLatestEmbeddingFailureMetadata(long jobId) {
        return dsl.select(PROCESSING_LOG.METADATA_JSON)
                .from(PROCESSING_LOG)
                .where(PROCESSING_LOG.JOB_ID.eq(Math.toIntExact(jobId)))
                .and(PROCESSING_LOG.STEP.eq("EMBEDDING_REBUILD"))
                .and(PROCESSING_LOG.STATUS.eq("FAILED"))
                .orderBy(PROCESSING_LOG.CREATED_AT.desc(), PROCESSING_LOG.ID.desc())
                .limit(1)
                .fetchOptional(PROCESSING_LOG.METADATA_JSON);
    }
}
