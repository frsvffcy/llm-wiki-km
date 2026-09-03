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
    @Autowired JdbcClient jdbc;

    @Test
    void distinguishesInitialBuildPartialReadyAndProviderDrift() {
        long workspace = insertWorkspace();
        long job = jobs.create(workspace, "embedding-readiness", ProcessingJobType.EMBEDDING_REBUILD, 1).id();

        repository.markQueued(workspace, job, EmbeddingEvidenceKind.WIKI, 1);
        assertThat(repository.find(workspace, EmbeddingEvidenceKind.WIKI).orElseThrow().status())
                .isEqualTo(EmbeddingProjectionReadinessStatus.QUEUED);
        repository.markRunning(workspace, job, EmbeddingEvidenceKind.WIKI);
        repository.markCompleted(workspace, job, EmbeddingEvidenceKind.WIKI, 0, 1, 1,
                null, null, null, true);
        assertThat(repository.find(workspace, EmbeddingEvidenceKind.WIKI).orElseThrow().status())
                .isEqualTo(EmbeddingProjectionReadinessStatus.PARTIAL);

        repository.markQueued(workspace, job, EmbeddingEvidenceKind.WIKI, 1);
        repository.markCompleted(workspace, job, EmbeddingEvidenceKind.WIKI, 1, 1, 0,
                "test", "model-a", 2, true);
        assertThat(repository.find(workspace, EmbeddingEvidenceKind.WIKI).orElseThrow().status())
                .isEqualTo(EmbeddingProjectionReadinessStatus.READY);
        repository.markStale(workspace, EmbeddingEvidenceKind.WIKI, "model changed");
        assertThat(repository.find(workspace, EmbeddingEvidenceKind.WIKI).orElseThrow().status())
                .isEqualTo(EmbeddingProjectionReadinessStatus.STALE);
    }

    @Test
    void preservesPriorReadyOnlyForSuccessfulIncrementalCompletion() {
        long workspace = insertWorkspace();
        long fullJob = jobs.create(workspace, "embedding-full", ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        repository.markQueued(workspace, fullJob, EmbeddingEvidenceKind.WIKI, 1);
        repository.markCompleted(workspace, fullJob, EmbeddingEvidenceKind.WIKI, 1, 1, 0,
                "provider", "model", 2, true);

        long incrementalJob = jobs.create(workspace, "embedding-incremental-ready",
                ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        repository.markQueued(workspace, incrementalJob, EmbeddingEvidenceKind.WIKI, 1);
        repository.markRunning(workspace, incrementalJob, EmbeddingEvidenceKind.WIKI);
        repository.markCompleted(workspace, incrementalJob, EmbeddingEvidenceKind.WIKI, 1, 1, 0,
                "provider", "model", 2, false);
        assertThat(repository.find(workspace, EmbeddingEvidenceKind.WIKI).orElseThrow().status())
                .isEqualTo(EmbeddingProjectionReadinessStatus.READY);

        long staleJob = jobs.create(workspace, "embedding-incremental-stale",
                ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        repository.markStale(workspace, EmbeddingEvidenceKind.WIKI, "canonical changed");
        repository.markQueued(workspace, staleJob, EmbeddingEvidenceKind.WIKI, 1);
        repository.markCompleted(workspace, staleJob, EmbeddingEvidenceKind.WIKI, 1, 1, 0,
                "provider", "model", 2, false);
        assertThat(repository.find(workspace, EmbeddingEvidenceKind.WIKI).orElseThrow().status())
                .isEqualTo(EmbeddingProjectionReadinessStatus.PARTIAL);

        long failedJob = jobs.create(workspace, "embedding-incremental-failed",
                ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        repository.markQueued(workspace, failedJob, EmbeddingEvidenceKind.WIKI, 1);
        repository.markFailed(workspace, failedJob, EmbeddingEvidenceKind.WIKI, "provider failure");
        long recoveryJob = jobs.create(workspace, "embedding-recovery",
                ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        repository.markQueued(workspace, recoveryJob, EmbeddingEvidenceKind.WIKI, 1);
        repository.markCompleted(workspace, recoveryJob, EmbeddingEvidenceKind.WIKI, 1, 1, 0,
                "provider", "model", 2, false);
        assertThat(repository.find(workspace, EmbeddingEvidenceKind.WIKI).orElseThrow().status())
                .isEqualTo(EmbeddingProjectionReadinessStatus.PARTIAL);
    }

    @Test
    void preservesPriorReadyForSourceIncrementalCompletion() {
        long workspace = insertWorkspace();
        long fullJob = jobs.create(workspace, "embedding-source-full", ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        repository.markQueued(workspace, fullJob, EmbeddingEvidenceKind.SOURCE_CHUNK, 2);
        repository.markCompleted(workspace, fullJob, EmbeddingEvidenceKind.SOURCE_CHUNK, 2, 2, 0,
                "provider", "model", 2, true);

        long incrementalJob = jobs.create(workspace, "embedding-source-incremental",
                ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        repository.markQueued(workspace, incrementalJob, EmbeddingEvidenceKind.SOURCE_CHUNK, 1);
        repository.markRunning(workspace, incrementalJob, EmbeddingEvidenceKind.SOURCE_CHUNK);
        repository.markCompleted(workspace, incrementalJob, EmbeddingEvidenceKind.SOURCE_CHUNK, 2, 2, 0,
                "provider", "model", 2, false);

        assertThat(repository.find(workspace, EmbeddingEvidenceKind.SOURCE_CHUNK).orElseThrow().status())
                .isEqualTo(EmbeddingProjectionReadinessStatus.READY);
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
        repository.markQueued(workspace, fullJob, EmbeddingEvidenceKind.WIKI, 1);
        repository.markCompleted(workspace, fullJob, EmbeddingEvidenceKind.WIKI, 1, 1, 0,
                "provider", "model", 2, true);

        repository.markSchedulingStale(workspace, EmbeddingEvidenceKind.WIKI, "canonical changed");
        repository.markSchedulingStale(workspace, EmbeddingEvidenceKind.WIKI, "enqueue unavailable");

        long retryJob = jobs.create(workspace, "embedding-retry-after-scheduling-failure",
                ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        repository.markQueued(workspace, retryJob, EmbeddingEvidenceKind.WIKI, 1);
        repository.markCompleted(workspace, retryJob, EmbeddingEvidenceKind.WIKI, 1, 1, 0,
                "provider", "model", 2, false);

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
}
