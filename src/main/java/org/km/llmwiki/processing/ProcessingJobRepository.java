package org.km.llmwiki.processing;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.select;
import static org.km.llmwiki.persistence.jooq.generated.Tables.PROCESSING_JOB;
import static org.km.llmwiki.persistence.jooq.generated.Tables.PROCESSING_JOB_ITEM;

@Repository
public class ProcessingJobRepository {

    private final DSLContext dsl;

    public ProcessingJobRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public ProcessingJob create(long workspaceId, String jobId, int totalCount) {
        return create(workspaceId, jobId, ProcessingJobType.ANALYZE, totalCount);
    }

    public ProcessingJob create(long workspaceId, String jobId, ProcessingJobType jobType,
                                int totalCount) {
        String now = now();
        Integer id = dsl.insertInto(PROCESSING_JOB)
                .columns(
                        PROCESSING_JOB.WORKSPACE_ID,
                        PROCESSING_JOB.JOB_ID,
                        PROCESSING_JOB.JOB_TYPE,
                        PROCESSING_JOB.STATUS,
                        PROCESSING_JOB.TOTAL_COUNT,
                        PROCESSING_JOB.CREATED_AT,
                        PROCESSING_JOB.UPDATED_AT
                )
                .values(
                        (int) workspaceId,
                        jobId,
                        jobType.name(),
                        ProcessingJobStatus.QUEUED.name(),
                        totalCount,
                        now,
                        now
                )
                .returningResult(PROCESSING_JOB.ID)
                .fetchOne(PROCESSING_JOB.ID);

        if (id == null) {
            throw new IllegalStateException("Processing job insert did not return a generated id");
        }
        return new ProcessingJob(id.longValue(), jobId, totalCount);
    }

    public void markRunning(long jobId) {
        String now = now();
        dsl.update(PROCESSING_JOB)
                .set(PROCESSING_JOB.STATUS, ProcessingJobStatus.RUNNING.name())
                .set(PROCESSING_JOB.STARTED_AT, now)
                .set(PROCESSING_JOB.UPDATED_AT, now)
                .where(PROCESSING_JOB.ID.eq((int) jobId))
                .execute();
    }

    public void markCompleted(long jobId) {
        String now = now();
        int intJobId = (int) jobId;

        dsl.update(PROCESSING_JOB)
                .set(PROCESSING_JOB.STATUS, ProcessingJobStatus.COMPLETED.name())
                .set(
                        PROCESSING_JOB.PROCESSED_COUNT,
                        select(count())
                                .from(PROCESSING_JOB_ITEM)
                                .where(PROCESSING_JOB_ITEM.JOB_ID.eq(intJobId))
                                .and(PROCESSING_JOB_ITEM.STATUS.in("SUCCEEDED", "FAILED", "SKIPPED"))
                )
                .set(
                        PROCESSING_JOB.SUCCESS_COUNT,
                        select(count())
                                .from(PROCESSING_JOB_ITEM)
                                .where(PROCESSING_JOB_ITEM.JOB_ID.eq(intJobId))
                                .and(PROCESSING_JOB_ITEM.STATUS.eq("SUCCEEDED"))
                )
                .set(
                        PROCESSING_JOB.FAILED_COUNT,
                        select(count())
                                .from(PROCESSING_JOB_ITEM)
                                .where(PROCESSING_JOB_ITEM.JOB_ID.eq(intJobId))
                                .and(PROCESSING_JOB_ITEM.STATUS.eq("FAILED"))
                )
                .set(
                        PROCESSING_JOB.SKIPPED_COUNT,
                        select(count())
                                .from(PROCESSING_JOB_ITEM)
                                .where(PROCESSING_JOB_ITEM.JOB_ID.eq(intJobId))
                                .and(PROCESSING_JOB_ITEM.STATUS.eq("SKIPPED"))
                )
                .set(PROCESSING_JOB.FINISHED_AT, now)
                .set(PROCESSING_JOB.UPDATED_AT, now)
                .where(PROCESSING_JOB.ID.eq(intJobId))
                .execute();
    }

    public void markCompleted(long jobId, int processedCount, int successCount,
                              int failedCount, int skippedCount) {
        String now = now();
        dsl.update(PROCESSING_JOB)
                .set(PROCESSING_JOB.STATUS, ProcessingJobStatus.COMPLETED.name())
                .set(PROCESSING_JOB.PROCESSED_COUNT, processedCount)
                .set(PROCESSING_JOB.SUCCESS_COUNT, successCount)
                .set(PROCESSING_JOB.FAILED_COUNT, failedCount)
                .set(PROCESSING_JOB.SKIPPED_COUNT, skippedCount)
                .set(PROCESSING_JOB.FINISHED_AT, now)
                .set(PROCESSING_JOB.UPDATED_AT, now)
                .where(PROCESSING_JOB.ID.eq(Math.toIntExact(jobId)))
                .execute();
    }

    public void markFailed(long jobId, int processedCount, int successCount,
                           int failedCount, String failureDetail) {
        String now = now();
        dsl.update(PROCESSING_JOB)
                .set(PROCESSING_JOB.STATUS, ProcessingJobStatus.FAILED.name())
                .set(PROCESSING_JOB.PROCESSED_COUNT, processedCount)
                .set(PROCESSING_JOB.SUCCESS_COUNT, successCount)
                .set(PROCESSING_JOB.FAILED_COUNT, failedCount)
                .set(PROCESSING_JOB.FINISHED_AT, now)
                .set(PROCESSING_JOB.UPDATED_AT, now)
                .where(PROCESSING_JOB.ID.eq(Math.toIntExact(jobId)))
                .execute();
    }

    public List<Long> findInterruptedIds(List<Long> linkedJobIds) {
        Condition interrupted = PROCESSING_JOB.STATUS.in(
                ProcessingJobStatus.QUEUED.name(), ProcessingJobStatus.RUNNING.name());
        if (!linkedJobIds.isEmpty()) {
            interrupted = interrupted.or(PROCESSING_JOB.ID.in(
                    linkedJobIds.stream().map(Math::toIntExact).toList()));
        }
        return dsl.select(PROCESSING_JOB.ID)
                .from(PROCESSING_JOB)
                .where(PROCESSING_JOB.JOB_TYPE.eq(ProcessingJobType.FTS_REBUILD.name()))
                .and(PROCESSING_JOB.STATUS.ne(ProcessingJobStatus.FAILED.name()))
                .and(interrupted)
                .orderBy(PROCESSING_JOB.ID.asc())
                .fetch(PROCESSING_JOB.ID)
                .stream().map(Integer::longValue).toList();
    }

    public List<Long> findInterruptedEmbeddingIds() {
        return dsl.select(PROCESSING_JOB.ID).from(PROCESSING_JOB)
                .where(PROCESSING_JOB.JOB_TYPE.eq(ProcessingJobType.EMBEDDING_REBUILD.name()))
                .and(PROCESSING_JOB.STATUS.in(ProcessingJobStatus.QUEUED.name(), ProcessingJobStatus.RUNNING.name()))
                .orderBy(PROCESSING_JOB.ID.asc()).fetch(PROCESSING_JOB.ID).stream().map(Integer::longValue).toList();
    }

    public int markInterrupted(List<Long> jobIds) {
        if (jobIds.isEmpty()) {
            return 0;
        }
        String now = now();
        return dsl.update(PROCESSING_JOB)
                .set(PROCESSING_JOB.STATUS, ProcessingJobStatus.FAILED.name())
                .set(PROCESSING_JOB.FAILED_COUNT, 1)
                .set(PROCESSING_JOB.FINISHED_AT, now)
                .set(PROCESSING_JOB.UPDATED_AT, now)
                .where(PROCESSING_JOB.ID.in(jobIds.stream().map(Math::toIntExact).toList()))
                .and(PROCESSING_JOB.JOB_TYPE.eq(ProcessingJobType.FTS_REBUILD.name()))
                .and(PROCESSING_JOB.STATUS.ne(ProcessingJobStatus.FAILED.name()))
                .execute();
    }

    public int markInterruptedEmbedding(List<Long> jobIds) {
        if (jobIds.isEmpty()) return 0;
        String now = now();
        return dsl.update(PROCESSING_JOB).set(PROCESSING_JOB.STATUS, ProcessingJobStatus.FAILED.name())
                .set(PROCESSING_JOB.FAILED_COUNT, 1).set(PROCESSING_JOB.FINISHED_AT, now)
                .set(PROCESSING_JOB.UPDATED_AT, now)
                .where(PROCESSING_JOB.ID.in(jobIds.stream().map(Math::toIntExact).toList()))
                .and(PROCESSING_JOB.JOB_TYPE.eq(ProcessingJobType.EMBEDDING_REBUILD.name()))
                .execute();
    }

    private static String now() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }
}
