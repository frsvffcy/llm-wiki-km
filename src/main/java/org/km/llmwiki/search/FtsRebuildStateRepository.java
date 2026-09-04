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

    public void markQueued(long workspaceId, long processingJobId, List<SearchCorpus> corpora) {
        String now = now();
        for (SearchCorpus corpus : corpora) {
            dsl.insertInto(SEARCH_INDEX_REBUILD_STATE)
                    .columns(SEARCH_INDEX_REBUILD_STATE.WORKSPACE_ID,
                            SEARCH_INDEX_REBUILD_STATE.CORPUS,
                            SEARCH_INDEX_REBUILD_STATE.STATUS,
                            SEARCH_INDEX_REBUILD_STATE.PROCESSING_JOB_ID,
                            SEARCH_INDEX_REBUILD_STATE.INDEXED_COUNT,
                            SEARCH_INDEX_REBUILD_STATE.FAILED_COUNT,
                            SEARCH_INDEX_REBUILD_STATE.PROJECTION_VERSION,
                            SEARCH_INDEX_REBUILD_STATE.FAILURE_DETAIL,
                            SEARCH_INDEX_REBUILD_STATE.STARTED_AT,
                            SEARCH_INDEX_REBUILD_STATE.COMPLETED_AT,
                            SEARCH_INDEX_REBUILD_STATE.UPDATED_AT)
                    .values(Math.toIntExact(workspaceId), corpus.name(), FtsRebuildStatus.QUEUED.name(),
                            Math.toIntExact(processingJobId), 0, 0, CjkBigramProjector.VERSION,
                            null, null, null, now)
                    .onConflict(SEARCH_INDEX_REBUILD_STATE.WORKSPACE_ID,
                            SEARCH_INDEX_REBUILD_STATE.CORPUS)
                    .doUpdate()
                    .set(SEARCH_INDEX_REBUILD_STATE.STATUS, FtsRebuildStatus.QUEUED.name())
                    .set(SEARCH_INDEX_REBUILD_STATE.PROCESSING_JOB_ID,
                            Math.toIntExact(processingJobId))
                    .set(SEARCH_INDEX_REBUILD_STATE.INDEXED_COUNT, 0)
                    .set(SEARCH_INDEX_REBUILD_STATE.FAILED_COUNT, 0)
                    .set(SEARCH_INDEX_REBUILD_STATE.PROJECTION_VERSION, CjkBigramProjector.VERSION)
                    .set(SEARCH_INDEX_REBUILD_STATE.FAILURE_DETAIL, (String) null)
                    .set(SEARCH_INDEX_REBUILD_STATE.STARTED_AT, (String) null)
                    .set(SEARCH_INDEX_REBUILD_STATE.COMPLETED_AT, (String) null)
                    .set(SEARCH_INDEX_REBUILD_STATE.UPDATED_AT, now)
                    .execute();
        }
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

    public boolean hasInProgress(long workspaceId, List<SearchCorpus> corpora) {
        return dsl.fetchExists(dsl.selectOne().from(SEARCH_INDEX_REBUILD_STATE)
                .where(SEARCH_INDEX_REBUILD_STATE.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(SEARCH_INDEX_REBUILD_STATE.CORPUS.in(corpora.stream().map(Enum::name).toList()))
                .and(SEARCH_INDEX_REBUILD_STATE.STATUS.in(
                        FtsRebuildStatus.QUEUED.name(), FtsRebuildStatus.RUNNING.name())));
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
