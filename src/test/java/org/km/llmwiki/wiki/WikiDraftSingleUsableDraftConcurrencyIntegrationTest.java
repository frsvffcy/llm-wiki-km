package org.km.llmwiki.wiki;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #601：single-usable-draft contract 的 deterministic 併發證明。
 *
 * <p>所有併發測試以 {@link CyclicBarrier} 會合（不以 sleep／retry luck 證明）；
 * 執行緒只回傳結果、斷言一律在主執行緒；{@code Future#get} 超時僅為防 hang 的
 * guard，不參與正確性論證——斷言在<b>任何交錯</b>下皆成立（單一可用＋同一 id）。
 */
class WikiDraftSingleUsableDraftConcurrencyIntegrationTest extends IsolatedIntegrationTest {

    private static final long HANG_GUARD_SECONDS = 60L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProposalAutoDraftService autoDraftService;

    @Autowired
    private WikiDraftCreationService creationService;

    @Autowired
    private WorkspaceService workspaceService;

    @TempDir
    Path tempDir;

    @Test
    void concurrentAutoPreparesYieldSingleUsableDraft() throws Exception {
        Fixture fixture = createFixture("ACTIVE");
        long workspaceId = activeWorkspaceId();

        Callable<Long> prepare = () -> {
            ProposalAutoDraft result = autoDraftService.prepare(workspaceId, fixture.proposalId());
            assertThat(result.errorCode()).isNull();
            return result.draftId();
        };
        List<Long> ids = runConcurrently(prepare, prepare);

        assertThat(new HashSet<>(ids)).hasSize(1);
        assertThat(usableCount(fixture)).isEqualTo(1);
        assertThat(publishedCount(fixture)).isZero();
    }

    @Test
    void concurrentAutoAndManualCreateYieldSingleUsableDraft() throws Exception {
        Fixture fixture = createFixture("ACTIVE");
        long workspaceId = activeWorkspaceId();

        List<Long> ids = runConcurrently(() -> {
                    ProposalAutoDraft result = autoDraftService.prepare(workspaceId, fixture.proposalId());
                    assertThat(result.errorCode()).isNull();
                    return result.draftId();
                },
                () -> creationService.createOrReuse(fixture.proposalId()).response().id());

        assertThat(new HashSet<>(ids)).hasSize(1);
        assertThat(usableCount(fixture)).isEqualTo(1);
        assertThat(publishedCount(fixture)).isZero();
    }

    @Test
    void concurrentPreparesAndManualCreateRaceOnSameBarrier() throws Exception {
        Fixture fixture = createFixture("ACTIVE");
        long workspaceId = activeWorkspaceId();

        List<Long> ids = runConcurrently(() -> {
                    ProposalAutoDraft result = autoDraftService.prepare(workspaceId, fixture.proposalId());
                    assertThat(result.errorCode()).isNull();
                    return result.draftId();
                },
                () -> {
                    ProposalAutoDraft result = autoDraftService.prepare(workspaceId, fixture.proposalId());
                    assertThat(result.errorCode()).isNull();
                    return result.draftId();
                },
                () -> creationService.createOrReuse(fixture.proposalId()).response().id());

        assertThat(new HashSet<>(ids)).hasSize(1);
        assertThat(usableCount(fixture)).isEqualTo(1);
    }

    @Test
    void concurrentRetriesOnExistingDraftReuseSameId() throws Exception {
        Fixture fixture = createFixture("ACTIVE");
        long workspaceId = activeWorkspaceId();
        mockMvc.perform(post("/api/v1/wiki-drafts").contentType("application/json")
                        .content("{\"proposalId\":" + fixture.proposalId() + "}"))
                .andExpect(status().isCreated());
        long existing = usableIds(fixture).get(0);

        List<Long> ids = runConcurrently(() -> autoDraftService.prepare(workspaceId, fixture.proposalId()).draftId(),
                () -> autoDraftService.prepare(workspaceId, fixture.proposalId()).draftId(),
                () -> creationService.createOrReuse(fixture.proposalId()).response().id());

        assertThat(ids).containsOnly(existing);
        assertThat(usableCount(fixture)).isEqualTo(1);
    }

    @Test
    void sequentialDoubleCreateReusesCommittedWinner() throws Exception {
        // 第二次 insert 必定撞上 V35 partial unique index（勝者已提交）：
        // deterministic 覆蓋 catch→reuse 分支（barrier 測試覆蓋交錯，兩者互補）。
        Fixture fixture = createFixture("ACTIVE");

        WikiDraftCreationService.CreatedDraft first = creationService.createOrReuse(fixture.proposalId());
        WikiDraftCreationService.CreatedDraft second = creationService.createOrReuse(fixture.proposalId());

        assertThat(first.reused()).isFalse();
        assertThat(second.reused()).isTrue();
        assertThat(second.response().id()).isEqualTo(first.response().id());
        assertThat(usableCount(fixture)).isEqualTo(1);
    }

    @Test
    void createAfterInvalidationProducesNewDraft() throws Exception {
        Fixture fixture = createFixture("ACTIVE");
        long firstId = creationService.createOrReuse(fixture.proposalId()).response().id();
        mockMvc.perform(post("/api/v1/wiki-drafts/{id}/invalidate", firstId))
                .andExpect(status().isOk());

        long secondId = creationService.createOrReuse(fixture.proposalId()).response().id();

        assertThat(secondId).isNotEqualTo(firstId);
        assertThat(usableCount(fixture)).isEqualTo(1);
        assertThat(statusOfDraft(firstId)).isEqualTo("INVALIDATED");
    }

    @Test
    void publishedDraftDoesNotBlockNewCreate() throws Exception {
        Fixture fixture = createFixture("ACTIVE");
        long firstId = creationService.createOrReuse(fixture.proposalId()).response().id();
        db().sql("UPDATE wiki_draft SET status = 'PUBLISHED' WHERE id = :draftId")
                .param("draftId", firstId).update();

        long secondId = creationService.createOrReuse(fixture.proposalId()).response().id();

        assertThat(secondId).isNotEqualTo(firstId);
        assertThat(usableCount(fixture)).isEqualTo(1);
    }

    @Test
    void regenerateAndCreateRaceKeepsSingleUsable() throws Exception {
        Fixture fixture = createFixture("ACTIVE");
        long oldId = creationService.createOrReuse(fixture.proposalId()).response().id();

        List<Long> ids = runConcurrently(() -> creationService.regenerate(oldId).response().id(),
                () -> creationService.createOrReuse(fixture.proposalId()).response().id());

        Set<Long> allIds = new HashSet<>(allDraftIds(fixture));
        assertThat(allIds).containsAll(ids);
        assertThat(usableCount(fixture)).isEqualTo(1);
        assertThat(publishedCount(fixture)).isZero();
    }

    @Test
    void crossWorkspaceCreatesDoNotInterfere() throws Exception {
        Fixture first = createFixture("ACTIVE");
        Fixture second = createFixture("INACTIVE");
        long firstId = creationService.createOrReuse(first.proposalId()).response().id();

        db().sql("UPDATE workspace SET status = 'INACTIVE' WHERE status = 'ACTIVE'").update();
        db().sql("UPDATE workspace SET status = 'ACTIVE' WHERE id = :workspaceId")
                .param("workspaceId", second.workspaceId()).update();
        long secondId = creationService.createOrReuse(second.proposalId()).response().id();

        assertThat(secondId).isNotEqualTo(firstId);
        assertThat(usableCount(first)).isEqualTo(1);
        assertThat(usableCount(second)).isEqualTo(1);
    }

    @SafeVarargs
    private List<Long> runConcurrently(Callable<Long>... tasks) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(tasks.length);
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (Callable<Long> task : tasks) {
                futures.add(pool.submit(() -> {
                    // 會合後同時進入建立路徑：交錯由排程決定，斷言與交錯無關。
                    barrier.await(HANG_GUARD_SECONDS, TimeUnit.SECONDS);
                    return task.call();
                }));
            }
            List<Long> results = new ArrayList<>();
            for (Future<Long> future : futures) {
                results.add(future.get(HANG_GUARD_SECONDS, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private long activeWorkspaceId() {
        return workspaceService.findActiveWithoutValidation().orElseThrow().id();
    }

    private int usableCount(Fixture fixture) {
        return db().sql("SELECT COUNT(*) FROM wiki_draft WHERE workspace_id = :workspaceId "
                        + "AND proposal_id = :proposalId AND status IN ('DRAFT', 'READY')")
                .param("workspaceId", fixture.workspaceId()).param("proposalId", fixture.proposalId())
                .query(Integer.class).single();
    }

    private List<Long> usableIds(Fixture fixture) {
        return db().sql("SELECT id FROM wiki_draft WHERE workspace_id = :workspaceId "
                        + "AND proposal_id = :proposalId AND status IN ('DRAFT', 'READY') ORDER BY id")
                .param("workspaceId", fixture.workspaceId()).param("proposalId", fixture.proposalId())
                .query(Long.class).list();
    }

    private List<Long> allDraftIds(Fixture fixture) {
        return db().sql("SELECT id FROM wiki_draft WHERE workspace_id = :workspaceId "
                        + "AND proposal_id = :proposalId")
                .param("workspaceId", fixture.workspaceId()).param("proposalId", fixture.proposalId())
                .query(Long.class).list();
    }

    private int publishedCount(Fixture fixture) {
        return db().sql("SELECT COUNT(*) FROM wiki_draft WHERE workspace_id = :workspaceId "
                        + "AND proposal_id = :proposalId AND status = 'PUBLISHED'")
                .param("workspaceId", fixture.workspaceId()).param("proposalId", fixture.proposalId())
                .query(Integer.class).single();
    }

    private String statusOfDraft(long draftId) {
        return db().sql("SELECT status FROM wiki_draft WHERE id = :draftId")
                .param("draftId", draftId).query(String.class).single();
    }

    private Fixture createFixture(String workspaceStatus) throws Exception {
        Path root = tempDir.resolve("race-" + System.nanoTime());
        Files.createDirectories(root.resolve("vault"));
        Files.createDirectories(root.resolve("inbox"));
        Files.createDirectories(root.resolve("archive"));
        Files.createDirectories(root.resolve("data"));
        long workspaceId = insert("""
                INSERT INTO workspace (name, root_path, inbox_path, archive_path, vault_path, data_path, status,
                    created_at, updated_at)
                VALUES (:name, :root, :inbox, :archive, :vault, :data, :status,
                    '2026-08-29T00:00:00Z', '2026-08-29T00:00:00Z')
                """, "name", "race-" + System.nanoTime(), "root", root.toString(),
                "inbox", root.resolve("inbox").toString(), "archive", root.resolve("archive").toString(),
                "vault", root.resolve("vault").toString(), "data", root.resolve("data").toString(),
                "status", workspaceStatus);
        long documentId = insert("""
                INSERT INTO document (workspace_id, file_name, source_path, sha256, created_at, updated_at)
                VALUES (:workspaceId, 'source.txt', 'source.txt', 'race-hash',
                    '2026-08-29T00:00:00Z', '2026-08-29T00:00:00Z')
                """, "workspaceId", workspaceId);
        long jobId = insert("""
                INSERT INTO processing_job (workspace_id, job_id, job_type, created_at, updated_at)
                VALUES (:workspaceId, :jobId, 'ANALYZE',
                    '2026-08-29T00:00:00Z', '2026-08-29T00:00:00Z')
                """, "workspaceId", workspaceId, "jobId", "RACE-JOB-" + System.nanoTime());
        long jobItemId = insert("""
                INSERT INTO processing_job_item (job_id, document_id) VALUES (:jobId, :documentId)
                """, "jobId", jobId, "documentId", documentId);
        long analysisId = insert("""
                INSERT INTO document_analysis (job_item_id, document_id, status, prompt_identifier, prompt_version,
                    provider, model, contract_version, created_at, updated_at)
                VALUES (:jobItemId, :documentId, 'SUCCEEDED', 'document-analysis@test', 'v1',
                    'test-provider', 'test-model', 'v1', '2026-08-29T00:00:00Z', '2026-08-29T00:00:00Z')
                """, "jobItemId", jobItemId, "documentId", documentId);
        long chunkId = insert("""
                INSERT INTO source_chunk (document_id, chunk_no, content, normalized_content, content_hash,
                    created_at, updated_at)
                VALUES (:documentId, 1, 'Race evidence', 'Race evidence', 'race-chunk',
                    '2026-08-29T00:00:00Z', '2026-08-29T00:00:00Z')
                """, "documentId", documentId);
        long candidateId = insert("""
                INSERT INTO knowledge_candidate (document_analysis_id, document_id, candidate_no, title,
                    candidate_type, summary, confidence, rationale, created_at, updated_at)
                VALUES (:analysisId, :documentId, 1, 'Race Candidate', 'CONCEPT',
                    'Race summary', 0.9, 'Race rationale',
                    '2026-08-29T00:00:00Z', '2026-08-29T00:00:00Z')
                """, "analysisId", analysisId, "documentId", documentId);
        db().sql("""
                        INSERT INTO knowledge_candidate_evidence (knowledge_candidate_id, source_chunk_id)
                        VALUES (:candidateId, :sourceChunkId)
                        """)
                .param("candidateId", candidateId).param("sourceChunkId", chunkId).update();
        long proposalId = insert("""
                INSERT INTO knowledge_proposal (workspace_id, document_analysis_id, document_id,
                    knowledge_candidate_id, action, status, provider, model,
                    prompt_identifier, prompt_version, contract_version, normalized_data_json, created_at, updated_at)
                VALUES (:workspaceId, :analysisId, :documentId, :candidateId, 'CREATE', 'APPROVED', 'provider',
                    'model', 'prompt', 'v1', 'v1', :normalized,
                    '2026-08-29T00:00:00Z', '2026-08-29T00:00:00Z')
                """, "workspaceId", workspaceId, "analysisId", analysisId, "documentId", documentId,
                "candidateId", candidateId, "normalized",
                """
                {"title":"Race Topic","pageType":"CONCEPT","summary":"Race summary","tags":["race"],
                 "sections":[{"heading":"Summary","content":"Race content"}]}""");
        db().sql("""
                        INSERT INTO knowledge_proposal_evidence (knowledge_proposal_id, source_chunk_id)
                        VALUES (:proposalId, :sourceChunkId)
                        """)
                .param("proposalId", proposalId).param("sourceChunkId", chunkId).update();
        return new Fixture(workspaceId, proposalId);
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
            throw new AssertionError("測試資料新增後未取得 id");
        }
        return key.longValue();
    }

    private record Fixture(long workspaceId, long proposalId) {
    }
}
