package org.km.llmwiki.processing;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AnalysisReadinessIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void freshWorkspaceReportsReadyWithOfflineFallbackSettings() throws Exception {
        Path root = createWorkspace();

        mockMvc.perform(get("/api/v1/analysis/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workspaceReady").value(true))
                .andExpect(jsonPath("$.data.promptStatus").value("READY"))
                .andExpect(jsonPath("$.data.promptErrorCode").doesNotExist())
                .andExpect(jsonPath("$.data.settingsValid").value(true))
                .andExpect(jsonPath("$.data.provider").value("stub"))
                .andExpect(jsonPath("$.data.model").value("offline"))
                .andExpect(jsonPath("$.data.maximumEvidenceChunks").value(50))
                .andExpect(jsonPath("$.data.analysisReady").value(true));

        assertThatEmptySettingsFallback(root);
    }

    @Test
    void missingPromptReportsMissingWithoutMarkingSystemStatusNotReady() throws Exception {
        Path root = createWorkspace();
        Files.delete(root.resolve("config/prompts/document-analysis.md"));

        mockMvc.perform(get("/api/v1/analysis/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workspaceReady").value(true))
                .andExpect(jsonPath("$.data.promptStatus").value("MISSING"))
                .andExpect(jsonPath("$.data.promptErrorCode").value("PROMPT_TEMPLATE_NOT_FOUND"))
                .andExpect(jsonPath("$.data.promptErrorMessage", containsString("config/prompts/document-analysis.md")))
                .andExpect(jsonPath("$.data.analysisReady").value(false))
                .andExpect(content().string(not(containsString(root.toString()))));

        mockMvc.perform(get("/api/v1/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("READY"));
    }

    @Test
    void blankPromptReportsInvalidWithTypedCode() throws Exception {
        Path root = createWorkspace();
        Files.writeString(root.resolve("config/prompts/document-analysis.md"), "   \n");

        mockMvc.perform(get("/api/v1/analysis/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.promptStatus").value("INVALID"))
                .andExpect(jsonPath("$.data.promptErrorCode").value("PROMPT_TEMPLATE_INVALID"))
                .andExpect(jsonPath("$.data.analysisReady").value(false));
    }

    @Test
    void promptMissingRequiredVariableReportsTypedCode() throws Exception {
        Path root = createWorkspace();
        Files.writeString(root.resolve("config/prompts/document-analysis.md"), """
                <!-- prompt-version: test -->
                {{document.metadata}}
                """);

        mockMvc.perform(get("/api/v1/analysis/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.promptStatus").value("INVALID"))
                .andExpect(jsonPath("$.data.promptErrorCode").value("PROMPT_VARIABLE_MISSING"))
                .andExpect(jsonPath("$.data.analysisReady").value(false));
    }

    @Test
    void promptWithUnsupportedVariableReportsTypedCode() throws Exception {
        Path root = createWorkspace();
        Files.writeString(root.resolve("config/prompts/document-analysis.md"), """
                <!-- prompt-version: test -->
                {{document.metadata}}
                {{evidence}}
                {{document.apiKey}}
                """);

        mockMvc.perform(get("/api/v1/analysis/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.promptStatus").value("INVALID"))
                .andExpect(jsonPath("$.data.promptErrorCode").value("PROMPT_VARIABLE_MISSING"))
                .andExpect(jsonPath("$.data.analysisReady").value(false));
    }

    @Test
    void invalidSettingsReportSettingsInvalidWhilePromptStaysReady() throws Exception {
        createWorkspace();
        long workspaceId = db().sql("SELECT id FROM workspace WHERE status = 'ACTIVE'")
                .query(Long.class).single();
        db().sql("""
                        INSERT INTO setting (workspace_id, setting_group, setting_key, setting_value, value_type,
                            created_at, updated_at)
                        VALUES (:workspaceId, 'analysis', 'maximum_evidence_chunks', 'not-a-number', 'STRING', :now, :now)
                        """).param("workspaceId", workspaceId).param("now", Instant.now().toString()).update();

        mockMvc.perform(get("/api/v1/analysis/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.promptStatus").value("READY"))
                .andExpect(jsonPath("$.data.settingsValid").value(false))
                .andExpect(jsonPath("$.data.settingsErrorCode").value("ANALYSIS_SETTING_INVALID"))
                .andExpect(jsonPath("$.data.analysisReady").value(false));
    }

    @Test
    void readinessRequiresAnActiveWorkspace() throws Exception {
        mockMvc.perform(get("/api/v1/analysis/readiness"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NO_ACTIVE_WORKSPACE"));
    }

    private Path createWorkspace() throws Exception {
        Path root = Path.of("target/test-data/analysis-readiness-" + UUID.randomUUID()).toAbsolutePath();
        mockMvc.perform(post("/api/v1/workspaces")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "Readiness Test", "rootPath": "%s"}
                                """.formatted(root)))
                .andExpect(status().isCreated());
        return root;
    }

    private void assertThatEmptySettingsFallback(Path root) {
        Integer settings = db().sql("SELECT COUNT(*) FROM setting").query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(settings).isZero();
        org.assertj.core.api.Assertions.assertThat(root.resolve("config/prompts/document-analysis.md"))
                .isRegularFile();
    }
}
