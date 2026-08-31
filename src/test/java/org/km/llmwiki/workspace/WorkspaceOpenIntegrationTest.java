package org.km.llmwiki.workspace;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("integration")
@SpringBootTest(properties = {
        "app.persistence.sqlite.path=target/test-data/${random.uuid}/knowledge.db"
})
@AutoConfigureMockMvc
class WorkspaceOpenIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WorkspaceStartupLoader startupLoader;
    @Test
    void opensExistingWorkspaceAndReportsValidLayout() throws Exception {
        Path root = tempRoot();
        long workspaceId = createWorkspace(root);

        mockMvc.perform(put("/api/v1/workspaces/current")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"workspaceId": %d}
                                """.formatted(workspaceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workspace.id").value((int) workspaceId))
                .andExpect(jsonPath("$.data.workspace.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.layout.valid").value(true));

        String lastOpenedAt = db().sql(
                        "SELECT last_opened_at FROM workspace WHERE id = :id")
                .param("id", workspaceId)
                .query(String.class)
                .single();
        assertThat(lastOpenedAt).isNotBlank();
    }
    @Test
    void repairsMissingRebuildableDirectoriesWithoutTouchingExistingData() throws Exception {
        Path root = tempRoot();
        long workspaceId = createWorkspace(root);
        Files.writeString(root.resolve("vault").resolve("keep.md"), "# do not delete");
        deleteRecursively(root.resolve("logs"));
        deleteRecursively(root.resolve("temp"));

        mockMvc.perform(get("/api/v1/workspaces/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.layout.valid").value(true))
                .andExpect(jsonPath("$.data.layout.repairedDirectories").isArray());

        assertThat(root.resolve("logs")).isDirectory();
        assertThat(root.resolve("temp")).isDirectory();
        assertThat(root.resolve("vault").resolve("keep.md"))
                .content().isEqualTo("# do not delete");
    }
    @Test
    void returnsDegradedWhenRootDirectoryDisappears() throws Exception {
        Path root = tempRoot();
        createWorkspace(root);
        deleteRecursively(root);

        mockMvc.perform(get("/api/v1/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DEGRADED"))
                .andExpect(jsonPath("$.data.database").value("READY"));
    }
    @Test
    void reportsNotInitializedWhenNoWorkspaceRegistered() throws Exception {
        mockMvc.perform(get("/api/v1/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NOT_INITIALIZED"))
                .andExpect(jsonPath("$.data.database").value("READY"));

        mockMvc.perform(get("/api/v1/workspaces/current"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NO_ACTIVE_WORKSPACE"));
    }
    @Test
    void listsWorkspacesAndGetById() throws Exception {
        long id = createWorkspace(tempRoot());

        mockMvc.perform(get("/api/v1/workspaces"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].id").value((int) id));

        mockMvc.perform(get("/api/v1/workspaces/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rootPath").isNotEmpty());
    }
    @Test
    void rejectsOpeningUnknownWorkspace() throws Exception {
        mockMvc.perform(put("/api/v1/workspaces/current")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"workspaceId": 99999}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("WORKSPACE_NOT_FOUND"));
    }
    @Test
    void startupLoaderLoadsExistingActiveWorkspaceWithoutError() throws Exception {
        Path root = tempRoot();
        long workspaceId = createWorkspace(root);

        db().sql("UPDATE workspace SET status = 'INACTIVE'").update();
        db().sql("""
                        UPDATE workspace SET status = 'ACTIVE', last_opened_at = NULL WHERE id = :id
                        """)
                .param("id", workspaceId)
                .update();

        startupLoader.run(null);

        Integer activeRows = db().sql("SELECT COUNT(*) FROM workspace WHERE status = 'ACTIVE'")
                .query(Integer.class)
                .single();
        assertThat(activeRows).isEqualTo(1);
    }


    @Test
    void startupRepairRestoresSingleActiveInvariantWhenCorrupted() throws Exception {
        Path firstRoot = tempRoot();
        createWorkspace(firstRoot);
        Path secondRoot = tempRoot();
        createWorkspace(secondRoot);

        db().sql("UPDATE workspace SET status = 'ACTIVE'").update();

        startupLoader.run(null);

        Integer activeCount = db().sql("SELECT COUNT(*) FROM workspace WHERE status = 'ACTIVE'")
                .query(Integer.class)
                .single();
        assertThat(activeCount).isEqualTo(1);

        String activeRootPath = db().sql(
                        "SELECT root_path FROM workspace WHERE status = 'ACTIVE'")
                .query(String.class)
                .single();
        assertThat(activeRootPath).isEqualTo(secondRoot.toString());
    }

    private long createWorkspace(Path root) throws Exception {
        String response = mockMvc.perform(post("/api/v1/workspaces")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "Open Test", "rootPath": "%s"}
                                """.formatted(root)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return Long.parseLong(response.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        try (var entries = Files.walk(path)) {
            entries.sorted(java.util.Comparator.reverseOrder()).forEach(entry -> {
                try {
                    Files.delete(entry);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
        }
    }

    private static Path tempRoot() {
        return Path.of("target/test-data/open-" + UUID.randomUUID()).toAbsolutePath();
    }
}
