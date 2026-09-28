package org.km.llmwiki.wiki;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #652 publish commit-point TOCTOU：preflight PASS 後、canonical commit 前的
 * concurrent evidence mutation 必須 fail closed。
 *
 * <p>Determinism：所有 race 皆用 CountDownLatch / barrier，不用 sleep。
 * 精準停在「preflight 已 PASS、canonical commit 尚未完成」（spy 擋在
 * {@code markFileCommitted} 入口：file 已 commit、DB finalizer 尚未進入），
 * concurrent mutation 經 JdbcClient 直接 COMMIT 並以 SELECT 驗證已提交後，
 * 才放行 publish；finalizer 交易內先取得 SQLite writer serialization 再重驗
 * persisted ASK evidence，stale 一律為 {@code WIKI_PUBLISH_PROPOSAL_INVALID}（409），
 * filesystem 補償且 ledger 為 {@code ROLLED_BACK}，不得留下 PUBLISHED。
 */
class WikiPublishCommitPointConcurrencyIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoSpyBean
    private WikiPublicationRepository publicationRepository;

    @MockitoSpyBean
    private WikiPublishAttemptRepository attemptRepository;

    @TempDir
    Path tempDir;

    private long seededChunkId;

    private String askRequestBody() {
        return askRequestBody(seededChunkId);
    }

    private String askRequestBody(long sourceChunkId) {
        return """
                {
                  "question": "transformer 的核心架構原則是什麼？",
                  "answerText": "Transformer 以 self-attention 為核心，搭配位置編碼與前饋層。",
                  "provider": "openai-compatible",
                  "model": "gpt-test",
                  "citations": [
                    {"evidenceId": "E1", "kind": "SOURCE", "sourceChunkId": %d},
                    {"evidenceId": "E2", "kind": "WIKI", "wikiPath": "vault/concepts/attention.md", "wikiRevision": 2}
                  ]
                }
                """.formatted(sourceChunkId);
    }

    @Test
    void createSourceDeletedRaceFailsClosedAtCommitPoint() throws Exception {
        seedWorkspaceWithSources();
        activate(lookupWorkspaceId("active"));
        long proposalId = createAskProposal();
        long draftId = approveAndGetDraft(proposalId);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            if (!resume.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("commit-point resume latch timed out");
            }
            return invocation.callRealMethod();
        }).when(publicationRepository).markFileCommitted(anyLong(), anyLong());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> publishFuture = executor.submit(() ->
                    mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                            .andReturn());

            // 精準停在 preflight PASS、canonical commit 尚未完成。
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();

            // Concurrent evidence mutation：SOURCE 轉 DELETED，直接 COMMIT。
            db().sql("UPDATE document SET status = 'DELETED' WHERE id = :id")
                    .param("id", lookupDocumentId()).update();
            // 證明 mutation 已 COMMIT（重讀驗證），再放行 publish。
            assertThat(db().sql("SELECT status FROM document WHERE id = :id")
                    .param("id", lookupDocumentId()).query(String.class).single())
                    .isEqualTo("DELETED");
            resume.countDown();

            MvcResult result = publishFuture.get(30, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            String body = result.getResponse().getContentAsString();
            assertThat(body).contains("WIKI_PUBLISH_PROPOSAL_INVALID");
            // Typed outcome 不洩漏 local path / SQL / raw exception。
            assertThat(body).doesNotContain(tempDir.toString());
            assertThat(body).doesNotContain("SELECT");
            assertThat(body).doesNotContain("knowledge_page");

            // Compensation / ledger：無 PUBLISHED，不得 COMPLETED，必為 ROLLED_BACK。
            assertThat(count("SELECT COUNT(*) FROM knowledge_page WHERE knowledge_id LIKE 'wiki:%'")).isEqualTo(0);
            // seed 的 attention 頁仍在，斷言時限定新 publish 產生的頁（排除 attention）。
            assertThat(db().sql("SELECT status FROM wiki_draft WHERE id = :id")
                    .param("id", draftId).query(String.class).single()).isEqualTo("READY");
            assertThat(db().sql("SELECT status FROM wiki_publish_operation WHERE draft_id = :id")
                    .param("id", draftId).query(String.class).single()).isEqualTo("ROLLED_BACK");
            // Filesystem compensation：stage 後 commit 的新檔已移除，vault 只剩 attention。
            try (var files = Files.list(activeVaultPath().resolve("concepts"))) {
                var names = files.map(p -> p.getFileName().toString()).sorted().toList();
                assertThat(names).containsExactly("attention.md");
            }

            // Retry 仍 fail closed 為同一 typed code，不退化為 METADATA_FAILURE。
            mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("WIKI_PUBLISH_PROPOSAL_INVALID"));
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUPERSEDED", "DUPLICATE"})
    void createSourceStatusRaceParameterizedFailsClosed(String staleStatus) throws Exception {
        seedWorkspaceWithSources();
        activate(lookupWorkspaceId("active"));
        long proposalId = createAskProposal();
        long draftId = approveAndGetDraft(proposalId);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            if (!resume.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("commit-point resume latch timed out");
            }
            return invocation.callRealMethod();
        }).when(publicationRepository).markFileCommitted(anyLong(), anyLong());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> publishFuture = executor.submit(() ->
                    mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                            .andReturn());
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();

            db().sql("UPDATE document SET status = :status WHERE id = :id")
                    .param("status", staleStatus).param("id", lookupDocumentId()).update();
            assertThat(db().sql("SELECT status FROM document WHERE id = :id")
                    .param("id", lookupDocumentId()).query(String.class).single())
                    .isEqualTo(staleStatus);
            resume.countDown();

            MvcResult result = publishFuture.get(30, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(result.getResponse().getContentAsString())
                    .contains("WIKI_PUBLISH_PROPOSAL_INVALID");
            assertThat(db().sql("SELECT status FROM wiki_publish_operation WHERE draft_id = :id")
                    .param("id", draftId).query(String.class).single()).isEqualTo("ROLLED_BACK");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void createSourceParseStatusRaceFailsClosed() throws Exception {
        seedWorkspaceWithSources();
        activate(lookupWorkspaceId("active"));
        long proposalId = createAskProposal();
        long draftId = approveAndGetDraft(proposalId);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            if (!resume.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("commit-point resume latch timed out");
            }
            return invocation.callRealMethod();
        }).when(publicationRepository).markFileCommitted(anyLong(), anyLong());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> publishFuture = executor.submit(() ->
                    mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                            .andReturn());
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();

            db().sql("UPDATE document SET parse_status = 'FAILED' WHERE id = :id")
                    .param("id", lookupDocumentId()).update();
            assertThat(db().sql("SELECT parse_status FROM document WHERE id = :id")
                    .param("id", lookupDocumentId()).query(String.class).single())
                    .isEqualTo("FAILED");
            resume.countDown();

            MvcResult result = publishFuture.get(30, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(result.getResponse().getContentAsString())
                    .contains("WIKI_PUBLISH_PROPOSAL_INVALID");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void createChunkHashDriftRaceFailsClosed() throws Exception {
        seedWorkspaceWithSources();
        activate(lookupWorkspaceId("active"));
        long proposalId = createAskProposal();
        long draftId = approveAndGetDraft(proposalId);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            if (!resume.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("commit-point resume latch timed out");
            }
            return invocation.callRealMethod();
        }).when(publicationRepository).markFileCommitted(anyLong(), anyLong());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> publishFuture = executor.submit(() ->
                    mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                            .andReturn());
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();

            db().sql("UPDATE source_chunk SET normalized_content = 'drifted content' WHERE id = :id")
                    .param("id", seededChunkId).update();
            assertThat(db().sql("SELECT normalized_content FROM source_chunk WHERE id = :id")
                    .param("id", seededChunkId).query(String.class).single())
                    .isEqualTo("drifted content");
            resume.countDown();

            MvcResult result = publishFuture.get(30, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(result.getResponse().getContentAsString())
                    .contains("WIKI_PUBLISH_PROPOSAL_INVALID");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void createWikiRevisionAdvanceRaceFailsClosed() throws Exception {
        seedWorkspaceWithSources();
        activate(lookupWorkspaceId("active"));
        long proposalId = createAskProposal();
        long draftId = approveAndGetDraft(proposalId);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            if (!resume.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("commit-point resume latch timed out");
            }
            return invocation.callRealMethod();
        }).when(publicationRepository).markFileCommitted(anyLong(), anyLong());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> publishFuture = executor.submit(() ->
                    mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                            .andReturn());
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();

            // WIKI revision 前進（r2 → r3），直接 COMMIT。
            db().sql("UPDATE knowledge_page SET revision = 3 WHERE markdown_path = 'vault/concepts/attention.md'")
                    .update();
            assertThat(db().sql("SELECT revision FROM knowledge_page WHERE markdown_path = 'vault/concepts/attention.md'")
                    .query(Integer.class).single()).isEqualTo(3);
            resume.countDown();

            MvcResult result = publishFuture.get(30, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(result.getResponse().getContentAsString())
                    .contains("WIKI_PUBLISH_PROPOSAL_INVALID");
            assertThat(db().sql("SELECT status FROM wiki_publish_operation WHERE draft_id = :id")
                    .param("id", draftId).query(String.class).single()).isEqualTo("ROLLED_BACK");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void createWikiBytesDriftRaceFailsClosed() throws Exception {
        seedWorkspaceWithSources();
        activate(lookupWorkspaceId("active"));
        long proposalId = createAskProposal();
        long draftId = approveAndGetDraft(proposalId);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            if (!resume.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("commit-point resume latch timed out");
            }
            return invocation.callRealMethod();
        }).when(publicationRepository).markFileCommitted(anyLong(), anyLong());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> publishFuture = executor.submit(() ->
                    mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                            .andReturn());
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();

            // Canonical bytes drift（revision 不變，content hash 不再相符），直接寫檔 COMMIT。
            Path wikiPath = activeVaultPath().resolve("concepts/attention.md");
            Files.writeString(wikiPath, publishedWikiMarkdown() + "\n外部未治理修改\n");
            assertThat(Files.readString(wikiPath)).contains("外部未治理修改");
            resume.countDown();

            MvcResult result = publishFuture.get(30, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(result.getResponse().getContentAsString())
                    .contains("WIKI_PUBLISH_PROPOSAL_INVALID");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void validCreateStillPublishesAndNoOpIsStable() throws Exception {
        seedWorkspaceWithSources();
        activate(lookupWorkspaceId("active"));
        long proposalId = createAskProposal();
        long draftId = approveAndGetDraft(proposalId);

        mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.result").value("PUBLISHED"));

        // Ledger 進入確定性成功態：PUBLISHED page/draft/COMPLETED。
        assertThat(db().sql("SELECT status FROM wiki_draft WHERE id = :id")
                .param("id", draftId).query(String.class).single()).isEqualTo("PUBLISHED");
        assertThat(db().sql("SELECT status FROM wiki_publish_operation WHERE draft_id = :id")
                .param("id", draftId).query(String.class).single()).isEqualTo("COMPLETED");

        // Repeat publish 為 NO_OP，不被 currentness gate 破壞。
        mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("NO_OP"));
    }

    @Test
    void mergeSourceDeletedRaceFailsClosedAtCommitPoint() throws Exception {
        seedWorkspaceWithSources();
        createMergeTarget();
        activate(lookupWorkspaceId("active"));
        long proposalId = createAskProposal();
        convertAskToMerge(proposalId);
        long draftId = approveAndGetDraft(proposalId);
        assertThat(db().sql("SELECT action FROM wiki_draft WHERE id = :id")
                .param("id", draftId).query(String.class).single()).isEqualTo("MERGE");
        String beforeHash = db().sql("SELECT content_hash FROM knowledge_page WHERE knowledge_id = 'existing-topic'")
                .query(String.class).single();
        Path target = activeVaultPath().resolve("concepts/existing-topic.md");
        String beforeBytes = Files.readString(target);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            if (!resume.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("commit-point resume latch timed out");
            }
            return invocation.callRealMethod();
        }).when(publicationRepository).markFileCommitted(anyLong(), anyLong());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> publishFuture = executor.submit(() ->
                    mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                            .andReturn());
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();

            db().sql("UPDATE document SET status = 'DELETED' WHERE id = :id")
                    .param("id", lookupDocumentId()).update();
            assertThat(db().sql("SELECT status FROM document WHERE id = :id")
                    .param("id", lookupDocumentId()).query(String.class).single())
                    .isEqualTo("DELETED");
            resume.countDown();

            MvcResult result = publishFuture.get(30, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(result.getResponse().getContentAsString())
                    .contains("WIKI_PUBLISH_PROPOSAL_INVALID");

            // Filesystem compensation：MERGE 原始 bytes 已還原，不得留下新 revision。
            assertThat(Files.readString(target)).isEqualTo(beforeBytes);
            assertThat(db().sql("SELECT content_hash FROM knowledge_page WHERE knowledge_id = 'existing-topic'")
                    .query(String.class).single()).isEqualTo(beforeHash);
            assertThat(db().sql("SELECT status FROM wiki_draft WHERE id = :id")
                    .param("id", draftId).query(String.class).single()).isEqualTo("READY");
            assertThat(db().sql("SELECT status FROM wiki_publish_operation WHERE draft_id = :id")
                    .param("id", draftId).query(String.class).single()).isEqualTo("ROLLED_BACK");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void mergeWikiRevisionAdvanceRaceFailsClosedAtCommitPoint() throws Exception {
        seedWorkspaceWithSources();
        createMergeTarget();
        activate(lookupWorkspaceId("active"));
        long proposalId = createAskProposal();
        convertAskToMerge(proposalId);
        long draftId = approveAndGetDraft(proposalId);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            if (!resume.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("commit-point resume latch timed out");
            }
            return invocation.callRealMethod();
        }).when(publicationRepository).markFileCommitted(anyLong(), anyLong());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> publishFuture = executor.submit(() ->
                    mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                            .andReturn());
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();

            // WIKI cited page revision 前進（attention r2 → r3），直接 COMMIT。
            db().sql("UPDATE knowledge_page SET revision = 3 WHERE markdown_path = 'vault/concepts/attention.md'")
                    .update();
            assertThat(db().sql("SELECT revision FROM knowledge_page WHERE markdown_path = 'vault/concepts/attention.md'")
                    .query(Integer.class).single()).isEqualTo(3);
            resume.countDown();

            MvcResult result = publishFuture.get(30, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(result.getResponse().getContentAsString())
                    .contains("WIKI_PUBLISH_PROPOSAL_INVALID");
            assertThat(db().sql("SELECT status FROM wiki_publish_operation WHERE draft_id = :id")
                    .param("id", draftId).query(String.class).single()).isEqualTo("ROLLED_BACK");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void mergeWikiBytesDriftRaceFailsClosedAtCommitPoint() throws Exception {
        seedWorkspaceWithSources();
        createMergeTarget();
        activate(lookupWorkspaceId("active"));
        long proposalId = createAskProposal();
        convertAskToMerge(proposalId);
        long draftId = approveAndGetDraft(proposalId);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            if (!resume.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("commit-point resume latch timed out");
            }
            return invocation.callRealMethod();
        }).when(publicationRepository).markFileCommitted(anyLong(), anyLong());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> publishFuture = executor.submit(() ->
                    mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                            .andReturn());
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();

            Path cited = activeVaultPath().resolve("concepts/attention.md");
            Files.writeString(cited, publishedWikiMarkdown() + "\n外部未治理修改\n");
            assertThat(Files.readString(cited)).contains("外部未治理修改");
            resume.countDown();

            MvcResult result = publishFuture.get(30, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(result.getResponse().getContentAsString())
                    .contains("WIKI_PUBLISH_PROPOSAL_INVALID");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void validMergeAskStillPublishes() throws Exception {
        seedWorkspaceWithSources();
        createMergeTarget();
        activate(lookupWorkspaceId("active"));
        long proposalId = createAskProposal();
        convertAskToMerge(proposalId);
        long draftId = approveAndGetDraft(proposalId);

        mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.outcome").value("MERGED"));

        assertThat(db().sql("SELECT status FROM wiki_draft WHERE id = :id")
                .param("id", draftId).query(String.class).single()).isEqualTo("PUBLISHED");
        assertThat(db().sql("SELECT revision FROM knowledge_page WHERE knowledge_id = 'existing-topic'")
                .query(Integer.class).single()).isEqualTo(2);

        mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("NO_OP"));
    }

    @Test
    void sameWorkspaceConcurrentPublishesSerializeWithoutGlobalBlock() throws Exception {
        // Bounded scope 證據：同 workspace 第二個 publish 不能在第一個的
        // file commit → DB finalizer critical section 中間換檔。
        // Determinism：secondAttempted 是 attempt-gate barrier——attempt.start 與
        // workspace lock 取用在同一執行緒且前者先行，因此 latch 證明 T2 已實際開始
        // 嘗試 publish（排除 executor 尚未排程的 vacuous pass）；而 T1 持有 lock 期間
        // markCount 不可能變為 2，故放行前計數仍為 1 是 sound 斷言，不依賴 timing。
        seedWorkspaceWithSources();
        activate(lookupWorkspaceId("active"));
        long firstProposal = createAskProposalWithQuestion("第一個併發主題是什麼？");
        long firstDraft = approveAndGetDraft(firstProposal);
        long secondProposal = createAskProposalWithQuestion("第二個併發主題是什麼？");
        long secondDraft = approveAndGetDraft(secondProposal);

        AtomicInteger markCount = new AtomicInteger(0);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch firstResume = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        doAnswer(invocation -> {
            StoredWikiDraft started = invocation.getArgument(0);
            if (started.id() == secondDraft) {
                secondAttempted.countDown();
            }
            return invocation.callRealMethod();
        }).when(attemptRepository).start(any(StoredWikiDraft.class));
        doAnswer(invocation -> {
            int ordinal = markCount.incrementAndGet();
            if (ordinal == 1) {
                firstEntered.countDown();
                if (!firstResume.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("serialization resume latch timed out");
                }
            }
            return invocation.callRealMethod();
        }).when(publicationRepository).markFileCommitted(anyLong(), anyLong());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> firstFuture = executor.submit(() ->
                    mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", firstDraft))
                            .andReturn());
            assertThat(firstEntered.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(markCount.get()).isEqualTo(1);

            Future<MvcResult> secondFuture = executor.submit(() ->
                    mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", secondDraft))
                            .andReturn());

            // T2 已到達 lock 前最後一道 gate（attempt.start），但尚未進入 commit
            // boundary：T1 仍持有 per-workspace lock，此斷言不可能因排程快慢而翻轉。
            assertThat(secondAttempted.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(markCount.get()).isEqualTo(1);
            assertThat(firstFuture.isDone()).isFalse();

            firstResume.countDown();
            MvcResult firstResult = firstFuture.get(30, TimeUnit.SECONDS);
            MvcResult secondResult = secondFuture.get(30, TimeUnit.SECONDS);
            assertThat(firstResult.getResponse().getStatus()).isIn(200, 201);
            assertThat(secondResult.getResponse().getStatus()).isIn(200, 201);
            assertThat(markCount.get()).isEqualTo(2);
            assertThat(db().sql("SELECT COUNT(*) FROM knowledge_page WHERE status = 'PUBLISHED'")
                    .query(Integer.class).single()).isGreaterThanOrEqualTo(3);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void threePublishHandoffKeepsSingleWorkspaceSerialization() throws Exception {
        // #653 corrective：per-workspace lock 不可移除，否則 T1 unlock → T2 接手舊 lock
        // → T1 因 queue 已空而移除舊 lock → T3 建新 lock 的 handoff race 會讓 T2/T3
        // 同時進入 critical section。此測試以三個同 workspace publish 強制兩次交棒：
        // T1 暫停 → T2 證明已嘗試且被擋 → 放行 T1 → T2 接手並暫停 → T3 證明已嘗試且
        // 被擋 → 放行 T2 → T3 接手完成。全程 latch/barrier，無 sleep；每個「被擋」
        // 斷言皆 sound（持有 lock 者未放行前，commit boundary 計數不可能前進）。
        seedWorkspaceWithSources();
        activate(lookupWorkspaceId("active"));
        long firstDraft = approveAndGetDraft(createAskProposalWithQuestion("交棒第一主題是什麼？"));
        long secondDraft = approveAndGetDraft(createAskProposalWithQuestion("交棒第二主題是什麼？"));
        long thirdDraft = approveAndGetDraft(createAskProposalWithQuestion("交棒第三主題是什麼？"));

        AtomicInteger markCount = new AtomicInteger(0);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch firstResume = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch secondResume = new CountDownLatch(1);
        CountDownLatch thirdAttempted = new CountDownLatch(1);
        CountDownLatch thirdEntered = new CountDownLatch(1);
        doAnswer(invocation -> {
            StoredWikiDraft started = invocation.getArgument(0);
            if (started.id() == secondDraft) {
                secondAttempted.countDown();
            } else if (started.id() == thirdDraft) {
                thirdAttempted.countDown();
            }
            return invocation.callRealMethod();
        }).when(attemptRepository).start(any(StoredWikiDraft.class));
        doAnswer(invocation -> {
            // 以 operationId 回查 draft 身分，不依賴執行緒搶鎖順序。
            long operationId = invocation.getArgument(1);
            long enteredDraft = db().sql("SELECT draft_id FROM wiki_publish_operation WHERE id = :id")
                    .param("id", operationId).query(Long.class).single();
            markCount.incrementAndGet();
            if (enteredDraft == firstDraft) {
                firstEntered.countDown();
                if (!firstResume.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("handoff first resume latch timed out");
                }
            } else if (enteredDraft == secondDraft) {
                secondEntered.countDown();
                if (!secondResume.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("handoff second resume latch timed out");
                }
            } else {
                thirdEntered.countDown();
            }
            return invocation.callRealMethod();
        }).when(publicationRepository).markFileCommitted(anyLong(), anyLong());

        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<MvcResult> firstFuture = executor.submit(() ->
                    mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", firstDraft))
                            .andReturn());
            assertThat(firstEntered.await(30, TimeUnit.SECONDS)).isTrue();

            Future<MvcResult> secondFuture = executor.submit(() ->
                    mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", secondDraft))
                            .andReturn());
            assertThat(secondAttempted.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(markCount.get()).isEqualTo(1);

            // 第一次交棒：T1 放行完成，T2 必須接手同一把 lock 並停在 commit boundary。
            firstResume.countDown();
            assertThat(secondEntered.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(markCount.get()).isEqualTo(2);
            assertThat(firstFuture.get(30, TimeUnit.SECONDS).getResponse().getStatus()).isIn(200, 201);

            Future<MvcResult> thirdFuture = executor.submit(() ->
                    mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", thirdDraft))
                            .andReturn());
            // T3 已到達 lock 前 gate，但 T2 仍持有 lock：T3 絕對不能進 commit boundary。
            assertThat(thirdAttempted.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(markCount.get()).isEqualTo(2);
            assertThat(secondFuture.isDone()).isFalse();

            // 第二次交棒：T2 放行完成，T3 必須接手並完成（證明沒有 lock 遺失或死結）。
            secondResume.countDown();
            assertThat(secondFuture.get(30, TimeUnit.SECONDS).getResponse().getStatus()).isIn(200, 201);
            assertThat(thirdEntered.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(thirdFuture.get(30, TimeUnit.SECONDS).getResponse().getStatus()).isIn(200, 201);

            assertThat(markCount.get()).isEqualTo(3);
            assertThat(db().sql("SELECT COUNT(*) FROM wiki_publish_operation WHERE status = 'COMPLETED'")
                    .query(Integer.class).single()).isEqualTo(3);
            assertThat(db().sql("SELECT COUNT(*) FROM wiki_draft WHERE status = 'PUBLISHED'")
                    .query(Integer.class).single()).isEqualTo(3);
        } finally {
            executor.shutdownNow();
        }
    }

    private long createAskProposal() throws Exception {
        mockMvc.perform(post("/api/v1/ask/proposals")
                        .contentType("application/json")
                        .content(askRequestBody()))
                .andExpect(status().isCreated());
        return db().sql("SELECT id FROM knowledge_proposal WHERE source_kind = 'ASK' ORDER BY id DESC LIMIT 1")
                .query(Long.class).single();
    }

    private long createAskProposalWithQuestion(String question) throws Exception {
        String body = """
                {
                  "question": "%s",
                  "answerText": "Transformer 以 self-attention 為核心，搭配位置編碼與前饋層。",
                  "provider": "openai-compatible",
                  "model": "gpt-test",
                  "citations": [
                    {"evidenceId": "E1", "kind": "SOURCE", "sourceChunkId": %d},
                    {"evidenceId": "E2", "kind": "WIKI", "wikiPath": "vault/concepts/attention.md", "wikiRevision": 2}
                  ]
                }
                """.formatted(question, seededChunkId);
        mockMvc.perform(post("/api/v1/ask/proposals")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated());
        return db().sql("SELECT id FROM knowledge_proposal WHERE source_kind = 'ASK' ORDER BY id DESC LIMIT 1")
                .query(Long.class).single();
    }

    private void convertAskToMerge(long proposalId) {
        int updated = db().sql("""
                UPDATE knowledge_proposal
                SET action = 'MERGE', merge_target_reference = 'wiki:existing-topic'
                WHERE id = :id AND source_kind = 'ASK'
                """).param("id", proposalId).update();
        assertThat(updated).isEqualTo(1);
    }

    private void createMergeTarget() throws Exception {
        String baseline = """
                ---
                id: "existing-topic"
                title: "Existing Topic"
                type: "CONCEPT"
                status: "PUBLISHED"
                ---

                # Existing Topic

                Baseline
                """;
        Path target = activeVaultPath().resolve("concepts/existing-topic.md");
        Files.writeString(target, baseline);
        insert("""
                INSERT INTO knowledge_page (workspace_id, knowledge_id, title, normalized_title, type,
                    markdown_path, status, content_hash, revision, created_at, updated_at)
                VALUES (:ws, 'existing-topic', 'Existing Topic', 'existing topic', 'CONCEPT',
                    'vault/concepts/existing-topic.md', 'PUBLISHED', :contentHash, 1, :now, :now)
                """, "ws", lookupWorkspaceId("active"),
                "contentHash", WikiContentHash.sha256(baseline),
                "now", "2026-09-01T00:00:00Z");
    }

    private long approveAndGetDraft(long proposalId) throws Exception {
        String approved = mockMvc.perform(patch("/api/v1/proposals/{id}/status", proposalId)
                        .contentType("application/json").content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.autoDraft.draftId").isNumber())
                .andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(approved)
                .path("data").path("autoDraft").path("draftId").asLong();
    }

    private void activate(long workspaceId) {
        db().sql("UPDATE workspace SET status = 'INACTIVE'").update();
        db().sql("UPDATE workspace SET status = 'ACTIVE' WHERE id = :id")
                .param("id", workspaceId).update();
    }

    private long lookupWorkspaceId(String name) {
        return db().sql("SELECT id FROM workspace WHERE name = :name").param("name", name)
                .query(Long.class).single();
    }

    private long lookupDocumentId() {
        return db().sql("SELECT id FROM document WHERE workspace_id = :ws")
                .param("ws", lookupWorkspaceId("active")).query(Long.class).single();
    }

    private int count(String sql) {
        return db().sql(sql).query(Integer.class).single();
    }

    private void seedWorkspaceWithSources() throws Exception {
        createWorkspace("active");
        long documentId = insert("""
                INSERT INTO document (workspace_id, file_name, source_path, sha256, status, parse_status,
                    created_at, updated_at)
                VALUES (:ws, 'source.txt', 'source.txt', 'hash', 'PROCESSED', 'PROCESSED', :now, :now)
                """, "ws", lookupWorkspaceId("active"), "now", "2026-09-01T00:00:00Z");
        seededChunkId = insert("""
                INSERT INTO source_chunk (document_id, chunk_no, content, normalized_content, content_hash,
                    created_at, updated_at)
                VALUES (:document, 1, 'chunk content', 'chunk content',
                    '61f41f018c953eb5d858cf3241d53c9a77cbaefe8d8443ec5666755809a4d132', :now, :now)
                """, "document", documentId, "now", "2026-09-01T00:00:00Z");
        String wikiMarkdown = publishedWikiMarkdown();
        Files.writeString(activeVaultPath().resolve("concepts/attention.md"), wikiMarkdown);
        insert("""
                INSERT INTO knowledge_page (workspace_id, knowledge_id, title, normalized_title, type,
                    markdown_path, status, content_hash, revision, created_at, updated_at)
                VALUES (:ws, 'wiki-attention', 'Attention', 'attention', 'CONCEPT',
                    'vault/concepts/attention.md', 'PUBLISHED', :contentHash, 2, :now, :now)
                """, "ws", lookupWorkspaceId("active"),
                "contentHash", WikiContentHash.sha256(wikiMarkdown),
                "now", "2026-09-01T00:00:00Z");
    }

    private Path activeVaultPath() {
        String vaultPath = db().sql("SELECT vault_path FROM workspace WHERE name = 'active'")
                .query(String.class).single();
        return Path.of(vaultPath);
    }

    private static String publishedWikiMarkdown() {
        return """
                ---
                id: "wiki-attention"
                title: "Attention"
                type: "CONCEPT"
                status: "PUBLISHED"
                ---

                # Attention

                Canonical attention content.
                """;
    }

    private void createWorkspace(String name) throws Exception {
        Path root = tempDir.resolve(name + "-" + System.nanoTime());
        Files.createDirectories(root.resolve("vault/concepts"));
        Files.createDirectories(root.resolve("inbox"));
        Files.createDirectories(root.resolve("archive"));
        Files.createDirectories(root.resolve("data"));
        insert("""
                INSERT INTO workspace (name, root_path, inbox_path, archive_path, vault_path, data_path, status,
                    created_at, updated_at)
                VALUES (:name, :root, :inbox, :archive, :vault, :data, 'ACTIVE', :now, :now)
                """, "name", name, "root", root.toString(), "inbox", root.resolve("inbox").toString(),
                "archive", root.resolve("archive").toString(), "vault", root.resolve("vault").toString(),
                "data", root.resolve("data").toString(), "now", "2026-09-01T00:00:00Z");
    }

    private long insert(String sql, Object... parameters) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        var statement = db().sql(sql);
        for (int index = 0; index < parameters.length; index += 2) {
            statement = statement.param((String) parameters[index], parameters[index + 1]);
        }
        statement.update(keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("insert did not return a key");
        }
        return key.longValue();
    }
}
