package org.km.llmwiki.processing;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DocumentAnalysisPromptFailureIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void missingPromptPersistsTypedNotFoundInsteadOfTheUmbrellaCode() throws Exception {
        Path root = createWorkspace();
        long documentId = uploadAndExtract("missing-prompt.txt", "需要分析的文件內容");
        Files.delete(root.resolve("config/prompts/document-analysis.md"));

        String jobId = startAnalysisJob();

        assertThat(db().sql("SELECT status FROM processing_job WHERE job_id = :jobId")
                .param("jobId", jobId).query(String.class).single()).isEqualTo("COMPLETED");
        assertThat(db().sql("SELECT failed_count FROM processing_job WHERE job_id = :jobId")
                .param("jobId", jobId).query(Integer.class).single()).isEqualTo(1);
        assertThat(db().sql("SELECT error_code FROM document_analysis WHERE document_id = :documentId")
                .param("documentId", documentId).query(String.class).single())
                .isEqualTo("PROMPT_TEMPLATE_NOT_FOUND");
        assertThat(db().sql("SELECT error_code FROM processing_job_item WHERE document_id = :documentId")
                .param("documentId", documentId).query(String.class).single())
                .isEqualTo("PROMPT_TEMPLATE_NOT_FOUND");
        assertThat(db().sql("SELECT error_message FROM processing_job_item WHERE document_id = :documentId")
                .param("documentId", documentId).query(String.class).single())
                .contains("config/prompts/document-analysis.md");
        assertThat(db().sql("SELECT metadata_json FROM processing_log WHERE document_id = :documentId")
                .param("documentId", documentId).query(String.class).single())
                .contains("PROMPT_TEMPLATE_NOT_FOUND");

        mockMvc.perform(get("/api/v1/analysis/jobs/{jobId}", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.failedCount").value(1))
                .andExpect(jsonPath("$.data.failureCode").value("PARTIAL_FAILURE"))
                .andExpect(jsonPath("$.data.failureSummary", containsString("failed items")))
                .andExpect(content().string(not(containsString(root.toString()))));
    }

    @Test
    void invalidPromptPersistsTypedVariableMissing() throws Exception {
        Path root = createWorkspace();
        long documentId = uploadAndExtract("invalid-prompt.txt", "需要分析的文件內容");
        Files.writeString(root.resolve("config/prompts/document-analysis.md"), """
                <!-- prompt-version: test -->
                {{document.metadata}}
                """);

        String jobId = startAnalysisJob();

        assertThat(db().sql("SELECT error_code FROM document_analysis WHERE document_id = :documentId")
                .param("documentId", documentId).query(String.class).single())
                .isEqualTo("PROMPT_VARIABLE_MISSING");
        assertThat(db().sql("SELECT error_code FROM processing_job_item WHERE document_id = :documentId")
                .param("documentId", documentId).query(String.class).single())
                .isEqualTo("PROMPT_VARIABLE_MISSING");
        assertThat(jobId).isNotBlank();
    }

    @Test
    void invalidSettingsPersistTypedAnalysisSettingInvalid() throws Exception {
        createWorkspace();
        long documentId = uploadAndExtract("invalid-settings.txt", "需要分析的文件內容");
        long workspaceId = db().sql("SELECT id FROM workspace WHERE status = 'ACTIVE'")
                .query(Long.class).single();
        db().sql("""
                        INSERT INTO setting (workspace_id, setting_group, setting_key, setting_value, value_type,
                            created_at, updated_at)
                        VALUES (:workspaceId, 'analysis', 'maximum_evidence_chunks', 'not-a-number', 'STRING', :now, :now)
                        """).param("workspaceId", workspaceId).param("now", Instant.now().toString()).update();

        startAnalysisJob();

        assertThat(db().sql("SELECT error_code FROM document_analysis WHERE document_id = :documentId")
                .param("documentId", documentId).query(String.class).single())
                .isEqualTo("ANALYSIS_SETTING_INVALID");
    }

    @Test
    void emptySettingsFallbackStillRunsAnalysisWithOfflineDefaults() throws Exception {
        createWorkspace();
        assertThat(db().sql("SELECT COUNT(*) FROM setting").query(Integer.class).single()).isZero();

        long documentId = uploadAndExtract("offline-default.txt", "需要分析的文件內容");
        startAnalysisJob();

        assertThat(db().sql("SELECT status FROM document_analysis WHERE document_id = :documentId")
                .param("documentId", documentId).query(String.class).single()).isEqualTo("SUCCEEDED");
        assertThat(db().sql("SELECT provider FROM document_analysis WHERE document_id = :documentId")
                .param("documentId", documentId).query(String.class).single()).isEqualTo("stub");
        assertThat(db().sql("SELECT model FROM document_analysis WHERE document_id = :documentId")
                .param("documentId", documentId).query(String.class).single()).isEqualTo("offline");
    }

    private Path createWorkspace() throws Exception {
        Path root = Path.of("target/test-data/analysis-prompt-failure-" + UUID.randomUUID()).toAbsolutePath();
        mockMvc.perform(post("/api/v1/workspaces")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "Prompt Failure Test", "rootPath": "%s"}
                                """.formatted(root)))
                .andExpect(status().isCreated());
        return root;
    }

    private long uploadAndExtract(String filename, String content) throws Exception {
        String response = mockMvc.perform(multipart("/api/v1/inbox/files")
                        .file(new MockMultipartFile("file", filename, "text/plain",
                                content.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long documentId = Long.parseLong(response.replaceAll(".*\"documentId\":(\\d+).*", "$1"));
        mockMvc.perform(post("/api/v1/documents/{documentId}/extract", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parseStatus").value("PROCESSED"));
        return documentId;
    }

    private String startAnalysisJob() throws Exception {
        String body = mockMvc.perform(post("/api/v1/analysis/jobs"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String jobId = body.replaceAll(".*\"jobId\":\"([^\"]+)\".*", "$1");
        awaitCompleted(jobId);
        return jobId;
    }

    private void awaitCompleted(String jobId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
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
}
