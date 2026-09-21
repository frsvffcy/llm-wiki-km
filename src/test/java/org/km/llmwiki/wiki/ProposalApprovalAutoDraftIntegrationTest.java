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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #570：核准後自動準備草稿——成功攜帶 draft、重試沿用、失敗保持 APPROVED＋typed recovery、
 * 絕不自動 publish；REPAIR 走同一條路徑；非核准轉換不攜帶 autoDraft。
 */
class ProposalApprovalAutoDraftIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProposalAutoDraftService autoDraftService;

    @Autowired
    private WorkspaceService workspaceService;

    @TempDir
    Path tempDir;

    @Test
    void approveCreatesDraftAndReturnsItWithoutPublishing() throws Exception {
        Fixture fixture = createFixture("CREATE", null, "REVIEW",
                """
                {"title":"Approve Topic","pageType":"CONCEPT","summary":"Approve summary",
                 "tags":["auto-tag"],"sections":[{"heading":"Summary","content":"Approve content"}]}""");

        String response = mockMvc.perform(patch("/api/v1/proposals/{proposalId}/status", fixture.proposalId())
                        .contentType("application/json").content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.allowedTransitions.length()").value(0))
                .andExpect(jsonPath("$.data.autoDraft.draftId").isNumber())
                .andExpect(jsonPath("$.data.autoDraft.draftStatus").value("READY"))
                .andExpect(jsonPath("$.data.autoDraft.reused").value(false))
                .andReturn().getResponse().getContentAsString();
        var autoDraft = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response)
                .path("data").path("autoDraft");
        long draftId = autoDraft.path("draftId").asLong();
        assertThat(autoDraft.path("errorCode").isNull()).isTrue();

        // 核准絕不自動發布：wiki 仍空、draft 為 READY snapshot。
        mockMvc.perform(get("/api/v1/wiki").param("page", "0").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));
        assertThat(count("wiki_draft")).isEqualTo(1);
        assertThat(draftStatusOf(draftId)).isEqualTo("READY");

        // 預覽走同一 task flow 可見，內容承接核准前 tags。
        mockMvc.perform(get("/api/v1/wiki-drafts/{id}/preview", draftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.markdown",
                        org.hamcrest.Matchers.containsString("auto-tag")));

        // Terminal 状态不可重複核准：無重複副作用。
        mockMvc.perform(patch("/api/v1/proposals/{proposalId}/status", fixture.proposalId())
                        .contentType("application/json").content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isBadRequest());
        assertThat(count("wiki_draft")).isEqualTo(1);
    }

    @Test
    void prepareReusesExistingUsableDraftInsteadOfDuplicating() throws Exception {
        Fixture fixture = createFixture("CREATE", null, "REVIEW", "{}");
        mockMvc.perform(patch("/api/v1/proposals/{proposalId}/status", fixture.proposalId())
                        .contentType("application/json").content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.autoDraft.reused").value(false));

        long workspaceId = workspaceService.findActiveWithoutValidation().orElseThrow().id();
        ProposalAutoDraft second = autoDraftService.prepare(workspaceId, fixture.proposalId());

        assertThat(second.draftId()).isNotNull();
        assertThat(second.reused()).isTrue();
        assertThat(count("wiki_draft")).isEqualTo(1);
    }

    @Test
    void autoDraftFailureKeepsApprovedWithTypedRecoveryAndNoPublish() throws Exception {
        // MERGE 卻無 merge target：converter fail-closed，核准本身不受影響。
        Fixture fixture = createFixture("MERGE", null, "REVIEW", "{}");

        String failureResponse = mockMvc.perform(patch("/api/v1/proposals/{proposalId}/status",
                                fixture.proposalId())
                        .contentType("application/json").content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.autoDraft.errorCode").value("AUTO_DRAFT_INVALID_NORMALIZED_DATA"))
                .andExpect(jsonPath("$.data.autoDraft.errorMessage").isString())
                .andReturn().getResponse().getContentAsString();
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(failureResponse)
                .path("data").path("autoDraft").path("draftId").isNull()).isTrue();

        assertThat(statusOf(fixture.proposalId())).isEqualTo("APPROVED");
        assertThat(count("wiki_draft")).isEqualTo(0);
        mockMvc.perform(get("/api/v1/wiki").param("page", "0").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));

        // Recovery path 仍為明確的人工建草稿（此處仍失敗，但為 typed 失敗且 proposal 不動）。
        mockMvc.perform(post("/api/v1/wiki-drafts").contentType("application/json")
                        .content("{\"proposalId\":" + fixture.proposalId() + "}"))
                .andExpect(status().is4xxClientError());
        assertThat(statusOf(fixture.proposalId())).isEqualTo("APPROVED");
    }

    @Test
    void repairProposalApproveSharesTheSamePathWithTypedOutcome() throws Exception {
        Fixture fixture = createFixture("MERGE", null, "REVIEW", "{}");
        db().sql("UPDATE knowledge_proposal SET source_kind = 'REPAIR', source_dedup_hash = 'repair-570' "
                        + "WHERE id = :proposalId")
                .param("proposalId", fixture.proposalId()).update();

        mockMvc.perform(patch("/api/v1/proposals/{proposalId}/status", fixture.proposalId())
                        .contentType("application/json").content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                // 同一路徑：成功攜 draftId，失敗攜 typed errorCode——皆不得另起第二套 authority。
                .andExpect(jsonPath("$.data.autoDraft").isMap());

        assertThat(statusOf(fixture.proposalId())).isEqualTo("APPROVED");
        mockMvc.perform(get("/api/v1/wiki").param("page", "0").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    void nonApproveTransitionsCarryNoAutoDraft() throws Exception {
        // 同一 ACTIVE 工作區兩個提案：active workspace 唯一，避免選取不確定。
        Path root = newWorkspaceRoot();
        long workspaceId = insertWorkspace(root);
        Fixture toReview = createProposalIn(workspaceId, "CREATE", null, "DRAFT", "{}");
        Fixture toRejected = createProposalIn(workspaceId, "CREATE", null, "DRAFT", "{}");

        assertAutoDraftAbsent(toReview.proposalId(), "REVIEW");
        assertAutoDraftAbsent(toRejected.proposalId(), "REJECTED");

        assertThat(count("wiki_draft")).isEqualTo(0);
    }

    private void assertAutoDraftAbsent(long proposalId, String targetStatus) throws Exception {
        String response = mockMvc.perform(patch("/api/v1/proposals/{proposalId}/status", proposalId)
                        .contentType("application/json").content("{\"status\":\"" + targetStatus + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(response)
                .path("data").path("autoDraft").isNull()).isTrue();
    }

    private Fixture createFixture(String action, String targetReference, String proposalStatus,
                                  String normalizedDataJson) throws Exception {
        Path root = newWorkspaceRoot();
        long workspaceId = insertWorkspace(root);
        return createProposalIn(workspaceId, action, targetReference, proposalStatus, normalizedDataJson);
    }

    private Path newWorkspaceRoot() throws Exception {
        Path root = tempDir.resolve("approve-" + System.nanoTime());
        Files.createDirectories(root.resolve("vault"));
        Files.createDirectories(root.resolve("inbox"));
        Files.createDirectories(root.resolve("archive"));
        Files.createDirectories(root.resolve("data"));
        return root;
    }

    private long insertWorkspace(Path root) {
        return insert("""
                INSERT INTO workspace (name, root_path, inbox_path, archive_path, vault_path, data_path, status,
                    created_at, updated_at)
                VALUES (:name, :root, :inbox, :archive, :vault, :data, 'ACTIVE',
                    '2026-08-29T00:00:00Z', '2026-08-29T00:00:00Z')
                """, "name", "approve-" + System.nanoTime(), "root", root.toString(),
                "inbox", root.resolve("inbox").toString(), "archive", root.resolve("archive").toString(),
                "vault", root.resolve("vault").toString(), "data", root.resolve("data").toString());
    }

    private Fixture createProposalIn(long workspaceId, String action, String targetReference,
                                     String proposalStatus, String normalizedDataJson) {
        long documentId = insert("""
                INSERT INTO document (workspace_id, file_name, source_path, sha256, created_at, updated_at)
                VALUES (:workspaceId, 'source.txt', 'source.txt', 'approve-hash',
                    '2026-08-29T00:00:00Z', '2026-08-29T00:00:00Z')
                """, "workspaceId", workspaceId);
        long jobId = insert("""
                INSERT INTO processing_job (workspace_id, job_id, job_type, created_at, updated_at)
                VALUES (:workspaceId, :jobId, 'ANALYZE',
                    '2026-08-29T00:00:00Z', '2026-08-29T00:00:00Z')
                """, "workspaceId", workspaceId, "jobId", "APPROVE-JOB-" + System.nanoTime());
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
                VALUES (:documentId, 1, 'Approve evidence', 'Approve evidence', 'approve-chunk',
                    '2026-08-29T00:00:00Z', '2026-08-29T00:00:00Z')
                """, "documentId", documentId);
        long candidateId = insert("""
                INSERT INTO knowledge_candidate (document_analysis_id, document_id, candidate_no, title,
                    candidate_type, summary, confidence, rationale, created_at, updated_at)
                VALUES (:analysisId, :documentId, 1, 'Approve Candidate', 'CONCEPT',
                    'Approve summary', 0.9, 'Approve rationale',
                    '2026-08-29T00:00:00Z', '2026-08-29T00:00:00Z')
                """, "analysisId", analysisId, "documentId", documentId);
        db().sql("""
                        INSERT INTO knowledge_candidate_evidence (knowledge_candidate_id, source_chunk_id)
                        VALUES (:candidateId, :sourceChunkId)
                        """)
                .param("candidateId", candidateId).param("sourceChunkId", chunkId).update();
        long proposalId = insert("""
                INSERT INTO knowledge_proposal (workspace_id, document_analysis_id, document_id,
                    knowledge_candidate_id, action, status, merge_target_reference, provider, model,
                    prompt_identifier, prompt_version, contract_version, normalized_data_json, created_at, updated_at)
                VALUES (:workspaceId, :analysisId, :documentId, :candidateId, :action, :status, :target,
                    'provider', 'model', 'prompt', 'v1', 'v1', :normalized,
                    '2026-08-29T00:00:00Z', '2026-08-29T00:00:00Z')
                """, "workspaceId", workspaceId, "analysisId", analysisId, "documentId", documentId,
                "candidateId", candidateId, "action", action, "status", proposalStatus, "target", targetReference,
                "normalized", normalizedDataJson);
        db().sql("""
                        INSERT INTO knowledge_proposal_evidence (knowledge_proposal_id, source_chunk_id)
                        VALUES (:proposalId, :sourceChunkId)
                        """)
                .param("proposalId", proposalId).param("sourceChunkId", chunkId).update();
        return new Fixture(proposalId);
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

    private String statusOf(long proposalId) {
        return db().sql("SELECT status FROM knowledge_proposal WHERE id = :proposalId")
                .param("proposalId", proposalId).query(String.class).single();
    }

    private String draftStatusOf(long draftId) {
        return db().sql("SELECT status FROM wiki_draft WHERE id = :draftId")
                .param("draftId", draftId).query(String.class).single();
    }

    private int count(String table) {
        return db().sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    private record Fixture(long proposalId) {
    }
}
