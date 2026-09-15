package org.km.llmwiki.workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkspacePromptBootstrapIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createWorkspaceProvisionsVersionedDefaultPrompt() throws Exception {
        Path root = tempRoot();

        mockMvc.perform(post("/api/v1/workspaces")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "Prompt Bootstrap", "rootPath": "%s"}
                                """.formatted(root)))
                .andExpect(status().isCreated());

        Path prompt = root.resolve("config/prompts/document-analysis.md");
        assertThat(prompt).isRegularFile();
        String content = Files.readString(prompt);
        assertThat(content).contains("<!-- prompt-version: v1 -->");
        assertThat(content).contains("{{document.metadata}}");
        assertThat(content).contains("{{evidence}}");
    }

    @Test
    void createWorkspaceNeverOverwritesAnExistingUserPrompt() throws Exception {
        Path root = tempRoot();
        Path prompt = root.resolve("config/prompts/document-analysis.md");
        Files.createDirectories(prompt.getParent());
        Files.writeString(prompt, """
                <!-- prompt-version: custom -->
                {{document.metadata}}
                {{evidence}}
                使用者自訂內容
                """);

        mockMvc.perform(post("/api/v1/workspaces")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "Prompt Keep", "rootPath": "%s"}
                                """.formatted(root)))
                .andExpect(status().isCreated());

        assertThat(Files.readString(prompt)).contains("使用者自訂內容");
        assertThat(Files.readString(prompt)).doesNotContain("文件分析助手");
    }

    @Test
    void explicitRepairProvisionsAMissingPromptWithoutTouchingExistingContent() throws Exception {
        Path root = tempRoot();
        createWorkspace(root);
        Path prompt = root.resolve("config/prompts/document-analysis.md");
        assertThat(prompt).isRegularFile();

        Files.delete(prompt);
        assertThat(prompt).doesNotExist();

        mockMvc.perform(post("/api/v1/workspaces/current/repair"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.layout.valid").value(true));

        assertThat(prompt).isRegularFile();
        assertThat(Files.readString(prompt)).contains("<!-- prompt-version: v1 -->");

        String custom = """
                <!-- prompt-version: custom -->
                {{document.metadata}}
                {{evidence}}
                使用者自訂內容
                """;
        Files.writeString(prompt, custom);

        mockMvc.perform(post("/api/v1/workspaces/current/repair"))
                .andExpect(status().isOk());

        assertThat(Files.readString(prompt)).isEqualTo(custom);
    }

    private void createWorkspace(Path root) throws Exception {
        mockMvc.perform(post("/api/v1/workspaces")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "Prompt Bootstrap", "rootPath": "%s"}
                                """.formatted(root)))
                .andExpect(status().isCreated());
    }

    private static Path tempRoot() {
        return Path.of("target/test-data/prompt-bootstrap-" + UUID.randomUUID()).toAbsolutePath();
    }
}
