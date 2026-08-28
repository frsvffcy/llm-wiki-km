package org.km.llmwiki.wiki;

import org.junit.jupiter.api.Test;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.persistence.sqlite.path=target/test-data/proposal-review-api-${random.uuid}/knowledge.db")
@AutoConfigureMockMvc
class KnowledgeProposalReviewApiIntegrationTest extends IsolatedIntegrationTest {

    private static final double HIGH_PRECISION_CONFIDENCE = 0.123456789012345;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listsActiveWorkspaceProposalsWithStatusFilterAndEvidenceSummary() throws Exception {
        Fixture active = createFixture("ACTIVE", "active.txt", "DRAFT");
        createFixture("INACTIVE", "other.txt", "REVIEW");

        mockMvc.perform(get("/api/v1/proposals").param("status", "DRAFT").param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(active.proposalId()))
                .andExpect(jsonPath("$.data[0].action").value("MERGE"))
                .andExpect(jsonPath("$.data[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.data[0].title").value("審核測試 Proposal"))
                .andExpect(jsonPath("$.data[0].rationale").value("具備來源佐證"))
                .andExpect(jsonPath("$.data[0].confidence").value(0.86))
                .andExpect(jsonPath("$.data[0].targetReference").value("wiki:existing-topic"))
                .andExpect(jsonPath("$.data[0].sourceDocument.id").value(active.documentId()))
                .andExpect(jsonPath("$.data[0].evidence[0].sourceChunkId").value(active.sourceChunkId()))
                .andExpect(jsonPath("$.data[0].evidence[0].content").value("可供人工審核的來源內容"))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    void preservesStoredConfidencePrecisionWhenReadingProposalReview() throws Exception {
        Fixture active = createFixture("ACTIVE", "precise.txt", "DRAFT", HIGH_PRECISION_CONFIDENCE);

        String response = mockMvc.perform(get("/api/v1/proposals/{proposalId}", active.proposalId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        double confidence = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(response).path("data").path("confidence").doubleValue();
        assertThat(confidence).isEqualTo(HIGH_PRECISION_CONFIDENCE);
    }

    @Test
    void getsProposalWithSourceDocumentAndRejectsCrossWorkspaceAccess() throws Exception {
        Fixture active = createFixture("ACTIVE", "active.txt", "DRAFT");
        Fixture other = createFixture("INACTIVE", "other.txt", "DRAFT");

        mockMvc.perform(get("/api/v1/proposals/{proposalId}", active.proposalId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceDocument.fileName").value("active.txt"))
                .andExpect(jsonPath("$.data.evidence[0].chunkNo").value(1));

        mockMvc.perform(get("/api/v1/proposals/{proposalId}", other.proposalId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("KNOWLEDGE_PROPOSAL_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/proposals/{proposalId}", 999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("KNOWLEDGE_PROPOSAL_NOT_FOUND"));

        db().sql("UPDATE document SET status = 'DELETED' WHERE id = :documentId")
                .param("documentId", active.documentId()).update();
        mockMvc.perform(get("/api/v1/proposals/{proposalId}", active.proposalId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("KNOWLEDGE_PROPOSAL_NOT_FOUND"));
    }

    @Test
    void transitionsDraftToReviewThenReviewToApprovedWithoutPublishing() throws Exception {
        Fixture fixture = createFixture("ACTIVE", "active.txt", "DRAFT");

        mockMvc.perform(patch("/api/v1/proposals/{proposalId}/status", fixture.proposalId())
                        .contentType("application/json").content("{\"status\":\"REVIEW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVIEW"));
        mockMvc.perform(patch("/api/v1/proposals/{proposalId}/status", fixture.proposalId())
                        .contentType("application/json").content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        assertThat(statusOf(fixture.proposalId())).isEqualTo("APPROVED");
        assertThat(count("knowledge_proposal")).isEqualTo(1);
        assertThat(count("knowledge_candidate")).isEqualTo(1);
    }

    @Test
    void permitsReviewToRejected() throws Exception {
        Fixture fixture = createFixture("ACTIVE", "active.txt", "REVIEW");

        mockMvc.perform(patch("/api/v1/proposals/{proposalId}/status", fixture.proposalId())
                        .contentType("application/json").content("{\"status\":\"REJECTED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        assertThat(statusOf(fixture.proposalId())).isEqualTo("REJECTED");
    }

    @Test
    void rejectsInvalidTransitionsAndRequestsWithoutChangingData() throws Exception {
        Fixture fixture = createFixture("ACTIVE", "active.txt", "DRAFT");

        mockMvc.perform(patch("/api/v1/proposals/{proposalId}/status", fixture.proposalId())
                        .contentType("application/json").content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mockMvc.perform(patch("/api/v1/proposals/{proposalId}/status", fixture.proposalId())
                        .contentType("application/json").content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/v1/proposals").param("status", "PUBLISHED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/v1/proposals").param("size", "201"))
                .andExpect(status().isBadRequest());

        assertThat(statusOf(fixture.proposalId())).isEqualTo("DRAFT");
    }

    private Fixture createFixture(String workspaceStatus, String fileName, String proposalStatus) {
        return createFixture(workspaceStatus, fileName, proposalStatus, 0.86);
    }

    private Fixture createFixture(String workspaceStatus, String fileName, String proposalStatus, double confidence) {
        long workspaceId = insert("""
                INSERT INTO workspace (name, root_path, inbox_path, archive_path, vault_path, data_path, status, created_at, updated_at)
                VALUES (:name, :rootPath, :inboxPath, :archivePath, :vaultPath, :dataPath, :status, :now, :now)
                """, "name", fileName, "rootPath", "/tmp/" + fileName, "inboxPath", "/tmp/" + fileName + "/inbox",
                "archivePath", "/tmp/" + fileName + "/archive", "vaultPath", "/tmp/" + fileName + "/vault",
                "dataPath", "/tmp/" + fileName + "/data", "status", workspaceStatus, "now", "2026-08-27T00:00:00Z");
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
                VALUES (:documentId, 1, '可供人工審核的來源內容', '可供人工審核的來源內容', :hash, :now, :now)
                """, "documentId", documentId, "hash", "chunk-" + fileName, "now", "2026-08-27T00:00:00Z");
        long candidateId = insert("""
                INSERT INTO knowledge_candidate (document_analysis_id, document_id, candidate_no, title, candidate_type,
                    summary, confidence, rationale, created_at, updated_at)
                VALUES (:analysisId, :documentId, 1, '審核測試 Proposal', 'CONCEPT', 'Proposal 摘要', :confidence, '具備來源佐證', :now, :now)
                """, "analysisId", analysisId, "documentId", documentId, "confidence", confidence,
                "now", "2026-08-27T00:00:00Z");
        long proposalId = insert("""
                INSERT INTO knowledge_proposal (workspace_id, document_analysis_id, document_id, knowledge_candidate_id,
                    action, status, merge_target_reference, provider, model, prompt_identifier, prompt_version,
                    contract_version, normalized_data_json, created_at, updated_at)
                VALUES (:workspaceId, :analysisId, :documentId, :candidateId, 'MERGE', :status, 'wiki:existing-topic',
                    'provider', 'model', 'prompt', 'v1', 'v1', '{}', :now, :now)
                """, "workspaceId", workspaceId, "analysisId", analysisId, "documentId", documentId,
                "candidateId", candidateId, "status", proposalStatus, "now", "2026-08-27T00:00:00Z");
        db().sql("""
                INSERT INTO knowledge_proposal_evidence (knowledge_proposal_id, source_chunk_id)
                VALUES (:proposalId, :sourceChunkId)
                """).param("proposalId", proposalId).param("sourceChunkId", sourceChunkId).update();
        return new Fixture(proposalId, documentId, sourceChunkId);
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

    private int count(String table) {
        return db().sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    private record Fixture(long proposalId, long documentId, long sourceChunkId) {
    }
}
