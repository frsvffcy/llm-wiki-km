package org.km.llmwiki.testsupport;

import org.junit.jupiter.api.Test;
import org.km.llmwiki.search.embedding.EmbeddingEvidenceKind;
import org.km.llmwiki.search.embedding.EmbeddingProjectionOperationStatus;
import org.km.llmwiki.search.embedding.EmbeddingProjectionResult;
import org.km.llmwiki.search.embedding.EmbeddingProjectionService;
import org.km.llmwiki.search.embedding.EmbeddingProjectionJobService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

/** Regression coverage for draining asynchronous embedding work before shared SQLite cleanup. */
class EmbeddingProjectionResetIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private EmbeddingProjectionJobService embeddingJobs;

    @MockitoSpyBean
    private EmbeddingProjectionService projectionService;

    @Test
    void waitsForInFlightEmbeddingJobBeforeDeletingItsProcessingRows() throws Exception {
        long workspaceId = insertWorkspace();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);

        doAnswer(invocation -> {
            started.countDown();
            try {
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                return new EmbeddingProjectionResult(EmbeddingProjectionOperationStatus.FRESH,
                        workspaceId, EmbeddingEvidenceKind.WIKI, "1", "hash", null, null);
            } finally {
                finished.countDown();
            }
        }).when(projectionService).projectWiki(anyLong(), anyLong(), anyLong());

        embeddingJobs.enqueueWiki(workspaceId, 1L);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        ExecutorService resetExecutor = Executors.newSingleThreadExecutor();
        Future<?> reset = resetExecutor.submit(() -> {
            try {
                resetApplicationTables();
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        });
        try {
            assertThatThrownBy(() -> reset.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            release.countDown();
            reset.get(5, TimeUnit.SECONDS);
            assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(count("processing_job")).isZero();
            assertThat(count("processing_log")).isZero();
        } finally {
            release.countDown();
            resetExecutor.shutdownNow();
        }
    }

    private long insertWorkspace() {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO workspace (name, root_path, inbox_path, archive_path, vault_path, data_path,
                    status, created_at, updated_at)
                VALUES ('embedding-reset', '/tmp/embedding-reset', '/tmp/embedding-reset/inbox',
                    '/tmp/embedding-reset/archive', '/tmp/embedding-reset/vault', '/tmp/embedding-reset/data',
                    'ACTIVE', '2026-09-03T00:00:00Z', '2026-09-03T00:00:00Z')
                """).update(key);
        return key.getKey().longValue();
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }
}
