package org.km.llmwiki.search;

import org.jooq.DSLContext;
import org.km.llmwiki.persistence.jooq.generated.tables.records.SearchIndexRebuildStateRecord;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.km.llmwiki.persistence.jooq.generated.Tables.SEARCH_INDEX_REBUILD_STATE;

/** Durable, workspace-scoped health gate for the latest rebuild attempt of each corpus. */
@Repository
public class FtsRebuildStateRepository {

    private final DSLContext dsl;

    public FtsRebuildStateRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Atomically claims QUEUED ownership of the requested corpora for one admitted job. The
     * claim re-evaluates in-progress ownership inside the caller's write transaction: a
     * corpus whose row is QUEUED or RUNNING is never re-owned, and a missing row is created
     * only when no overlapping operation exists. The caller must run this inside the same
     * transaction that created the processing job and roll back entirely when the returned
     * count is smaller than the requested corpora.
     *
     * @return the number of corpora successfully claimed
     */
    public int claimQueued(long workspaceId, long processingJobId, List<SearchCorpus> corpora) {
        String now = now();
        int claimed = 0;
        for (SearchCorpus corpus : corpora) {
            int requeued = dsl.update(SEARCH_INDEX_REBUILD_STATE)
                    .set(SEARCH_INDEX_REBUILD_STATE.STATUS, FtsRebuildStatus.QUEUED.name())
                    .set(SEARCH_INDEX_REBUILD_STATE.PROCESSING_JOB_ID,
                            Math.toIntExact(processingJobId))
                    .set(SEARCH_INDEX_REBUILD_STATE.INDEXED_COUNT, 0)
                    .set(SEARCH_INDEX_REBUILD_STATE.FAILED_COUNT, 0)
                    .set(SEARCH_INDEX_REBUILD_STATE.FAILURE_DETAIL, (String) null)
                    .set(SEARCH_INDEX_REBUILD_STATE.STARTED_AT, (String) null)
                    .set(SEARCH_INDEX_REBUILD_STATE.COMPLETED_AT, (String) null)
                    .set(SEARCH_INDEX_REBUILD_STATE.UPDATED_AT, now)
                    .where(SEARCH_INDEX_REBUILD_STATE.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                    .and(SEARCH_INDEX_REBUILD_STATE.CORPUS.eq(corpus.name()))
                    .and(SEARCH_INDEX_REBUILD_STATE.STATUS.notIn(
                            FtsRebuildStatus.QUEUED.name(), FtsRebuildStatus.RUNNING.name()))
                    .execute();
            if (requeued > 0) {
                claimed++;
                continue;
            }
            claimed += dsl.execute("""
                    INSERT INTO search_index_rebuild_state
                        (workspace_id, corpus, status, processing_job_id, indexed_count,
                         failed_count, projection_version, updated_at)
                    SELECT {0}, {1}, 'QUEUED', {2}, 0, 0, {3}, {4}
                     WHERE NOT EXISTS (
                        SELECT 1 FROM search_index_rebuild_state
                         WHERE workspace_id = {0} AND corpus = {1}
                           AND status IN ('QUEUED', 'RUNNING'))
                    """, Math.toIntExact(workspaceId), corpus.name(),
                    Math.toIntExact(processingJobId), CjkBigramProjector.VERSION, now);
        }
        return claimed;
    }

    public void markRunning(long workspaceId, long processingJobId, List<SearchCorpus> corpora) {
        String now = now();
        dsl.update(SEARCH_INDEX_REBUILD_STATE)
                .set(SEARCH_INDEX_REBUILD_STATE.STATUS, FtsRebuildStatus.RUNNING.name())
                .set(SEARCH_INDEX_REBUILD_STATE.STARTED_AT, now)
                .set(SEARCH_INDEX_REBUILD_STATE.UPDATED_AT, now)
                .where(SEARCH_INDEX_REBUILD_STATE.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(SEARCH_INDEX_REBUILD_STATE.PROCESSING_JOB_ID.eq(Math.toIntExact(processingJobId)))
                .and(SEARCH_INDEX_REBUILD_STATE.CORPUS.in(corpora.stream().map(Enum::name).toList()))
                .execute();
    }

    public void markCompleted(long workspaceId, long processingJobId, SearchCorpus corpus,
                              int indexedCount) {
        String now = now();
        dsl.update(SEARCH_INDEX_REBUILD_STATE)
                .set(SEARCH_INDEX_REBUILD_STATE.STATUS, FtsRebuildStatus.COMPLETED.name())
                .set(SEARCH_INDEX_REBUILD_STATE.INDEXED_COUNT, indexedCount)
                .set(SEARCH_INDEX_REBUILD_STATE.FAILED_COUNT, 0)
                .set(SEARCH_INDEX_REBUILD_STATE.FAILURE_DETAIL, (String) null)
                .set(SEARCH_INDEX_REBUILD_STATE.COMPLETED_AT, now)
                .set(SEARCH_INDEX_REBUILD_STATE.UPDATED_AT, now)
                .where(SEARCH_INDEX_REBUILD_STATE.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(SEARCH_INDEX_REBUILD_STATE.PROCESSING_JOB_ID.eq(Math.toIntExact(processingJobId)))
                .and(SEARCH_INDEX_REBUILD_STATE.CORPUS.eq(corpus.name()))
                .execute();
    }

    public void markFailed(long workspaceId, long processingJobId, List<SearchCorpus> corpora,
                           String failureDetail) {
        String now = now();
        String detail = bounded(failureDetail);
        dsl.update(SEARCH_INDEX_REBUILD_STATE)
                .set(SEARCH_INDEX_REBUILD_STATE.STATUS, FtsRebuildStatus.FAILED.name())
                .set(SEARCH_INDEX_REBUILD_STATE.FAILED_COUNT, 1)
                .set(SEARCH_INDEX_REBUILD_STATE.FAILURE_DETAIL, detail)
                .set(SEARCH_INDEX_REBUILD_STATE.COMPLETED_AT, now)
                .set(SEARCH_INDEX_REBUILD_STATE.UPDATED_AT, now)
                .where(SEARCH_INDEX_REBUILD_STATE.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(SEARCH_INDEX_REBUILD_STATE.PROCESSING_JOB_ID.eq(Math.toIntExact(processingJobId)))
                .and(SEARCH_INDEX_REBUILD_STATE.CORPUS.in(corpora.stream().map(Enum::name).toList()))
                .execute();
    }

    public List<Long> findInProgressProcessingJobIds() {
        return dsl.selectDistinct(SEARCH_INDEX_REBUILD_STATE.PROCESSING_JOB_ID)
                .from(SEARCH_INDEX_REBUILD_STATE)
                .where(SEARCH_INDEX_REBUILD_STATE.STATUS.in(
                        FtsRebuildStatus.QUEUED.name(), FtsRebuildStatus.RUNNING.name()))
                .orderBy(SEARCH_INDEX_REBUILD_STATE.PROCESSING_JOB_ID.asc())
                .fetch(SEARCH_INDEX_REBUILD_STATE.PROCESSING_JOB_ID)
                .stream().map(Integer::longValue).toList();
    }

    public int markInterrupted(String failureDetail) {
        String now = now();
        return dsl.update(SEARCH_INDEX_REBUILD_STATE)
                .set(SEARCH_INDEX_REBUILD_STATE.STATUS, FtsRebuildStatus.FAILED.name())
                .set(SEARCH_INDEX_REBUILD_STATE.FAILED_COUNT, 1)
                .set(SEARCH_INDEX_REBUILD_STATE.FAILURE_DETAIL, bounded(failureDetail))
                .set(SEARCH_INDEX_REBUILD_STATE.COMPLETED_AT, now)
                .set(SEARCH_INDEX_REBUILD_STATE.UPDATED_AT, now)
                .where(SEARCH_INDEX_REBUILD_STATE.STATUS.in(
                        FtsRebuildStatus.QUEUED.name(), FtsRebuildStatus.RUNNING.name()))
                .execute();
    }

    public List<FtsRebuildState> findAll(long workspaceId) {
        return dsl.selectFrom(SEARCH_INDEX_REBUILD_STATE)
                .where(SEARCH_INDEX_REBUILD_STATE.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .orderBy(SEARCH_INDEX_REBUILD_STATE.CORPUS.asc())
                .fetch(this::map);
    }

    private FtsRebuildState map(SearchIndexRebuildStateRecord record) {
        return new FtsRebuildState(record.getWorkspaceId().longValue(),
                SearchCorpus.valueOf(record.getCorpus()), FtsRebuildStatus.valueOf(record.getStatus()),
                record.getProcessingJobId().longValue(), record.getIndexedCount(),
                record.getFailedCount(), record.getProjectionVersion(), record.getFailureDetail(),
                record.getStartedAt(), record.getCompletedAt(), record.getUpdatedAt());
    }

    private static String bounded(String detail) {
        String resolved = detail == null || detail.isBlank() ? "Unspecified FTS rebuild failure" : detail;
        return resolved.substring(0, Math.min(resolved.length(), 1000));
    }

    private static String now() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }
}
