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
