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
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.persistence.sqlite.path=target/test-data/rescan-${random.uuid}/knowledge.db"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InboxRescanIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    private static Path root;

    @Test
    @Order(1)
    void rejectsRescanWhenNoWorkspaceRegistered() throws Exception {
        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NO_ACTIVE_WORKSPACE"));
    }

    @Test
    @Order(2)
    void scansNewFilesRecursivelyAndIsIdempotent() throws Exception {
        root = createWorkspace();
        Files.createDirectories(root.resolve("inbox").resolve("sub"));
        Files.writeString(root.resolve("inbox").resolve("sub").resolve("a.txt"), "content a");
        Files.writeString(root.resolve("inbox").resolve("b.txt"), "content b");

        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.newDocuments").value(2))
                .andExpect(jsonPath("$.data.existing").value(0))
                .andExpect(jsonPath("$.data.duplicates").value(0))
                .andExpect(jsonPath("$.data.removed").value(0));

        String sourcePath = jdbcClient.sql(
                        "SELECT source_path FROM document WHERE file_name = 'a.txt'")
                .query(String.class)
                .single();
        assertThat(sourcePath).isEqualTo("inbox/sub/a.txt");

        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.newDocuments").value(0))
                .andExpect(jsonPath("$.data.existing").value(2));
    }

    @Test
    @Order(3)
    void detectsContentDuplicatesAndRegistersDuplicateRecord() throws Exception {
        long before = documentCount();
        Files.writeString(root.resolve("inbox").resolve("copy-of-b.txt"), "content b");

        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.duplicates").value(1))
                .andExpect(jsonPath("$.data.newDocuments").value(0));

        assertThat(documentCount()).isEqualTo(before + 1);

        var duplicateRow = jdbcClient.sql("""
                        SELECT d.status, d.duplicate_of_document_id, o.file_name
                        FROM document d
                        JOIN document o ON o.id = d.duplicate_of_document_id
                        WHERE d.file_name = 'copy-of-b.txt'
                        """)
                .query((rs, rowNum) -> new Object[] {
                        rs.getString("status"),
                        rs.getLong("duplicate_of_document_id"),
                        rs.getString("file_name")
                })
                .single();
        assertThat((String) duplicateRow[0]).isEqualTo("DUPLICATE");
        assertThat((String) duplicateRow[2]).isEqualTo("b.txt");
    }

    @Test
    @Order(4)
    void marksRemovedFilesAsDeletedOnlyOnce() throws Exception {
        Files.delete(root.resolve("inbox").resolve("b.txt"));

        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.removed").value(1));

        Integer deletedRows = jdbcClient.sql(
                        "SELECT COUNT(*) FROM document WHERE status = 'DELETED'")
                .query(Integer.class)
                .single();
        assertThat(deletedRows).isEqualTo(1);

        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.removed").value(0));
    }

    @Test
    @Order(5)
    void doesNotScanOutsideWorkspaceInbox() throws Exception {
        Files.writeString(root.resolve("vault").resolve("secret.md"), "vault content");
        Files.writeString(root.resolve("temp").resolve("scratch.txt"), "temp content");
        long before = documentCount();

        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.newDocuments").value(0));

        assertThat(documentCount()).isEqualTo(before);
    }


    @Test
    @Order(6)
    void modifiedFileCreatesVersionChainWithSingleActiveRow() throws Exception {
        Path versioned = root.resolve("inbox").resolve("ver.txt");
        Files.writeString(versioned, "v1");
        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(jsonPath("$.data.newDocuments").value(1));
        long v1Id = latestDocumentId("inbox/ver.txt");

        Files.writeString(versioned, "v2");
        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(jsonPath("$.data.newDocuments").value(1));

        long v2Id = latestDocumentId("inbox/ver.txt");
        assertSupersededWithParent(v1Id, v2Id);

        Integer activeRowCount = jdbcClient.sql("""
                        SELECT COUNT(*) FROM document
                        WHERE source_path = 'inbox/ver.txt'
                          AND status NOT IN ('DELETED', 'SUPERSEDED')
                        """)
                .query(Integer.class)
                .single();
        assertThat(activeRowCount).isEqualTo(1);

        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.newDocuments").value(0))
                .andExpect(jsonPath("$.data.existing").value(3));

        Files.writeString(versioned, "v3");
        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(jsonPath("$.data.newDocuments").value(1));

        long v3Id = latestDocumentId("inbox/ver.txt");
        assertSupersededWithParent(v2Id, v3Id);
    }

    @Test
    @Order(7)
    void removedDetectionCoversDuplicateRecords() throws Exception {
        Files.writeString(root.resolve("inbox").resolve("dup-copy.txt"), "content b");
        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(jsonPath("$.data.duplicates").value(1));

        Long duplicateId = jdbcClient.sql(
                        "SELECT id FROM document WHERE file_name = 'dup-copy.txt' AND status = 'DUPLICATE'")
                .query(Long.class)
                .single();

        Files.delete(root.resolve("inbox").resolve("dup-copy.txt"));
        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.removed").value(1));

        String status = jdbcClient.sql("SELECT status FROM document WHERE id = :id")
                .param("id", duplicateId)
                .query(String.class)
                .single();
        assertThat(status).isEqualTo("DELETED");

        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(jsonPath("$.data.removed").value(0));
    }

    private long latestDocumentId(String sourcePath) {
        return jdbcClient.sql("""
                        SELECT id FROM document WHERE source_path = :sourcePath ORDER BY id DESC LIMIT 1
                        """)
                .param("sourcePath", sourcePath)
                .query(Long.class)
                .single();
    }

    private void assertSupersededWithParent(long supersededId, long currentId) {
        var row = jdbcClient.sql("""
                        SELECT status, parent_version_document_id FROM document WHERE id = :id
                        """)
                .param("id", currentId)
                .query((rs, rowNum) -> new Object[] {
                        rs.getString("status"),
                        rs.getLong("parent_version_document_id")
                })
                .single();
        assertThat((Long) row[1]).isEqualTo(supersededId);

        String oldStatus = jdbcClient.sql("SELECT status FROM document WHERE id = :id")
                .param("id", supersededId)
                .query(String.class)
                .single();
        assertThat(oldStatus).isEqualTo("SUPERSEDED");
    }

    private long documentCount() {
        return jdbcClient.sql("SELECT COUNT(*) FROM document WHERE status <> 'DELETED'")
                .query(Long.class)
                .single();
    }

    private Path createWorkspace() throws Exception {
        Path workspaceRoot = Path.of("target/test-data/rescan-root-" + UUID.randomUUID()).toAbsolutePath();
        mockMvc.perform(post("/api/v1/workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Rescan Test", "rootPath": "%s"}
                                """.formatted(workspaceRoot)))
                .andExpect(status().isCreated());
        return workspaceRoot;
    }
}
