package org.km.llmwiki.wiki;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
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
 * #569：proposal tags 是唯一的人控 tag mutation point。
 * REVIEW 可編輯（正規化＋保留其餘 normalized keys）、terminal 狀態 400、
 * REPAIR lineage 422、跨 workspace 404；documentId 過濾供 organize surface 使用。
 */
class ProposalTagsApiIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @TempDir
    Path tempDir;

    @Test
    void updatesReviewProposalTagsAndPreservesOtherNormalizedKeys() throws Exception {
        Fixture fixture = createFixture("ACTIVE", "tags.txt", "REVIEW",
                """
                {"title":"Tag Topic","summary":"Tag summary","tags":["old-tag"],"aliases":["Alias One"]}""");

        mockMvc.perform(patch("/api/v1/proposals/{proposalId}/tags", fixture.proposalId())
                        .contentType("application/json")
                        .content("{\"tags\":[\"  Human-Tag \",\"human-tag\",\"second\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(fixture.proposalId()))
                .andExpect(jsonPath("$.data.status").value("REVIEW"));

        String normalized = normalizedDataOf(fixture.proposalId());
        assertThat(normalized).contains("\"title\":\"Tag Topic\"");
        assertThat(normalized).contains("\"summary\":\"Tag summary\"");
        assertThat(normalized).contains("\"aliases\":[\"Alias One\"]");
        assertThat(normalized).contains("\"tags\":[\"human-tag\",\"second\"]");
        assertThat(normalized).doesNotContain("old-tag");
        assertThat(statusOf(fixture.proposalId())).isEqualTo("REVIEW");
    }

    @Test
    void rejectsTagUpdatesOnNonReviewStatusesWithoutChangingData() throws Exception {
        long workspaceId = insertWorkspace("ACTIVE", "status-guard");
        Fixture draft = createFixtureIn(workspaceId, "draft.txt", "DRAFT", "{}", "CONCEPT", "MERGE",
                "wiki:existing-topic");
        Fixture approved = createFixtureIn(workspaceId, "approved.txt", "REVIEW", "{}", "CONCEPT", "MERGE",
                "wiki:existing-topic");
        mockMvc.perform(patch("/api/v1/proposals/{proposalId}/status", approved.proposalId())
                        .contentType("application/json").content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/proposals/{proposalId}/tags", draft.proposalId())
                        .contentType("application/json").content("{\"tags\":[\"x\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mockMvc.perform(patch("/api/v1/proposals/{proposalId}/tags", approved.proposalId())
                        .contentType("application/json").content("{\"tags\":[\"x\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(normalizedDataOf(draft.proposalId())).isEqualTo("{}");
        assertThat(normalizedDataOf(approved.proposalId())).isEqualTo("{}");
    }

    @Test
    void rejectsRepairLineageWithTypedUnprocessableEntity() throws Exception {
        Fixture fixture = createFixture("ACTIVE", "repair.txt", "REVIEW", "{}");
        db().sql("UPDATE knowledge_proposal SET source_kind = 'REPAIR', source_dedup_hash = 'repair-hash-1' "
                        + "WHERE id = :proposalId")
                .param("proposalId", fixture.proposalId()).update();

        mockMvc.perform(patch("/api/v1/proposals/{proposalId}/tags", fixture.proposalId())
                        .contentType("application/json").content("{\"tags\":[\"human\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("PROPOSAL_TAGS_NOT_EDITABLE"));

        assertThat(normalizedDataOf(fixture.proposalId())).isEqualTo("{}");
    }

    @Test
    void rejectsCrossWorkspaceAndMissingProposalsAsNotFound() throws Exception {
        Fixture active = createFixture("ACTIVE", "active.txt", "REVIEW", "{}");
        Fixture other = createFixture("INACTIVE", "other.txt", "REVIEW", "{}");

        mockMvc.perform(patch("/api/v1/proposals/{proposalId}/tags", other.proposalId())
                        .contentType("application/json").content("{\"tags\":[\"x\"]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("KNOWLEDGE_PROPOSAL_NOT_FOUND"));
        mockMvc.perform(patch("/api/v1/proposals/{proposalId}/tags", 999_999L)
                        .contentType("application/json").content("{\"tags\":[\"x\"]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("KNOWLEDGE_PROPOSAL_NOT_FOUND"));

        assertThat(normalizedDataOf(active.proposalId())).isEqualTo("{}");
    }

    @Test
    void rejectsInvalidTagShapesWithoutChangingData() throws Exception {
        Fixture fixture = createFixture("ACTIVE", "shapes.txt", "REVIEW", "{\"tags\":[\"keep\"]}");

        mockMvc.perform(patch("/api/v1/proposals/{proposalId}/tags", fixture.proposalId())
                        .contentType("application/json").content("{\"tags\":[\"ok\",\"   \"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        StringBuilder tooMany = new StringBuilder("{\"tags\":[");
        for (int i = 0; i < KnowledgeTagPolicy.MAX_TAGS + 1; i++) {
            if (i > 0) {
                tooMany.append(',');
            }
            tooMany.append("\"tag-").append(i).append('\"');
        }
        mockMvc.perform(patch("/api/v1/proposals/{proposalId}/tags", fixture.proposalId())
                        .contentType("application/json").content(tooMany.append("]}").toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(normalizedDataOf(fixture.proposalId())).contains("\"tags\":[\"keep\"]");
    }

    @Test
    void listsProposalsFilteredByDocumentForTheOrganizeSurface() throws Exception {
        Fixture first = createFixture("ACTIVE", "first.txt", "REVIEW", "{}");
        Fixture second = createFixture("INACTIVE-DOC", "second.txt", "REVIEW", "{}");
        insertAskProposal();

        mockMvc.perform(get("/api/v1/proposals").param("status", "REVIEW")
                        .param("documentId", String.valueOf(first.documentId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(first.proposalId()));

        // 第二份文件在同一個 ACTIVE workspace 之外不可見；未過濾時 ASK 提案仍可見。
        mockMvc.perform(get("/api/v1/proposals").param("status", "REVIEW")
                        .param("documentId", String.valueOf(second.documentId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(get("/api/v1/proposals").param("status", "REVIEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2));

        mockMvc.perform(get("/api/v1/proposals").param("documentId", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void patchedTagsFlowIntoNewlyCreatedDraftPreview() throws Exception {
        Path root = tempDir.resolve("tagflow");
        Files.createDirectories(root.resolve("vault"));
        Files.createDirectories(root.resolve("inbox"));
        Files.createDirectories(root.resolve("archive"));
        Files.createDirectories(root.resolve("data"));
        long workspaceId = insert("""
                INSERT INTO workspace (name, root_path, inbox_path, archive_path, vault_path, data_path, status,
                    created_at, updated_at)
                VALUES ('tagflow', :root, :inbox, :archive, :vault, :data, 'ACTIVE',
                    '2026-08-29T00:00:00Z', '2026-08-29T00:00:00Z')
                """, "root", root.toString(), "inbox", root.resolve("inbox").toString(),
                "archive", root.resolve("archive").toString(), "vault", root.resolve("vault").toString(),
                "data", root.resolve("data").toString());
        Fixture fixture = createFixtureIn(workspaceId, "flow.txt", "REVIEW",
                """
                {"title":"Flow Topic","pageType":"CONCEPT","summary":"Flow summary","tags":["stale-tag"],
                 "sections":[{"heading":"Summary","content":"Flow content"}]}""",
                "CONCEPT", "CREATE", null);

        mockMvc.perform(patch("/api/v1/proposals/{proposalId}/tags", fixture.proposalId())
                        .contentType("application/json").content("{\"tags\":[\"human-tag\"]}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/proposals/{proposalId}/status", fixture.proposalId())
                        .contentType("application/json").content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isOk());

        String created = mockMvc.perform(post("/api/v1/wiki-drafts").contentType("application/json")
                        .content("{\"proposalId\":" + fixture.proposalId() + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long draftId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(created).path("data").path("id").asLong();

        mockMvc.perform(get("/api/v1/wiki-drafts/{id}/preview", draftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.markdown",
                        org.hamcrest.Matchers.containsString("human-tag")));
        String markdown = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                mockMvc.perform(get("/api/v1/wiki-drafts/{id}/preview", draftId))
                        .andReturn().getResponse().getContentAsString())
                .path("data").path("markdown").asText();
        assertThat(markdown).doesNotContain("stale-tag");
    }

    @Test
    void tagMutationLeavesRetrievalSourceDataUntouched() throws Exception {
        Fixture fixture = createFixture("ACTIVE", "untouched.txt", "REVIEW", "{\"tags\":[\"before\"]}");
        String documentBefore = db().sql("SELECT updated_at || '|' || status FROM document WHERE id = :documentId")
                .param("documentId", fixture.documentId()).query(String.class).single();
        String candidateBefore = db().sql("SELECT title || '|' || candidate_type FROM knowledge_candidate "
                        + "WHERE document_id = :documentId")
                .param("documentId", fixture.documentId()).query(String.class).single();

        mockMvc.perform(patch("/api/v1/proposals/{proposalId}/tags", fixture.proposalId())
                        .contentType("application/json").content("{\"tags\":[\"after\"]}"))
                .andExpect(status().isOk());

        assertThat(db().sql("SELECT updated_at || '|' || status FROM document WHERE id = :documentId")
                .param("documentId", fixture.documentId()).query(String.class).single()).isEqualTo(documentBefore);
        assertThat(db().sql("SELECT title || '|' || candidate_type FROM knowledge_candidate "
                        + "WHERE document_id = :documentId")
                .param("documentId", fixture.documentId()).query(String.class).single()).isEqualTo(candidateBefore);
    }

    private Fixture createFixture(String workspaceStatus, String fileName, String proposalStatus,
                                  String normalizedDataJson) {
        return createFixtureIn(insertWorkspace(workspaceStatus, fileName), fileName, proposalStatus,
                normalizedDataJson, "CONCEPT", "MERGE", "wiki:existing-topic");
    }

    private long insertWorkspace(String workspaceStatus, String fileName) {
        return insert("""
                INSERT INTO workspace (name, root_path, inbox_path, archive_path, vault_path, data_path, status, created_at, updated_at)
                VALUES (:name, :rootPath, :inboxPath, :archivePath, :vaultPath, :dataPath, :status, :now, :now)
                """, "name", fileName, "rootPath", "/tmp/" + fileName, "inboxPath", "/tmp/" + fileName + "/inbox",
                "archivePath", "/tmp/" + fileName + "/archive", "vaultPath", "/tmp/" + fileName + "/vault",
                "dataPath", "/tmp/" + fileName + "/data", "status", workspaceStatus, "now", "2026-08-27T00:00:00Z");
    }

    private Fixture createFixtureIn(long workspaceId, String fileName, String proposalStatus,
                                    String normalizedDataJson, String candidateType, String action,
                                    String targetReference) {
        long documentId = insert("""
                INSERT INTO document (workspace_id, file_name, original_file_name, source_path, sha256, status, created_at, updated_at)
                VALUES (:workspaceId, :fileName, :fileName, :sourcePath, :hash, 'PROCESSED', :now, :now)
                """, "workspaceId", workspaceId, "fileName", fileName, "sourcePath", "inbox/" + fileName,
                "hash", "hash-" + fileName, "now", "2026-08-27T00:00:00Z");
        long jobId = insert("""
                INSERT INTO processing_job (workspace_id, job_id, job_type, created_at, updated_at)
                VALUES (:workspaceId, :jobId, 'ANALYZE', :now, :now)
                """, "workspaceId", workspaceId, "jobId", "JOB-" + fileName, "now", "2026-08-27T00:00:00Z");
        long jobItemId = insert("""
                INSERT INTO processing_job_item (job_id, document_id)
                VALUES (:jobId, :documentId)
                """, "jobId", jobId, "documentId", documentId);
        long analysisId = insert("""
                INSERT INTO document_analysis (job_item_id, document_id, status, prompt_identifier, prompt_version,
                    provider, model, contract_version, created_at, updated_at)
                VALUES (:jobItemId, :documentId, 'SUCCEEDED', 'prompt', 'v1', 'provider', 'model', 'v1', :now, :now)
                """, "jobItemId", jobItemId, "documentId", documentId, "now", "2026-08-27T00:00:00Z");
        long sourceChunkId = insert("""
                INSERT INTO source_chunk (document_id, chunk_no, content, normalized_content, content_hash, created_at, updated_at)
                VALUES (:documentId, 1, '可供標籤調整的來源內容', '可供標籤調整的來源內容', :hash, :now, :now)
                """, "documentId", documentId, "hash", "chunk-" + fileName, "now", "2026-08-27T00:00:00Z");
        long candidateId = insert("""
                INSERT INTO knowledge_candidate (document_analysis_id, document_id, candidate_no, title, candidate_type,
                    summary, confidence, rationale, created_at, updated_at)
                VALUES (:analysisId, :documentId, 1, '標籤測試候選', :candidateType, '候選摘要', 0.8, '具備來源佐證', :now, :now)
                """, "analysisId", analysisId, "documentId", documentId, "candidateType", candidateType,
                "now", "2026-08-27T00:00:00Z");
        db().sql("""
                INSERT INTO knowledge_candidate_evidence (knowledge_candidate_id, source_chunk_id)
                VALUES (:candidateId, :sourceChunkId)
                """).param("candidateId", candidateId).param("sourceChunkId", sourceChunkId).update();
        long proposalId = insert("""
                INSERT INTO knowledge_proposal (workspace_id, document_analysis_id, document_id, knowledge_candidate_id,
                    action, status, merge_target_reference, provider, model, prompt_identifier, prompt_version,
                    contract_version, normalized_data_json, created_at, updated_at)
                VALUES (:workspaceId, :analysisId, :documentId, :candidateId, :action, :status, :target, 'provider',
                    'model', 'prompt', 'v1', 'v1', :normalized, :now, :now)
                """, "workspaceId", workspaceId, "analysisId", analysisId, "documentId", documentId,
                "candidateId", candidateId, "action", action, "status", proposalStatus, "target", targetReference,
                "normalized", normalizedDataJson, "now", "2026-08-27T00:00:00Z");
        db().sql("""
                INSERT INTO knowledge_proposal_evidence (knowledge_proposal_id, source_chunk_id)
                VALUES (:proposalId, :sourceChunkId)
                """).param("proposalId", proposalId).param("sourceChunkId", sourceChunkId).update();
        return new Fixture(proposalId, documentId, sourceChunkId);
    }

    private void insertAskProposal() {
        long workspaceId = db().sql("SELECT id FROM workspace WHERE status = 'ACTIVE'")
                .query(Long.class).single();
        db().sql("""
                INSERT INTO knowledge_proposal (workspace_id, action, status, provider, model, prompt_identifier,
                    prompt_version, contract_version, normalized_data_json, source_kind, ask_question,
                    ask_answer_text, ask_citations_json, source_dedup_hash, created_at, updated_at)
                VALUES (:workspaceId, 'CREATE', 'REVIEW', 'provider', 'model', 'prompt', 'v1', 'v1', '{}', 'ASK',
                    '提問？', '回答', '[]', 'ask-hash-1', '2026-08-27T00:00:00Z', '2026-08-27T00:00:00Z')
                """).param("workspaceId", workspaceId).update();
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

    private String normalizedDataOf(long proposalId) {
        return db().sql("SELECT normalized_data_json FROM knowledge_proposal WHERE id = :proposalId")
                .param("proposalId", proposalId).query(String.class).single();
    }

    private record Fixture(long proposalId, long documentId, long sourceChunkId) {
    }
}
