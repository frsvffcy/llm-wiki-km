package org.km.llmwiki.processing;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

import static org.km.llmwiki.persistence.jooq.generated.Tables.PROCESSING_LOG;

@Repository
public class ProcessingLogRepository {

    private final DSLContext dsl;

    public ProcessingLogRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void append(long jobId, Long jobItemId, Long documentId, ProcessingJobItemStatus status,
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
                        "ANALYZE",
                        status.name(),
                        message,
                        metadataJson,
                        now
                )
                .execute();
    }
}
