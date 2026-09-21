package org.km.llmwiki.processing;

import org.jooq.DSLContext;
import org.km.llmwiki.source.DocumentAnalysisTarget;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import static org.jooq.impl.DSL.coalesce;
import static org.km.llmwiki.persistence.jooq.generated.Tables.PROCESSING_JOB;
import static org.km.llmwiki.persistence.jooq.generated.Tables.PROCESSING_JOB_ITEM;

@Repository
public class ProcessingJobItemRepository {

    private final DSLContext dsl;

    public ProcessingJobItemRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public ProcessingJobItem create(long jobId, DocumentAnalysisTarget document) {
        long id = create(jobId, document.documentId(), "ANALYZE");
        return new ProcessingJobItem(id, document);
    }

    public long create(long jobId, long documentId, String step) {
        Integer id = dsl.insertInto(PROCESSING_JOB_ITEM)
                .columns(
                        PROCESSING_JOB_ITEM.JOB_ID,
                        PROCESSING_JOB_ITEM.DOCUMENT_ID,
                        PROCESSING_JOB_ITEM.STATUS,
                        PROCESSING_JOB_ITEM.CURRENT_STEP
                )
                .values(
                        Math.toIntExact(jobId),
                        Math.toIntExact(documentId),
                        ProcessingJobItemStatus.QUEUED.name(),
                        step
                )
                .returningResult(PROCESSING_JOB_ITEM.ID)
                .fetchOne(PROCESSING_JOB_ITEM.ID);
        if (id == null) {
            throw new IllegalStateException("Processing job item insert did not return a generated id");
        }
        return id.longValue();
    }

    public void markRunning(long itemId) {
        markRunning(itemId, "ANALYZE");
    }

    public void markRunning(long itemId, String step) {
        String now = now();
        dsl.update(PROCESSING_JOB_ITEM)
                .set(PROCESSING_JOB_ITEM.STATUS, ProcessingJobItemStatus.RUNNING.name())
                .set(PROCESSING_JOB_ITEM.CURRENT_STEP, step)
                .set(PROCESSING_JOB_ITEM.STARTED_AT, coalesce(PROCESSING_JOB_ITEM.STARTED_AT, now))
                .where(PROCESSING_JOB_ITEM.ID.eq(Math.toIntExact(itemId)))
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
                .where(PROCESSING_JOB_ITEM.ID.eq(Math.toIntExact(itemId)))
                .execute();
    }

    public void markFinished(long itemId, ProcessingJobItemStatus status, String errorCode,
                             String errorMessage, boolean retryEligible) {
        markFinished(itemId, status, "ANALYZE", errorCode, errorMessage, retryEligible);
    }

    public void markFinished(long itemId, ProcessingJobItemStatus status, String step,
                             String errorCode, String errorMessage, boolean retryEligible) {
        String now = now();
        dsl.update(PROCESSING_JOB_ITEM)
                .set(PROCESSING_JOB_ITEM.STATUS, status.name())
                .set(PROCESSING_JOB_ITEM.CURRENT_STEP, step)
                .set(PROCESSING_JOB_ITEM.FINISHED_AT, now)
                .set(PROCESSING_JOB_ITEM.ERROR_CODE, errorCode)
                .set(PROCESSING_JOB_ITEM.ERROR_MESSAGE, errorMessage)
                .set(PROCESSING_JOB_ITEM.RETRY_ELIGIBLE, retryEligible ? 1 : 0)
                .where(PROCESSING_JOB_ITEM.ID.eq(Math.toIntExact(itemId)))
                .execute();
    }

    public Optional<ProcessingJobItemState> findActiveIngestState(long workspaceId, long documentId) {
        return dsl.select(
                        PROCESSING_JOB_ITEM.STATUS,
                        PROCESSING_JOB_ITEM.CURRENT_STEP,
                        PROCESSING_JOB_ITEM.ERROR_CODE,
                        PROCESSING_JOB_ITEM.ERROR_MESSAGE)
                .from(PROCESSING_JOB_ITEM)
                .join(PROCESSING_JOB)
                .on(PROCESSING_JOB.ID.eq(PROCESSING_JOB_ITEM.JOB_ID))
                .where(PROCESSING_JOB.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(PROCESSING_JOB.JOB_TYPE.eq(ProcessingJobType.INGEST.name()))
                .and(PROCESSING_JOB.STATUS.in(
                        ProcessingJobStatus.QUEUED.name(), ProcessingJobStatus.RUNNING.name()))
                .and(PROCESSING_JOB_ITEM.DOCUMENT_ID.eq(Math.toIntExact(documentId)))
                .and(PROCESSING_JOB_ITEM.STATUS.in(
                        ProcessingJobItemStatus.QUEUED.name(), ProcessingJobItemStatus.RUNNING.name()))
                .orderBy(PROCESSING_JOB_ITEM.ID.desc())
                .limit(1)
                .fetchOptional(record -> new ProcessingJobItemState(
                        ProcessingJobItemStatus.valueOf(record.get(PROCESSING_JOB_ITEM.STATUS)),
                        record.get(PROCESSING_JOB_ITEM.CURRENT_STEP),
                        record.get(PROCESSING_JOB_ITEM.ERROR_CODE),
                        record.get(PROCESSING_JOB_ITEM.ERROR_MESSAGE)));
    }

    public int markInterruptedIngest(List<Long> jobIds, String failureDetail) {
        if (jobIds.isEmpty()) {
            return 0;
        }
        String now = now();
        return dsl.update(PROCESSING_JOB_ITEM)
                .set(PROCESSING_JOB_ITEM.STATUS, ProcessingJobItemStatus.FAILED.name())
                .set(PROCESSING_JOB_ITEM.CURRENT_STEP, "INGEST")
                .set(PROCESSING_JOB_ITEM.ERROR_CODE, "APPLICATION_RESTART")
                .set(PROCESSING_JOB_ITEM.ERROR_MESSAGE, failureDetail)
                .set(PROCESSING_JOB_ITEM.FINISHED_AT, now)
                .where(PROCESSING_JOB_ITEM.JOB_ID.in(jobIds.stream().map(Math::toIntExact).toList()))
                .and(PROCESSING_JOB_ITEM.STATUS.in(
                        ProcessingJobItemStatus.QUEUED.name(), ProcessingJobItemStatus.RUNNING.name()))
                .execute();
    }

    private static String now() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }
}
