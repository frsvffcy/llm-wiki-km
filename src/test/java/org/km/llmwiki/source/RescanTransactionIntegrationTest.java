package org.km.llmwiki.source;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.persistence.sqlite.path=target/test-data/tx-${random.uuid}/knowledge.db"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RescanTransactionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InboxScanService inboxScanService;

    @Autowired
    private JdbcClient jdbcClient;

    @MockitoSpyBean
    private DocumentRepository documentRepository;

    private Path root;

    @Test
    @Order(1)
    void failedVersionTransitionRollsBackSupersedeAndRetriesSuccessfully() throws Exception {
        root = createWorkspace();
        Path versioned = root.resolve("inbox").resolve("ver.txt");
        Files.writeString(versioned, "v1");

        inboxScanService.rescan();
        long v1Id = latestDocumentId();

        Files.writeString(versioned, "v2");

        doThrow(new IllegalStateException("simulated registration failure"))
                .when(documentRepository)
                .insert(anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(inboxScanService::rescan)
                .isInstanceOf(RuntimeException.class);

        assertSingleCurrentRow(v1Id, "PENDING", null);

        doCallRealMethod()
                .when(documentRepository)
                .insert(anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        inboxScanService.rescan();

        long v2Id = latestDocumentId();
        assertThat(v2Id).isNotEqualTo(v1Id);
        assertSingleCurrentRow(v2Id, "PENDING", v1Id);

        String oldStatus = jdbcClient.sql("SELECT status FROM document WHERE id = :id")
                .param("id", v1Id)
                .query(String.class)
                .single();
        assertThat(oldStatus).isEqualTo("SUPERSEDED");

        inboxScanService.rescan();
        Integer activeRowCount = jdbcClient.sql("""
                        SELECT COUNT(*) FROM document
                        WHERE source_path = 'inbox/ver.txt'
                          AND status NOT IN ('DELETED', 'SUPERSEDED')
                        """)
                .query(Integer.class)
                .single();
        assertThat(activeRowCount).isEqualTo(1);
    }

    private void assertSingleCurrentRow(long expectedId, String expectedStatus, Long expectedParent) {
        var rows = jdbcClient.sql("""
                        SELECT id, status, parent_version_document_id FROM document
                        WHERE source_path = 'inbox/ver.txt'
                          AND status NOT IN ('DELETED', 'SUPERSEDED')
                        """)
                .query((rs, rowNum) -> new Object[] {
                        rs.getLong("id"),
                        rs.getString("status"),
                        rs.getObject("parent_version_document_id") == null
                                ? null : rs.getLong("parent_version_document_id")
                })
                .list();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[0]).isEqualTo(expectedId);
        assertThat(rows.get(0)[1]).isEqualTo(expectedStatus);
        if (expectedParent == null) {
            assertThat(rows.get(0)[2]).isNull();
        } else {
            assertThat(rows.get(0)[2]).isEqualTo(expectedParent);
        }
    }

    private long latestDocumentId() {
        return jdbcClient.sql("""
                        SELECT id FROM document WHERE source_path = 'inbox/ver.txt' ORDER BY id DESC LIMIT 1
                        """)
                .query(Long.class)
                .single();
    }

    private Path createWorkspace() throws Exception {
        Path workspaceRoot = Path.of("target/test-data/tx-root-" + UUID.randomUUID()).toAbsolutePath();
        mockMvc.perform(post("/api/v1/workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Tx Test", "rootPath": "%s"}
                                """.formatted(workspaceRoot)))
                .andExpect(status().isCreated());
        return workspaceRoot;
    }
}
