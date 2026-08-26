package org.km.llmwiki.workspace;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.persistence.sqlite.path=target/test-data/${random.uuid}/knowledge.db"
})
@AutoConfigureMockMvc
class WorkspaceApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void createsWorkspaceAndDirectoryLayoutOnEmptyRoot() throws Exception {
        Path root = tempRoot();

        mockMvc.perform(post("/api/v1/workspaces")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "Personal Knowledge", "rootPath": "%s"}
                                """.formatted(root)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.name").value("Personal Knowledge"))
                .andExpect(jsonPath("$.data.rootPath").value(root.toString()))
                .andExpect(jsonPath("$.data.inboxPath").value(root.resolve("inbox").toString()))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        assertThat(root.resolve("inbox")).isDirectory();
        assertThat(root.resolve("archive")).isDirectory();
        assertThat(root.resolve("vault")).isDirectory();
        assertThat(root.resolve("data")).isDirectory();
        assertThat(root.resolve("config")).isDirectory();
        assertThat(root.resolve("logs")).isDirectory();
        assertThat(root.resolve("temp")).isDirectory();
        assertThat(root.resolve("data").resolve("knowledge.db")).isRegularFile();

        Integer workspaceRows = jdbcClient.sql("SELECT COUNT(*) FROM workspace WHERE root_path = :rootPath")
                .param("rootPath", root.toString())
                .query(Integer.class)
                .single();
        assertThat(workspaceRows).isEqualTo(1);
    }

    @Test
    void preservesExistingDataWhenRootAlreadyExists() throws Exception {
        Path root = tempRoot();
        Files.createDirectories(root.resolve("inbox"));
        Files.writeString(root.resolve("inbox").resolve("existing-note.txt"), "keep me");

        mockMvc.perform(post("/api/v1/workspaces")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "Reused Root", "rootPath": "%s"}
                                """.formatted(root)))
                .andExpect(status().isCreated());

        assertThat(root.resolve("inbox").resolve("existing-note.txt"))
                .content().isEqualTo("keep me");
    }

    @Test
    void rejectsDuplicateRootRegistrationWithConflict() throws Exception {
        Path root = tempRoot();

        mockMvc.perform(post("/api/v1/workspaces")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "First", "rootPath": "%s"}
                                """.formatted(root)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/workspaces")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "Second", "rootPath": "%s"}
                                """.formatted(root)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("WORKSPACE_ALREADY_EXISTS"));
    }

    @Test
    void rejectsBlankName() throws Exception {
        mockMvc.perform(post("/api/v1/workspaces")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "  ", "rootPath": "%s"}
                                """.formatted(tempRoot())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsRelativeRootPath() throws Exception {
        mockMvc.perform(post("/api/v1/workspaces")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "Local", "rootPath": "relative/path"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsFilesystemRootAsRootPath() throws Exception {
        String filesystemRoot = Path.of("").toAbsolutePath().getRoot().toString();

        mockMvc.perform(post("/api/v1/workspaces")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "Too Broad", "rootPath": "%s"}
                                """.formatted(filesystemRoot)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsExistingFileAsRootPath() throws Exception {
        Path fileRoot = tempRoot().resolve("plain-file.txt");
        Files.createDirectories(fileRoot.getParent());
        Files.writeString(fileRoot, "not a directory");

        mockMvc.perform(post("/api/v1/workspaces")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "File Root", "rootPath": "%s"}
                                """.formatted(fileRoot)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    private static Path tempRoot() {
        return Path.of("target/test-data/workspace-" + UUID.randomUUID()).toAbsolutePath();
    }
}
