package org.km.llmwiki.source;

import org.junit.jupiter.api.Test;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
class InboxRescanIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsRescanWhenNoWorkspaceRegistered() throws Exception {
        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NO_ACTIVE_WORKSPACE"));
    }

    @Test
    void scansNewFilesRecursivelyAndIsIdempotent() throws Exception {
        Path root = createWorkspace();
        Files.createDirectories(root.resolve("inbox").resolve("sub"));
        Files.writeString(root.resolve("inbox").resolve("sub").resolve("a.txt"), "content a");
        Files.writeString(root.resolve("inbox").resolve("b.txt"), "content b");

        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.newDocuments").value(2))
                .andExpect(jsonPath("$.data.existing").value(0))
                .andExpect(jsonPath("$.data.duplicates").value(0))
                .andExpect(jsonPath("$.data.removed").value(0));

        String sourcePath = db().sql("SELECT source_path FROM document WHERE file_name = 'a.txt'")
                .query(String.class)
                .single();
        assertThat(sourcePath).isEqualTo("inbox/sub/a.txt");

        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.newDocuments").value(0))
                .andExpect(jsonPath("$.data.existing").value(2));
    }

    @Test
    void detectsContentDuplicatesAndRegistersDuplicateRecord() throws Exception {
        Path root = createWorkspace();
        Files.writeString(root.resolve("inbox").resolve("b.txt"), "content b");
        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(jsonPath("$.data.newDocuments").value(1));

        long before = activeDocumentCount();
        Files.writeString(root.resolve("inbox").resolve("copy-of-b.txt"), "content b");

        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.duplicates").value(1))
                .andExpect(jsonPath("$.data.newDocuments").value(0));

        assertThat(activeDocumentCount()).isEqualTo(before + 1);

        var duplicateRow = db().sql("""
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
    void marksRemovedFilesAsDeletedOnlyOnce() throws Exception {
        Path root = createWorkspace();
        Files.writeString(root.resolve("inbox").resolve("b.txt"), "content b");
        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(jsonPath("$.data.newDocuments").value(1));

        Files.delete(root.resolve("inbox").resolve("b.txt"));

        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.removed").value(1));

        Integer deletedRows = db().sql("SELECT COUNT(*) FROM document WHERE status = 'DELETED'")
                .query(Integer.class)
                .single();
        assertThat(deletedRows).isEqualTo(1);

        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.removed").value(0));
    }

    @Test
    void doesNotScanOutsideWorkspaceInbox() throws Exception {
        Path root = createWorkspace();
        Files.writeString(root.resolve("vault").resolve("secret.md"), "vault content");
        Files.writeString(root.resolve("temp").resolve("scratch.txt"), "temp content");

        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.newDocuments").value(0));

        assertThat(activeDocumentCount()).isZero();
    }

    @Test
    void modifiedFileCreatesVersionChainWithSingleActiveRow() throws Exception {
        Path root = createWorkspace();
        Path versioned = root.resolve("inbox").resolve("ver.txt");

        Files.writeString(versioned, "v1");
        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(jsonPath("$.data.newDocuments").value(1));
        long v1Id = latestDocumentId();

        Files.writeString(versioned, "v2");
        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(jsonPath("$.data.newDocuments").value(1));

        long v2Id = latestDocumentId();
        assertSupersededWithParent(v1Id, v2Id);
        assertThat(activeRowCountFor("inbox/ver.txt")).isEqualTo(1);

        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.newDocuments").value(0))
                .andExpect(jsonPath("$.data.existing").value(1));

        Files.writeString(versioned, "v3");
        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(jsonPath("$.data.newDocuments").value(1));

        long v3Id = latestDocumentId();
        assertSupersededWithParent(v2Id, v3Id);
        assertThat(activeRowCountFor("inbox/ver.txt")).isEqualTo(1);
    }

    @Test
    void removedDetectionCoversDuplicateRecords() throws Exception {
        Path root = createWorkspace();
        Files.writeString(root.resolve("inbox").resolve("base.txt"), "shared content");
        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(jsonPath("$.data.newDocuments").value(1));

        Files.writeString(root.resolve("inbox").resolve("dup-copy.txt"), "shared content");
        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.duplicates").value(1));

        Long duplicateId = db().sql(
                        "SELECT id FROM document WHERE file_name = 'dup-copy.txt' AND status = 'DUPLICATE'")
                .query(Long.class)
                .single();

        Files.delete(root.resolve("inbox").resolve("dup-copy.txt"));
        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.removed").value(1));

        String status = db().sql("SELECT status FROM document WHERE id = :id")
                .param("id", duplicateId)
                .query(String.class)
                .single();
        assertThat(status).isEqualTo("DELETED");

        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.removed").value(0));
    }

    private long activeDocumentCount() {
        return db().sql("SELECT COUNT(*) FROM document WHERE status <> 'DELETED'")
                .query(Long.class)
                .single();
    }

    private long latestDocumentId() {
        return db().sql("""
                        SELECT id FROM document WHERE source_path = 'inbox/ver.txt' ORDER BY id DESC LIMIT 1
                        """)
                .query(Long.class)
                .single();
    }

    private int activeRowCountFor(String sourcePath) {
        return db().sql("""
                        SELECT COUNT(*) FROM document
                        WHERE source_path = :sourcePath
                          AND status NOT IN ('DELETED', 'SUPERSEDED')
                        """)
                .param("sourcePath", sourcePath)
                .query(Integer.class)
                .single();
    }

    private void assertSupersededWithParent(long supersededId, long currentId) {
        var row = db().sql("""
                        SELECT status, parent_version_document_id FROM document WHERE id = :id
                        """)
                .param("id", currentId)
                .query((rs, rowNum) -> new Object[] {
                        rs.getString("status"),
                        rs.getLong("parent_version_document_id")
                })
                .single();
        assertThat((Long) row[1]).isEqualTo(supersededId);

        String oldStatus = db().sql("SELECT status FROM document WHERE id = :id")
                .param("id", supersededId)
                .query(String.class)
                .single();
        assertThat(oldStatus).isEqualTo("SUPERSEDED");
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
