package org.km.llmwiki.search;

import org.junit.jupiter.api.Test;
import org.km.llmwiki.processing.ProcessingJob;
import org.km.llmwiki.processing.ProcessingJobRepository;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deterministic concurrent admission contract for FTS rebuilds (Issue #283). Every critical
 * window is reproduced with explicit transaction state, barriers, or latches — never with
 * sleeps or wall-clock luck. The documented semantics: a duplicate admission for the same
 * workspace with an overlapping physical corpus is a typed reject
 * ({@code FTS_REBUILD_IN_PROGRESS}, HTTP 409); {@code WIKI} and {@code SOURCE} may be
 * admitted concurrently while {@code ALL} conflicts with both; a rejected admission leaves no
 * orphan {@code processing_job}; and a worker can only complete state it owns.
 */
class FtsRebuildAdmissionIntegrationTest extends IsolatedIntegrationTest {

    private static final String NOW = "2026-09-09T00:00:00Z";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FtsRebuildStateRepository rebuildStateRepository;

    @Autowired
    private ProcessingJobRepository jobRepository;

    @Autowired
    private FtsRebuildStartupReconciler startupReconciler;

    @Autowired
    private WorkspaceService workspaceService;

    @Autowired
    @Qualifier("ftsRebuildTaskExecutor")
    private TaskExecutor ftsWorker;

    @Autowired
    private DataSource dataSource;

    @Test
    void duplicateAdmissionIsATypedRejectWithoutOrphanJob() throws Exception {
        long workspaceId = insertWorkspace("dup-admit");
        CountDownLatch workerBlocked = new CountDownLatch(1);
        CountDownLatch workerStarted = new CountDownLatch(1);
        ftsWorker.execute(() -> {
            workerStarted.countDown();
            awaitQuietly(workerBlocked);
        });
        String firstJob;
        try {
            assertThat(workerStarted.await(5, TimeUnit.SECONDS)).isTrue();

            firstJob = startRebuild("WIKI");
            assertThat(rebuildState(workspaceId, "WIKI").processingJobId())
                    .isEqualTo(jobId(firstJob));

            MvcResult conflict = mockMvc.perform(post("/api/v1/search/index/rebuild")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"corpus\":\"WIKI\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("FTS_REBUILD_IN_PROGRESS"))
                    .andExpect(jsonPath("$.error.message").value(
                            "An FTS rebuild is already in progress for this workspace and corpus"))
                    .andReturn();

            // The rejected admission leaves no orphan processing job and no stolen owner.
            assertThat(jobCount(workspaceId)).isEqualTo(1L);
            assertThat(rebuildState(workspaceId, "WIKI").processingJobId())
                    .isEqualTo(jobId(firstJob));
            assertThat(conflict.getResponse().getContentAsString()).doesNotContain("IllegalStateException");
        } finally {
            workerBlocked.countDown();
        }
        awaitJob(firstJob, "COMPLETED");
        assertThat(startRebuild("WIKI")).isNotBlank();
    }

    @Test
    void wikiAndSourceCoexistWhileAllBlocksBoth() throws Exception {
        long workspaceId = insertWorkspace("overlap");
        CountDownLatch workerBlocked = new CountDownLatch(1);
        CountDownLatch workerStarted = new CountDownLatch(1);
        ftsWorker.execute(() -> {
            workerStarted.countDown();
            awaitQuietly(workerBlocked);
        });
        String wikiJob;
        try {
            assertThat(workerStarted.await(5, TimeUnit.SECONDS)).isTrue();

            wikiJob = startRebuild("WIKI");
            // WIKI and SOURCE do not overlap physically: both may be admitted concurrently.
            startRebuild("SOURCE");
            // ALL overlaps both in-progress corpora and must be rejected atomically.
            mockMvc.perform(post("/api/v1/search/index/rebuild")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"corpus\":\"ALL\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("FTS_REBUILD_IN_PROGRESS"));
            mockMvc.perform(post("/api/v1/search/index/rebuild")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"corpus\":\"WIKI\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("FTS_REBUILD_IN_PROGRESS"));

            assertThat(rebuildState(workspaceId, "WIKI").status()).isEqualTo(FtsRebuildStatus.QUEUED);
            assertThat(rebuildState(workspaceId, "SOURCE").status()).isEqualTo(FtsRebuildStatus.QUEUED);
        } finally {
            workerBlocked.countDown();
        }
        awaitJob(wikiJob, "COMPLETED");
        awaitJob(jobOfCorpus(workspaceId, "SOURCE"), "COMPLETED");
        mockMvc.perform(post("/api/v1/search/index/rebuild")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"corpus\":\"ALL\"}"))
                .andExpect(status().isAccepted());
    }

    @Test
    void lateCompletionOnlyCompletesTheStateItOwns() throws Exception {
        long workspaceId = insertWorkspace("late-callback");
        String firstJob = startRebuild("WIKI");
        awaitJob(firstJob, "COMPLETED");
        long ownerJobId = jobId(firstJob);

        // A newer operation re-owns the same corpus after the previous one reached terminal
        // state (the admission contract allows re-admission only from a terminal owner).
        ProcessingJob newer = jobRepository.create(workspaceId, "late-callback-newer",
                org.km.llmwiki.processing.ProcessingJobType.FTS_REBUILD, 1);
        assertThat(rebuildStateRepository.claimQueued(workspaceId, newer.id(),
                List.of(SearchCorpus.WIKI))).isEqualTo(1);

        // A late completion from the previous worker must not touch the newer ownership.
        rebuildStateRepository.markRunning(workspaceId, ownerJobId, List.of(SearchCorpus.WIKI));
        rebuildStateRepository.markCompleted(workspaceId, ownerJobId, SearchCorpus.WIKI, 99);
        FtsRebuildState state = rebuildState(workspaceId, "WIKI");
        assertThat(state.status()).isEqualTo(FtsRebuildStatus.QUEUED);
        assertThat(state.processingJobId()).isEqualTo(newer.id());

        // The owning worker completes its own state.
        rebuildStateRepository.markRunning(workspaceId, newer.id(), List.of(SearchCorpus.WIKI));
        rebuildStateRepository.markCompleted(workspaceId, newer.id(), SearchCorpus.WIKI, 1);
        assertThat(rebuildState(workspaceId, "WIKI").status()).isEqualTo(FtsRebuildStatus.COMPLETED);
        assertThat(rebuildState(workspaceId, "WIKI").processingJobId()).isEqualTo(newer.id());
    }

    @Test
    void reconcilerRecoversOnlyInProgressOwnersAndKeepsCompletedState() throws Exception {
        long workspaceId = insertWorkspace("reconcile-owner");
        String job = startRebuild("ALL");
        awaitJob(job, "COMPLETED");

        FtsRebuildStartupReconciler.RecoveryResult result = startupReconciler.reconcile();
        assertThat(result.rebuildStates()).isZero();
        assertThat(result.processingJobs()).isZero();
        assertThat(rebuildState(workspaceId, "WIKI").status()).isEqualTo(FtsRebuildStatus.COMPLETED);
        assertThat(rebuildState(workspaceId, "SOURCE").status()).isEqualTo(FtsRebuildStatus.COMPLETED);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/search/index/health").param("corpus", "ALL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("HEALTHY"));
    }

    @Test
    void checkThenAdmissionCriticalWindowFailsTypedAndLeavesNoOrphan() throws Exception {
        long workspaceId = insertWorkspace("critical-window");
        // Window 1 — the legacy check-then-create pattern: a connection that read the
        // in-progress guard before another admission committed must fail its write (stale
        // snapshot), never silently double-admit, and roll back its own job.
        try (Connection reader = dataSource.getConnection()) {
            reader.setAutoCommit(false);
            int observed = inProgressCount(reader, workspaceId);
            assertThat(observed).isZero();

            admitViaRepository(workspaceId, "window-1-owner", SearchCorpus.WIKI);

            SQLException writeFailure = null;
            try (PreparedStatement insert = reader.prepareStatement("""
                    INSERT INTO processing_job (workspace_id, job_id, job_type, status, total_count,
                        created_at, updated_at) VALUES (?, 'window-1-orphan', 'FTS_REBUILD', 'QUEUED',
                        1, ?, ?)
                    """)) {
                insert.setLong(1, workspaceId);
                insert.setString(2, NOW);
                insert.setString(3, NOW);
                insert.executeUpdate();
            } catch (SQLException exception) {
                writeFailure = exception;
            } finally {
                reader.rollback();
            }
            // The stale-snapshot write fails as a SQLite busy-type error, deterministically.
            assertThat((Throwable) writeFailure).isInstanceOf(org.sqlite.SQLiteException.class);
            assertThat(jobIdCount(workspaceId, "window-1-orphan")).isZero();
            assertThat(inProgressOwner(workspaceId, "WIKI")).isEqualTo("window-1-owner");
        }

        // Window 2 — the production claim contract: a competing admission inside a write
        // transaction re-evaluates ownership at write time, observes the committed owner,
        // and rolls its own job back without disturbing the winner.
        try (Connection competitor = dataSource.getConnection()) {
            competitor.setAutoCommit(false);
            try (Statement statement = competitor.createStatement()) {
                statement.execute("PRAGMA busy_timeout = 300");
            }
            long competitorJobId;
            try (PreparedStatement insert = competitor.prepareStatement("""
                    INSERT INTO processing_job (workspace_id, job_id, job_type, status, total_count,
                        created_at, updated_at) VALUES (?, 'window-2-competitor', 'FTS_REBUILD',
                        'QUEUED', 1, ?, ?)
                    """)) {
                insert.setLong(1, workspaceId);
                insert.setString(2, NOW);
                insert.setString(3, NOW);
                insert.executeUpdate();
                competitorJobId = lastInsertedId(competitor);
            }
            int claimed = claimOn(competitor, workspaceId, competitorJobId, "WIKI");
            competitor.rollback();
            assertThat(claimed).isZero();
            assertThat(rebuildState(workspaceId, "WIKI").processingJobId())
                    .isEqualTo(jobId("window-1-owner"));
            assertThat(jobIdCount(workspaceId, "window-2-competitor")).isZero();
        }
    }

    @Test
    void uncommittedOwnerMakesACompetingClaimWaitThenFailWithoutStealing() throws Exception {
        long workspaceId = insertWorkspace("uncommitted-owner");
        try (Connection owner = dataSource.getConnection();
             Connection competitor = dataSource.getConnection()) {
            owner.setAutoCommit(false);
            competitor.setAutoCommit(false);
            try (Statement statement = competitor.createStatement()) {
                statement.execute("PRAGMA busy_timeout = 300");
            }

            long ownerJobId = rawInsertJob(owner, workspaceId, "uncommitted-owner");
            assertThat(claimOn(owner, workspaceId, ownerJobId, "WIKI")).isEqualTo(1);

            long competitorJobId;
            SQLException contention;
            try {
                // The competitor's admission fails at its first write: an uncommitted owner
                // holds the SQLite write lock, so the challenger waits for its configured
                // busy_timeout and then observes a lock failure — it never steals ownership
                // and never leaves a partially admitted job behind.
                competitorJobId = rawInsertJob(competitor, workspaceId, "uncommitted-competitor");
                contention = null;
            } catch (SQLException exception) {
                contention = exception;
                competitorJobId = -1;
            }
            assertThat((Throwable) contention).isNotNull();

            competitor.rollback();
            owner.commit();
            assertThat(rebuildState(workspaceId, "WIKI").processingJobId()).isEqualTo(ownerJobId);
            assertThat(jobIdCount(workspaceId, "uncommitted-competitor")).isZero();
        }
    }

    private void admitViaRepository(long workspaceId, String jobKey, SearchCorpus corpus) {
        ProcessingJob job = jobRepository.create(workspaceId, jobKey,
                org.km.llmwiki.processing.ProcessingJobType.FTS_REBUILD, 1);
        assertThat(rebuildStateRepository.claimQueued(workspaceId, job.id(), List.of(corpus)))
                .isEqualTo(1);
    }

    /**
     * Mirrors {@link FtsRebuildStateRepository#claimQueued} statement-for-statement on a raw
     * connection: the two-connection critical-window tests cannot drive the production
     * repository through a second Spring-managed transaction, so this mirror pins the same
     * SQL shapes; a drift in the production claim must update both.
     */
    private int claimOn(Connection connection, long workspaceId, long jobId, String corpus)
            throws SQLException {
        int requeued;
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE search_index_rebuild_state
                   SET status = 'QUEUED', processing_job_id = ?, indexed_count = 0,
                       failed_count = 0, failure_detail = NULL, started_at = NULL,
                       completed_at = NULL, updated_at = ?
                 WHERE workspace_id = ? AND corpus = ?
                   AND status NOT IN ('QUEUED', 'RUNNING')
                """)) {
            update.setLong(1, jobId);
            update.setString(2, NOW);
            update.setLong(3, workspaceId);
            update.setString(4, corpus);
            requeued = update.executeUpdate();
        }
        if (requeued > 0) {
            return 1;
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO search_index_rebuild_state
                    (workspace_id, corpus, status, processing_job_id, indexed_count,
                     failed_count, projection_version, updated_at)
                SELECT ?, ?, 'QUEUED', ?, 0, 0, ?, ?
                 WHERE NOT EXISTS (
                    SELECT 1 FROM search_index_rebuild_state
                     WHERE workspace_id = ? AND corpus = ?
                       AND status IN ('QUEUED', 'RUNNING'))
                """)) {
            insert.setLong(1, workspaceId);
            insert.setString(2, corpus);
            insert.setLong(3, jobId);
            insert.setString(4, CjkBigramProjector.VERSION);
            insert.setString(5, NOW);
            insert.setLong(6, workspaceId);
            insert.setString(7, corpus);
            return insert.executeUpdate();
        }
    }

    private long rawInsertJob(Connection connection, long workspaceId, String jobKey)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO processing_job (workspace_id, job_id, job_type, status, total_count,
                    created_at, updated_at) VALUES (?, ?, 'FTS_REBUILD', 'QUEUED', 1, ?, ?)
                """)) {
            insert.setLong(1, workspaceId);
            insert.setString(2, jobKey);
            insert.setString(3, NOW);
            insert.setString(4, NOW);
            insert.executeUpdate();
            return lastInsertedId(connection);
        }
    }

    private long lastInsertedId(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT last_insert_rowid()")) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private int inProgressCount(Connection connection, long workspaceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM search_index_rebuild_state WHERE workspace_id = ? "
                        + "AND status IN ('QUEUED', 'RUNNING')")) {
            statement.setLong(1, workspaceId);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private String inProgressOwner(long workspaceId, String corpus) {
        return db().sql("""
                SELECT j.job_id FROM search_index_rebuild_state s
                  JOIN processing_job j ON j.id = s.processing_job_id
                 WHERE s.workspace_id = :workspace AND s.corpus = :corpus
                """).param("workspace", workspaceId).param("corpus", corpus)
                .query(String.class).single();
    }

    private long jobIdCount(long workspaceId, String jobKey) {
        return db().sql("SELECT COUNT(*) FROM processing_job WHERE workspace_id = :workspace "
                        + "AND job_id = :jobKey")
                .param("workspace", workspaceId).param("jobKey", jobKey).query(Long.class).single();
    }

    private long jobCount(long workspaceId) {
        return db().sql("SELECT COUNT(*) FROM processing_job WHERE workspace_id = :workspace")
                .param("workspace", workspaceId).query(Long.class).single();
    }

    private FtsRebuildState rebuildState(long workspaceId, String corpus) {
        return db().sql("""
                SELECT * FROM search_index_rebuild_state
                 WHERE workspace_id = :workspace AND corpus = :corpus
                """).param("workspace", workspaceId).param("corpus", corpus)
                .query((resultSet, row) -> new FtsRebuildState(
                        resultSet.getLong("workspace_id"), SearchCorpus.valueOf(resultSet.getString("corpus")),
                        FtsRebuildStatus.valueOf(resultSet.getString("status")),
                        resultSet.getLong("processing_job_id"), resultSet.getInt("indexed_count"),
                        resultSet.getInt("failed_count"), resultSet.getString("projection_version"),
                        resultSet.getString("failure_detail"), resultSet.getString("started_at"),
                        resultSet.getString("completed_at"), resultSet.getString("updated_at")))
                .single();
    }

    private String startRebuild(String corpus) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/search/index/rebuild")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"corpus\":\"" + corpus + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.corpus").value(corpus))
                .andReturn();
        return com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(result.getResponse().getContentAsString())
                .path("data").path("jobId").asText();
    }

    private String jobOfCorpus(long workspaceId, String corpus) {
        return db().sql("""
                SELECT j.job_id FROM search_index_rebuild_state s
                  JOIN processing_job j ON j.id = s.processing_job_id
                 WHERE s.workspace_id = :workspace AND s.corpus = :corpus
                """).param("workspace", workspaceId).param("corpus", corpus)
                .query(String.class).single();
    }

    private long jobId(String jobId) {
        return db().sql("SELECT id FROM processing_job WHERE job_id = :jobId")
                .param("jobId", jobId).query(Long.class).single();
    }

    private void awaitJob(String jobId, String expectedStatus) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(10);
        String actual = null;
        while (Instant.now().isBefore(deadline)) {
            actual = db().sql("SELECT status FROM processing_job WHERE job_id = :jobId")
                    .param("jobId", jobId).query(String.class).optional().orElse(null);
            if (expectedStatus.equals(actual)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("FTS rebuild " + jobId + " expected " + expectedStatus
                + " but was " + actual);
    }

    private long insertWorkspace(String suffix) throws Exception {
        Path root = Path.of("target", "fts-admission", suffix + "-" + System.nanoTime())
                .toAbsolutePath();
        Files.createDirectories(root);
        var key = new org.springframework.jdbc.support.GeneratedKeyHolder();
        db().sql("""
                INSERT INTO workspace (name, root_path, inbox_path, archive_path, vault_path,
                    data_path, config_path, status, created_at, updated_at)
                VALUES (:name, :root, :inbox, :archive, :vault, :data, :config, 'ACTIVE', :now, :now)
                """).param("name", "Admission " + suffix).param("root", root.toString())
                .param("inbox", root.resolve("inbox").toString())
                .param("archive", root.resolve("archive").toString())
                .param("vault", root.resolve("vault").toString())
                .param("data", root.resolve("data").toString())
                .param("config", root.resolve("config").toString()).param("now", NOW).update(key);
        return key.getKey().longValue();
    }

    private void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
