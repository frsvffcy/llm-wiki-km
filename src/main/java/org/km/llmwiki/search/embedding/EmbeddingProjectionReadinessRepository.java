package org.km.llmwiki.search.embedding;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.km.llmwiki.persistence.jooq.generated.Tables.EMBEDDING_PROJECTION_READINESS;

@Repository
public class EmbeddingProjectionReadinessRepository {
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(api[-_ ]?key|authorization|token|secret|password)\\s*[:=]\\s*(?:bearer\\s+)?[^\\s,;]+"
    );
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)\\bbearer\\s+[^\\s,;]+");
    private static final Pattern SECRET_KEY = Pattern.compile("\\bsk-[A-Za-z0-9_-]{8,}\\b");
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
    public void markQueued(long workspaceId, long jobId, EmbeddingEvidenceKind corpus, int expected) {
        var previous = find(workspaceId, corpus).orElse(null);
        int indexed = previous == null ? 0 : previous.indexedCount();
        int expectedTotal = previous == null ? expected : Math.max(previous.expectedCount(), expected);
        upsert(workspaceId, corpus, EmbeddingProjectionReadinessStatus.QUEUED, jobId, indexed, expectedTotal, 0,
                null, null, null, EmbeddingProjectionContract.VERSION, null, null, null);
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
        var previous = find(workspaceId, corpus).orElse(null);
        var status = failed > 0 ? EmbeddingProjectionReadinessStatus.PARTIAL
                : (fullRebuild ? EmbeddingProjectionReadinessStatus.READY : incrementalStatus(workspaceId, corpus));
        int persistedIndexed = fullRebuild || previous == null ? indexed : previous.indexedCount();
        int persistedExpected = fullRebuild || previous == null ? expected : Math.max(previous.expectedCount(), expected);
        dsl.update(EMBEDDING_PROJECTION_READINESS)
                .set(EMBEDDING_PROJECTION_READINESS.STATUS, status.name())
                .set(EMBEDDING_PROJECTION_READINESS.INDEXED_COUNT, persistedIndexed)
                .set(EMBEDDING_PROJECTION_READINESS.EXPECTED_COUNT, persistedExpected)
                .set(EMBEDDING_PROJECTION_READINESS.FAILED_COUNT, failed)
                .set(EMBEDDING_PROJECTION_READINESS.EMBEDDING_PROVIDER, provider)
                .set(EMBEDDING_PROJECTION_READINESS.EMBEDDING_MODEL, model)
                .set(EMBEDDING_PROJECTION_READINESS.DIMENSION, dimension)
                .set(EMBEDDING_PROJECTION_READINESS.FAILURE_DETAIL, (String) null)
                .set(EMBEDDING_PROJECTION_READINESS.PROJECTION_VERSION, EmbeddingProjectionContract.VERSION)
                .set(EMBEDDING_PROJECTION_READINESS.COMPLETED_AT, now())
                .set(EMBEDDING_PROJECTION_READINESS.UPDATED_AT, now())
                .where(EMBEDDING_PROJECTION_READINESS.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(EMBEDDING_PROJECTION_READINESS.CORPUS.eq(corpus.storageValue()))
                .and(EMBEDDING_PROJECTION_READINESS.PROCESSING_JOB_ID.eq(Math.toIntExact(jobId))).execute();
    }

    private EmbeddingProjectionReadinessStatus incrementalStatus(long workspaceId, EmbeddingEvidenceKind corpus) {
        return find(workspaceId, corpus).map(value -> value.status() == EmbeddingProjectionReadinessStatus.READY
                ? EmbeddingProjectionReadinessStatus.READY : EmbeddingProjectionReadinessStatus.PARTIAL)
                .orElse(EmbeddingProjectionReadinessStatus.PARTIAL);
    }
    public void markFailed(long workspaceId, long jobId, EmbeddingEvidenceKind corpus, String detail) {
        dsl.update(EMBEDDING_PROJECTION_READINESS)
                .set(EMBEDDING_PROJECTION_READINESS.STATUS, EmbeddingProjectionReadinessStatus.FAILED.name())
                .set(EMBEDDING_PROJECTION_READINESS.FAILED_COUNT, 1)
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
                .set(EMBEDDING_PROJECTION_READINESS.FAILED_COUNT, 1)
                .set(EMBEDDING_PROJECTION_READINESS.FAILURE_DETAIL, bounded(detail))
                .set(EMBEDDING_PROJECTION_READINESS.COMPLETED_AT, now())
                .set(EMBEDDING_PROJECTION_READINESS.UPDATED_AT, now())
                .where(EMBEDDING_PROJECTION_READINESS.STATUS.in("QUEUED", "REBUILDING")).execute();
    }

    public int markInterrupted(List<Long> jobIds, String detail) {
        if (jobIds.isEmpty()) return 0;
        return dsl.update(EMBEDDING_PROJECTION_READINESS)
                .set(EMBEDDING_PROJECTION_READINESS.STATUS, EmbeddingProjectionReadinessStatus.FAILED.name())
                .set(EMBEDDING_PROJECTION_READINESS.FAILED_COUNT, 1)
                .set(EMBEDDING_PROJECTION_READINESS.FAILURE_DETAIL, bounded(detail))
                .set(EMBEDDING_PROJECTION_READINESS.COMPLETED_AT, now())
                .set(EMBEDDING_PROJECTION_READINESS.UPDATED_AT, now())
                .where(EMBEDDING_PROJECTION_READINESS.PROCESSING_JOB_ID.in(jobIds.stream().map(Math::toIntExact).toList()))
                .and(EMBEDDING_PROJECTION_READINESS.STATUS.in("QUEUED", "REBUILDING")).execute();
    }

    public int markStale(long workspaceId, EmbeddingEvidenceKind corpus, String detail) {
        return dsl.update(EMBEDDING_PROJECTION_READINESS)
                .set(EMBEDDING_PROJECTION_READINESS.STATUS, EmbeddingProjectionReadinessStatus.STALE.name())
                .set(EMBEDDING_PROJECTION_READINESS.FAILURE_DETAIL, bounded(detail))
                .set(EMBEDDING_PROJECTION_READINESS.UPDATED_AT, now())
                .where(EMBEDDING_PROJECTION_READINESS.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(EMBEDDING_PROJECTION_READINESS.CORPUS.eq(corpus.storageValue()))
                .and(EMBEDDING_PROJECTION_READINESS.STATUS.eq(EmbeddingProjectionReadinessStatus.READY.name()))
                .execute();
    }
    private void upsert(long workspaceId, EmbeddingEvidenceKind corpus, EmbeddingProjectionReadinessStatus status,
                        long jobId, int indexed, int expected, int failed, String provider, String model,
                        Integer dimension, String version, String detail, String started, String completed) {
        String t = now();
        dsl.insertInto(EMBEDDING_PROJECTION_READINESS)
                .columns(EMBEDDING_PROJECTION_READINESS.WORKSPACE_ID, EMBEDDING_PROJECTION_READINESS.CORPUS,
                        EMBEDDING_PROJECTION_READINESS.STATUS, EMBEDDING_PROJECTION_READINESS.PROCESSING_JOB_ID,
                        EMBEDDING_PROJECTION_READINESS.INDEXED_COUNT, EMBEDDING_PROJECTION_READINESS.EXPECTED_COUNT,
                        EMBEDDING_PROJECTION_READINESS.FAILED_COUNT, EMBEDDING_PROJECTION_READINESS.PROJECTION_VERSION,
                        EMBEDDING_PROJECTION_READINESS.UPDATED_AT)
                .values(Math.toIntExact(workspaceId), corpus.storageValue(), status.name(), Math.toIntExact(jobId), indexed,
                        expected, failed, version, t)
                .onConflict(EMBEDDING_PROJECTION_READINESS.WORKSPACE_ID, EMBEDDING_PROJECTION_READINESS.CORPUS)
                .doUpdate().set(EMBEDDING_PROJECTION_READINESS.STATUS, status.name())
                .set(EMBEDDING_PROJECTION_READINESS.PROCESSING_JOB_ID, Math.toIntExact(jobId))
                .set(EMBEDDING_PROJECTION_READINESS.INDEXED_COUNT, indexed)
                .set(EMBEDDING_PROJECTION_READINESS.EXPECTED_COUNT, expected)
                .set(EMBEDDING_PROJECTION_READINESS.FAILED_COUNT, failed)
                .set(EMBEDDING_PROJECTION_READINESS.PROJECTION_VERSION, version)
                .set(EMBEDDING_PROJECTION_READINESS.FAILURE_DETAIL, (String) null)
                .set(EMBEDDING_PROJECTION_READINESS.STARTED_AT, (String) null)
                .set(EMBEDDING_PROJECTION_READINESS.COMPLETED_AT, (String) null)
                .set(EMBEDDING_PROJECTION_READINESS.UPDATED_AT, t).execute();
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
        return d.substring(0, Math.min(160, d.length()));
    }
}
