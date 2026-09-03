package org.km.llmwiki.search.embedding;

import org.junit.jupiter.api.Test;
import org.km.llmwiki.processing.ProcessingJob;
import org.km.llmwiki.processing.ProcessingJobRepository;
import org.km.llmwiki.processing.ProcessingJobStatus;
import org.km.llmwiki.processing.ProcessingJobType;
import org.km.llmwiki.processing.ProcessingLogRepository;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EmbeddingProjectionJobQueryIntegrationTest extends IsolatedIntegrationTest {

    private static final String CREATED = "2026-09-03T00:00:00Z";
    private static final String STARTED = "2026-09-03T00:01:00Z";
    private static final String FINISHED = "2026-09-03T00:02:00Z";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProcessingJobRepository jobs;

    @Autowired
    private ProcessingLogRepository logs;

    @Autowired
    private EmbeddingProjectionReadinessRepository readiness;

    @Test
    void returnsQueuedRunningAndTerminalLifecycleDataForActiveWorkspace() throws Exception {
        long workspace = insertWorkspace("active");
        ProcessingJob queued = createJob(workspace, "embedding-queued", ProcessingJobType.EMBEDDING_REBUILD);
        readiness.markQueued(workspace, queued.id(), EmbeddingEvidenceKind.WIKI, 0);

        getJob(queued.jobId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobId").value(queued.jobId()))
                .andExpect(jsonPath("$.data.jobType").value("EMBEDDING_REBUILD"))
                .andExpect(jsonPath("$.data.corpus").value("WIKI"))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.createdAt").value(CREATED))
                .andExpect(jsonPath("$.data.startedAt").doesNotExist())
                .andExpect(jsonPath("$.data.completedAt").doesNotExist())
                .andExpect(jsonPath("$.data.failureCode").doesNotExist());

        ProcessingJob running = createJob(workspace, "embedding-running", ProcessingJobType.EMBEDDING_REBUILD);
        readiness.markQueued(workspace, running.id(), EmbeddingEvidenceKind.SOURCE_CHUNK, 1);
        readiness.markRunning(workspace, running.id(), EmbeddingEvidenceKind.SOURCE_CHUNK);
        setRunning(running.id());

        getJob(running.jobId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.corpus").value("SOURCE"))
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.startedAt").value(STARTED))
                .andExpect(jsonPath("$.data.completedAt").doesNotExist());

        ProcessingJob succeeded = createJob(workspace, "embedding-succeeded", ProcessingJobType.EMBEDDING_REBUILD);
        readiness.markQueued(workspace, succeeded.id(), EmbeddingEvidenceKind.WIKI, 1);
        readiness.markQueued(workspace, succeeded.id(), EmbeddingEvidenceKind.SOURCE_CHUNK, 1);
        readiness.markCompleted(workspace, succeeded.id(), EmbeddingEvidenceKind.WIKI,
                1, 1, 0, "provider-is-not-exposed", "model-is-not-exposed", 2, true);
        readiness.markCompleted(workspace, succeeded.id(), EmbeddingEvidenceKind.SOURCE_CHUNK,
                1, 1, 0, "provider-is-not-exposed", "model-is-not-exposed", 2, true);
        setTerminal(succeeded.id(), ProcessingJobStatus.COMPLETED, 2, 2, 0, 0);

        getJob(succeeded.jobId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.corpus").value("ALL"))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.processedCount").value(2))
                .andExpect(jsonPath("$.data.successCount").value(2))
                .andExpect(jsonPath("$.data.failedCount").value(0))
                .andExpect(jsonPath("$.data.completedAt").value(FINISHED))
                .andExpect(jsonPath("$.data.failureCode").doesNotExist());
    }

    @Test
    void exposesPartialCompletionAndSanitizedFailureDiagnostics() throws Exception {
        long workspace = insertWorkspace("diagnostics");
        ProcessingJob partial = createJob(workspace, "embedding-partial", ProcessingJobType.EMBEDDING_REBUILD);
        readiness.markQueued(workspace, partial.id(), EmbeddingEvidenceKind.WIKI, 2);
        readiness.markCompleted(workspace, partial.id(), EmbeddingEvidenceKind.WIKI,
                1, 2, 1, null, null, null, true);
        setTerminal(partial.id(), ProcessingJobStatus.COMPLETED, 2, 1, 1, 0);

        getJob(partial.jobId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.failureCode").value("PARTIAL_FAILURE"))
                .andExpect(jsonPath("$.data.failureSummary").value(
                        "Embedding rebuild completed with failed items"));

        ProcessingJob failed = createJob(workspace, "embedding-failed", ProcessingJobType.EMBEDDING_REBUILD);
        readiness.markQueued(workspace, failed.id(), EmbeddingEvidenceKind.SOURCE_CHUNK, 1);
        readiness.markFailed(workspace, failed.id(), EmbeddingEvidenceKind.SOURCE_CHUNK,
                "REBUILD_FAILED: provider response body, apiKey=do-not-return, /native/vec0.dylib");
        setTerminal(failed.id(), ProcessingJobStatus.FAILED, 1, 0, 1, 0);
        logs.append(failed.id(), null, null, "EMBEDDING_REBUILD", "FAILED",
                "java.lang.IllegalStateException: provider body contains sk-secret-value, vector blob, SELECT * FROM secrets, /native/vec0.dylib",
                "{\"failureType\":\"REBUILD_FAILED\"}");

        getJob(failed.jobId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.failureCode").value("REBUILD_FAILED"))
                .andExpect(jsonPath("$.data.failureSummary").value("Embedding rebuild failed"))
                .andExpect(content().string(not(containsString("sk-secret-value"))))
                .andExpect(content().string(not(containsString("vec0.dylib"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))))
                .andExpect(content().string(not(containsString("SELECT * FROM secrets"))));
    }

    @Test
    void failsClosedForUnknownCrossWorkspaceAndUnrelatedJobs() throws Exception {
        long activeWorkspace = insertWorkspace("active");
        long inactiveWorkspace = insertWorkspace("inactive");
        db().sql("UPDATE workspace SET status = 'INACTIVE' WHERE id = :id")
                .param("id", inactiveWorkspace).update();

        ProcessingJob crossWorkspace = createJob(inactiveWorkspace, "embedding-cross-workspace",
                ProcessingJobType.EMBEDDING_REBUILD);
        readiness.markQueued(inactiveWorkspace, crossWorkspace.id(), EmbeddingEvidenceKind.WIKI, 1);
        ProcessingJob unrelated = createJob(activeWorkspace, "fts-not-public", ProcessingJobType.FTS_REBUILD);

        getJob(crossWorkspace.jobId())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PROCESSING_JOB_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("Embedding rebuild job not found"));
        getJob("does-not-exist")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PROCESSING_JOB_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("Embedding rebuild job not found"));
        getJob(unrelated.jobId())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PROCESSING_JOB_NOT_FOUND"));
    }

    @Test
    void keepsReadinessAggregateSeparateAndRebuildResponseBackwardCompatible() throws Exception {
        long workspace = insertWorkspace("readiness-contract");
        ProcessingJob job = createJob(workspace, "embedding-readiness-contract", ProcessingJobType.EMBEDDING_REBUILD);
        readiness.markQueued(workspace, job.id(), EmbeddingEvidenceKind.WIKI, 0);
        readiness.markQueued(workspace, job.id(), EmbeddingEvidenceKind.SOURCE_CHUNK, 0);

        mockMvc.perform(get("/api/v1/search/index/embedding/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].corpus").value(containsInAnyOrder("WIKI", "SOURCE_CHUNK")))
                .andExpect(jsonPath("$.data[*].status").value(containsInAnyOrder("QUEUED", "QUEUED")));

        mockMvc.perform(post("/api/v1/search/index/embedding/rebuild")
                        .param("corpus", "WIKI")
                        .contentType(APPLICATION_JSON))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.jobId").isString())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.workspaceId").value(workspace))
                .andExpect(jsonPath("$.data.corpus").value("WIKI"));
    }

    private ResultActions getJob(String jobId) throws Exception {
        return mockMvc.perform(get("/api/v1/search/index/embedding/rebuild/{jobId}", jobId));
    }

    private ProcessingJob createJob(long workspaceId, String jobId, ProcessingJobType type) {
        ProcessingJob job = jobs.create(workspaceId, jobId, type, 1);
        db().sql("UPDATE processing_job SET created_at = :created WHERE id = :id")
                .param("created", CREATED).param("id", job.id()).update();
        return job;
    }

    private void setRunning(long jobId) {
        db().sql("UPDATE processing_job SET status = 'RUNNING', started_at = :started, updated_at = :started WHERE id = :id")
                .param("started", STARTED).param("id", jobId).update();
    }

    private void setTerminal(long jobId, ProcessingJobStatus status, int processed, int succeeded,
                             int failed, int skipped) {
        db().sql("""
                UPDATE processing_job
                SET status = :status, processed_count = :processed, success_count = :succeeded,
                    failed_count = :failed, skipped_count = :skipped,
                    started_at = :started, finished_at = :finished, updated_at = :finished
                WHERE id = :id
                """)
                .param("status", status.name()).param("processed", processed).param("succeeded", succeeded)
                .param("failed", failed).param("skipped", skipped).param("started", STARTED)
                .param("finished", FINISHED).param("id", jobId).update();
    }

    private long insertWorkspace(String suffix) throws Exception {
        Path root = Path.of("target", "issue-205", suffix + "-" + System.nanoTime()).toAbsolutePath();
        Files.createDirectories(root);
        KeyHolder key = new GeneratedKeyHolder();
        db().sql("""
                INSERT INTO workspace (name, root_path, inbox_path, archive_path, vault_path, data_path,
                    config_path, status, created_at, updated_at)
                VALUES (:name, :root, :inbox, :archive, :vault, :data, :config, 'ACTIVE', :now, :now)
                """)
                .param("name", "Issue 205 " + suffix)
                .param("root", root.toString())
                .param("inbox", root.resolve("inbox").toString())
                .param("archive", root.resolve("archive").toString())
                .param("vault", root.resolve("vault").toString())
                .param("data", root.resolve("data").toString())
                .param("config", root.resolve("config").toString())
                .param("now", CREATED)
                .update(key);
        return key.getKey().longValue();
    }
}
