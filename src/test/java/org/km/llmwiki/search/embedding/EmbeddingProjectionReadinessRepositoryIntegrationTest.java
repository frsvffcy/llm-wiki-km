package org.km.llmwiki.search.embedding;

import org.junit.jupiter.api.Test;
import org.km.llmwiki.processing.ProcessingJobRepository;
import org.km.llmwiki.processing.ProcessingJobType;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies persisted readiness transitions used by asynchronous production rebuilds. */
class EmbeddingProjectionReadinessRepositoryIntegrationTest extends IsolatedIntegrationTest {
    @Autowired EmbeddingProjectionReadinessRepository repository;
    @Autowired ProcessingJobRepository jobs;
    @Autowired EmbeddingProjectionStartupReconciler startupReconciler;
    @Autowired JdbcClient jdbc;

    @Test
    void distinguishesInitialBuildPartialReadyAndProviderDrift() {
        long workspace = insertWorkspace();
        long job = jobs.create(workspace, "embedding-readiness", ProcessingJobType.EMBEDDING_REBUILD, 1).id();

        long generation = repository.markQueued(workspace, job, EmbeddingEvidenceKind.WIKI, 0);
        assertThat(repository.find(workspace, EmbeddingEvidenceKind.WIKI).orElseThrow().status())
                .isEqualTo(EmbeddingProjectionReadinessStatus.QUEUED);
        repository.markRunning(workspace, job, EmbeddingEvidenceKind.WIKI);
        repository.markCompletedForGeneration(workspace, job, EmbeddingEvidenceKind.WIKI, generation,
                0, 1, 1, null, null, null, false, null);
        assertThat(repository.find(workspace, EmbeddingEvidenceKind.WIKI).orElseThrow().status())
                .isEqualTo(EmbeddingProjectionReadinessStatus.PARTIAL);

        long retryJob = jobs.create(workspace, "embedding-readiness-retry", ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long retryGeneration = repository.markQueued(workspace, retryJob, EmbeddingEvidenceKind.WIKI, 0);
        repository.markCompletedForGeneration(workspace, retryJob, EmbeddingEvidenceKind.WIKI, retryGeneration,
                1, 1, 0, "test", "model-a", 2, true, "snapshot-a");
        assertThat(repository.find(workspace, EmbeddingEvidenceKind.WIKI).orElseThrow().status())
                .isEqualTo(EmbeddingProjectionReadinessStatus.READY);
        repository.markStale(workspace, EmbeddingEvidenceKind.WIKI, "model changed");
        assertThat(repository.find(workspace, EmbeddingEvidenceKind.WIKI).orElseThrow().status())
                .isEqualTo(EmbeddingProjectionReadinessStatus.STALE);
    }

    @Test
    void staleMarkUsesCompareAndSetAndCannotRewriteNewerReadyGeneration() {
        long workspace = insertWorkspace();
        long firstJob = jobs.create(workspace, "cas-first", ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long firstGeneration = repository.markQueued(workspace, firstJob, EmbeddingEvidenceKind.WIKI, 0);
        repository.markCompletedForGeneration(workspace, firstJob, EmbeddingEvidenceKind.WIKI,
                firstGeneration, 1, 1, 0, "provider", "model", 2, true, "proof-first");

        long secondJob = jobs.create(workspace, "cas-second", ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long secondGeneration = queueIncremental(workspace, secondJob, EmbeddingEvidenceKind.WIKI, 1);
        repository.markCompletedForGeneration(workspace, secondJob, EmbeddingEvidenceKind.WIKI,
                secondGeneration, 1, 1, 0, "provider", "model", 2, true, "proof-second");

        assertThat(repository.markStaleIfGeneration(workspace, EmbeddingEvidenceKind.WIKI,
                firstGeneration, "delayed query metadata drift")).isZero();
        assertThat(repository.find(workspace, EmbeddingEvidenceKind.WIKI).orElseThrow())
                .satisfies(state -> {
                    assertThat(state.status()).isEqualTo(EmbeddingProjectionReadinessStatus.READY);
                    assertThat(state.targetGeneration()).isEqualTo(secondGeneration);
                    assertThat(state.appliedGeneration()).isEqualTo(secondGeneration);
                    assertThat(state.projectionSnapshotToken()).isEqualTo("proof-second");
                });

        assertThat(repository.markStaleIfGeneration(workspace, EmbeddingEvidenceKind.WIKI,
                secondGeneration, "current query metadata drift")).isOne();
        assertThat(repository.find(workspace, EmbeddingEvidenceKind.WIKI).orElseThrow().status())
                .isEqualTo(EmbeddingProjectionReadinessStatus.STALE);
    }

    @Test
    void preservesPriorReadyOnlyForSuccessfulIncrementalCompletion() {
        long workspace = insertWorkspace();
        long fullJob = jobs.create(workspace, "embedding-full", ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long fullGeneration = repository.markQueued(workspace, fullJob, EmbeddingEvidenceKind.WIKI, 0);
        repository.markCompletedForGeneration(workspace, fullJob, EmbeddingEvidenceKind.WIKI, fullGeneration,
                1, 1, 0, "provider", "model", 2, true, "snapshot-full");

        long incrementalJob = jobs.create(workspace, "embedding-incremental-ready",
                ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long incrementalGeneration = queueIncremental(workspace, incrementalJob, EmbeddingEvidenceKind.WIKI, 1);
        repository.markRunning(workspace, incrementalJob, EmbeddingEvidenceKind.WIKI);
        repository.markCompletedForGeneration(workspace, incrementalJob, EmbeddingEvidenceKind.WIKI,
                incrementalGeneration, 1, 1, 0, "provider", "model", 2, true, "snapshot-incremental");
        assertThat(repository.find(workspace, EmbeddingEvidenceKind.WIKI).orElseThrow().status())
                .isEqualTo(EmbeddingProjectionReadinessStatus.READY);

        long staleJob = jobs.create(workspace, "embedding-incremental-stale",
                ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long staleGeneration = queueIncremental(workspace, staleJob, EmbeddingEvidenceKind.WIKI, 1);
        repository.markCompletedForGeneration(workspace, staleJob, EmbeddingEvidenceKind.WIKI,
                staleGeneration, 1, 1, 0, "provider", "model", 2, false, null);
        assertThat(repository.find(workspace, EmbeddingEvidenceKind.WIKI).orElseThrow().status())
                .isEqualTo(EmbeddingProjectionReadinessStatus.PARTIAL);

        long failedJob = jobs.create(workspace, "embedding-incremental-failed",
                ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        queueIncremental(workspace, failedJob, EmbeddingEvidenceKind.WIKI, 1);
        repository.markFailed(workspace, failedJob, EmbeddingEvidenceKind.WIKI, "provider failure");
        long recoveryJob = jobs.create(workspace, "embedding-recovery",
                ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long recoveryGeneration = queueIncremental(workspace, recoveryJob, EmbeddingEvidenceKind.WIKI, 1);
        repository.markCompletedForGeneration(workspace, recoveryJob, EmbeddingEvidenceKind.WIKI,
                recoveryGeneration, 1, 1, 0, "provider", "model", 2, false, null);
        assertThat(repository.find(workspace, EmbeddingEvidenceKind.WIKI).orElseThrow().status())
                .isEqualTo(EmbeddingProjectionReadinessStatus.PARTIAL);
    }

    @Test
    void preservesPriorReadyForSourceIncrementalCompletion() {
        long workspace = insertWorkspace();
        long fullJob = jobs.create(workspace, "embedding-source-full", ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long fullGeneration = repository.markQueued(workspace, fullJob, EmbeddingEvidenceKind.SOURCE_CHUNK, 0);
        repository.markCompletedForGeneration(workspace, fullJob, EmbeddingEvidenceKind.SOURCE_CHUNK,
                fullGeneration, 2, 2, 0, "provider", "model", 2, true, "source-full");

        long incrementalJob = jobs.create(workspace, "embedding-source-incremental",
                ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long incrementalGeneration = queueIncremental(workspace, incrementalJob,
                EmbeddingEvidenceKind.SOURCE_CHUNK, 1);
        repository.markRunning(workspace, incrementalJob, EmbeddingEvidenceKind.SOURCE_CHUNK);
        repository.markCompletedForGeneration(workspace, incrementalJob, EmbeddingEvidenceKind.SOURCE_CHUNK,
                incrementalGeneration, 2, 2, 0, "provider", "model", 2, true, "source-incremental");

        assertThat(repository.find(workspace, EmbeddingEvidenceKind.SOURCE_CHUNK).orElseThrow().status())
                .isEqualTo(EmbeddingProjectionReadinessStatus.READY);
    }

    @Test
    void twoQueuedIncrementalsConvergeToReadyWithoutUsingAStaleCallbackProof() {
        long workspace = insertWorkspace();
        long fullJob = jobs.create(workspace, "generation-baseline", ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long fullGeneration = repository.markQueued(workspace, fullJob, EmbeddingEvidenceKind.WIKI, 0);
        repository.markCompletedForGeneration(workspace, fullJob, EmbeddingEvidenceKind.WIKI, fullGeneration,
                2, 2, 0, "provider", "model", 2, true, "full-proof");

        long firstJob = jobs.create(workspace, "generation-first", ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long firstGeneration = queueIncremental(workspace, firstJob, EmbeddingEvidenceKind.WIKI, 1);
        long secondJob = jobs.create(workspace, "generation-second", ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long secondGeneration = queueIncremental(workspace, secondJob, EmbeddingEvidenceKind.WIKI, 1);

        repository.markCompletedForGeneration(workspace, firstJob, EmbeddingEvidenceKind.WIKI, firstGeneration,
                2, 2, 0, "provider", "model", 2, true, "old-proof");
        assertThat(repository.find(workspace, EmbeddingEvidenceKind.WIKI).orElseThrow().status())
                .isEqualTo(EmbeddingProjectionReadinessStatus.QUEUED);

        repository.markCompletedForGeneration(workspace, secondJob, EmbeddingEvidenceKind.WIKI, secondGeneration,
                2, 2, 0, "provider", "model", 2, true, "new-proof");
        assertThat(repository.find(workspace, EmbeddingEvidenceKind.WIKI).orElseThrow())
                .satisfies(state -> {
                    assertThat(state.status()).isEqualTo(EmbeddingProjectionReadinessStatus.READY);
                    assertThat(state.targetGeneration()).isEqualTo(secondGeneration);
                    assertThat(state.appliedGeneration()).isEqualTo(secondGeneration);
                    assertThat(state.projectionSnapshotToken()).isEqualTo("new-proof");
                });
    }

    @Test
    void newerSuccessfulCallbackCanBeCompletedBeforeOlderAndStillConvergesDeterministically() {
        long workspace = insertWorkspace();
        long fullJob = jobs.create(workspace, "out-of-order-baseline", ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long fullGeneration = repository.markQueued(workspace, fullJob, EmbeddingEvidenceKind.WIKI, 0);
        repository.markCompletedForGeneration(workspace, fullJob, EmbeddingEvidenceKind.WIKI, fullGeneration,
                2, 2, 0, "provider", "model", 2, true, "baseline");
        long olderJob = jobs.create(workspace, "out-of-order-older", ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long olderGeneration = queueIncremental(workspace, olderJob, EmbeddingEvidenceKind.WIKI, 1);
        long newerJob = jobs.create(workspace, "out-of-order-newer", ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long newerGeneration = queueIncremental(workspace, newerJob, EmbeddingEvidenceKind.WIKI, 1);

        repository.markCompletedForGeneration(workspace, newerJob, EmbeddingEvidenceKind.WIKI, newerGeneration,
                2, 2, 0, "provider", "model", 2, true, "newer-observation");
        assertThat(repository.find(workspace, EmbeddingEvidenceKind.WIKI).orElseThrow().status())
                .isEqualTo(EmbeddingProjectionReadinessStatus.QUEUED);

        repository.markCompletedForGeneration(workspace, olderJob, EmbeddingEvidenceKind.WIKI, olderGeneration,
                2, 2, 0, "provider", "model", 2, true, "old-observation");
        repository.reconcileCurrentGeneration(workspace, EmbeddingEvidenceKind.WIKI, newerGeneration,
                2, 2, 0, "provider", "model", 2, true, "current-proof");

        assertThat(repository.find(workspace, EmbeddingEvidenceKind.WIKI).orElseThrow().status())
                .isEqualTo(EmbeddingProjectionReadinessStatus.READY);
        assertThat(repository.find(workspace, EmbeddingEvidenceKind.WIKI).orElseThrow().projectionSnapshotToken())
                .isEqualTo("current-proof");
    }

    @Test
    void historicalOlderFailureDoesNotBlockNewerCompleteProof() {
        long workspace = insertWorkspace();
        long fullJob = jobs.create(workspace, "failure-baseline", ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long fullGeneration = repository.markQueued(workspace, fullJob, EmbeddingEvidenceKind.WIKI, 0);
        repository.markCompletedForGeneration(workspace, fullJob, EmbeddingEvidenceKind.WIKI, fullGeneration,
                2, 2, 0, "provider", "model", 2, true, "baseline");
        long olderJob = jobs.create(workspace, "failure-older", ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        queueIncremental(workspace, olderJob, EmbeddingEvidenceKind.WIKI, 1);
        long newerJob = jobs.create(workspace, "failure-newer", ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long newerGeneration = queueIncremental(workspace, newerJob, EmbeddingEvidenceKind.WIKI, 1);

        repository.markFailed(workspace, olderJob, EmbeddingEvidenceKind.WIKI, "older failure");
        repository.markCompletedForGeneration(workspace, newerJob, EmbeddingEvidenceKind.WIKI, newerGeneration,
                2, 2, 0, "provider", "model", 2, true, "newer-proof");

        assertThat(repository.find(workspace, EmbeddingEvidenceKind.WIKI).orElseThrow().status())
                .isEqualTo(EmbeddingProjectionReadinessStatus.READY);
    }

    @Test
    void newerFailureAfterOlderSuccessIsFailedAndNotReady() {
        long workspace = insertWorkspace();
        long fullJob = jobs.create(workspace, "newer-failure-baseline", ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long fullGeneration = repository.markQueued(workspace, fullJob, EmbeddingEvidenceKind.WIKI, 0);
        repository.markCompletedForGeneration(workspace, fullJob, EmbeddingEvidenceKind.WIKI, fullGeneration,
                2, 2, 0, "provider", "model", 2, true, "baseline");
        long olderJob = jobs.create(workspace, "newer-failure-older", ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long olderGeneration = queueIncremental(workspace, olderJob, EmbeddingEvidenceKind.WIKI, 1);
        long newerJob = jobs.create(workspace, "newer-failure-newer", ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        queueIncremental(workspace, newerJob, EmbeddingEvidenceKind.WIKI, 1);

        repository.markCompletedForGeneration(workspace, olderJob, EmbeddingEvidenceKind.WIKI, olderGeneration,
                2, 2, 0, "provider", "model", 2, true, "older-proof");
        repository.markFailed(workspace, newerJob, EmbeddingEvidenceKind.WIKI, "newer failure");

        assertThat(repository.find(workspace, EmbeddingEvidenceKind.WIKI).orElseThrow().status())
                .isEqualTo(EmbeddingProjectionReadinessStatus.FAILED);
    }

    @Test
    void incrementalAfterNewerFullFailureCannotResurrectOlderReadyProof() {
        long workspace = insertWorkspace();
        long baselineJob = jobs.create(workspace, "full-failure-barrier-baseline",
                ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long baselineGeneration = repository.markQueued(workspace, baselineJob, EmbeddingEvidenceKind.WIKI, 0);
        repository.markCompletedForGeneration(workspace, baselineJob, EmbeddingEvidenceKind.WIKI,
                baselineGeneration, 2, 2, 0, "provider", "model", 2, true, "baseline-proof");

        long failedFullJob = jobs.create(workspace, "full-failure-barrier-full",
                ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        repository.markQueued(workspace, failedFullJob, EmbeddingEvidenceKind.WIKI, 0);
        repository.markFailed(workspace, failedFullJob, EmbeddingEvidenceKind.WIKI, "full rebuild failed");

        long incrementalJob = jobs.create(workspace, "full-failure-barrier-incremental",
                ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long incrementalGeneration = queueIncremental(workspace, incrementalJob,
                EmbeddingEvidenceKind.WIKI, 1);
        repository.markCompletedForGeneration(workspace, incrementalJob, EmbeddingEvidenceKind.WIKI,
                incrementalGeneration, 2, 2, 0, "provider", "model", 2, true, "unsafe-repair-proof");

        assertThat(repository.find(workspace, EmbeddingEvidenceKind.WIKI).orElseThrow())
                .satisfies(state -> {
                    assertThat(state.status()).isIn(EmbeddingProjectionReadinessStatus.STALE,
                            EmbeddingProjectionReadinessStatus.FAILED);
                    assertThat(state.appliedGeneration()).isNotEqualTo(incrementalGeneration);
                    assertThat(state.projectionSnapshotToken()).isNull();
                });
    }

    @Test
    void completedFullBaselineMayBeFollowedByQueuedIncrementalGeneration() {
        long workspace = insertWorkspace();
        long fullJob = jobs.create(workspace, "overlap-full", ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long fullGeneration = repository.markQueued(workspace, fullJob, EmbeddingEvidenceKind.WIKI, 0);
        long incrementalJob = jobs.create(workspace, "overlap-incremental",
                ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long incrementalGeneration = queueIncremental(workspace, incrementalJob, EmbeddingEvidenceKind.WIKI, 1);

        repository.markCompletedForGeneration(workspace, fullJob, EmbeddingEvidenceKind.WIKI,
                fullGeneration, 2, 2, 0, "provider", "model", 2, true, "full-proof");
        assertThat(repository.find(workspace, EmbeddingEvidenceKind.WIKI).orElseThrow().status())
                .isEqualTo(EmbeddingProjectionReadinessStatus.QUEUED);

        repository.markCompletedForGeneration(workspace, incrementalJob, EmbeddingEvidenceKind.WIKI,
                incrementalGeneration, 2, 2, 0, "provider", "model", 2, true, "incremental-proof");

        assertThat(repository.find(workspace, EmbeddingEvidenceKind.WIKI).orElseThrow())
                .satisfies(state -> {
                    assertThat(state.status()).isEqualTo(EmbeddingProjectionReadinessStatus.READY);
                    assertThat(state.appliedGeneration()).isEqualTo(incrementalGeneration);
                    assertThat(state.projectionSnapshotToken()).isEqualTo("incremental-proof");
                });
    }

    @Test
    void newerFullGenerationSupersedesAnEarlierIncrementalRepair() {
        long workspace = insertWorkspace();
        long incrementalJob = jobs.create(workspace, "overlap-incremental-first",
                ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long incrementalGeneration = queueIncremental(workspace, incrementalJob, EmbeddingEvidenceKind.WIKI, 1);
        repository.markFailed(workspace, incrementalJob, EmbeddingEvidenceKind.WIKI, "incremental unavailable");

        long fullJob = jobs.create(workspace, "overlap-full-second",
                ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long fullGeneration = repository.markQueued(workspace, fullJob, EmbeddingEvidenceKind.WIKI, 0);
        assertThat(fullGeneration).isGreaterThan(incrementalGeneration);
        repository.markCompletedForGeneration(workspace, fullJob, EmbeddingEvidenceKind.WIKI,
                fullGeneration, 2, 2, 0, "provider", "model", 2, true, "full-proof");

        assertThat(repository.find(workspace, EmbeddingEvidenceKind.WIKI).orElseThrow().status())
                .isEqualTo(EmbeddingProjectionReadinessStatus.READY);
    }

    @Test
    void incrementalIdentityDriftRequiresFullRebuild() {
        long workspace = insertWorkspace();
        long fullJob = jobs.create(workspace, "identity-baseline", ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long fullGeneration = repository.markQueued(workspace, fullJob, EmbeddingEvidenceKind.WIKI, 0);
        repository.markCompletedForGeneration(workspace, fullJob, EmbeddingEvidenceKind.WIKI, fullGeneration,
                2, 2, 0, "provider-a", "model-a", 2, true, "baseline");

        long incrementalJob = jobs.create(workspace, "identity-drift", ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long generation = queueIncremental(workspace, incrementalJob, EmbeddingEvidenceKind.WIKI, 1);
        repository.markCompletedForGeneration(workspace, incrementalJob, EmbeddingEvidenceKind.WIKI, generation,
                2, 2, 0, "provider-b", "model-b", 4, true, "mixed-or-drifted");

        assertThat(repository.find(workspace, EmbeddingEvidenceKind.WIKI).orElseThrow())
                .satisfies(state -> {
                    assertThat(state.status()).isEqualTo(EmbeddingProjectionReadinessStatus.STALE);
                    assertThat(state.projectionSnapshotToken()).isNull();
                    assertThat(state.failureDetail()).contains("full rebuild required");
                });
    }

    @Test
    void fullRebuildMixedIdentityFailsClosedAsStale() {
        long workspace = insertWorkspace();
        long job = jobs.create(workspace, "full-mixed-identity",
                ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long generation = repository.markQueued(workspace, job, EmbeddingEvidenceKind.WIKI, 0);

        // A full operation may observe mixed provider/model/dimension/version rows when the
        // provider changes during execution or a newer overlap is still being reconciled. That
        // observation is not a partial-count condition: the next safe boundary is full rebuild.
        repository.markCompletedForGeneration(workspace, job, EmbeddingEvidenceKind.WIKI, generation,
                2, 2, 0, "provider-a", "model-a", 2, false, true, null);

        assertThat(repository.find(workspace, EmbeddingEvidenceKind.WIKI).orElseThrow())
                .satisfies(state -> {
                    assertThat(state.status()).isEqualTo(EmbeddingProjectionReadinessStatus.STALE);
                    assertThat(state.projectionSnapshotToken()).isNull();
                    assertThat(state.failureDetail()).contains("full rebuild required");
                });
    }

    @Test
    void emptyFullRebuildHasDeterministicReadyProof() {
        long workspace = insertWorkspace();
        long job = jobs.create(workspace, "empty-corpus", ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long generation = repository.markQueued(workspace, job, EmbeddingEvidenceKind.WIKI, 0);
        repository.markCompletedForGeneration(workspace, job, EmbeddingEvidenceKind.WIKI, generation,
                0, 0, 0, null, null, null, true, "empty-proof");

        assertThat(repository.find(workspace, EmbeddingEvidenceKind.WIKI).orElseThrow())
                .satisfies(state -> {
                    assertThat(state.status()).isEqualTo(EmbeddingProjectionReadinessStatus.READY);
                    assertThat(state.targetGeneration()).isEqualTo(generation);
                    assertThat(state.appliedGeneration()).isEqualTo(generation);
                    assertThat(state.projectionSnapshotToken()).isEqualTo("empty-proof");
                });
    }

    @Test
    void schedulingInvalidationIsPersistedEvenWhenNoJobCanBeCreated() {
        long workspace = insertWorkspace();
        repository.markSchedulingStale(workspace, EmbeddingEvidenceKind.SOURCE_CHUNK,
                "enqueue unavailable");

        EmbeddingProjectionReadiness state = repository.find(workspace, EmbeddingEvidenceKind.SOURCE_CHUNK)
                .orElseThrow();
        assertThat(state.status()).isEqualTo(EmbeddingProjectionReadinessStatus.STALE);
        assertThat(state.processingJobId()).isNull();
        assertThat(state.failureDetail()).isEqualTo("enqueue unavailable");
        assertThat(state.failedCount()).isZero();
    }

    @Test
    void failedSchedulingRetryCannotReuseAnOldReadyInvariant() {
        long workspace = insertWorkspace();
        long fullJob = jobs.create(workspace, "embedding-full-before-scheduling-failure",
                ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long fullGeneration = repository.markQueued(workspace, fullJob, EmbeddingEvidenceKind.WIKI, 0);
        repository.markCompletedForGeneration(workspace, fullJob, EmbeddingEvidenceKind.WIKI, fullGeneration,
                1, 1, 0, "provider", "model", 2, true, "ready");

        repository.markSchedulingStale(workspace, EmbeddingEvidenceKind.WIKI, "enqueue unavailable");

        long retryJob = jobs.create(workspace, "embedding-retry-after-scheduling-failure",
                ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long retryGeneration = queueIncremental(workspace, retryJob, EmbeddingEvidenceKind.WIKI, 1);
        repository.markCompletedForGeneration(workspace, retryJob, EmbeddingEvidenceKind.WIKI,
                retryGeneration, 1, 1, 0, "provider", "model", 2, false, null);

        assertThat(repository.find(workspace, EmbeddingEvidenceKind.WIKI).orElseThrow().status())
                .isEqualTo(EmbeddingProjectionReadinessStatus.PARTIAL);
    }

    @Test
    void failureAccountingNeverExceedsExpectedCount() {
        long workspace = insertWorkspace();
        long job = jobs.create(workspace, "embedding-zero-expected", ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        repository.markQueued(workspace, job, EmbeddingEvidenceKind.WIKI, 0);
        repository.markFailed(workspace, job, EmbeddingEvidenceKind.WIKI, "dispatch rejected");

        EmbeddingProjectionReadiness state = repository.find(workspace, EmbeddingEvidenceKind.WIKI).orElseThrow();
        assertThat(state.failedCount()).isLessThanOrEqualTo(state.expectedCount());
        assertThat(state.failedCount()).isZero();
    }

    @Test
    void delayedFailureCallbackCannotRewriteCompletedProcessingJob() {
        long workspace = insertWorkspace();
        long job = jobs.create(workspace, "late-failure-callback", ProcessingJobType.EMBEDDING_REBUILD, 0).id();
        jobs.markCompleted(job);

        jobs.markFailed(job, 0, 0, 1, "late callback");

        assertThat(jobStatus(job)).isEqualTo("COMPLETED");
    }

    @Test
    void interruptedRecoveryOnlyMarksLinkedEmbeddingJobs() {
        long workspace = insertWorkspace();
        long embeddingJob = jobs.create(workspace, "embedding-interrupted", ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long unrelatedJob = jobs.create(workspace, "other", ProcessingJobType.FTS_REBUILD, 1).id();
        repository.markQueued(workspace, embeddingJob, EmbeddingEvidenceKind.SOURCE_CHUNK, 1);
        repository.markQueued(workspace, unrelatedJob, EmbeddingEvidenceKind.WIKI, 1);

        repository.markInterrupted(java.util.List.of(embeddingJob), "restart");
        assertThat(repository.find(workspace, EmbeddingEvidenceKind.SOURCE_CHUNK).orElseThrow().status())
                .isEqualTo(EmbeddingProjectionReadinessStatus.FAILED);
        assertThat(repository.find(workspace, EmbeddingEvidenceKind.WIKI).orElseThrow().status())
                .isEqualTo(EmbeddingProjectionReadinessStatus.QUEUED);
    }

    @Test
    void startupRecoveryDoesNotLetInterruptedOlderGenerationDestroyNewerProof() {
        long workspace = insertWorkspace();
        long baselineJob = jobs.create(workspace, "restart-baseline", ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long baselineGeneration = repository.markQueued(workspace, baselineJob, EmbeddingEvidenceKind.WIKI, 0);
        repository.markCompletedForGeneration(workspace, baselineJob, EmbeddingEvidenceKind.WIKI,
                baselineGeneration, 2, 2, 0, "provider", "model", 2, true, "baseline-proof");
        jobs.markCompleted(baselineJob);

        long olderJob = jobs.create(workspace, "restart-older", ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long olderGeneration = queueIncremental(workspace, olderJob, EmbeddingEvidenceKind.WIKI, 1);
        long newerJob = jobs.create(workspace, "restart-newer", ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long newerGeneration = queueIncremental(workspace, newerJob, EmbeddingEvidenceKind.WIKI, 1);
        repository.markCompletedForGeneration(workspace, newerJob, EmbeddingEvidenceKind.WIKI, newerGeneration,
                2, 2, 0, "provider", "model", 2, true, "newer-proof");
        jobs.markCompleted(newerJob);

        startupReconciler.reconcile();

        assertThat(repository.find(workspace, EmbeddingEvidenceKind.WIKI).orElseThrow())
                .satisfies(state -> {
                    assertThat(state.status()).isEqualTo(EmbeddingProjectionReadinessStatus.READY);
                    assertThat(state.targetGeneration()).isEqualTo(newerGeneration);
                    assertThat(state.appliedGeneration()).isEqualTo(newerGeneration);
                    assertThat(state.projectionSnapshotToken()).isEqualTo("newer-proof");
                });
        assertThat(jobStatus(olderJob)).isEqualTo("FAILED");
        assertThat(jobStatus(newerJob)).isEqualTo("COMPLETED");
        assertThat(repository.generationFor(workspace, olderJob, EmbeddingEvidenceKind.WIKI))
                .isEqualTo(olderGeneration);
    }

    @Test
    void startupRecoveryFailsClosedWhenCurrentGenerationWasInterrupted() {
        long workspace = insertWorkspace();
        long baselineJob = jobs.create(workspace, "restart-current-baseline",
                ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long baselineGeneration = repository.markQueued(workspace, baselineJob, EmbeddingEvidenceKind.WIKI, 0);
        repository.markCompletedForGeneration(workspace, baselineJob, EmbeddingEvidenceKind.WIKI,
                baselineGeneration, 1, 1, 0, "provider", "model", 2, true, "baseline-proof");
        jobs.markCompleted(baselineJob);

        long currentJob = jobs.create(workspace, "restart-current", ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long currentGeneration = queueIncremental(workspace, currentJob, EmbeddingEvidenceKind.WIKI, 1);
        long unrelatedJob = jobs.create(workspace, "restart-unrelated", ProcessingJobType.FTS_REBUILD, 1).id();

        startupReconciler.reconcile();

        assertThat(repository.find(workspace, EmbeddingEvidenceKind.WIKI).orElseThrow())
                .satisfies(state -> {
                    assertThat(state.status()).isEqualTo(EmbeddingProjectionReadinessStatus.FAILED);
                    assertThat(state.targetGeneration()).isEqualTo(currentGeneration);
                    assertThat(state.appliedGeneration()).isEqualTo(baselineGeneration);
                    assertThat(state.projectionSnapshotToken()).isNull();
                });
        assertThat(jobStatus(currentJob)).isEqualTo("FAILED");
        assertThat(jobStatus(unrelatedJob)).isEqualTo("QUEUED");
    }

    private long insertWorkspace() {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO workspace (name, root_path, inbox_path, archive_path, vault_path, data_path,
                    status, created_at, updated_at)
                VALUES ('readiness', '/tmp/readiness', '/tmp/readiness/inbox', '/tmp/readiness/archive',
                    '/tmp/readiness/vault', '/tmp/readiness/data', 'ACTIVE', '2026-09-02T00:00:00Z',
                    '2026-09-02T00:00:00Z')
                """).update(key);
        return key.getKey().longValue();
    }

    private long queueIncremental(long workspace, long job, EmbeddingEvidenceKind corpus, int expected) {
        long generation = repository.markSchedulingStale(workspace, corpus, "canonical changed");
        repository.markQueued(workspace, job, corpus, expected, generation);
        return generation;
    }

    private String jobStatus(long jobId) {
        return jdbc.sql("SELECT status FROM processing_job WHERE id = :id")
                .param("id", jobId).query(String.class).single();
    }
}
