package org.km.llmwiki.search.embedding;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.km.llmwiki.persistence.jooq.generated.Tables.EMBEDDING_PROJECTION_OPERATION;
import static org.km.llmwiki.persistence.jooq.generated.Tables.EMBEDDING_PROJECTION_READINESS;

/** Current serving state plus the durable generation ledger for embedding operations. */
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
                .orderBy(EMBEDDING_PROJECTION_READINESS.CORPUS.asc()).fetch(this::map);
    }

    /** Historical lookup retained for compatibility; operation history is now ledger-backed. */
    public List<EmbeddingEvidenceKind> findCorporaForJob(long workspaceId, long jobId) {
        return dsl.select(EMBEDDING_PROJECTION_OPERATION.CORPUS).from(EMBEDDING_PROJECTION_OPERATION)
                .where(EMBEDDING_PROJECTION_OPERATION.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(EMBEDDING_PROJECTION_OPERATION.PROCESSING_JOB_ID.eq(Math.toIntExact(jobId)))
                .orderBy(EMBEDDING_PROJECTION_OPERATION.CORPUS.asc())
                .fetch(EMBEDDING_PROJECTION_OPERATION.CORPUS).stream()
                .map(EmbeddingEvidenceKind::fromStorageValue).toList();
    }

    /** Invalidates serving after a canonical commit and reserves the next generation. */
    @Transactional
    public long markSchedulingStale(long workspaceId, EmbeddingEvidenceKind corpus, String detail) {
        String timestamp = now();
        var target = EMBEDDING_PROJECTION_READINESS.TARGET_GENERATION;
        dsl.insertInto(EMBEDDING_PROJECTION_READINESS)
                .columns(EMBEDDING_PROJECTION_READINESS.WORKSPACE_ID,
                        EMBEDDING_PROJECTION_READINESS.CORPUS, EMBEDDING_PROJECTION_READINESS.STATUS,
                        EMBEDDING_PROJECTION_READINESS.INDEXED_COUNT,
                        EMBEDDING_PROJECTION_READINESS.EXPECTED_COUNT,
                        EMBEDDING_PROJECTION_READINESS.FAILED_COUNT,
                        EMBEDDING_PROJECTION_READINESS.PROJECTION_VERSION,
                        EMBEDDING_PROJECTION_READINESS.FAILURE_DETAIL,
                        EMBEDDING_PROJECTION_READINESS.INCREMENTAL_PRIOR_READY,
                        EMBEDDING_PROJECTION_READINESS.TARGET_GENERATION,
                        EMBEDDING_PROJECTION_READINESS.APPLIED_GENERATION,
                        EMBEDDING_PROJECTION_READINESS.PROJECTION_SNAPSHOT_TOKEN,
                        EMBEDDING_PROJECTION_READINESS.UPDATED_AT)
                .values(Math.toIntExact(workspaceId), corpus.storageValue(),
                        EmbeddingProjectionReadinessStatus.STALE.name(), 0, 0, 0,
                        EmbeddingProjectionContract.VERSION, bounded(detail), 0,
                        1, 0, null, timestamp)
                .onConflict(EMBEDDING_PROJECTION_READINESS.WORKSPACE_ID,
                        EMBEDDING_PROJECTION_READINESS.CORPUS)
                .doUpdate()
                .set(EMBEDDING_PROJECTION_READINESS.STATUS, EmbeddingProjectionReadinessStatus.STALE.name())
                .set(EMBEDDING_PROJECTION_READINESS.PROCESSING_JOB_ID, (Integer) null)
                .set(EMBEDDING_PROJECTION_READINESS.FAILED_COUNT, 0)
                .set(EMBEDDING_PROJECTION_READINESS.FAILURE_DETAIL, bounded(detail))
                .set(EMBEDDING_PROJECTION_READINESS.INCREMENTAL_PRIOR_READY, 0)
                .set(target, target.add(1))
                .set(EMBEDDING_PROJECTION_READINESS.PROJECTION_SNAPSHOT_TOKEN, (String) null)
                .set(EMBEDDING_PROJECTION_READINESS.UPDATED_AT, timestamp).execute();
        // SQLite serializes the write transaction. Reading after the UPSERT therefore returns
        // the generation reserved by this transaction without relying on SQLite RETURNING for
        // an ON CONFLICT update, which is not consistently exposed by all supported drivers.
        var generation = dsl.select(target).from(EMBEDDING_PROJECTION_READINESS)
                .where(EMBEDDING_PROJECTION_READINESS.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(EMBEDDING_PROJECTION_READINESS.CORPUS.eq(corpus.storageValue()))
                .fetchOne(target);
        if (generation == null) throw new IllegalStateException("Could not reserve embedding generation");
        return generation.longValue();
    }

    /**
     * Creates current queued state and its immutable ledger entry. Rebuild callers pass zero as
     * expected because the authority count is discovered during execution; that identifies a
     * FULL operation. Incremental callers reserve a generation in markSchedulingStale first.
     */
    @Transactional
    public long markQueued(long workspaceId, long jobId, EmbeddingEvidenceKind corpus, int expected) {
        var previous = findRecord(workspaceId, corpus).orElse(null);
        long generation = previous != null && EmbeddingProjectionReadinessStatus.STALE.name().equals(previous.getStatus())
                ? previous.getTargetGeneration().longValue()
                : previous == null ? 1L : previous.getTargetGeneration().longValue() + 1L;
        // Compatibility callers may reserve an incremental generation with
        // markSchedulingStale first. Full operations always get a new generation.
        if (expected == 0 && previous != null) {
            generation = previous.getTargetGeneration().longValue() + 1L;
        }
        return markQueued(workspaceId, jobId, corpus, expected, generation);
    }

    /** Queues an operation against a generation reserved by canonical invalidation. */
    @Transactional
    public long markQueued(long workspaceId, long jobId, EmbeddingEvidenceKind corpus, int expected,
                           long generation) {
        if (generation <= 0) throw new IllegalArgumentException("Embedding generation must be positive");
        var previous = findRecord(workspaceId, corpus).orElse(null);
        if (previous != null && generation < previous.getTargetGeneration().longValue()) {
            throw new IllegalStateException("Embedding generation is older than the current target");
        }
        EmbeddingProjectionOperationKind operationKind = expected == 0
                ? EmbeddingProjectionOperationKind.FULL : EmbeddingProjectionOperationKind.INCREMENTAL;
        int indexed = previous == null ? 0 : previous.getIndexedCount();
        int expectedTotal = previous == null ? Math.max(0, expected) : Math.max(previous.getExpectedCount(), expected);
        indexed = Math.min(Math.max(0, indexed), expectedTotal);
        int priorReady = previous != null && EmbeddingProjectionReadinessStatus.READY.name().equals(previous.getStatus()) ? 1 : 0;
        String timestamp = now();
        dsl.insertInto(EMBEDDING_PROJECTION_READINESS)
                .columns(EMBEDDING_PROJECTION_READINESS.WORKSPACE_ID,
                        EMBEDDING_PROJECTION_READINESS.CORPUS, EMBEDDING_PROJECTION_READINESS.STATUS,
                        EMBEDDING_PROJECTION_READINESS.PROCESSING_JOB_ID,
                        EMBEDDING_PROJECTION_READINESS.INDEXED_COUNT,
                        EMBEDDING_PROJECTION_READINESS.EXPECTED_COUNT,
                        EMBEDDING_PROJECTION_READINESS.FAILED_COUNT,
                        EMBEDDING_PROJECTION_READINESS.PROJECTION_VERSION,
                        EMBEDDING_PROJECTION_READINESS.INCREMENTAL_PRIOR_READY,
                        EMBEDDING_PROJECTION_READINESS.TARGET_GENERATION,
                        EMBEDDING_PROJECTION_READINESS.APPLIED_GENERATION,
                        EMBEDDING_PROJECTION_READINESS.PROJECTION_SNAPSHOT_TOKEN,
                        EMBEDDING_PROJECTION_READINESS.UPDATED_AT)
                .values(Math.toIntExact(workspaceId), corpus.storageValue(), EmbeddingProjectionReadinessStatus.QUEUED.name(),
                        Math.toIntExact(jobId), indexed, expectedTotal, 0, EmbeddingProjectionContract.VERSION,
                        priorReady, Math.toIntExact(generation), previous == null ? 0 : Math.toIntExact(previous.getAppliedGeneration()), null, timestamp)
                .onConflict(EMBEDDING_PROJECTION_READINESS.WORKSPACE_ID, EMBEDDING_PROJECTION_READINESS.CORPUS)
                .doUpdate().set(EMBEDDING_PROJECTION_READINESS.STATUS, EmbeddingProjectionReadinessStatus.QUEUED.name())
                .set(EMBEDDING_PROJECTION_READINESS.PROCESSING_JOB_ID, Math.toIntExact(jobId))
                .set(EMBEDDING_PROJECTION_READINESS.INDEXED_COUNT, indexed)
                .set(EMBEDDING_PROJECTION_READINESS.EXPECTED_COUNT, expectedTotal)
                .set(EMBEDDING_PROJECTION_READINESS.FAILED_COUNT, 0)
                .set(EMBEDDING_PROJECTION_READINESS.INCREMENTAL_PRIOR_READY, priorReady)
                .set(EMBEDDING_PROJECTION_READINESS.TARGET_GENERATION, Math.toIntExact(generation))
                .set(EMBEDDING_PROJECTION_READINESS.PROJECTION_SNAPSHOT_TOKEN, (String) null)
                .set(EMBEDDING_PROJECTION_READINESS.FAILURE_DETAIL, (String) null)
                .set(EMBEDDING_PROJECTION_READINESS.STARTED_AT, (String) null)
                .set(EMBEDDING_PROJECTION_READINESS.COMPLETED_AT, (String) null)
                .set(EMBEDDING_PROJECTION_READINESS.UPDATED_AT, timestamp).execute();
        dsl.insertInto(EMBEDDING_PROJECTION_OPERATION)
                .columns(EMBEDDING_PROJECTION_OPERATION.WORKSPACE_ID,
                        EMBEDDING_PROJECTION_OPERATION.CORPUS,
                        EMBEDDING_PROJECTION_OPERATION.PROCESSING_JOB_ID,
                        EMBEDDING_PROJECTION_OPERATION.GENERATION,
                        EMBEDDING_PROJECTION_OPERATION.OPERATION_KIND,
                        EMBEDDING_PROJECTION_OPERATION.STATUS,
                        EMBEDDING_PROJECTION_OPERATION.CREATED_AT,
                        EMBEDDING_PROJECTION_OPERATION.UPDATED_AT)
                .values(Math.toIntExact(workspaceId), corpus.storageValue(), Math.toIntExact(jobId), Math.toIntExact(generation),
                        operationKind.name(), "QUEUED", timestamp, timestamp).execute();
        return generation;
    }

    @Transactional
    public void markRunning(long workspaceId, long jobId, EmbeddingEvidenceKind corpus) {
        String timestamp = now();
        int transitioned = dsl.update(EMBEDDING_PROJECTION_OPERATION).set(EMBEDDING_PROJECTION_OPERATION.STATUS, "RUNNING")
                .set(EMBEDDING_PROJECTION_OPERATION.STARTED_AT, timestamp)
                .set(EMBEDDING_PROJECTION_OPERATION.UPDATED_AT, timestamp)
                .where(EMBEDDING_PROJECTION_OPERATION.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(EMBEDDING_PROJECTION_OPERATION.PROCESSING_JOB_ID.eq(Math.toIntExact(jobId)))
                .and(EMBEDDING_PROJECTION_OPERATION.CORPUS.eq(corpus.storageValue()))
                .and(EMBEDDING_PROJECTION_OPERATION.STATUS.eq("QUEUED")).execute();
        if (transitioned == 0) return;
        dsl.update(EMBEDDING_PROJECTION_READINESS)
                .set(EMBEDDING_PROJECTION_READINESS.STATUS, EmbeddingProjectionReadinessStatus.REBUILDING.name())
                .set(EMBEDDING_PROJECTION_READINESS.STARTED_AT, timestamp)
                .set(EMBEDDING_PROJECTION_READINESS.UPDATED_AT, timestamp)
                .where(EMBEDDING_PROJECTION_READINESS.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(EMBEDDING_PROJECTION_READINESS.CORPUS.eq(corpus.storageValue()))
                .and(EMBEDDING_PROJECTION_READINESS.PROCESSING_JOB_ID.eq(Math.toIntExact(jobId))).execute();
    }

    /** Returns the immutable generation assigned to one processing-job/corpus operation. */
    public long generationFor(long workspaceId, long jobId, EmbeddingEvidenceKind corpus) {
        return operationGeneration(workspaceId, jobId, corpus)
                .orElseThrow(() -> new IllegalStateException("Embedding operation generation is missing"));
    }

    /** Returns the serving target currently in force for a workspace/corpus. */
    public long currentGeneration(long workspaceId, EmbeddingEvidenceKind corpus) {
        return findRecord(workspaceId, corpus).map(record -> record.getTargetGeneration().longValue()).orElse(0L);
    }

    /** Compatibility overload for existing repository callers; production uses the generation-aware overload. */
    public void markCompleted(long workspaceId, long jobId, EmbeddingEvidenceKind corpus,
                              int indexed, int expected, int failed, String provider, String model,
                              Integer dimension, boolean fullRebuild) {
        long generation = operationGeneration(workspaceId, jobId, corpus).orElse(0L);
        markCompletedForGeneration(workspaceId, jobId, corpus, generation, indexed, expected, failed,
                provider, model, dimension, true, false, "legacy-completion-proof");
    }

    /** Compatibility overload retaining the pre-drift metadata contract. */
    @Transactional
    public void markCompletedForGeneration(long workspaceId, long jobId, EmbeddingEvidenceKind corpus,
                                           long generation, int indexed, int expected, int failed,
                                           String provider, String model, Integer dimension,
                                           boolean metadataComplete, String snapshotToken) {
        markCompletedForGeneration(workspaceId, jobId, corpus, generation, indexed, expected, failed,
                provider, model, dimension, metadataComplete, false, snapshotToken);
    }

    /** Publishes one callback, while preventing it from overwriting newer generation fields. */
    @Transactional
    public void markCompletedForGeneration(long workspaceId, long jobId, EmbeddingEvidenceKind corpus,
                                           long generation, int indexed, int expected, int failed,
                                           String provider, String model, Integer dimension,
                                           boolean metadataComplete, boolean identityDrifted,
                                           String snapshotToken) {
        String timestamp = now();
        int transitioned = dsl.update(EMBEDDING_PROJECTION_OPERATION).set(EMBEDDING_PROJECTION_OPERATION.STATUS, "COMPLETED")
                .set(EMBEDDING_PROJECTION_OPERATION.FAILURE_DETAIL, (String) null)
                .set(EMBEDDING_PROJECTION_OPERATION.COMPLETED_AT, timestamp)
                .set(EMBEDDING_PROJECTION_OPERATION.UPDATED_AT, timestamp)
                .where(EMBEDDING_PROJECTION_OPERATION.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(EMBEDDING_PROJECTION_OPERATION.PROCESSING_JOB_ID.eq(Math.toIntExact(jobId)))
                .and(EMBEDDING_PROJECTION_OPERATION.CORPUS.eq(corpus.storageValue()))
                .and(EMBEDDING_PROJECTION_OPERATION.GENERATION.eq(Math.toIntExact(generation)))
                .and(EMBEDDING_PROJECTION_OPERATION.STATUS.in("QUEUED", "RUNNING")).execute();
        // A durable operation is a one-shot state machine. In particular, a delayed or
        // duplicated callback must not replace the proof belonging to a newer callback.
        if (transitioned == 0) return;
        var current = findRecord(workspaceId, corpus).orElse(null);
        if (current == null) return;
        if (generation == current.getTargetGeneration().longValue()) {
            boolean requiresFullRebuild = requiresFullRebuild(current, workspaceId, corpus,
                    metadataComplete, identityDrifted, provider, model, dimension);
            if (!requiresFullRebuild) {
                updateObservation(workspaceId, corpus, generation, indexed, expected, failed,
                        provider, model, dimension, metadataComplete, snapshotToken, timestamp);
            }
            recompute(workspaceId, corpus, requiresFullRebuild
                    ? "Embedding identity changed; full rebuild required" : null);
            if (requiresFullRebuild) markFullRebuildRequired(workspaceId, corpus, timestamp);
            return;
        }
        // A completed full operation below the current target is still the durable baseline
        // for a later incremental generation. Preserve only its projection identity when the
        // current row has no identity yet; counts, applied generation, and snapshot proof remain
        // owned by the current target and are intentionally not copied from the older callback.
        if (generation < current.getTargetGeneration().longValue()
                && isFullOperation(workspaceId, corpus, generation)
                && metadataComplete
                && current.getEmbeddingProvider() == null
                && current.getEmbeddingModel() == null
                && current.getDimension() == null
                && (current.getProjectionVersion() == null
                || EmbeddingProjectionContract.VERSION.equals(current.getProjectionVersion()))) {
            dsl.update(EMBEDDING_PROJECTION_READINESS)
                    .set(EMBEDDING_PROJECTION_READINESS.EMBEDDING_PROVIDER, provider)
                    .set(EMBEDDING_PROJECTION_READINESS.EMBEDDING_MODEL, model)
                    .set(EMBEDDING_PROJECTION_READINESS.DIMENSION, dimension)
                    .set(EMBEDDING_PROJECTION_READINESS.PROJECTION_VERSION,
                            EmbeddingProjectionContract.VERSION)
                    .set(EMBEDDING_PROJECTION_READINESS.UPDATED_AT, timestamp)
                    .where(EMBEDDING_PROJECTION_READINESS.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                    .and(EMBEDDING_PROJECTION_READINESS.CORPUS.eq(corpus.storageValue())).execute();
        }
        recompute(workspaceId, corpus, null);
    }

    /**
     * Reconciles the current target against a fresh authority/projection observation. This is
     * intentionally separate from an operation callback: a delayed older callback can finish
     * after a newer operation has already changed the rows, so its original counts and metadata
     * are not a valid proof for the current target.
     */
    @Transactional
    public void reconcileCurrentGeneration(long workspaceId, EmbeddingEvidenceKind corpus,
                                           long observationGeneration,
                                           int indexed, int expected, int failed,
                                           String provider, String model, Integer dimension,
                                           boolean metadataComplete, String snapshotToken) {
        reconcileCurrentGeneration(workspaceId, corpus, observationGeneration, indexed, expected, failed,
                provider, model, dimension, metadataComplete, false, snapshotToken);
    }

    @Transactional
    public void reconcileCurrentGeneration(long workspaceId, EmbeddingEvidenceKind corpus,
                                           long observationGeneration,
                                           int indexed, int expected, int failed,
                                           String provider, String model, Integer dimension,
                                           boolean metadataComplete, boolean identityDrifted,
                                           String snapshotToken) {
        var current = findRecord(workspaceId, corpus).orElse(null);
        // A callback may be delayed behind a newer enqueue. Its counts and metadata describe
        // the callback's generation, not the current target, so it must not overwrite the
        // current proof or snapshot boundary.
        if (current == null || observationGeneration != current.getTargetGeneration().longValue()) return;
        updateObservation(workspaceId, corpus, observationGeneration, indexed,
                expected, failed, provider, model, dimension, metadataComplete, snapshotToken, now());
        if (identityDrifted) markFullRebuildRequired(workspaceId, corpus, now());
        recompute(workspaceId, corpus, null);
    }

    @Transactional
    public void markFailed(long workspaceId, long jobId, EmbeddingEvidenceKind corpus, String detail) {
        long generation = operationGeneration(workspaceId, jobId, corpus).orElse(0L);
        String safe = bounded(detail);
        String timestamp = now();
        int transitioned = dsl.update(EMBEDDING_PROJECTION_OPERATION).set(EMBEDDING_PROJECTION_OPERATION.STATUS, "FAILED")
                .set(EMBEDDING_PROJECTION_OPERATION.FAILURE_DETAIL, safe)
                .set(EMBEDDING_PROJECTION_OPERATION.COMPLETED_AT, timestamp)
                .set(EMBEDDING_PROJECTION_OPERATION.UPDATED_AT, timestamp)
                .where(EMBEDDING_PROJECTION_OPERATION.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(EMBEDDING_PROJECTION_OPERATION.PROCESSING_JOB_ID.eq(Math.toIntExact(jobId)))
                .and(EMBEDDING_PROJECTION_OPERATION.CORPUS.eq(corpus.storageValue()))
                .and(EMBEDDING_PROJECTION_OPERATION.STATUS.in("QUEUED", "RUNNING")).execute();
        if (transitioned == 0) return;
        var current = findRecord(workspaceId, corpus).orElse(null);
        if (current != null && generation == current.getTargetGeneration().longValue()) {
            dsl.update(EMBEDDING_PROJECTION_READINESS)
                    .set(EMBEDDING_PROJECTION_READINESS.FAILED_COUNT, current.getExpectedCount() > 0 ? 1 : 0)
                    .set(EMBEDDING_PROJECTION_READINESS.PROJECTION_SNAPSHOT_TOKEN, (String) null)
                    .set(EMBEDDING_PROJECTION_READINESS.FAILURE_DETAIL, safe)
                    .set(EMBEDDING_PROJECTION_READINESS.COMPLETED_AT, timestamp)
                    .set(EMBEDDING_PROJECTION_READINESS.UPDATED_AT, timestamp)
                    .where(EMBEDDING_PROJECTION_READINESS.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                    .and(EMBEDDING_PROJECTION_READINESS.CORPUS.eq(corpus.storageValue())).execute();
        }
        recompute(workspaceId, corpus, safe);
    }

    public int markInterrupted(String detail) {
        List<Long> ids = dsl.select(EMBEDDING_PROJECTION_OPERATION.PROCESSING_JOB_ID)
                .from(EMBEDDING_PROJECTION_OPERATION)
                .where(EMBEDDING_PROJECTION_OPERATION.STATUS.in("QUEUED", "RUNNING"))
                .fetch(EMBEDDING_PROJECTION_OPERATION.PROCESSING_JOB_ID).stream().map(Integer::longValue).distinct().toList();
        return markInterrupted(ids, detail);
    }

    @Transactional
    public int markInterrupted(List<Long> jobIds, String detail) {
        if (jobIds.isEmpty()) return 0;
        String safe = bounded(detail);
        List<OperationKey> keys = dsl.select(EMBEDDING_PROJECTION_OPERATION.WORKSPACE_ID,
                        EMBEDDING_PROJECTION_OPERATION.CORPUS,
                        EMBEDDING_PROJECTION_OPERATION.PROCESSING_JOB_ID)
                .from(EMBEDDING_PROJECTION_OPERATION)
                .where(EMBEDDING_PROJECTION_OPERATION.PROCESSING_JOB_ID.in(jobIds.stream().map(Math::toIntExact).toList()))
                .and(EMBEDDING_PROJECTION_OPERATION.STATUS.in("QUEUED", "RUNNING"))
                .fetch(r -> new OperationKey(r.get(EMBEDDING_PROJECTION_OPERATION.WORKSPACE_ID).longValue(),
                        EmbeddingEvidenceKind.fromStorageValue(r.get(EMBEDDING_PROJECTION_OPERATION.CORPUS))));
        String timestamp = now();
        int changed = dsl.update(EMBEDDING_PROJECTION_OPERATION).set(EMBEDDING_PROJECTION_OPERATION.STATUS, "FAILED")
                .set(EMBEDDING_PROJECTION_OPERATION.FAILURE_DETAIL, safe)
                .set(EMBEDDING_PROJECTION_OPERATION.COMPLETED_AT, timestamp)
                .set(EMBEDDING_PROJECTION_OPERATION.UPDATED_AT, timestamp)
                .where(EMBEDDING_PROJECTION_OPERATION.PROCESSING_JOB_ID.in(jobIds.stream().map(Math::toIntExact).toList()))
                .and(EMBEDDING_PROJECTION_OPERATION.STATUS.in("QUEUED", "RUNNING")).execute();
        for (OperationKey key : keys) recompute(key.workspaceId(), key.corpus(), safe);
        return changed;
    }

    @Transactional
    public int markStale(long workspaceId, EmbeddingEvidenceKind corpus, String detail) {
        return dsl.update(EMBEDDING_PROJECTION_READINESS)
                .set(EMBEDDING_PROJECTION_READINESS.STATUS, EmbeddingProjectionReadinessStatus.STALE.name())
                .set(EMBEDDING_PROJECTION_READINESS.PROJECTION_SNAPSHOT_TOKEN, (String) null)
                .set(EMBEDDING_PROJECTION_READINESS.FAILURE_DETAIL, bounded(detail))
                .set(EMBEDDING_PROJECTION_READINESS.UPDATED_AT, now())
                .where(EMBEDDING_PROJECTION_READINESS.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(EMBEDDING_PROJECTION_READINESS.CORPUS.eq(corpus.storageValue()))
                .and(EMBEDDING_PROJECTION_READINESS.STATUS.eq(EmbeddingProjectionReadinessStatus.READY.name())).execute();
    }

    /**
     * Invalidates only the READY generation observed by a caller. A delayed semantic query must
     * never turn a newer generation's proof stale after a rebuild has already published it.
     */
    @Transactional
    public int markStaleIfGeneration(long workspaceId, EmbeddingEvidenceKind corpus,
                                     long expectedGeneration, String detail) {
        return dsl.update(EMBEDDING_PROJECTION_READINESS)
                .set(EMBEDDING_PROJECTION_READINESS.STATUS, EmbeddingProjectionReadinessStatus.STALE.name())
                .set(EMBEDDING_PROJECTION_READINESS.PROJECTION_SNAPSHOT_TOKEN, (String) null)
                .set(EMBEDDING_PROJECTION_READINESS.FAILURE_DETAIL, bounded(detail))
                .set(EMBEDDING_PROJECTION_READINESS.UPDATED_AT, now())
                .where(EMBEDDING_PROJECTION_READINESS.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(EMBEDDING_PROJECTION_READINESS.CORPUS.eq(corpus.storageValue()))
                .and(EMBEDDING_PROJECTION_READINESS.STATUS.eq(EmbeddingProjectionReadinessStatus.READY.name()))
                .and(EMBEDDING_PROJECTION_READINESS.TARGET_GENERATION.eq(Math.toIntExact(expectedGeneration)))
                .execute();
    }

    private void recompute(long workspaceId, EmbeddingEvidenceKind corpus, String detail) {
        var current = findRecord(workspaceId, corpus).orElse(null);
        if (current == null) return;
        long target = current.getTargetGeneration().longValue();
        var all = dsl.selectFrom(EMBEDDING_PROJECTION_OPERATION)
                .where(EMBEDDING_PROJECTION_OPERATION.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(EMBEDDING_PROJECTION_OPERATION.CORPUS.eq(corpus.storageValue()))
                .and(EMBEDDING_PROJECTION_OPERATION.GENERATION.le(Math.toIntExact(target)))
                .orderBy(EMBEDDING_PROJECTION_OPERATION.GENERATION.asc()).fetch();
        long latestFull = all.stream().filter(r -> "FULL".equals(r.getOperationKind()))
                .mapToLong(r -> r.getGeneration().longValue()).max().orElse(0L);
        var effective = all.stream().filter(r -> r.getGeneration().longValue() >= latestFull).toList();
        boolean pending = effective.stream().anyMatch(r -> "QUEUED".equals(r.getStatus()) || "RUNNING".equals(r.getStatus()));
        boolean generationApplied = current.getAppliedGeneration().longValue() == target;
        boolean targetOperationCompleted = effective.stream().anyMatch(r ->
                r.getGeneration().longValue() == target && "COMPLETED".equals(r.getStatus()));
        Optional<String> latestFullStatus = latestFullStatus(workspaceId, corpus, target);
        boolean proof = generationApplied && targetOperationCompleted
                && current.getProjectionSnapshotToken() != null && current.getFailedCount() == 0
                && current.getIndexedCount() == current.getExpectedCount();
        boolean missingTargetOperation = target > 0 && all.stream().noneMatch(r ->
                r.getGeneration().longValue() == target);
        EmbeddingProjectionReadinessStatus next;
        if (missingTargetOperation) {
            next = EmbeddingProjectionReadinessStatus.STALE;
        } else if (pending) {
            next = effective.stream().anyMatch(r -> "RUNNING".equals(r.getStatus()))
                    ? EmbeddingProjectionReadinessStatus.REBUILDING : EmbeddingProjectionReadinessStatus.QUEUED;
        } else if (latestFullStatus.filter("FAILED"::equals).isPresent()) {
            // A failed full rebuild is a barrier: a later one-item repair cannot resurrect the
            // older whole-corpus proof, even if that repair happens to finish successfully.
            next = EmbeddingProjectionReadinessStatus.FAILED;
        } else if (effective.stream().max(Comparator.comparing(r -> r.getGeneration().longValue()))
                .map(r -> "FAILED".equals(r.getStatus())).orElse(false)) {
            // A failure below a later completed target is historical. It must not overwrite the
            // newer proof; the corpus proof still decides whether that target can serve. A
            // failure at the latest effective generation remains fail-closed.
            next = EmbeddingProjectionReadinessStatus.FAILED;
        } else if (proof && (latestFull == 0L || effective.stream().anyMatch(r ->
                r.getGeneration().longValue() == latestFull && "COMPLETED".equals(r.getStatus())))) {
            next = EmbeddingProjectionReadinessStatus.READY;
        } else {
            next = EmbeddingProjectionReadinessStatus.PARTIAL;
        }
        dsl.update(EMBEDDING_PROJECTION_READINESS).set(EMBEDDING_PROJECTION_READINESS.STATUS, next.name())
                .set(EMBEDDING_PROJECTION_READINESS.FAILURE_DETAIL,
                        detail == null ? current.getFailureDetail() : bounded(detail))
                .set(EMBEDDING_PROJECTION_READINESS.UPDATED_AT, now())
                .where(EMBEDDING_PROJECTION_READINESS.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(EMBEDDING_PROJECTION_READINESS.CORPUS.eq(corpus.storageValue()))
                .execute();
    }

    private void updateObservation(long workspaceId, EmbeddingEvidenceKind corpus, long generation,
                                   int indexed, int expected, int failed, String provider, String model,
                                   Integer dimension, boolean metadataComplete, String snapshotToken,
                                   String timestamp) {
        var current = findRecord(workspaceId, corpus).orElse(null);
        if (current == null || generation != current.getTargetGeneration().longValue()) return;
        int expectedTotal = Math.max(0, expected);
        int failedTotal = Math.min(Math.max(0, failed), expectedTotal);
        int indexedTotal = Math.min(Math.max(0, indexed), expectedTotal);
        boolean usableMetadata = metadataComplete && snapshotToken != null && !snapshotToken.isBlank();
        boolean targetProofReady = targetOperationHasCompleted(workspaceId, corpus, generation);
        boolean proof = targetProofReady && usableMetadata && failedTotal == 0
                && indexedTotal == expectedTotal;
        dsl.update(EMBEDDING_PROJECTION_READINESS)
                .set(EMBEDDING_PROJECTION_READINESS.INDEXED_COUNT, indexedTotal)
                .set(EMBEDDING_PROJECTION_READINESS.EXPECTED_COUNT, expectedTotal)
                .set(EMBEDDING_PROJECTION_READINESS.FAILED_COUNT, failedTotal)
                .set(EMBEDDING_PROJECTION_READINESS.EMBEDDING_PROVIDER, usableMetadata ? provider : null)
                .set(EMBEDDING_PROJECTION_READINESS.EMBEDDING_MODEL, usableMetadata ? model : null)
                .set(EMBEDDING_PROJECTION_READINESS.DIMENSION, usableMetadata ? dimension : null)
                .set(EMBEDDING_PROJECTION_READINESS.PROJECTION_VERSION,
                        usableMetadata ? EmbeddingProjectionContract.VERSION : null)
                .set(EMBEDDING_PROJECTION_READINESS.PROJECTION_SNAPSHOT_TOKEN,
                        proof ? snapshotToken : null)
                .set(EMBEDDING_PROJECTION_READINESS.APPLIED_GENERATION,
                        proof ? Math.toIntExact(generation) : current.getAppliedGeneration())
                .set(EMBEDDING_PROJECTION_READINESS.INCREMENTAL_PRIOR_READY, 0)
                .set(EMBEDDING_PROJECTION_READINESS.FAILURE_DETAIL, (String) null)
                .set(EMBEDDING_PROJECTION_READINESS.COMPLETED_AT, timestamp)
                .set(EMBEDDING_PROJECTION_READINESS.UPDATED_AT, timestamp)
                .where(EMBEDDING_PROJECTION_READINESS.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(EMBEDDING_PROJECTION_READINESS.CORPUS.eq(corpus.storageValue())).execute();
    }

    private boolean targetOperationHasCompleted(long workspaceId, EmbeddingEvidenceKind corpus,
                                                long generation) {
        var operations = dsl.selectFrom(EMBEDDING_PROJECTION_OPERATION)
                .where(EMBEDDING_PROJECTION_OPERATION.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(EMBEDDING_PROJECTION_OPERATION.CORPUS.eq(corpus.storageValue()))
                .and(EMBEDDING_PROJECTION_OPERATION.GENERATION.le(Math.toIntExact(generation)))
                .fetch();
        long latestFull = operations.stream().filter(row -> "FULL".equals(row.getOperationKind()))
                .mapToLong(row -> row.getGeneration().longValue()).max().orElse(0L);
        var effective = operations.stream()
                .filter(row -> row.getGeneration().longValue() >= latestFull).toList();
        // Older operations may still be pending while an out-of-order newer callback publishes
        // a valid whole-corpus proof. Readiness remains QUEUED/REBUILDING until those operations
        // terminate, but retaining the proof lets restart recovery converge after an older job
        // is marked failed without inventing a new observation.
        return effective.stream().anyMatch(row -> row.getGeneration().longValue() == generation
                && "COMPLETED".equals(row.getStatus()));
    }

    private boolean requiresFullRebuild(
            org.km.llmwiki.persistence.jooq.generated.tables.records.EmbeddingProjectionReadinessRecord current,
            long workspaceId, EmbeddingEvidenceKind corpus, boolean metadataComplete,
            boolean identityDrifted, String provider, String model, Integer dimension) {
        // Identity drift is a corpus-wide proof failure for every operation kind. A FULL
        // operation is allowed to establish a new identity only when all rows agree; if its
        // terminal observation is mixed/obsolete, leave the corpus explicitly STALE instead of
        // degrading to PARTIAL and making the next repair boundary ambiguous.
        if (identityDrifted) return true;
        var operationKind = dsl.select(EMBEDDING_PROJECTION_OPERATION.OPERATION_KIND)
                .from(EMBEDDING_PROJECTION_OPERATION)
                .where(EMBEDDING_PROJECTION_OPERATION.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(EMBEDDING_PROJECTION_OPERATION.CORPUS.eq(corpus.storageValue()))
                .and(EMBEDDING_PROJECTION_OPERATION.GENERATION.eq(current.getTargetGeneration()))
                .fetchOptional(EMBEDDING_PROJECTION_OPERATION.OPERATION_KIND).orElse(null);
        if (!"INCREMENTAL".equals(operationKind)) return false;
        if (latestFullStatus(workspaceId, corpus, current.getTargetGeneration().longValue())
                .filter(status -> !"COMPLETED".equals(status)).isPresent()) {
            return true;
        }
        // A pre-V25 readiness/projection baseline has no durable whole-corpus proof. It may be
        // retained for compatibility, but the first generation-aware operation must be a full
        // rebuild before it can establish READY again.
        if (current.getAppliedGeneration() <= 0
                && !hasCompletedFullOperation(workspaceId, corpus, current.getTargetGeneration().longValue())) {
            return true;
        }
        if (!metadataComplete) return false;
        return !java.util.Objects.equals(current.getEmbeddingProvider(), provider)
                || !java.util.Objects.equals(current.getEmbeddingModel(), model)
                || !java.util.Objects.equals(current.getDimension(), dimension)
                || !java.util.Objects.equals(current.getProjectionVersion(), EmbeddingProjectionContract.VERSION);
    }

    private boolean hasCompletedFullOperation(long workspaceId, EmbeddingEvidenceKind corpus,
                                              long targetGeneration) {
        return latestFullStatus(workspaceId, corpus, targetGeneration)
                .filter("COMPLETED"::equals).isPresent();
    }

    private Optional<String> latestFullStatus(long workspaceId, EmbeddingEvidenceKind corpus,
                                              long targetGeneration) {
        return dsl.select(EMBEDDING_PROJECTION_OPERATION.STATUS)
                .from(EMBEDDING_PROJECTION_OPERATION)
                .where(EMBEDDING_PROJECTION_OPERATION.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(EMBEDDING_PROJECTION_OPERATION.CORPUS.eq(corpus.storageValue()))
                .and(EMBEDDING_PROJECTION_OPERATION.OPERATION_KIND.eq("FULL"))
                .and(EMBEDDING_PROJECTION_OPERATION.GENERATION.le(Math.toIntExact(targetGeneration)))
                .orderBy(EMBEDDING_PROJECTION_OPERATION.GENERATION.desc())
                .limit(1)
                .fetchOptional(EMBEDDING_PROJECTION_OPERATION.STATUS);
    }

    private boolean isFullOperation(long workspaceId, EmbeddingEvidenceKind corpus, long generation) {
        return dsl.fetchExists(dsl.selectOne().from(EMBEDDING_PROJECTION_OPERATION)
                .where(EMBEDDING_PROJECTION_OPERATION.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(EMBEDDING_PROJECTION_OPERATION.CORPUS.eq(corpus.storageValue()))
                .and(EMBEDDING_PROJECTION_OPERATION.GENERATION.eq(Math.toIntExact(generation)))
                .and(EMBEDDING_PROJECTION_OPERATION.OPERATION_KIND.eq("FULL"))
                .and(EMBEDDING_PROJECTION_OPERATION.STATUS.eq("COMPLETED")));
    }

    private void markFullRebuildRequired(long workspaceId, EmbeddingEvidenceKind corpus, String timestamp) {
        dsl.update(EMBEDDING_PROJECTION_READINESS)
                .set(EMBEDDING_PROJECTION_READINESS.STATUS, EmbeddingProjectionReadinessStatus.STALE.name())
                .set(EMBEDDING_PROJECTION_READINESS.PROJECTION_SNAPSHOT_TOKEN, (String) null)
                .set(EMBEDDING_PROJECTION_READINESS.FAILURE_DETAIL,
                        "Embedding identity changed; full rebuild required")
                .set(EMBEDDING_PROJECTION_READINESS.COMPLETED_AT, timestamp)
                .set(EMBEDDING_PROJECTION_READINESS.UPDATED_AT, timestamp)
                .where(EMBEDDING_PROJECTION_READINESS.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(EMBEDDING_PROJECTION_READINESS.CORPUS.eq(corpus.storageValue())).execute();
    }

    private Optional<Long> operationGeneration(long workspaceId, long jobId, EmbeddingEvidenceKind corpus) {
        return dsl.select(EMBEDDING_PROJECTION_OPERATION.GENERATION).from(EMBEDDING_PROJECTION_OPERATION)
                .where(EMBEDDING_PROJECTION_OPERATION.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(EMBEDDING_PROJECTION_OPERATION.PROCESSING_JOB_ID.eq(Math.toIntExact(jobId)))
                .and(EMBEDDING_PROJECTION_OPERATION.CORPUS.eq(corpus.storageValue()))
                .fetchOptional(EMBEDDING_PROJECTION_OPERATION.GENERATION).map(Integer::longValue);
    }

    private Optional<org.km.llmwiki.persistence.jooq.generated.tables.records.EmbeddingProjectionReadinessRecord>
    findRecord(long workspaceId, EmbeddingEvidenceKind corpus) {
        return dsl.selectFrom(EMBEDDING_PROJECTION_READINESS)
                .where(EMBEDDING_PROJECTION_READINESS.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(EMBEDDING_PROJECTION_READINESS.CORPUS.eq(corpus.storageValue())).fetchOptional();
    }

    private EmbeddingProjectionReadiness map(
            org.km.llmwiki.persistence.jooq.generated.tables.records.EmbeddingProjectionReadinessRecord r) {
        return new EmbeddingProjectionReadiness(r.getWorkspaceId().longValue(),
                EmbeddingEvidenceKind.fromStorageValue(r.getCorpus()),
                EmbeddingProjectionReadinessStatus.valueOf(r.getStatus()),
                r.getProcessingJobId() == null ? null : r.getProcessingJobId().longValue(),
                r.getIndexedCount(), r.getExpectedCount(), r.getFailedCount(), r.getEmbeddingProvider(),
                r.getEmbeddingModel(), r.getDimension(), r.getProjectionVersion(), r.getFailureDetail(),
                r.getStartedAt(), r.getCompletedAt(), r.getUpdatedAt(), r.getTargetGeneration().longValue(),
                r.getAppliedGeneration().longValue(), r.getProjectionSnapshotToken());
    }

    private static String now() { return DateTimeFormatter.ISO_INSTANT.format(Instant.now()); }
    private static String bounded(String detail) {
        String d = detail == null || detail.isBlank() ? "Unspecified embedding rebuild failure" : detail;
        d = d.replaceAll("[\\r\\n\\t]+", " ").trim();
        d = SECRET_ASSIGNMENT.matcher(d).replaceAll("$1=[REDACTED]");
        d = BEARER_TOKEN.matcher(d).replaceAll("Bearer [REDACTED]");
        d = SECRET_KEY.matcher(d).replaceAll("[REDACTED]");
        if (UNSAFE_DIAGNOSTIC.matcher(d).find()) return GENERIC_FAILURE_DETAIL;
        return d.substring(0, Math.min(160, d.length()));
    }

    private record OperationKey(long workspaceId, EmbeddingEvidenceKind corpus) {}
}
