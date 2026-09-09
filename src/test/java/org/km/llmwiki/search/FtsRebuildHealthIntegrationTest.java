package org.km.llmwiki.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.processing.ProcessingJob;
import org.km.llmwiki.processing.ProcessingJobRepository;
import org.km.llmwiki.processing.ProcessingJobStatus;
import org.km.llmwiki.processing.ProcessingJobType;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.km.llmwiki.wiki.WikiContentHash;
import org.km.llmwiki.wiki.WikiPageType;
import org.km.llmwiki.wiki.WikiPathContract;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FtsRebuildHealthIntegrationTest extends IsolatedIntegrationTest {

    private static final String NOW = "2026-08-31T00:00:00Z";
    private static final String CREATED = "2026-09-03T00:00:00Z";
    private static final String STARTED = "2026-09-03T00:01:00Z";
    private static final String FINISHED = "2026-09-03T00:02:00Z";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WikiPathContract pathContract;

    @Autowired
    private FtsSearchIndexRepository ftsRepository;

    @Autowired
    private SearchService searchService;

    @Autowired
    private FtsRebuildStartupReconciler startupReconciler;

    @Autowired
    private ProcessingJobRepository jobs;

    @TempDir
    Path tempDirectory;

    @Test
    void rebuildsFullyClearedWikiDeterministicallyAndPreservesSearchOrder() throws Exception {
        WorkspaceFixture workspace = insertWorkspace("wiki", "ACTIVE");
        insertWiki(workspace, "wiki-b", "Beta Guide", "deterministic rebuild marker", 2);
        insertWiki(workspace, "wiki-a", "Alpha Guide", "deterministic rebuild marker", 3);

        assertHealth("WIKI", "REBUILD_REQUIRED", 2, 2, 0);
        String firstJobId = startRebuild("WIKI");
        awaitJob(firstJobId, "COMPLETED");
        getFtsJob(firstJobId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobType").value("FTS_REBUILD"))
                .andExpect(jsonPath("$.data.corpus").value("WIKI"))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
        assertHealth("WIKI", "HEALTHY", 0, 0, 0);

        List<String> order = searchService.search("deterministic rebuild marker", "WIKI",
                        null, null, 0, 20).data().stream().map(SearchResult::stableId).toList();
        List<String> identities = identitySnapshot(workspace.id());
        assertThat(order).containsExactly("wiki-a", "wiki-b");

        ftsRepository.clearKnowledge(workspace.id());
        assertHealth("WIKI", "REBUILD_REQUIRED", 2, 0, 0);
        awaitJob(startRebuild("WIKI"), "COMPLETED");
        awaitJob(startRebuild("WIKI"), "COMPLETED");

        assertHealth("WIKI", "HEALTHY", 0, 0, 0);
        assertThat(identitySnapshot(workspace.id())).isEqualTo(identities);
        assertThat(searchService.search("deterministic rebuild marker", "WIKI",
                null, null, 0, 20).data().stream().map(SearchResult::stableId).toList())
                .isEqualTo(order);
    }

    @Test
    void rebuildsSourceAndAllFromEligibleAuthorityWithTraceableProvenance() throws Exception {
        WorkspaceFixture workspace = insertWorkspace("source-all", "ACTIVE");
        insertWiki(workspace, "wiki-source", "Source Companion", "combined search marker", 1);
        SourceFixture source = insertSource(workspace.id(), "eligible.pdf", "PROCESSED",
                "raw source evidence", "combined search marker source evidence");
        insertSource(workspace.id(), "unsupported.pdf", "UNSUPPORTED",
                "raw unsupported", "combined search marker unsupported");

        awaitJob(startRebuild("SOURCE"), "COMPLETED");
        assertHealth("SOURCE", "HEALTHY", 0, 0, 0);
        assertThat(ftsRepository.matchSourceEvidence(workspace.id(), "evidence"))
                .singleElement().satisfies(match -> {
                    assertThat(match.sourceChunkId()).isEqualTo(source.chunkId());
                    assertThat(match.documentId()).isEqualTo(source.documentId());
                    assertThat(match.normalizedContent())
                            .isEqualTo("combined search marker source evidence");
                });
        assertThat(canonicalSource(source.chunkId()))
                .isEqualTo("raw source evidence|combined search marker source evidence");

        awaitJob(startRebuild("ALL"), "COMPLETED");
        assertHealth("ALL", "HEALTHY", 0, 0, 0);
        assertThat(searchService.search("combined search marker", "ALL",
                null, null, 0, 20).data())
                .extracting(SearchResult::stableId)
                .containsExactly(Long.toString(source.chunkId()), "wiki-source");
        assertThat(count("source_fts", workspace.id())).isEqualTo(1);
        assertThat(db().sql("SELECT status FROM source_search_index_sync WHERE document_id = :id")
                .param("id", source.documentId()).query(String.class).single()).isEqualTo("SYNCED");
    }

    @Test
    void detectsMissingStaleOrphanAndProjectionFingerprintDrift() throws Exception {
        WorkspaceFixture workspace = insertWorkspace("drift", "ACTIVE");
        WikiFixture missingWiki = insertWiki(workspace, "wiki-missing", "Missing Wiki",
                "drift detection marker", 1);
        WikiFixture staleWiki = insertWiki(workspace, "wiki-stale", "Stale Wiki",
                "drift detection marker", 1);
        SourceFixture source = insertSource(workspace.id(), "drift.pdf", "PROCESSED",
                "raw drift", "drift detection source");
        awaitJob(startRebuild("ALL"), "COMPLETED");

        Long missingRowId = identityRowId("KNOWLEDGE", workspace.id(), missingWiki.knowledgeId());
        db().sql("DELETE FROM knowledge_fts WHERE rowid = :rowid")
                .param("rowid", missingRowId).update();
        db().sql("UPDATE knowledge_page SET revision = revision + 1 WHERE id = :id")
                .param("id", staleWiki.pageId()).update();
        db().sql("""
                INSERT INTO knowledge_fts
                    (rowid, workspace_id, knowledge_id, title, content, normalized_title,
                     markdown_path, page_type, page_status, content_hash)
                VALUES (9000, :workspace, 'wiki-orphan', 'Orphan', 'orphan body', 'orphan',
                        'vault/concepts/orphan.md', 'CONCEPT', 'PUBLISHED', :hash)
                """).param("workspace", workspace.id()).param("hash", "0".repeat(64)).update();

        String changed = "changed canonical source projection";
        db().sql("""
                UPDATE source_chunk
                   SET normalized_content = :content, content_hash = :hash, updated_at = :now
                 WHERE id = :id
                """).param("content", changed).param("hash", SourceSearchEligibilityPolicy.sha256(changed))
                .param("now", NOW).param("id", source.chunkId()).update();
        db().sql("""
                INSERT INTO source_fts
                    (rowid, workspace_id, source_chunk_id, document_id, chunk_no, page_no,
                     normalized_content, section, heading_path, content_hash)
                VALUES (9001, :workspace, '999999', :document, 99, NULL,
                        'orphan source', NULL, NULL, :hash)
                """).param("workspace", workspace.id()).param("document", source.documentId())
                .param("hash", SourceSearchEligibilityPolicy.sha256("orphan source")).update();

        mockMvc.perform(get("/api/v1/search/index/health").param("corpus", "ALL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REBUILD_REQUIRED"))
                .andExpect(jsonPath("$.data.summary.missing", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.summary.stale", greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.data.summary.orphan", greaterThanOrEqualTo(3)))
                .andExpect(jsonPath("$.data.summary.failed").value(0));
    }

    @Test
    void failsClosedOnManualWikiVaultDriftWithoutChangingAuthorityOrOldIndex() throws Exception {
        WorkspaceFixture workspace = insertWorkspace("manual-drift", "ACTIVE");
        WikiFixture wiki = insertWiki(workspace, "wiki-manual", "Manual Drift",
                "trusted indexed content", 4);
        SourceFixture source = insertSource(workspace.id(), "manual.pdf", "PROCESSED",
                "canonical raw evidence", "canonical normalized evidence");
        Files.writeString(workspace.archive().resolve("keep.txt"), "archive remains canonical");
        awaitJob(startRebuild("ALL"), "COMPLETED");

        String drifted = wiki.markdown().replace("trusted indexed content", "manual unknown bytes");
        Files.writeString(wiki.file(), drifted);
        String jobId = startRebuild("WIKI");
        awaitJob(jobId, "FAILED");

        assertThat(Files.readString(wiki.file())).isEqualTo(drifted);
        assertThat(Files.readString(workspace.archive().resolve("keep.txt")))
                .isEqualTo("archive remains canonical");
        assertThat(db().sql("SELECT content_hash || ':' || revision FROM knowledge_page WHERE id = :id")
                .param("id", wiki.pageId()).query(String.class).single())
                .isEqualTo(wiki.hash() + ":4");
        assertThat(canonicalSource(source.chunkId()))
                .isEqualTo("canonical raw evidence|canonical normalized evidence");
        assertThat(ftsRepository.matchKnowledge(workspace.id(), "trusted indexed content"))
                .extracting(SearchIndexMatch::stableId).containsExactly("wiki-manual");
        assertThat(ftsRepository.matchKnowledge(workspace.id(), "manual unknown bytes")).isEmpty();
        mockMvc.perform(get("/api/v1/search/index/health").param("corpus", "WIKI"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DEGRADED"))
                .andExpect(jsonPath("$.data.summary.failed", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.corpora[0].rebuildState.failureDetail")
                        .value(org.hamcrest.Matchers.containsString("hash differs")));
    }

    @Test
    void persistsOnlyAnOperatorSafeFailureProjectionWhenRebuildFailsWithHostileDetail()
            throws Exception {
        WorkspaceFixture workspace = insertWorkspace("redaction", "ACTIVE");
        insertWiki(workspace, "wiki-redaction", "Redaction", "redaction indexed content", 1);
        insertSource(workspace.id(), "redaction.pdf", "PROCESSED",
                "redaction raw evidence", "redaction normalized evidence");
        awaitJob(startRebuild("ALL"), "COMPLETED");

        db().sql("""
                CREATE TRIGGER fail_redaction_rebuild
                BEFORE INSERT ON search_index_identity
                WHEN NEW.corpus = 'KNOWLEDGE'
                BEGIN
                    SELECT RAISE(ABORT, 'path /Users/x/y/vault token=abcdef123456
                        Authorization: Bearer abc.def SELECT * FROM t RID #12:0');
                END
                """).update();
        String jobId;
        try {
            jobId = startRebuild("WIKI");
            awaitJob(jobId, "FAILED");
        } finally {
            db().sql("DROP TRIGGER IF EXISTS fail_redaction_rebuild").update();
        }

        String persisted = db().sql("""
                SELECT failure_detail FROM search_index_rebuild_state
                 WHERE workspace_id = :workspace AND corpus = 'WIKI'
                """).param("workspace", workspace.id()).query(String.class).single();
        assertThat(persisted).startsWith("fts_rebuild_failed: ");
        assertThat(persisted).doesNotContain("/Users", "toddyeh", "token=abcdef123456",
                "Bearer", "SELECT", "RID", "#12:0", "sqlite");

        String logMetadata = db().sql("""
                SELECT metadata_json FROM processing_log
                 WHERE job_id = (SELECT id FROM processing_job WHERE job_id = :jobId)
                   AND status = 'FAILED'
                """).param("jobId", jobId).query(String.class).single();
        assertThat(logMetadata).doesNotContain("/Users", "token=abcdef123456", "Bearer",
                "SELECT", "RID", "#12:0");

        mockMvc.perform(get("/api/v1/search/index/health").param("corpus", "WIKI"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DEGRADED"))
                .andExpect(jsonPath("$.data.corpora[0].rebuildState.failureDetail")
                        .value(persisted));
    }

    @Test
    void healthProjectionRedactsLegacyRawFailureDetails() throws Exception {
        WorkspaceFixture workspace = insertWorkspace("legacy-detail", "ACTIVE");
        insertWiki(workspace, "wiki-legacy", "Legacy Detail", "legacy indexed content", 1);
        insertSource(workspace.id(), "legacy.pdf", "PROCESSED",
                "legacy raw evidence", "legacy normalized evidence");
        awaitJob(startRebuild("ALL"), "COMPLETED");

        String hostile = "SQLiteException: /Users/toddyeh/workspace/secret "
                + "Authorization: Bearer abc token=abcdef123456 SELECT * FROM t RID #12:0";
        String pathOnly = "authority mismatch while reading /Users/toddyeh/workspace/secret";
        db().sql("""
                UPDATE search_index_rebuild_state
                   SET status = 'FAILED', failed_count = 1, failure_detail = :detail
                 WHERE workspace_id = :workspace AND corpus = 'WIKI'
                """).param("detail", hostile).param("workspace", workspace.id()).update();
        db().sql("""
                UPDATE search_index_rebuild_state
                   SET status = 'FAILED', failed_count = 1, failure_detail = :detail
                 WHERE workspace_id = :workspace AND corpus = 'SOURCE'
                """).param("detail", pathOnly).param("workspace", workspace.id()).update();

        MvcResult result = mockMvc.perform(get("/api/v1/search/index/health")
                        .param("corpus", "ALL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DEGRADED"))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("/Users", "toddyeh", "Bearer", "token=abcdef123456",
                "SELECT", "RID", "#12:0", "SQLiteException");
        assertThat(body).contains("authority mismatch while reading [REDACTED]");
    }

    @Test
    void rollsBackAllProjectionChangesWhenAllRebuildPartiallyFails() throws Exception {
        WorkspaceFixture workspace = insertWorkspace("partial", "ACTIVE");
        insertWiki(workspace, "wiki-old", "Old Wiki", "old wiki projection", 1);
        SourceFixture source = insertSource(workspace.id(), "partial.pdf", "PROCESSED",
                "raw source stays", "old source projection");
        awaitJob(startRebuild("ALL"), "COMPLETED");
        List<String> before = identitySnapshot(workspace.id());

        insertWiki(workspace, "wiki-new", "New Wiki", "new wiki authority", 1);
        String changed = "new source authority";
        db().sql("""
                UPDATE source_chunk SET normalized_content = :content, content_hash = :hash
                 WHERE id = :id
                """).param("content", changed).param("hash", SourceSearchEligibilityPolicy.sha256(changed))
                .param("id", source.chunkId()).update();
        db().sql("""
                CREATE TRIGGER fail_source_rebuild_identity
                BEFORE INSERT ON search_index_identity
                WHEN NEW.corpus = 'SOURCE'
                BEGIN
                    SELECT RAISE(ABORT, 'simulated partial SOURCE rebuild failure');
                END
                """).update();
        String jobId;
        try {
            jobId = startRebuild("ALL");
            awaitJob(jobId, "FAILED");
        } finally {
            db().sql("DROP TRIGGER IF EXISTS fail_source_rebuild_identity").update();
        }

        assertThat(identitySnapshot(workspace.id())).isEqualTo(before);
        assertThat(ftsRepository.matchKnowledge(workspace.id(), "old wiki projection")).hasSize(1);
        assertThat(ftsRepository.matchKnowledge(workspace.id(), "new wiki authority")).isEmpty();
        assertThat(ftsRepository.matchSource(workspace.id(), "old source projection")).hasSize(1);
        assertThat(ftsRepository.matchSource(workspace.id(), "new source authority")).isEmpty();
        assertThat(canonicalSource(source.chunkId())).isEqualTo("raw source stays|new source authority");
        mockMvc.perform(get("/api/v1/search/index/health").param("corpus", "ALL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DEGRADED"))
                .andExpect(jsonPath("$.data.summary.failed").value(2));
    }

    @Test
    void rebuildsOnlyTheActiveWorkspaceAndLeavesInactiveProjectionUntouched() throws Exception {
        WorkspaceFixture inactive = insertWorkspace("inactive", "INACTIVE");
        SourceFixture hiddenSource = insertSource(inactive.id(), "hidden.pdf", "PROCESSED",
                "hidden raw", "hidden workspace marker");
        ftsRepository.upsertSource(new SourceSearchDocument(inactive.id(), hiddenSource.chunkId(),
                hiddenSource.documentId(), 1, null, "hidden workspace marker", null, null,
                SourceSearchEligibilityPolicy.sha256("hidden workspace marker")));
        List<String> hiddenBefore = identitySnapshot(inactive.id());

        WorkspaceFixture active = insertWorkspace("active", "ACTIVE");
        insertWiki(active, "wiki-active", "Active Wiki", "active workspace marker", 1);
        insertSource(active.id(), "active.pdf", "PROCESSED", "active raw",
                "active workspace marker source");
        awaitJob(startRebuild("ALL"), "COMPLETED");

        assertThat(identitySnapshot(inactive.id())).isEqualTo(hiddenBefore);
        assertThat(ftsRepository.matchSource(inactive.id(), "hidden workspace marker")).hasSize(1);
        assertThat(ftsRepository.matchSource(active.id(), "hidden workspace marker")).isEmpty();
        assertThat(db().sql("SELECT COUNT(*) FROM search_index_rebuild_state WHERE workspace_id = :id")
                .param("id", inactive.id()).query(Long.class).single()).isZero();
        assertThat(db().sql("SELECT COUNT(*) FROM search_index_rebuild_state WHERE workspace_id = :id")
                .param("id", active.id()).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void reconcilesInterruptedQueuedAndRunningRebuildsWithoutChangingKnowledgeData()
            throws Exception {
        WorkspaceFixture workspace = insertWorkspace("interrupted", "ACTIVE");
        WikiFixture wiki = insertWiki(workspace, "wiki-interrupted", "Interrupted Wiki",
                "preserved wiki projection", 5);
        SourceFixture source = insertSource(workspace.id(), "interrupted.txt", "PROCESSED",
                "preserved raw source", "preserved source projection");
        Path archiveFile = Files.writeString(workspace.archive().resolve("keep.txt"),
                "preserved archive bytes");
        awaitJob(startRebuild("ALL"), "COMPLETED");

        List<String> projectionBefore = identitySnapshot(workspace.id());
        String wikiFileBefore = Files.readString(wiki.file());
        String wikiAuthorityBefore = canonicalWiki(wiki.pageId());
        String sourceAuthorityBefore = canonicalSource(source.chunkId());
        String archiveBefore = Files.readString(archiveFile);

        long queuedJobId = insertProcessingJob(workspace.id(), "QUEUED", "queued");
        long runningJobId = insertProcessingJob(workspace.id(), "RUNNING", "running");
        persistInterruptedState(workspace.id(), "WIKI", "QUEUED", queuedJobId);
        persistInterruptedState(workspace.id(), "SOURCE", "RUNNING", runningJobId);

        FtsRebuildStartupReconciler.RecoveryResult first = startupReconciler.reconcile();

        assertThat(first.rebuildStates()).isEqualTo(2);
        assertThat(first.processingJobs()).isEqualTo(2);
        assertThat(db().sql("""
                SELECT corpus || ':' || status || ':' || failure_detail || ':' || failed_count
                  FROM search_index_rebuild_state
                 WHERE workspace_id = :workspace
                 ORDER BY corpus
                """).param("workspace", workspace.id()).query(String.class).list())
                .containsExactly(
                        "SOURCE:FAILED:Interrupted by application restart:1",
                        "WIKI:FAILED:Interrupted by application restart:1");
        assertThat(db().sql("""
                SELECT id || ':' || status || ':' || failed_count || ':'
                       || CASE WHEN finished_at IS NULL THEN 'OPEN' ELSE 'FINISHED' END
                  FROM processing_job
                 WHERE id IN (:queued, :running)
                 ORDER BY id
                """).param("queued", queuedJobId).param("running", runningJobId)
                .query(String.class).list())
                .containsExactly(
                        queuedJobId + ":FAILED:1:FINISHED",
                        runningJobId + ":FAILED:1:FINISHED");
        assertThat(restartLogSnapshot(queuedJobId, runningJobId)).containsExactly(
                queuedJobId + ":FTS_REBUILD:FAILED:Interrupted by application restart:"
                        + "{\"reason\":\"APPLICATION_RESTART\"}",
                runningJobId + ":FTS_REBUILD:FAILED:Interrupted by application restart:"
                        + "{\"reason\":\"APPLICATION_RESTART\"}");

        mockMvc.perform(get("/api/v1/search/index/health").param("corpus", "ALL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DEGRADED"))
                .andExpect(jsonPath("$.data.summary.failed").value(2));
        assertThat(identitySnapshot(workspace.id())).isEqualTo(projectionBefore);
        assertThat(ftsRepository.matchKnowledge(workspace.id(), "preserved wiki projection"))
                .extracting(SearchIndexMatch::stableId).containsExactly("wiki-interrupted");
        assertThat(ftsRepository.matchSource(workspace.id(), "preserved source projection"))
                .extracting(SearchIndexMatch::stableId)
                .containsExactly(Long.toString(source.chunkId()));
        assertThat(Files.readString(wiki.file())).isEqualTo(wikiFileBefore);
        assertThat(canonicalWiki(wiki.pageId())).isEqualTo(wikiAuthorityBefore);
        assertThat(canonicalSource(source.chunkId())).isEqualTo(sourceAuthorityBefore);
        assertThat(Files.readString(archiveFile)).isEqualTo(archiveBefore);

        FtsRebuildStartupReconciler.RecoveryResult second = startupReconciler.reconcile();

        assertThat(second.rebuildStates()).isZero();
        assertThat(second.processingJobs()).isZero();
        assertThat(restartLogSnapshot(queuedJobId, runningJobId)).hasSize(2);

        awaitJob(startRebuild("ALL"), "COMPLETED");
        assertHealth("ALL", "HEALTHY", 0, 0, 0);
        assertThat(db().sql("""
                SELECT corpus || ':' || status FROM search_index_rebuild_state
                 WHERE workspace_id = :workspace ORDER BY corpus
                """).param("workspace", workspace.id()).query(String.class).list())
                .containsExactly("SOURCE:COMPLETED", "WIKI:COMPLETED");
    }

    @Test
    void synchronizesTerminalFtsJobExplicitlyLinkedToInterruptedState() throws Exception {
        WorkspaceFixture workspace = insertWorkspace("split-state", "ACTIVE");
        long linkedJobId = insertProcessingJob(workspace.id(), "COMPLETED", "split-state");
        insertInterruptedState(workspace.id(), "WIKI", "RUNNING", linkedJobId);

        FtsRebuildStartupReconciler.RecoveryResult result = startupReconciler.reconcile();

        assertThat(result.rebuildStates()).isOne();
        assertThat(result.processingJobs()).isOne();
        assertThat(db().sql("SELECT status FROM processing_job WHERE id = :id")
                .param("id", linkedJobId).query(String.class).single()).isEqualTo("FAILED");
        assertThat(db().sql("SELECT status FROM search_index_rebuild_state WHERE workspace_id = :id")
                .param("id", workspace.id()).query(String.class).single()).isEqualTo("FAILED");
        assertThat(restartLogSnapshot(linkedJobId, linkedJobId)).hasSize(1);
    }

    @Test
    void exposesFtsLifecycleCountersAndPartialFailureFromImmutableOperationMetadata()
            throws Exception {
        WorkspaceFixture workspace = insertWorkspace("fts-job-status", "ACTIVE");
        ProcessingJob job = createFtsJob(workspace.id(), "fts-status-job", SearchCorpus.SOURCE, 3);
        setCreated(job.id());

        getFtsJob(job.jobId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobId").value(job.jobId()))
                .andExpect(jsonPath("$.data.jobType").value("FTS_REBUILD"))
                .andExpect(jsonPath("$.data.corpus").value("SOURCE"))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.totalCount").value(3))
                .andExpect(jsonPath("$.data.processedCount").value(0))
                .andExpect(jsonPath("$.data.successCount").value(0))
                .andExpect(jsonPath("$.data.failedCount").value(0))
                .andExpect(jsonPath("$.data.skippedCount").value(0))
                .andExpect(jsonPath("$.data.createdAt").value(CREATED))
                .andExpect(jsonPath("$.data.startedAt").doesNotExist())
                .andExpect(jsonPath("$.data.completedAt").doesNotExist())
                .andExpect(jsonPath("$.data.failureCode").doesNotExist());

        setRunning(job.id());
        getFtsJob(job.jobId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.startedAt").value(STARTED))
                .andExpect(jsonPath("$.data.completedAt").doesNotExist());

        setTerminal(job.id(), ProcessingJobStatus.COMPLETED, 3, 2, 1, 0);
        getFtsJob(job.jobId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.processedCount").value(3))
                .andExpect(jsonPath("$.data.successCount").value(2))
                .andExpect(jsonPath("$.data.failedCount").value(1))
                .andExpect(jsonPath("$.data.skippedCount").value(0))
                .andExpect(jsonPath("$.data.completedAt").value(FINISHED))
                .andExpect(jsonPath("$.data.failureCode").value("PARTIAL_FAILURE"))
                .andExpect(jsonPath("$.data.failureSummary")
                        .value("FTS rebuild completed with failed items"));

        // A later operation does not change the corpus captured by this historical job.
        createFtsJob(workspace.id(), "fts-status-later-job", SearchCorpus.WIKI, 1);
        getFtsJob(job.jobId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.corpus").value("SOURCE"));
    }

    @Test
    void projectsSafeFtsFailureDiagnosticsAndDoesNotExposeProcessingDetails() throws Exception {
        WorkspaceFixture workspace = insertWorkspace("fts-job-failure", "ACTIVE");
        ProcessingJob job = createFtsJob(workspace.id(), "fts-failed-job", SearchCorpus.WIKI, 1);
        db().sql("""
                INSERT INTO processing_log (job_id, document_id, step, status, message, metadata_json,
                    created_at)
                VALUES (:job, NULL, 'FTS_REBUILD', 'FAILED',
                    'java.lang.IllegalStateException: /Users/private SELECT * FROM secrets token=secret',
                    '{"detail":"Authorization: Bearer secret"}', :now)
                """).param("job", job.id()).param("now", NOW).update();
        setTerminal(job.id(), ProcessingJobStatus.FAILED, 1, 0, 1, 0);

        getFtsJob(job.jobId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.failureCode").value("REBUILD_FAILED"))
                .andExpect(jsonPath("$.data.failureSummary").value("FTS rebuild failed"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/Users/private"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SELECT * FROM secrets"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
    }

    @Test
    void returnsUnknownCorpusForLegacyMetadataWithoutGuessingFromCurrentHealthState() throws Exception {
        WorkspaceFixture workspace = insertWorkspace("fts-legacy-metadata", "ACTIVE");
        ProcessingJob legacy = jobs.create(workspace.id(), "fts-legacy-job",
                ProcessingJobType.FTS_REBUILD, 1);

        getFtsJob(legacy.jobId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobType").value("FTS_REBUILD"))
                .andExpect(jsonPath("$.data.corpus").doesNotExist());
    }

    @Test
    void hidesUnknownCrossWorkspaceAndWrongTypeFtsJobsBehindTheSameNotFound() throws Exception {
        WorkspaceFixture inactive = insertWorkspace("fts-cross-workspace", "INACTIVE");
        WorkspaceFixture active = insertWorkspace("fts-active", "ACTIVE");
        ProcessingJob crossWorkspace = createFtsJob(inactive.id(), "fts-cross-workspace-job",
                SearchCorpus.WIKI, 1);
        ProcessingJob wrongType = jobs.create(active.id(), "fts-wrong-type-job",
                ProcessingJobType.ANALYZE, 1);

        assertFtsNotFound(getFtsJob(crossWorkspace.jobId()));
        assertFtsNotFound(getFtsJob(wrongType.jobId()));
        assertFtsNotFound(getFtsJob("fts-does-not-exist"));
    }

    private String startRebuild(String corpus) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/search/index/rebuild")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"corpus\":\"" + corpus + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.corpus").value(corpus))
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.path("data").path("jobId").asText();
    }

    private ResultActions getFtsJob(String jobId) throws Exception {
        return mockMvc.perform(get("/api/v1/search/index/rebuild/{jobId}", jobId));
    }

    private void assertFtsNotFound(ResultActions result) throws Exception {
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PROCESSING_JOB_NOT_FOUND"));
    }

    private ProcessingJob createFtsJob(long workspaceId, String jobId, SearchCorpus corpus,
                                       int totalCount) {
        return jobs.create(workspaceId, jobId, ProcessingJobType.FTS_REBUILD, totalCount,
                FtsRebuildOperationMetadataCodec.encode(corpus));
    }

    private void setCreated(long jobId) {
        db().sql("UPDATE processing_job SET created_at = :created, updated_at = :created WHERE id = :id")
                .param("created", CREATED).param("id", jobId).update();
    }

    private void setRunning(long jobId) {
        db().sql("UPDATE processing_job SET status = 'RUNNING', started_at = :started, updated_at = :started "
                        + "WHERE id = :id")
                .param("started", STARTED).param("id", jobId).update();
    }

    private void setTerminal(long jobId, ProcessingJobStatus jobStatus, int processed, int succeeded,
                             int failed, int skipped) {
        db().sql("""
                UPDATE processing_job
                   SET status = :status, processed_count = :processed, success_count = :succeeded,
                       failed_count = :failed, skipped_count = :skipped,
                       started_at = :started, finished_at = :finished, updated_at = :finished
                 WHERE id = :id
                """).param("status", jobStatus.name()).param("processed", processed)
                .param("succeeded", succeeded).param("failed", failed).param("skipped", skipped)
                .param("started", STARTED).param("finished", FINISHED).param("id", jobId).update();
    }

    private void awaitJob(String jobId, String expectedStatus) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
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

    private void assertHealth(String corpus, String status, int missing, int stale, int orphan)
            throws Exception {
        mockMvc.perform(get("/api/v1/search/index/health").param("corpus", corpus))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(jsonPath("$.data.status").value(status))
                .andExpect(jsonPath("$.data.summary.missing").value(missing))
                .andExpect(jsonPath("$.data.summary.stale").value(stale))
                .andExpect(jsonPath("$.data.summary.orphan").value(orphan));
    }

    private WorkspaceFixture insertWorkspace(String suffix, String status) throws IOException {
        Path root = tempDirectory.resolve(suffix);
        Path inbox = Files.createDirectories(root.resolve("inbox"));
        Path archive = Files.createDirectories(root.resolve("archive"));
        Path vault = Files.createDirectories(root.resolve("vault"));
        Path data = Files.createDirectories(root.resolve("data"));
        Path config = Files.createDirectories(root.resolve("config"));
        KeyHolder key = new GeneratedKeyHolder();
        db().sql("""
                INSERT INTO workspace (name, root_path, inbox_path, archive_path, vault_path,
                    data_path, config_path, status, created_at, updated_at)
                VALUES (:name, :root, :inbox, :archive, :vault, :data, :config, :status, :now, :now)
                """).param("name", "FTS " + suffix).param("root", root.toString())
                .param("inbox", inbox.toString()).param("archive", archive.toString())
                .param("vault", vault.toString()).param("data", data.toString())
                .param("config", config.toString()).param("status", status).param("now", NOW)
                .update(key);
        return new WorkspaceFixture(key.getKey().longValue(), root, vault, archive);
    }

    private WikiFixture insertWiki(WorkspaceFixture workspace, String knowledgeId, String title,
                                   String body, int revision) throws IOException {
        String logicalPath = pathContract.resolveLogicalPath(WikiPageType.CONCEPT, title);
        String markdown = """
                ---
                id: "%s"
                title: "%s"
                type: "CONCEPT"
                status: "PUBLISHED"
                ---

                # %s

                %s
                """.formatted(knowledgeId, title, title, body);
        Path file = workspace.vault().resolve(logicalPath.substring("vault/".length()));
        Files.createDirectories(file.getParent());
        Files.writeString(file, markdown);
        String hash = WikiContentHash.sha256(markdown);
        KeyHolder key = new GeneratedKeyHolder();
        db().sql("""
                INSERT INTO knowledge_page (workspace_id, knowledge_id, title, normalized_title,
                    type, markdown_path, status, content_hash, revision, created_at, updated_at,
                    published_at)
                VALUES (:workspace, :knowledgeId, :title, :normalizedTitle, 'CONCEPT', :path,
                    'PUBLISHED', :hash, :revision, :now, :now, :now)
                """).param("workspace", workspace.id()).param("knowledgeId", knowledgeId)
                .param("title", title).param("normalizedTitle", title.toLowerCase())
                .param("path", logicalPath).param("hash", hash).param("revision", revision)
                .param("now", NOW).update(key);
        return new WikiFixture(key.getKey().longValue(), knowledgeId, file, markdown, hash);
    }

    private SourceFixture insertSource(long workspaceId, String fileName, String parseStatus,
                                       String raw, String normalized) {
        KeyHolder document = new GeneratedKeyHolder();
        db().sql("""
                INSERT INTO document (workspace_id, file_name, original_file_name, extension,
                    source_path, archive_path, sha256, status, parse_status, created_at, updated_at)
                VALUES (:workspace, :fileName, :fileName, 'txt', :source, :archive, :hash,
                    'PENDING', :parseStatus, :now, :now)
                """).param("workspace", workspaceId).param("fileName", fileName)
                .param("source", "inbox/" + fileName).param("archive", "archive/" + fileName)
                .param("hash", "a".repeat(64)).param("parseStatus", parseStatus).param("now", NOW)
                .update(document);
        long documentId = document.getKey().longValue();
        KeyHolder chunk = new GeneratedKeyHolder();
        db().sql("""
                INSERT INTO source_chunk (document_id, chunk_no, page_no, section, heading_path,
                    content, normalized_content, content_hash, created_at, updated_at)
                VALUES (:document, 1, NULL, NULL, NULL, :raw, :normalized, :hash, :now, :now)
                """).param("document", documentId).param("raw", raw).param("normalized", normalized)
                .param("hash", SourceSearchEligibilityPolicy.sha256(normalized)).param("now", NOW)
                .update(chunk);
        return new SourceFixture(documentId, chunk.getKey().longValue());
    }

    private long insertProcessingJob(long workspaceId, String status, String suffix) {
        KeyHolder key = new GeneratedKeyHolder();
        db().sql("""
                INSERT INTO processing_job (workspace_id, job_id, job_type, status, total_count,
                    started_at, created_at, updated_at)
                VALUES (:workspace, :externalId, 'FTS_REBUILD', :status, 1, :now, :now, :now)
                """).param("workspace", workspaceId)
                .param("externalId", "interrupted-job-" + suffix)
                .param("status", status).param("now", NOW).update(key);
        return key.getKey().longValue();
    }

    private void persistInterruptedState(long workspaceId, String corpus, String status,
                                         long processingJobId) {
        db().sql("""
                UPDATE search_index_rebuild_state
                   SET status = :status,
                       processing_job_id = :job,
                       indexed_count = 0,
                       failed_count = 0,
                       failure_detail = NULL,
                       completed_at = NULL,
                       updated_at = :now
                 WHERE workspace_id = :workspace AND corpus = :corpus
                """).param("status", status).param("job", processingJobId).param("now", NOW)
                .param("workspace", workspaceId).param("corpus", corpus).update();
    }

    private void insertInterruptedState(long workspaceId, String corpus, String status,
                                        long processingJobId) {
        db().sql("""
                INSERT INTO search_index_rebuild_state
                    (workspace_id, corpus, status, processing_job_id, indexed_count,
                     failed_count, updated_at)
                VALUES (:workspace, :corpus, :status, :job, 0, 0, :now)
                """).param("workspace", workspaceId).param("corpus", corpus)
                .param("status", status).param("job", processingJobId).param("now", NOW).update();
    }

    private List<String> restartLogSnapshot(long queuedJobId, long runningJobId) {
        return db().sql("""
                SELECT job_id || ':' || step || ':' || status || ':' || message || ':'
                       || metadata_json
                  FROM processing_log
                 WHERE job_id IN (:queued, :running)
                 ORDER BY job_id, id
                """).param("queued", queuedJobId).param("running", runningJobId)
                .query(String.class).list();
    }

    private List<String> identitySnapshot(long workspaceId) {
        return db().sql("""
                SELECT corpus || ':' || stable_id || ':' || fts_rowid
                  FROM search_index_identity
                 WHERE workspace_id = :workspace
                 ORDER BY corpus, stable_id
                """).param("workspace", workspaceId).query(String.class).list();
    }

    private Long identityRowId(String corpus, long workspaceId, String stableId) {
        return db().sql("""
                SELECT fts_rowid FROM search_index_identity
                 WHERE corpus = :corpus AND workspace_id = :workspace AND stable_id = :stableId
                """).param("corpus", corpus).param("workspace", workspaceId)
                .param("stableId", stableId).query(Long.class).single();
    }

    private long count(String table, long workspaceId) {
        String controlled = switch (table) {
            case "knowledge_fts" -> "knowledge_fts";
            case "source_fts" -> "source_fts";
            default -> throw new IllegalArgumentException("unexpected table");
        };
        return db().sql("SELECT COUNT(*) FROM " + controlled + " WHERE workspace_id = :workspace")
                .param("workspace", workspaceId).query(Long.class).single();
    }

    private String canonicalSource(long chunkId) {
        return db().sql("""
                SELECT content || '|' || normalized_content FROM source_chunk WHERE id = :id
                """).param("id", chunkId).query(String.class).single();
    }

    private String canonicalWiki(long pageId) {
        return db().sql("""
                SELECT knowledge_id || '|' || title || '|' || markdown_path || '|'
                       || content_hash || '|' || revision
                  FROM knowledge_page WHERE id = :id
                """).param("id", pageId).query(String.class).single();
    }

    private record WorkspaceFixture(long id, Path root, Path vault, Path archive) {
    }

    private record WikiFixture(long pageId, String knowledgeId, Path file, String markdown,
                               String hash) {
    }

    private record SourceFixture(long documentId, long chunkId) {
    }
}
