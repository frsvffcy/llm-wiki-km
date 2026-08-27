package org.km.llmwiki.processing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.km.llmwiki.ai.AnalysisEvidence;
import org.km.llmwiki.ai.DocumentAnalysisRequest;
import org.km.llmwiki.ai.LlmAnalysisResult;
import org.km.llmwiki.ai.LlmClient;
import org.km.llmwiki.ai.LlmClientException;
import org.km.llmwiki.ai.LlmFailureType;
import org.km.llmwiki.ai.LlmProposalAction;
import org.km.llmwiki.ai.LlmProviderMetadata;
import org.km.llmwiki.ai.KnowledgeCandidate;
import org.km.llmwiki.ai.KnowledgeCandidateType;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.persistence.sqlite.path=target/test-data/analysis-${random.uuid}/knowledge.db"
})
@AutoConfigureMockMvc
@Import(DocumentAnalysisJobIntegrationTest.FakeLlmConfiguration.class)
class DocumentAnalysisJobIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RecordingLlmClient llmClient;

    @BeforeEach
    void resetFakeProvider() {
        llmClient.reset();
    }

    @Test
    void createsAcceptedAsyncJobAndPersistsTraceableValidatedAnalysis() throws Exception {
        createWorkspace();
        long documentId = uploadAndExtract("eligible.txt", "可作為分析依據的文件內容");

        String body = mockMvc.perform(post("/api/v1/analysis/jobs"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andReturn().getResponse().getContentAsString();

        String jobId = jobId(body);
        awaitCompleted(jobId);

        assertThat(db().sql("SELECT status FROM processing_job WHERE job_id = :jobId")
                .param("jobId", jobId).query(String.class).single()).isEqualTo("COMPLETED");
        assertThat(db().sql("SELECT success_count FROM processing_job WHERE job_id = :jobId")
                .param("jobId", jobId).query(Integer.class).single()).isEqualTo(1);
        assertThat(db().sql("SELECT status FROM processing_job_item WHERE document_id = :documentId")
                .param("documentId", documentId).query(String.class).single()).isEqualTo("SUCCEEDED");
        assertThat(db().sql("SELECT status FROM document_analysis WHERE document_id = :documentId")
                .param("documentId", documentId).query(String.class).single()).isEqualTo("SUCCEEDED");
        assertThat(db().sql("SELECT prompt_identifier FROM document_analysis WHERE document_id = :documentId")
                .param("documentId", documentId).query(String.class).single()).startsWith("document-analysis@");
        assertThat(db().sql("SELECT metadata_json FROM processing_log WHERE document_id = :documentId")
                .param("documentId", documentId).query(String.class).single()).contains("sourceChunkIds");
        assertThat(db().sql("SELECT COUNT(*) FROM knowledge_candidate WHERE document_id = :documentId")
                .param("documentId", documentId).query(Integer.class).single()).isEqualTo(1);
        assertThat(db().sql("""
                        SELECT source_chunk.content || '|' || source_chunk.normalized_content
                        FROM knowledge_candidate_evidence
                        JOIN knowledge_candidate ON knowledge_candidate.id = knowledge_candidate_evidence.knowledge_candidate_id
                        JOIN source_chunk ON source_chunk.id = knowledge_candidate_evidence.source_chunk_id
                        WHERE knowledge_candidate.document_id = :documentId
                        """).param("documentId", documentId).query(String.class).single())
                .contains("可作為分析依據的文件內容");
        assertThat(llmClient.calls()).isEqualTo(1);
    }

    @Test
    void skipsDocumentsWithoutEligibleProcessedSourceChunksWithoutCallingLlm() throws Exception {
        createWorkspace();
        long documentId = uploadAndExtract("without-chunks.txt", "之後會移除 chunk 的文件內容");
        db().sql("DELETE FROM source_chunk WHERE document_id = :documentId")
                .param("documentId", documentId).update();

        String body = mockMvc.perform(post("/api/v1/analysis/jobs"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.totalCount").value(0))
                .andReturn().getResponse().getContentAsString();
        awaitCompleted(jobId(body));

        assertThat(llmClient.calls()).isZero();
        assertThat(db().sql("SELECT status FROM processing_log WHERE step = 'ANALYZE'")
                .query(String.class).single()).isEqualTo("SKIPPED");
        assertThat(db().sql("SELECT metadata_json FROM processing_log WHERE step = 'ANALYZE'")
                .query(String.class).single()).contains("NO_ELIGIBLE_DOCUMENTS");
    }

    @Test
    void persistsZeroOrMultipleCandidatesWithoutTreatingADocumentAsAWikiPage() throws Exception {
        createWorkspace();
        long emptyDocument = uploadAndExtract("empty-candidates.txt", "證據不足的文件內容");
        long multipleDocument = uploadAndExtract("multiple-candidates.txt", "可產生多個候選的文件內容");

        String body = mockMvc.perform(post("/api/v1/analysis/jobs"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.totalCount").value(2))
                .andReturn().getResponse().getContentAsString();
        awaitCompleted(jobId(body));

        assertThat(db().sql("SELECT COUNT(*) FROM knowledge_candidate WHERE document_id = :documentId")
                .param("documentId", emptyDocument).query(Integer.class).single()).isZero();
        assertThat(db().sql("SELECT COUNT(*) FROM knowledge_candidate WHERE document_id = :documentId")
                .param("documentId", multipleDocument).query(Integer.class).single()).isEqualTo(2);
        assertThat(db().sql("SELECT COUNT(*) FROM knowledge_candidate WHERE document_id IN (:empty, :multiple)")
                .param("empty", emptyDocument).param("multiple", multipleDocument).query(Integer.class).single())
                .isEqualTo(2);
    }

    @Test
    void isolatesProviderAndValidationFailuresAndContinuesTheSameJob() throws Exception {
        createWorkspace();
        long successfulDocument = uploadAndExtract("successful.txt", "第一份可成功分析的內容");
        long failingDocument = uploadAndExtract("provider-failure.txt", "第二份會觸發 provider failure 的內容");
        long validationFailingDocument = uploadAndExtract("validation-failure.txt", "第三份會觸發 validation failure 的內容");

        String body = mockMvc.perform(post("/api/v1/analysis/jobs"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.totalCount").value(3))
                .andReturn().getResponse().getContentAsString();
        String jobId = jobId(body);
        awaitCompleted(jobId);

        assertThat(db().sql("SELECT success_count FROM processing_job WHERE job_id = :jobId")
                .param("jobId", jobId).query(Integer.class).single()).isEqualTo(1);
        assertThat(db().sql("SELECT failed_count FROM processing_job WHERE job_id = :jobId")
                .param("jobId", jobId).query(Integer.class).single()).isEqualTo(2);
        assertThat(db().sql("SELECT status FROM document_analysis WHERE document_id = :documentId")
                .param("documentId", successfulDocument).query(String.class).single()).isEqualTo("SUCCEEDED");
        assertThat(db().sql("SELECT error_code FROM document_analysis WHERE document_id = :documentId")
                .param("documentId", failingDocument).query(String.class).single()).isEqualTo("LLM_PROVIDER_UNAVAILABLE");
        assertThat(db().sql("SELECT error_code FROM document_analysis WHERE document_id = :documentId")
                .param("documentId", validationFailingDocument).query(String.class).single()).isEqualTo("LLM_VALIDATION");
        assertThat(llmClient.calls()).isEqualTo(3);
    }

    private long uploadAndExtract(String filename, String content) throws Exception {
        long documentId = upload(filename, content);
        mockMvc.perform(post("/api/v1/documents/{documentId}/extract", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parseStatus").value("PROCESSED"));
        return documentId;
    }

    private long upload(String filename, String content) throws Exception {
        String response = mockMvc.perform(multipart("/api/v1/inbox/files")
                        .file(new MockMultipartFile("file", filename, "text/plain",
                                content.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(response.replaceAll(".*\\\"documentId\\\":(\\d+).*", "$1"));
    }

    private void createWorkspace() throws Exception {
        Path root = Path.of("target/test-data/analysis-root-" + UUID.randomUUID()).toAbsolutePath();
        mockMvc.perform(post("/api/v1/workspaces")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "Analysis Test", "rootPath": "%s"}
                                """.formatted(root)))
                .andExpect(status().isCreated());
        Path prompt = root.resolve("config/prompts/document-analysis.md");
        Files.createDirectories(prompt.getParent());
        Files.writeString(prompt, """
                <!-- prompt-version: integration-test -->
                文件：{{document.metadata}}
                證據：{{evidence}}
                """);
    }

    private void awaitCompleted(String jobId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            String status = db().sql("SELECT status FROM processing_job WHERE job_id = :jobId")
                    .param("jobId", jobId).query(String.class).single();
            if ("COMPLETED".equals(status)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("文件分析 job 未在預期時間內完成");
    }

    private static String jobId(String response) {
        return response.replaceAll(".*\\\"jobId\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }

    @TestConfiguration
    static class FakeLlmConfiguration {

        @Bean
        @Primary
        RecordingLlmClient recordingLlmClient() {
            return new RecordingLlmClient();
        }
    }

    static class RecordingLlmClient implements LlmClient {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public LlmAnalysisResult analyze(DocumentAnalysisRequest request) {
            calls.incrementAndGet();
            if (request.prompt() == null || request.settings() == null) {
                throw new LlmClientException(LlmFailureType.VALIDATION, "分析 request 缺少 Prompt 或設定");
            }
            if (request.document().originalFileName().equals("provider-failure.txt")) {
                throw new LlmClientException(LlmFailureType.PROVIDER_UNAVAILABLE, "測試 provider 無法使用");
            }
            long evidenceChunkId = request.sourceChunkEvidence().getFirst().sourceChunkId();
            long candidateChunkId = request.document().originalFileName().equals("validation-failure.txt")
                    ? evidenceChunkId + 1 : evidenceChunkId;
            List<KnowledgeCandidate> candidates = candidatesFor(request.document().originalFileName(), candidateChunkId);
            return new LlmAnalysisResult(new LlmProviderMetadata("fake", "integration-test", "v1"),
                    LlmProposalAction.REVIEW, "經驗證的測試分析結果",
                    List.of(new AnalysisEvidence(evidenceChunkId, request.sourceChunkEvidence().getFirst().content())),
                    candidates);
        }

        private static List<KnowledgeCandidate> candidatesFor(String fileName, long sourceChunkId) {
            if (fileName.equals("empty-candidates.txt")) {
                return List.of();
            }
            KnowledgeCandidate first = new KnowledgeCandidate("測試知識候選", KnowledgeCandidateType.CONCEPT,
                    "候選摘要", List.of(sourceChunkId), 0.8, "測試證據足以支持候選");
            if (fileName.equals("multiple-candidates.txt")) {
                return List.of(first, new KnowledgeCandidate("第二個測試知識候選", KnowledgeCandidateType.PROCEDURE,
                        "第二個候選摘要", List.of(sourceChunkId), 0.7, "同一文件可支持多個候選"));
            }
            return List.of(first);
        }

        int calls() {
            return calls.get();
        }

        void reset() {
            calls.set(0);
        }
    }
}
