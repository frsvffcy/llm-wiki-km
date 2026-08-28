package org.km.llmwiki.processing;

import org.jooq.DSLContext;
import org.km.llmwiki.source.DocumentAnalysisTarget;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

import static org.jooq.impl.DSL.coalesce;
import static org.km.llmwiki.persistence.jooq.generated.Tables.PROCESSING_JOB_ITEM;

@Repository
public class ProcessingJobItemRepository {

    private final DSLContext dsl;

    public ProcessingJobItemRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public ProcessingJobItem create(long jobId, DocumentAnalysisTarget document) {
        Integer id = dsl.insertInto(PROCESSING_JOB_ITEM)
                .columns(
                        PROCESSING_JOB_ITEM.JOB_ID,
                        PROCESSING_JOB_ITEM.DOCUMENT_ID,
                        PROCESSING_JOB_ITEM.STATUS,
                        PROCESSING_JOB_ITEM.CURRENT_STEP
                )
                .values(
                        (int) jobId,
                        (int) document.documentId(),
                        ProcessingJobItemStatus.QUEUED.name(),
                        "ANALYZE"
                )
                .returningResult(PROCESSING_JOB_ITEM.ID)
                .fetchOne(PROCESSING_JOB_ITEM.ID);

        if (id == null) {
            throw new IllegalStateException("Processing job item insert did not return a generated id");
        }
        return new ProcessingJobItem(id.longValue(), document);
    }

    public void markRunning(long itemId) {
        String now = now();
        dsl.update(PROCESSING_JOB_ITEM)
                .set(PROCESSING_JOB_ITEM.STATUS, ProcessingJobItemStatus.RUNNING.name())
                .set(PROCESSING_JOB_ITEM.CURRENT_STEP, "ANALYZE")
                .set(PROCESSING_JOB_ITEM.STARTED_AT, coalesce(PROCESSING_JOB_ITEM.STARTED_AT, now))
                .where(PROCESSING_JOB_ITEM.ID.eq((int) itemId))
                .execute();
    }

    public void markForRetry(long itemId, int retryCount, String errorCode, String errorMessage) {
        dsl.update(PROCESSING_JOB_ITEM)
                .set(PROCESSING_JOB_ITEM.STATUS, ProcessingJobItemStatus.QUEUED.name())
                .set(PROCESSING_JOB_ITEM.CURRENT_STEP, "ANALYZE")
                .set(PROCESSING_JOB_ITEM.RETRY_COUNT, retryCount)
                .set(PROCESSING_JOB_ITEM.RETRY_ELIGIBLE, 1)
                .set(PROCESSING_JOB_ITEM.ERROR_CODE, errorCode)
                .set(PROCESSING_JOB_ITEM.ERROR_MESSAGE, errorMessage)
                .where(PROCESSING_JOB_ITEM.ID.eq((int) itemId))
                .execute();
    }

    public void markFinished(long itemId, ProcessingJobItemStatus status, String errorCode, String errorMessage,
                             boolean retryEligible) {
        String now = now();
        dsl.update(PROCESSING_JOB_ITEM)
                .set(PROCESSING_JOB_ITEM.STATUS, status.name())
                .set(PROCESSING_JOB_ITEM.CURRENT_STEP, "ANALYZE")
                .set(PROCESSING_JOB_ITEM.FINISHED_AT, now)
                .set(PROCESSING_JOB_ITEM.ERROR_CODE, errorCode)
                .set(PROCESSING_JOB_ITEM.ERROR_MESSAGE, errorMessage)
                .set(PROCESSING_JOB_ITEM.RETRY_ELIGIBLE, retryEligible ? 1 : 0)
                .where(PROCESSING_JOB_ITEM.ID.eq((int) itemId))
                .execute();
    }

    private static String now() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }
}
