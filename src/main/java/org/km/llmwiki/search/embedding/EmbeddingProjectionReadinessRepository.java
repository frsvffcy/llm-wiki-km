package org.km.llmwiki.search.embedding;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.km.llmwiki.persistence.jooq.generated.Tables.EMBEDDING_PROJECTION_READINESS;
import static org.jooq.impl.DSL.when;

@Repository
public class EmbeddingProjectionReadinessRepository {
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(api[-_ ]?key|authorization|token|secret|password)\\s*[:=]\\s*(?:bearer\\s+)?[^\\s,;]+"
    );
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)\\bbearer\\s+[^\\s,;]+");
    private static final Pattern SECRET_KEY = Pattern.compile("\\bsk-[A-Za-z0-9_-]{8,}\\b");
    private static final Pattern UNSAFE_DIAGNOSTIC = Pattern.compile(
            "(?is)(?:raw\\s+response|provider\\s+response|stack\\s*trace|\\b(?:select|insert|update|delete|pragma|create|alter|drop)\\b|"
                    + "(?:^|[\\s(])[/\\\\][^\\s,;)]*|\\b[\\w$]+Exception\\b|\\.(?:dylib|so|dll)\\b)");
    private static final String GENERIC_FAILURE_DETAIL =
            "Embedding rebuild failed; inspect the job status for a safe failure code";
    private final DSLContext dsl;
    public EmbeddingProjectionReadinessRepository(DSLContext dsl) { this.dsl = dsl; }

    public Optional<EmbeddingProjectionReadiness> find(long workspaceId, EmbeddingEvidenceKind corpus) {
        return dsl.selectFrom(EMBEDDING_PROJECTION_READINESS)
                .where(EMBEDDING_PROJECTION_READINESS.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(EMBEDDING_PROJECTION_READINESS.CORPUS.eq(corpus.storageValue()))
                .fetchOptional(this::map);
    }
    public List<EmbeddingProjectionReadiness> findAll(long workspaceId) {
        return dsl.selectFrom(EMBEDDING_PROJECTION_READINESS)
                .where(EMBEDDING_PROJECTION_READINESS.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .fetch(this::map);
    }

    public List<EmbeddingEvidenceKind> findCorporaForJob(long workspaceId, long jobId) {
        return dsl.select(EMBEDDING_PROJECTION_READINESS.CORPUS)
                .from(EMBEDDING_PROJECTION_READINESS)
                .where(EMBEDDING_PROJECTION_READINESS.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(EMBEDDING_PROJECTION_READINESS.PROCESSING_JOB_ID.eq(Math.toIntExact(jobId)))
                .orderBy(EMBEDDING_PROJECTION_READINESS.CORPUS.asc())
                .fetch(EMBEDDING_PROJECTION_READINESS.CORPUS)
                .stream()
                .map(EmbeddingEvidenceKind::fromStorageValue)
                .toList();
    }
    public void markQueued(long workspaceId, long jobId, EmbeddingEvidenceKind corpus, int expected) {
        var previous = findRecord(workspaceId, corpus).orElse(null);
        int indexed = previous == null ? 0 : previous.getIndexedCount();
        int expectedTotal = previous == null ? expected : Math.max(previous.getExpectedCount(), expected);
        indexed = Math.min(Math.max(0, indexed), Math.max(0, expectedTotal));
        int priorReady = previous != null && (previous.getStatus().equals(EmbeddingProjectionReadinessStatus.READY.name())
                || previous.getIncrementalPriorReady() != null && previous.getIncrementalPriorReady() == 1) ? 1 : 0;
        upsert(workspaceId, corpus, EmbeddingProjectionReadinessStatus.QUEUED, jobId, indexed, expectedTotal, 0,
                null, null, null, EmbeddingProjectionContract.VERSION, null, null, null, priorReady);
    }
    public void markRunning(long workspaceId, long jobId, EmbeddingEvidenceKind corpus) {
        dsl.update(EMBEDDING_PROJECTION_READINESS)
                .set(EMBEDDING_PROJECTION_READINESS.STATUS, EmbeddingProjectionReadinessStatus.REBUILDING.name())
                .set(EMBEDDING_PROJECTION_READINESS.STARTED_AT, now())
                .set(EMBEDDING_PROJECTION_READINESS.UPDATED_AT, now())
                .where(EMBEDDING_PROJECTION_READINESS.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(EMBEDDING_PROJECTION_READINESS.CORPUS.eq(corpus.storageValue()))
                .and(EMBEDDING_PROJECTION_READINESS.PROCESSING_JOB_ID.eq(Math.toIntExact(jobId))).execute();
    }
    public void markCompleted(long workspaceId, long jobId, EmbeddingEvidenceKind corpus,
                              int indexed, int expected, int failed, String provider, String model, Integer dimension) {
        markCompleted(workspaceId, jobId, corpus, indexed, expected, failed, provider, model, dimension, true);
    }
    public void markCompleted(long workspaceId, long jobId, EmbeddingEvidenceKind corpus,
                              int indexed, int expected, int failed, String provider, String model, Integer dimension,
                              boolean fullRebuild) {
        var previousRecord = findRecord(workspaceId, corpus).orElse(null);
        boolean priorReady = previousRecord != null && previousRecord.getIncrementalPriorReady() != null
                && previousRecord.getIncrementalPriorReady() == 1;
        int persistedExpected = Math.max(0, expected);
        int persistedFailed = Math.min(Math.max(0, failed), persistedExpected);
        var status = persistedFailed > 0 ? EmbeddingProjectionReadinessStatus.PARTIAL
                : (fullRebuild ? EmbeddingProjectionReadinessStatus.READY
                : priorReady ? EmbeddingProjectionReadinessStatus.READY : EmbeddingProjectionReadinessStatus.PARTIAL);
        int persistedIndexed = Math.min(Math.max(0, indexed), persistedExpected);
        dsl.update(EMBEDDING_PROJECTION_READINESS)
                .set(EMBEDDING_PROJECTION_READINESS.STATUS, status.name())
                .set(EMBEDDING_PROJECTION_READINESS.INDEXED_COUNT, persistedIndexed)
                .set(EMBEDDING_PROJECTION_READINESS.EXPECTED_COUNT, persistedExpected)
                .set(EMBEDDING_PROJECTION_READINESS.FAILED_COUNT, persistedFailed)
                .set(EMBEDDING_PROJECTION_READINESS.EMBEDDING_PROVIDER, provider)
                .set(EMBEDDING_PROJECTION_READINESS.EMBEDDING_MODEL, model)
                .set(EMBEDDING_PROJECTION_READINESS.DIMENSION, dimension)
                .set(EMBEDDING_PROJECTION_READINESS.FAILURE_DETAIL, (String) null)
                .set(EMBEDDING_PROJECTION_READINESS.PROJECTION_VERSION, EmbeddingProjectionContract.VERSION)
                .set(EMBEDDING_PROJECTION_READINESS.INCREMENTAL_PRIOR_READY, 0)
                .set(EMBEDDING_PROJECTION_READINESS.COMPLETED_AT, now())
                .set(EMBEDDING_PROJECTION_READINESS.UPDATED_AT, now())
                .where(EMBEDDING_PROJECTION_READINESS.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(EMBEDDING_PROJECTION_READINESS.CORPUS.eq(corpus.storageValue()))
                .and(EMBEDDING_PROJECTION_READINESS.PROCESSING_JOB_ID.eq(Math.toIntExact(jobId))).execute();
    }

    public void markFailed(long workspaceId, long jobId, EmbeddingEvidenceKind corpus, String detail) {
        dsl.update(EMBEDDING_PROJECTION_READINESS)
                .set(EMBEDDING_PROJECTION_READINESS.STATUS, EmbeddingProjectionReadinessStatus.FAILED.name())
                .set(EMBEDDING_PROJECTION_READINESS.FAILED_COUNT,
                        when(EMBEDDING_PROJECTION_READINESS.EXPECTED_COUNT.gt(0), 1).otherwise(0))
                .set(EMBEDDING_PROJECTION_READINESS.INCREMENTAL_PRIOR_READY, 0)
                .set(EMBEDDING_PROJECTION_READINESS.FAILURE_DETAIL, bounded(detail))
                .set(EMBEDDING_PROJECTION_READINESS.COMPLETED_AT, now())
                .set(EMBEDDING_PROJECTION_READINESS.UPDATED_AT, now())
                .where(EMBEDDING_PROJECTION_READINESS.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(EMBEDDING_PROJECTION_READINESS.CORPUS.eq(corpus.storageValue()))
                .and(EMBEDDING_PROJECTION_READINESS.PROCESSING_JOB_ID.eq(Math.toIntExact(jobId))).execute();
    }
    public int markInterrupted(String detail) {
        return dsl.update(EMBEDDING_PROJECTION_READINESS)
                .set(EMBEDDING_PROJECTION_READINESS.STATUS, EmbeddingProjectionReadinessStatus.FAILED.name())
                .set(EMBEDDING_PROJECTION_READINESS.FAILED_COUNT,
                        when(EMBEDDING_PROJECTION_READINESS.EXPECTED_COUNT.gt(0), 1).otherwise(0))
                .set(EMBEDDING_PROJECTION_READINESS.INCREMENTAL_PRIOR_READY, 0)
                .set(EMBEDDING_PROJECTION_READINESS.FAILURE_DETAIL, bounded(detail))
                .set(EMBEDDING_PROJECTION_READINESS.COMPLETED_AT, now())
                .set(EMBEDDING_PROJECTION_READINESS.UPDATED_AT, now())
                .where(EMBEDDING_PROJECTION_READINESS.STATUS.in("QUEUED", "REBUILDING")).execute();
    }

    public int markInterrupted(List<Long> jobIds, String detail) {
        if (jobIds.isEmpty()) return 0;
        return dsl.update(EMBEDDING_PROJECTION_READINESS)
                .set(EMBEDDING_PROJECTION_READINESS.STATUS, EmbeddingProjectionReadinessStatus.FAILED.name())
                .set(EMBEDDING_PROJECTION_READINESS.FAILED_COUNT,
                        when(EMBEDDING_PROJECTION_READINESS.EXPECTED_COUNT.gt(0), 1).otherwise(0))
                .set(EMBEDDING_PROJECTION_READINESS.INCREMENTAL_PRIOR_READY, 0)
                .set(EMBEDDING_PROJECTION_READINESS.FAILURE_DETAIL, bounded(detail))
                .set(EMBEDDING_PROJECTION_READINESS.COMPLETED_AT, now())
                .set(EMBEDDING_PROJECTION_READINESS.UPDATED_AT, now())
                .where(EMBEDDING_PROJECTION_READINESS.PROCESSING_JOB_ID.in(jobIds.stream().map(Math::toIntExact).toList()))
                .and(EMBEDDING_PROJECTION_READINESS.STATUS.in("QUEUED", "REBUILDING")).execute();
    }

    public int markStale(long workspaceId, EmbeddingEvidenceKind corpus, String detail) {
        return dsl.update(EMBEDDING_PROJECTION_READINESS)
                .set(EMBEDDING_PROJECTION_READINESS.STATUS, EmbeddingProjectionReadinessStatus.STALE.name())
                .set(EMBEDDING_PROJECTION_READINESS.INCREMENTAL_PRIOR_READY, 0)
                .set(EMBEDDING_PROJECTION_READINESS.FAILURE_DETAIL, bounded(detail))
                .set(EMBEDDING_PROJECTION_READINESS.UPDATED_AT, now())
                .where(EMBEDDING_PROJECTION_READINESS.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(EMBEDDING_PROJECTION_READINESS.CORPUS.eq(corpus.storageValue()))
                .and(EMBEDDING_PROJECTION_READINESS.STATUS.eq(EmbeddingProjectionReadinessStatus.READY.name()))
                .execute();
    }

    /**
     * Invalidates serving readiness after canonical content has committed and before an
     * incremental job is created. The separate transaction makes a failed enqueue fail closed.
     * A prior READY state is retained only as an internal completion invariant for the next
     * incremental job; it is never exposed by the readiness API.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSchedulingStale(long workspaceId, EmbeddingEvidenceKind corpus, String detail) {
        var previous = findRecord(workspaceId, corpus).orElse(null);
        int priorReady = previous != null
                && previous.getStatus().equals(EmbeddingProjectionReadinessStatus.READY.name()) ? 1 : 0;
        String timestamp = now();
        if (previous == null) {
            dsl.insertInto(EMBEDDING_PROJECTION_READINESS)
                    .columns(EMBEDDING_PROJECTION_READINESS.WORKSPACE_ID,
                            EMBEDDING_PROJECTION_READINESS.CORPUS,
                            EMBEDDING_PROJECTION_READINESS.STATUS,
                            EMBEDDING_PROJECTION_READINESS.INDEXED_COUNT,
                            EMBEDDING_PROJECTION_READINESS.EXPECTED_COUNT,
                            EMBEDDING_PROJECTION_READINESS.FAILED_COUNT,
                            EMBEDDING_PROJECTION_READINESS.PROJECTION_VERSION,
                            EMBEDDING_PROJECTION_READINESS.FAILURE_DETAIL,
                            EMBEDDING_PROJECTION_READINESS.INCREMENTAL_PRIOR_READY,
                            EMBEDDING_PROJECTION_READINESS.UPDATED_AT)
                    .values(Math.toIntExact(workspaceId), corpus.storageValue(),
                            EmbeddingProjectionReadinessStatus.STALE.name(), 0, 0, 0,
                            EmbeddingProjectionContract.VERSION, bounded(detail), priorReady, timestamp)
                    .execute();
            return;
        }
        dsl.update(EMBEDDING_PROJECTION_READINESS)
                .set(EMBEDDING_PROJECTION_READINESS.STATUS, EmbeddingProjectionReadinessStatus.STALE.name())
                .set(EMBEDDING_PROJECTION_READINESS.PROCESSING_JOB_ID, (Integer) null)
                .set(EMBEDDING_PROJECTION_READINESS.FAILED_COUNT, 0)
                .set(EMBEDDING_PROJECTION_READINESS.FAILURE_DETAIL, bounded(detail))
                .set(EMBEDDING_PROJECTION_READINESS.INCREMENTAL_PRIOR_READY, priorReady)
                .set(EMBEDDING_PROJECTION_READINESS.UPDATED_AT, timestamp)
                .where(EMBEDDING_PROJECTION_READINESS.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(EMBEDDING_PROJECTION_READINESS.CORPUS.eq(corpus.storageValue()))
                .execute();
    }
    private void upsert(long workspaceId, EmbeddingEvidenceKind corpus, EmbeddingProjectionReadinessStatus status,
                        long jobId, int indexed, int expected, int failed, String provider, String model,
                        Integer dimension, String version, String detail, String started, String completed,
                        int priorReady) {
        String t = now();
        dsl.insertInto(EMBEDDING_PROJECTION_READINESS)
                .columns(EMBEDDING_PROJECTION_READINESS.WORKSPACE_ID, EMBEDDING_PROJECTION_READINESS.CORPUS,
                        EMBEDDING_PROJECTION_READINESS.STATUS, EMBEDDING_PROJECTION_READINESS.PROCESSING_JOB_ID,
                        EMBEDDING_PROJECTION_READINESS.INDEXED_COUNT, EMBEDDING_PROJECTION_READINESS.EXPECTED_COUNT,
                        EMBEDDING_PROJECTION_READINESS.FAILED_COUNT, EMBEDDING_PROJECTION_READINESS.PROJECTION_VERSION,
                        EMBEDDING_PROJECTION_READINESS.INCREMENTAL_PRIOR_READY,
                        EMBEDDING_PROJECTION_READINESS.UPDATED_AT)
                .values(Math.toIntExact(workspaceId), corpus.storageValue(), status.name(), Math.toIntExact(jobId), indexed,
                        expected, failed, version, priorReady, t)
                .onConflict(EMBEDDING_PROJECTION_READINESS.WORKSPACE_ID, EMBEDDING_PROJECTION_READINESS.CORPUS)
                .doUpdate().set(EMBEDDING_PROJECTION_READINESS.STATUS, status.name())
                .set(EMBEDDING_PROJECTION_READINESS.PROCESSING_JOB_ID, Math.toIntExact(jobId))
                .set(EMBEDDING_PROJECTION_READINESS.INDEXED_COUNT, indexed)
                .set(EMBEDDING_PROJECTION_READINESS.EXPECTED_COUNT, expected)
                .set(EMBEDDING_PROJECTION_READINESS.FAILED_COUNT, failed)
                .set(EMBEDDING_PROJECTION_READINESS.PROJECTION_VERSION, version)
                .set(EMBEDDING_PROJECTION_READINESS.INCREMENTAL_PRIOR_READY, priorReady)
                .set(EMBEDDING_PROJECTION_READINESS.FAILURE_DETAIL, (String) null)
                .set(EMBEDDING_PROJECTION_READINESS.STARTED_AT, (String) null)
                .set(EMBEDDING_PROJECTION_READINESS.COMPLETED_AT, (String) null)
                .set(EMBEDDING_PROJECTION_READINESS.UPDATED_AT, t).execute();
    }
    private Optional<org.km.llmwiki.persistence.jooq.generated.tables.records.EmbeddingProjectionReadinessRecord>
    findRecord(long workspaceId, EmbeddingEvidenceKind corpus) {
        return dsl.selectFrom(EMBEDDING_PROJECTION_READINESS)
                .where(EMBEDDING_PROJECTION_READINESS.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(EMBEDDING_PROJECTION_READINESS.CORPUS.eq(corpus.storageValue()))
                .fetchOptional();
    }
    private EmbeddingProjectionReadiness map(org.km.llmwiki.persistence.jooq.generated.tables.records.EmbeddingProjectionReadinessRecord r) {
        return new EmbeddingProjectionReadiness(r.getWorkspaceId().longValue(), EmbeddingEvidenceKind.fromStorageValue(r.getCorpus()),
                EmbeddingProjectionReadinessStatus.valueOf(r.getStatus()), r.getProcessingJobId() == null ? null : r.getProcessingJobId().longValue(),
                r.getIndexedCount(), r.getExpectedCount(), r.getFailedCount(), r.getEmbeddingProvider(), r.getEmbeddingModel(),
                r.getDimension(), r.getProjectionVersion(), r.getFailureDetail(), r.getStartedAt(), r.getCompletedAt(), r.getUpdatedAt());
    }
    private static String now() { return DateTimeFormatter.ISO_INSTANT.format(Instant.now()); }
    private static String bounded(String detail) {
        String d = detail == null || detail.isBlank() ? "Unspecified embedding rebuild failure" : detail;
        d = d.replaceAll("[\\r\\n\\t]+", " ").trim();
        d = SECRET_ASSIGNMENT.matcher(d).replaceAll("$1=[REDACTED]");
        d = BEARER_TOKEN.matcher(d).replaceAll("Bearer [REDACTED]");
        d = SECRET_KEY.matcher(d).replaceAll("[REDACTED]");
        if (UNSAFE_DIAGNOSTIC.matcher(d).find()) {
            return GENERIC_FAILURE_DETAIL;
        }
        return d.substring(0, Math.min(160, d.length()));
    }
}
