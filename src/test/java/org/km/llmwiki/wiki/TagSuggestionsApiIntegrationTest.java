package org.km.llmwiki.wiki;

import org.junit.jupiter.api.Test;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #569：自動分類建議是唯讀 ephemeral 投影——candidate controlled type 推導、
 * 最新非 REJECTED 提案 tags、不虛構 ambiguous pageType、stale 明示、零寫入。
 */
class TagSuggestionsApiIntegrationTest extends IsolatedIntegrationTest {

    private static final String DOC_TIME = "2026-08-27T00:00:00Z";
    private static final String ANALYSIS_TIME = "2026-08-27T01:00:00Z";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsCurrentSuggestionsWithPageTypeAndProposalTags() throws Exception {
        Fixture fixture = createFixture("ACTIVE", "suggest.txt");

        String response = mockMvc.perform(get("/api/v1/organization/tag-suggestions").param("documentId",
                        String.valueOf(fixture.documentId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documentId").value(fixture.documentId()))
                .andExpect(jsonPath("$.data.analysisId").value(fixture.analysisId()))
                .andExpect(jsonPath("$.data.current").value(true))
                .andExpect(jsonPath("$.data.freshness").value("CURRENT"))
                .andExpect(jsonPath("$.data.suggestions.length()").value(2))
                .andExpect(jsonPath("$.data.suggestions[0].candidateNo").value(1))
                .andExpect(jsonPath("$.data.suggestions[0].candidateType").value("CONCEPT"))
                .andExpect(jsonPath("$.data.suggestions[0].suggestedPageType").value("CONCEPT"))
                .andExpect(jsonPath("$.data.suggestions[0].tags[0]").value("live"))
                .andExpect(jsonPath("$.data.suggestions[0].tagsOrigin").value("PROPOSAL"))
                .andExpect(jsonPath("$.data.suggestions[1].candidateNo").value(2))
                .andExpect(jsonPath("$.data.suggestions[1].candidateType").value("FACT"))
                .andExpect(jsonPath("$.data.suggestions[1].tags.length()").value(0))
                .andExpect(jsonPath("$.data.suggestions[1].tagsOrigin").value("NONE"))
                .andReturn().getResponse().getContentAsString();

        // FACT 無單一 Wiki home：不虛構建議，以 null 明示需人工決定。
        var second = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response)
                .path("data").path("suggestions").path(1);
        assertThat(second.path("suggestedPageType").isNull()).isTrue();
    }

    @Test
    void marksStaleWhenDocumentChangedAfterAnalysis() throws Exception {
        Fixture fixture = createFixture("ACTIVE", "stale.txt");
        db().sql("UPDATE document SET updated_at = '2026-08-28T00:00:00Z' WHERE id = :documentId")
                .param("documentId", fixture.documentId()).update();

        mockMvc.perform(get("/api/v1/organization/tag-suggestions").param("documentId",
                        String.valueOf(fixture.documentId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current").value(false))
                .andExpect(jsonPath("$.data.freshness").value("DOCUMENT_CHANGED_AFTER_ANALYSIS"))
                .andExpect(jsonPath("$.data.suggestions.length()").value(2));
    }

    @Test
    void returnsNoAnalysisWhenNeverAnalyzed() throws Exception {
        long workspaceId = insertWorkspace("ACTIVE", "empty.txt");
        long documentId = insertDocument(workspaceId, "empty.txt");

        String response = mockMvc.perform(get("/api/v1/organization/tag-suggestions").param("documentId",
                        String.valueOf(documentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current").value(false))
                .andExpect(jsonPath("$.data.freshness").value("NO_ANALYSIS"))
                .andExpect(jsonPath("$.data.suggestions.length()").value(0))
                .andReturn().getResponse().getContentAsString();

        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(response)
                .path("data").path("analysisId").isNull()).isTrue();
    }

    @Test
    void rejectsCrossWorkspaceDeletedAndInvalidDocuments() throws Exception {
        Fixture active = createFixture("ACTIVE", "visible.txt");
        Fixture other = createFixture("INACTIVE", "foreign.txt", "foreign-analysis");

        mockMvc.perform(get("/api/v1/organization/tag-suggestions").param("documentId",
                        String.valueOf(other.documentId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/organization/tag-suggestions").param("documentId", "999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_NOT_FOUND"));

        db().sql("UPDATE document SET status = 'DELETED' WHERE id = :documentId")
                .param("documentId", active.documentId()).update();
        mockMvc.perform(get("/api/v1/organization/tag-suggestions").param("documentId",
                        String.valueOf(active.documentId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/organization/tag-suggestions").param("documentId", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void suggestionReadPerformsNoWrites() throws Exception {
        Fixture fixture = createFixture("ACTIVE", "readonly.txt");
        long proposalsBefore = count("knowledge_proposal");
        long candidatesBefore = count("knowledge_candidate");
        String documentBefore = db().sql("SELECT updated_at FROM document WHERE id = :documentId")
                .param("documentId", fixture.documentId()).query(String.class).single();

        mockMvc.perform(get("/api/v1/organization/tag-suggestions").param("documentId",
                        String.valueOf(fixture.documentId())))
                .andExpect(status().isOk());

        assertThat(count("knowledge_proposal")).isEqualTo(proposalsBefore);
        assertThat(count("knowledge_candidate")).isEqualTo(candidatesBefore);
        assertThat(db().sql("SELECT updated_at FROM document WHERE id = :documentId")
                .param("documentId", fixture.documentId()).query(String.class).single()).isEqualTo(documentBefore);
    }

    private Fixture createFixture(String workspaceStatus, String fileName) {
        return createFixture(workspaceStatus, fileName, "analysis-" + fileName);
    }

    private Fixture createFixture(String workspaceStatus, String fileName, String jobTag) {
        long workspaceId = insertWorkspace(workspaceStatus, fileName);
        long documentId = insertDocument(workspaceId, fileName);
        long jobId = insert("""
                INSERT INTO processing_job (workspace_id, job_id, job_type, created_at, updated_at)
                VALUES (:workspaceId, :jobId, 'ANALYZE', :now, :now)
                """, "workspaceId", workspaceId, "jobId", "JOB-" + jobTag, "now", DOC_TIME);
        long jobItemId = insert("""
                INSERT INTO processing_job_item (job_id, document_id)
                VALUES (:jobId, :documentId)
                """, "jobId", jobId, "documentId", documentId);
        long analysisId = insert("""
                INSERT INTO document_analysis (job_item_id, document_id, status, prompt_identifier, prompt_version,
                    provider, model, contract_version, created_at, updated_at)
                VALUES (:jobItemId, :documentId, 'SUCCEEDED', 'prompt', 'v1', 'provider', 'model', 'v1',
                    :created, :created)
                """, "jobItemId", jobItemId, "documentId", documentId, "created", ANALYSIS_TIME);
        long sourceChunkId = insert("""
                INSERT INTO source_chunk (document_id, chunk_no, content, normalized_content, content_hash, created_at, updated_at)
                VALUES (:documentId, 1, '建議投影的來源內容', '建議投影的來源內容', :hash, :now, :now)
                """, "documentId", documentId, "hash", "chunk-" + fileName, "now", DOC_TIME);
        long firstCandidateId = insertCandidate(analysisId, documentId, 1, "整理候選一", "CONCEPT");
        long secondCandidateId = insertCandidate(analysisId, documentId, 2, "整理候選二", "FACT");
        for (long candidateId : new long[]{firstCandidateId, secondCandidateId}) {
            db().sql("""
                    INSERT INTO knowledge_candidate_evidence (knowledge_candidate_id, source_chunk_id)
                    VALUES (:candidateId, :sourceChunkId)
                    """).param("candidateId", candidateId).param("sourceChunkId", sourceChunkId).update();
        }
        // 同一候選兩筆提案：舊的 REJECTED 不得污染建議，新的 REVIEW tags 為準。
        insertProposal(workspaceId, analysisId, documentId, firstCandidateId, sourceChunkId, "REJECTED",
                "{\"tags\":[\"dead\"]}");
        insertProposal(workspaceId, analysisId, documentId, firstCandidateId, sourceChunkId, "REVIEW",
                "{\"title\":\"整理候選一\",\"tags\":[\"Live\",\" live \",\"second\"]}");
        return new Fixture(documentId, analysisId);
    }

    private long insertCandidate(long analysisId, long documentId, int candidateNo, String title, String type) {
        return insert("""
                INSERT INTO knowledge_candidate (document_analysis_id, document_id, candidate_no, title, candidate_type,
                    summary, confidence, rationale, created_at, updated_at)
                VALUES (:analysisId, :documentId, :candidateNo, :title, :candidateType, '候選摘要', 0.75, '候選理由', :now, :now)
                """, "analysisId", analysisId, "documentId", documentId, "candidateNo", candidateNo,
                "title", title, "candidateType", type, "now", DOC_TIME);
    }

    private void insertProposal(long workspaceId, long analysisId, long documentId, long candidateId,
                                long sourceChunkId, String status, String normalizedDataJson) {
        long proposalId = insert("""
                INSERT INTO knowledge_proposal (workspace_id, document_analysis_id, document_id, knowledge_candidate_id,
                    action, status, provider, model, prompt_identifier, prompt_version, contract_version,
                    normalized_data_json, created_at, updated_at)
                VALUES (:workspaceId, :analysisId, :documentId, :candidateId, 'CREATE', :status, 'provider',
                    'model', 'prompt', 'v1', 'v1', :normalized, :now, :now)
                """, "workspaceId", workspaceId, "analysisId", analysisId, "documentId", documentId,
                "candidateId", candidateId, "status", status, "normalized", normalizedDataJson, "now", DOC_TIME);
        db().sql("""
                INSERT INTO knowledge_proposal_evidence (knowledge_proposal_id, source_chunk_id)
                VALUES (:proposalId, :sourceChunkId)
                """).param("proposalId", proposalId).param("sourceChunkId", sourceChunkId).update();
    }

    private long insertWorkspace(String workspaceStatus, String fileName) {
        return insert("""
                INSERT INTO workspace (name, root_path, inbox_path, archive_path, vault_path, data_path, status, created_at, updated_at)
                VALUES (:name, :rootPath, :inboxPath, :archivePath, :vaultPath, :dataPath, :status, :now, :now)
                """, "name", fileName, "rootPath", "/tmp/" + fileName, "inboxPath", "/tmp/" + fileName + "/inbox",
                "archivePath", "/tmp/" + fileName + "/archive", "vaultPath", "/tmp/" + fileName + "/vault",
                "dataPath", "/tmp/" + fileName + "/data", "status", workspaceStatus, "now", DOC_TIME);
    }

    private long insertDocument(long workspaceId, String fileName) {
        return insert("""
                INSERT INTO document (workspace_id, file_name, original_file_name, source_path, sha256, status, created_at, updated_at)
                VALUES (:workspaceId, :fileName, :fileName, :sourcePath, :hash, 'PROCESSED', :now, :now)
                """, "workspaceId", workspaceId, "fileName", fileName, "sourcePath", "inbox/" + fileName,
                "hash", "hash-" + fileName, "now", DOC_TIME);
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

    private int count(String table) {
        return db().sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    private record Fixture(long documentId, long analysisId) {
    }
}
