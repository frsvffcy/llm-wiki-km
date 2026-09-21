package org.km.llmwiki.source;

import org.junit.jupiter.api.Test;
import org.km.llmwiki.ai.ask.AskDocumentScopeException;
import org.km.llmwiki.ai.ask.AskDocumentScopeValidator;
import org.km.llmwiki.rag.DocumentRetrievalScope;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IngestReadinessIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IngestStartupReconciler startupReconciler;

    @Autowired
    private AskDocumentScopeValidator documentScopeValidator;

    @Test
    void autoProcessedUploadBecomesReadyOnlyAfterFreshSourceIndexExists() throws Exception {
        createWorkspace();
        long documentId = uploadAuto("ready.txt", "可搜尋關鍵字 readiness-token");

        awaitIngestProcessingTasks();

        mockMvc.perform(get("/api/v1/inbox"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].documentId").value(documentId))
                .andExpect(jsonPath("$.data[0].parseStatus").value("PROCESSED"))
                .andExpect(jsonPath("$.data[0].usability.status").value("READY_TO_USE"))
                .andExpect(jsonPath("$.data[0].usability.searchReady").value(true))
                .andExpect(jsonPath("$.data[0].usability.nextAction").value("START_USING"));

        assertThat(db().sql("""
                        SELECT status FROM source_search_index_sync
                         WHERE document_id = :document
                        """).param("document", documentId).query(String.class).single())
                .isEqualTo("SYNCED");
        assertThat(db().sql("""
                        SELECT status FROM processing_job
                         WHERE job_type = 'INGEST'
                         ORDER BY id DESC LIMIT 1
                        """).query(String.class).single()).isEqualTo("COMPLETED");
    }

    @Test
    void batchUploadUsesOneBoundedIngestJobAndProcessesItemsSerially() throws Exception {
        createWorkspace();

        mockMvc.perform(multipart("/api/v1/inbox/files/batch")
                        .file(new MockMultipartFile("files", "one.txt", "text/plain",
                                "第一份文件 batch-one".getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("files", "two.txt", "text/plain",
                                "第二份文件 batch-two".getBytes(StandardCharsets.UTF_8)))
                        .param("autoProcess", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(2))
                .andExpect(jsonPath("$.data.failed").value(0));

        awaitIngestProcessingTasks();

        assertThat(db().sql("SELECT COUNT(*) FROM processing_job WHERE job_type = 'INGEST'")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(db().sql("""
                        SELECT COUNT(*) FROM processing_job_item item
                        JOIN processing_job job ON job.id = item.job_id
                        WHERE job.job_type = 'INGEST'
                        """).query(Integer.class).single()).isEqualTo(2);
        assertThat(db().sql("""
                        SELECT COUNT(*) FROM processing_job_item item
                        JOIN processing_job job ON job.id = item.job_id
                        WHERE job.job_type = 'INGEST' AND item.status = 'SUCCEEDED'
                        """).query(Integer.class).single()).isEqualTo(2);

        mockMvc.perform(get("/api/v1/inbox"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].usability.status").value("READY_TO_USE"))
                .andExpect(jsonPath("$.data[1].usability.status").value("READY_TO_USE"));
    }

    @Test
    void extractedDocumentWithFailedFtsSyncIsIndexPendingAndNeverReady() throws Exception {
        createWorkspace();
        db().sql("""
                CREATE TRIGGER fail_ingest_source_identity
                BEFORE INSERT ON search_index_identity
                WHEN NEW.corpus = 'SOURCE'
                BEGIN
                    SELECT RAISE(ABORT, 'simulated ingest Source FTS outage');
                END
                """).update();

        long documentId;
        try {
            documentId = uploadAuto("pending.txt", "這份文件已抽取但索引故障 pending-token");
            awaitIngestProcessingTasks();
        } finally {
            db().sql("DROP TRIGGER IF EXISTS fail_ingest_source_identity").update();
        }

        mockMvc.perform(get("/api/v1/inbox"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].documentId").value(documentId))
                .andExpect(jsonPath("$.data[0].parseStatus").value("PROCESSED"))
                .andExpect(jsonPath("$.data[0].usability.status").value("INDEX_PENDING"))
                .andExpect(jsonPath("$.data[0].usability.searchReady").value(false))
                .andExpect(jsonPath("$.data[0].usability.nextAction").value("RETRY_PROCESSING"));

        assertThat(db().sql("""
                        SELECT status FROM source_search_index_sync
                         WHERE document_id = :document
                        """).param("document", documentId).query(String.class).single())
                .isEqualTo("INDEX_PENDING");
        assertThat(db().sql("""
                        SELECT item.status FROM processing_job_item item
                        JOIN processing_job job ON job.id = item.job_id
                        WHERE job.job_type = 'INGEST' AND item.document_id = :document
                        ORDER BY item.id DESC LIMIT 1
                        """).param("document", documentId).query(String.class).single())
                .isEqualTo("FAILED");
    }

    @Test
    void syncedLedgerWithCanonicalDriftIsStaleAndNeverReady() throws Exception {
        createWorkspace();
        long documentId = uploadAuto("stale.txt", "原始內容 stale-token");
        awaitIngestProcessingTasks();

        db().sql("""
                UPDATE source_chunk
                   SET normalized_content = '內容已變動 stale-token-new',
                       content_hash = :hash
                 WHERE document_id = :document
                """)
                .param("hash", sha256("內容已變動 stale-token-new"))
                .param("document", documentId)
                .update();

        mockMvc.perform(get("/api/v1/inbox"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].usability.status").value("INDEX_STALE"))
                .andExpect(jsonPath("$.data[0].usability.searchReady").value(false))
                .andExpect(jsonPath("$.data[0].usability.nextAction").value("RETRY_PROCESSING"));
    }

    @Test
    void askDocumentScopeRevalidatesReadyStaleDeletedSupersededAndForeignDocuments()
            throws Exception {
        createWorkspace();
        long ready = uploadAuto("ready-scope.txt", "ready scope unique one");
        awaitIngestProcessingTasks();
        long stale = uploadAuto("stale-scope.txt", "stale scope unique two");
        awaitIngestProcessingTasks();
        long deleted = uploadAuto("deleted-scope.txt", "deleted scope unique three");
        awaitIngestProcessingTasks();
        long superseded = uploadAuto("superseded-scope.txt", "superseded scope unique four");
        awaitIngestProcessingTasks();

        documentScopeValidator.requireCurrent(new DocumentRetrievalScope(ready));
        mockMvc.perform(get("/api/v1/inbox/documents/{documentId}", ready))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documentId").value(ready))
                .andExpect(jsonPath("$.data.fileName").value("ready-scope.txt"))
                .andExpect(jsonPath("$.data.usability.status").value("READY_TO_USE"))
                .andExpect(jsonPath("$.data.usability.searchReady").value(true));

        db().sql("""
                UPDATE source_chunk SET normalized_content = 'drifted', content_hash = :hash
                 WHERE document_id = :document
                """).param("hash", sha256("drifted")).param("document", stale).update();
        db().sql("UPDATE document SET status = 'DELETED' WHERE id = :id")
                .param("id", deleted).update();
        db().sql("UPDATE document SET status = 'SUPERSEDED' WHERE id = :id")
                .param("id", superseded).update();

        assertScopeFailure(stale, AskDocumentScopeException.Reason.STALE);
        assertScopeFailure(deleted, AskDocumentScopeException.Reason.INVALID);
        assertScopeFailure(superseded, AskDocumentScopeException.Reason.INVALID);

        createWorkspace();
        assertScopeFailure(ready, AskDocumentScopeException.Reason.INVALID);
        mockMvc.perform(get("/api/v1/inbox/documents/{documentId}", ready))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_NOT_FOUND"));
    }

    @Test
    void interruptedIngestFailsClosedAfterRestartAndDoesNotRemainProcessing() throws Exception {
        createWorkspace();
        long documentId = uploadManual("restart.txt", "restart-boundary");
        long workspaceId = db().sql("SELECT workspace_id FROM document WHERE id = :document")
                .param("document", documentId).query(Long.class).single();

        db().sql("""
                INSERT INTO processing_job
                    (workspace_id, job_id, job_type, status, total_count, created_at, updated_at)
                VALUES (:workspace, 'interrupted-ingest', 'INGEST', 'RUNNING', 1,
                        '2026-09-21T00:00:00Z', '2026-09-21T00:00:00Z')
                """).param("workspace", workspaceId).update();
        long jobId = db().sql("SELECT id FROM processing_job WHERE job_id = 'interrupted-ingest'")
                .query(Long.class).single();
        db().sql("""
                INSERT INTO processing_job_item
                    (job_id, document_id, status, current_step, retry_count, retry_eligible, started_at)
                VALUES (:job, :document, 'RUNNING', 'INGEST', 0, 0,
                        '2026-09-21T00:00:01Z')
                """).param("job", jobId).param("document", documentId).update();

        mockMvc.perform(get("/api/v1/inbox"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].usability.status").value("PROCESSING"));

        startupReconciler.reconcile();

        mockMvc.perform(get("/api/v1/inbox"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].usability.status").value("FAILED"))
                .andExpect(jsonPath("$.data[0].usability.searchReady").value(false))
                .andExpect(jsonPath("$.data[0].usability.nextAction").value("RETRY_PROCESSING"));

        assertThat(db().sql("SELECT status FROM processing_job WHERE id = :job")
                .param("job", jobId).query(String.class).single()).isEqualTo("FAILED");
        assertThat(db().sql("""
                        SELECT status FROM processing_job_item
                         WHERE job_id = :job AND document_id = :document
                        """).param("job", jobId).param("document", documentId)
                .query(String.class).single()).isEqualTo("FAILED");
    }

    @Test
    void unsupportedAutoProcessedDocumentExposesTypedNextActionInsteadOfReady() throws Exception {
        createWorkspace();
        mockMvc.perform(multipart("/api/v1/inbox/files")
                        .file(new MockMultipartFile("file", "archive.bin", "application/octet-stream",
                                "not supported".getBytes(StandardCharsets.UTF_8)))
                        .param("autoProcess", "true"))
                .andExpect(status().isCreated());

        awaitIngestProcessingTasks();

        mockMvc.perform(get("/api/v1/inbox"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].parseStatus").value("UNSUPPORTED"))
                .andExpect(jsonPath("$.data[0].usability.status").value("UNSUPPORTED"))
                .andExpect(jsonPath("$.data[0].usability.searchReady").value(false))
                .andExpect(jsonPath("$.data[0].usability.nextAction").value("NONE"));
    }

    private long uploadManual(String fileName, String body) throws Exception {
        String response = mockMvc.perform(multipart("/api/v1/inbox/files")
                        .file(new MockMultipartFile("file", fileName, "text/plain",
                                body.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(response.replaceAll(".*\\\"documentId\\\":(\\d+).*", "$1"));
    }

    private long uploadAuto(String fileName, String body) throws Exception {
        String response = mockMvc.perform(multipart("/api/v1/inbox/files")
                        .file(new MockMultipartFile("file", fileName, "text/plain",
                                body.getBytes(StandardCharsets.UTF_8)))
                        .param("autoProcess", "true"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.duplicate").value(false))
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(response.replaceAll(".*\\\"documentId\\\":(\\d+).*", "$1"));
    }

    private static String sha256(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private void createWorkspace() throws Exception {
        Path root = Path.of("target/test-data/ingest-readiness-" + UUID.randomUUID()).toAbsolutePath();
        mockMvc.perform(post("/api/v1/workspaces")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "Ingest Readiness", "rootPath": "%s"}
                                """.formatted(root)))
                .andExpect(status().isCreated());
    }

    private void assertScopeFailure(long documentId, AskDocumentScopeException.Reason reason) {
        assertThatThrownBy(() -> documentScopeValidator.requireCurrent(
                new DocumentRetrievalScope(documentId)))
                .isInstanceOfSatisfying(AskDocumentScopeException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(reason));
    }
}
