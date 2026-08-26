package org.km.llmwiki.workspace;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.persistence.sqlite.path=target/test-data/ws-tx-${random.uuid}/knowledge.db"
})
@AutoConfigureMockMvc
class WorkspaceCreationTransactionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @MockitoSpyBean
    private WorkspaceRepository workspaceRepository;

    @Test
    void failedActivationRollsBackInsertAndRetrySucceeds() throws Exception {
        Path firstRoot = tempRoot();
        long firstId = createWorkspace(firstRoot);

        Path secondRoot = tempRoot();
        doThrow(new IllegalStateException("simulated activation failure"))
                .when(workspaceRepository)
                .activate(anyLong());

        mockMvc.perform(post("/api/v1/workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Broken", "rootPath": "%s"}
                                """.formatted(secondRoot)))
                .andExpect(status().isInternalServerError());

        Integer residualRows = jdbcClient.sql(
                        "SELECT COUNT(*) FROM workspace WHERE root_path = :rootPath")
                .param("rootPath", secondRoot.toString())
                .query(Integer.class)
                .single();
        assertThat(residualRows).isZero();

        Integer activeCount = jdbcClient.sql("SELECT COUNT(*) FROM workspace WHERE status = 'ACTIVE'")
                .query(Integer.class)
                .single();
        assertThat(activeCount).isEqualTo(1);

        String activeRoot = jdbcClient.sql("SELECT root_path FROM workspace WHERE status = 'ACTIVE'")
                .query(String.class)
                .single();
        assertThat(activeRoot).isEqualTo(firstRoot.toString());

        doCallRealMethod().when(workspaceRepository).activate(anyLong());

        mockMvc.perform(post("/api/v1/workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Retry", "rootPath": "%s"}
                                """.formatted(secondRoot)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        Integer totalActive = jdbcClient.sql("SELECT COUNT(*) FROM workspace WHERE status = 'ACTIVE'")
                .query(Integer.class)
                .single();
        assertThat(totalActive).isEqualTo(1);

        String currentActiveRoot = jdbcClient.sql("SELECT root_path FROM workspace WHERE status = 'ACTIVE'")
                .query(String.class)
                .single();
        assertThat(currentActiveRoot).isEqualTo(secondRoot.toString());

        Integer firstStillExists = jdbcClient.sql(
                        "SELECT COUNT(*) FROM workspace WHERE id = :id AND status = 'INACTIVE'")
                .param("id", firstId)
                .query(Integer.class)
                .single();
        assertThat(firstStillExists).isEqualTo(1);
    }

    private long createWorkspace(Path root) throws Exception {
        String response = mockMvc.perform(post("/api/v1/workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "First", "rootPath": "%s"}
                                """.formatted(root)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(response.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    private static Path tempRoot() {
        return Path.of("target/test-data/ws-tx-root-" + UUID.randomUUID()).toAbsolutePath();
    }
}
