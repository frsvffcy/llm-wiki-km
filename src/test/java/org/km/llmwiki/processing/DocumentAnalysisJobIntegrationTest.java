package org.km.llmwiki.processing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.km.llmwiki.ai.AnalysisEvidence;
import org.km.llmwiki.ai.AnalysisFailureCode;
import org.km.llmwiki.ai.DocumentAnalysisRequest;
import org.km.llmwiki.ai.LlmAnalysisResult;
import org.km.llmwiki.ai.LlmAnalysisValidationException;
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
import java.util.concurrent.ConcurrentHashMap;
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
                .param("documentId", failingDocument).query(String.class).single()).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(db().sql("SELECT error_code FROM document_analysis WHERE document_id = :documentId")
                .param("documentId", validationFailingDocument).query(String.class).single()).isEqualTo("ILLEGAL_EVIDENCE");
        assertThat(db().sql("SELECT retry_count FROM processing_job_item WHERE document_id = :documentId")
                .param("documentId", failingDocument).query(Integer.class).single()).isEqualTo(1);
        assertThat(llmClient.calls()).isEqualTo(4);
    }

    @Test
    void classifiesInvalidOutputsAndProviderTimeoutsWithoutPersistingCandidatesOrSecrets() throws Exception {
        createWorkspace();
        long malformed = uploadAndExtract("malformed-json.txt", "不合法 JSON");
        long contract = uploadAndExtract("contract-failure.txt", "不符合契約");
        long unknownEnum = uploadAndExtract("unknown-enum.txt", "未知列舉");
        long illegalEvidence = uploadAndExtract("illegal-evidence.txt", "不合法證據");
        long insufficientEvidence = uploadAndExtract("insufficient-evidence.txt", "證據不足");
        long timeout = uploadAndExtract("provider-timeout.txt", "Provider 逾時");

        String body = mockMvc.perform(post("/api/v1/analysis/jobs"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        awaitCompleted(jobId(body));

        assertFailure(malformed, AnalysisFailureCode.MALFORMED_JSON, false);
        assertFailure(contract, AnalysisFailureCode.CONTRACT_VALIDATION_FAILED, false);
        assertFailure(unknownEnum, AnalysisFailureCode.UNKNOWN_ENUM, false);
        assertFailure(illegalEvidence, AnalysisFailureCode.ILLEGAL_EVIDENCE, false);
        assertFailure(insufficientEvidence, AnalysisFailureCode.INSUFFICIENT_EVIDENCE, false);
        assertFailure(timeout, AnalysisFailureCode.PROVIDER_TIMEOUT, true);
        assertThat(db().sql("SELECT COUNT(*) FROM knowledge_candidate").query(Integer.class).single()).isZero();
        assertThat(db().sql("SELECT COUNT(*) FROM knowledge_proposal").query(Integer.class).single()).isZero();
        assertThat(db().sql("SELECT error_message FROM processing_job_item WHERE document_id = :documentId")
                .param("documentId", timeout).query(String.class).single()).doesNotContain("supersecret");
        assertThat(db().sql("SELECT metadata_json FROM processing_log WHERE document_id = :documentId")
                .param("documentId", timeout).query(String.class).list()).allSatisfy(metadata ->
                assertThat(metadata).doesNotContain("supersecret"));
    }

    @Test
    void retriesAProviderFailureOnceAndPersistsOnlyTheSuccessfulAnalysis() throws Exception {
        createWorkspace();
        long documentId = uploadAndExtract("retry-success.txt", "第一次失敗，第二次成功");

        String body = mockMvc.perform(post("/api/v1/analysis/jobs"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        awaitCompleted(jobId(body));

        assertThat(llmClient.callsFor("retry-success.txt")).isEqualTo(2);
        assertThat(db().sql("SELECT retry_count FROM processing_job_item WHERE document_id = :documentId")
                .param("documentId", documentId).query(Integer.class).single()).isEqualTo(1);
        assertThat(db().sql("SELECT status FROM processing_job_item WHERE document_id = :documentId")
                .param("documentId", documentId).query(String.class).single()).isEqualTo("SUCCEEDED");
        assertThat(db().sql("SELECT COUNT(*) FROM document_analysis WHERE document_id = :documentId")
                .param("documentId", documentId).query(Integer.class).single()).isEqualTo(1);
        assertThat(db().sql("SELECT COUNT(*) FROM knowledge_candidate WHERE document_id = :documentId")
                .param("documentId", documentId).query(Integer.class).single()).isEqualTo(1);
        assertThat(db().sql("SELECT status FROM processing_log WHERE document_id = :documentId ORDER BY id")
                .param("documentId", documentId).query(String.class).list()).containsExactly("FAILED", "SUCCEEDED");
    }

    @Test
    void rollsBackPartialAnalysisPersistenceAndRecordsTheFailure() throws Exception {
        createWorkspace();
        long documentId = uploadAndExtract("persistence-failure.txt", "持久化失敗");
        db().sql("""
                        CREATE TRIGGER fail_analysis_candidate
                        BEFORE INSERT ON knowledge_candidate
                        WHEN NEW.document_id = %d
                        BEGIN
                            SELECT RAISE(ABORT, 'test persistence failure');
                        END
                        """.formatted(documentId)).update();
        try {
            String body = mockMvc.perform(post("/api/v1/analysis/jobs"))
                    .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
            awaitCompleted(jobId(body));

            assertFailure(documentId, AnalysisFailureCode.PERSISTENCE_FAILED, true);
            assertThat(db().sql("SELECT retry_count FROM processing_job_item WHERE document_id = :documentId")
                    .param("documentId", documentId).query(Integer.class).single()).isEqualTo(1);
            assertThat(db().sql("SELECT COUNT(*) FROM knowledge_candidate WHERE document_id = :documentId")
                    .param("documentId", documentId).query(Integer.class).single()).isZero();
            assertThat(db().sql("SELECT COUNT(*) FROM knowledge_candidate_evidence").query(Integer.class).single()).isZero();
            assertThat(db().sql("SELECT COUNT(*) FROM knowledge_proposal").query(Integer.class).single()).isZero();
        } finally {
            db().sql("DROP TRIGGER IF EXISTS fail_analysis_candidate").update();
        }
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

    private void assertFailure(long documentId, AnalysisFailureCode errorCode, boolean retryEligible) {
        assertThat(db().sql("SELECT error_code FROM document_analysis WHERE document_id = :documentId")
                .param("documentId", documentId).query(String.class).single()).isEqualTo(errorCode.name());
        assertThat(db().sql("SELECT error_code FROM processing_job_item WHERE document_id = :documentId")
                .param("documentId", documentId).query(String.class).single()).isEqualTo(errorCode.name());
        assertThat(db().sql("SELECT retry_eligible FROM processing_job_item WHERE document_id = :documentId")
                .param("documentId", documentId).query(Integer.class).single()).isEqualTo(retryEligible ? 1 : 0);
        assertThat(db().sql("SELECT metadata_json FROM processing_log WHERE document_id = :documentId ORDER BY id DESC")
                .param("documentId", documentId).query(String.class).list().getFirst()).contains(errorCode.name());
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
        private final ConcurrentHashMap<String, AtomicInteger> callsByFile = new ConcurrentHashMap<>();

        @Override
        public LlmAnalysisResult analyze(DocumentAnalysisRequest request) {
            calls.incrementAndGet();
            String fileName = request.document().originalFileName();
            int callsForFile = callsByFile.computeIfAbsent(fileName, ignored -> new AtomicInteger()).incrementAndGet();
            if (request.prompt() == null || request.settings() == null) {
                throw new LlmClientException(LlmFailureType.VALIDATION, "分析 request 缺少 Prompt 或設定");
            }
            if (fileName.equals("provider-failure.txt")) {
                throw new LlmClientException(LlmFailureType.PROVIDER_UNAVAILABLE, "apiKey=supersecret");
            }
            if (fileName.equals("provider-timeout.txt")) {
                throw new LlmClientException(LlmFailureType.PROVIDER_TIMEOUT, "token=supersecret");
            }
            if (fileName.equals("retry-success.txt") && callsForFile == 1) {
                throw new LlmClientException(LlmFailureType.PROVIDER_UNAVAILABLE, "authorization=supersecret");
            }
            if (fileName.equals("malformed-json.txt")) {
                throw new LlmAnalysisValidationException(LlmFailureType.MALFORMED_JSON, "不合法 JSON");
            }
            if (fileName.equals("contract-failure.txt")) {
                throw new LlmAnalysisValidationException("契約欄位缺漏");
            }
            if (fileName.equals("unknown-enum.txt")) {
                throw new LlmAnalysisValidationException(LlmFailureType.UNKNOWN_ENUM, "未知 action");
            }
            if (fileName.equals("insufficient-evidence.txt")) {
                throw new LlmAnalysisValidationException(AnalysisFailureCode.INSUFFICIENT_EVIDENCE, "證據不足");
            }
            long evidenceChunkId = request.sourceChunkEvidence().getFirst().sourceChunkId();
            long candidateChunkId = fileName.equals("validation-failure.txt") || fileName.equals("illegal-evidence.txt")
                    ? evidenceChunkId + 1 : evidenceChunkId;
            List<KnowledgeCandidate> candidates = candidatesFor(fileName, candidateChunkId);
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
            callsByFile.clear();
        }

        int callsFor(String fileName) {
            AtomicInteger fileCalls = callsByFile.get(fileName);
            return fileCalls == null ? 0 : fileCalls.get();
        }
    }
}
