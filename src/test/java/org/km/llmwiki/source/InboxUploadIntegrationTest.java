package org.km.llmwiki.source;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.persistence.sqlite.path=target/test-data/inbox-${random.uuid}/knowledge.db"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InboxUploadIntegrationTest {

    private static final String CONTENT = "Spring Boot migration notes: javax to jakarta.";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    @Order(1)
    void rejectsUploadWhenNoWorkspaceRegistered() throws Exception {
        mockMvc.perform(uploadFile("note.txt"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NO_ACTIVE_WORKSPACE"));
    }

    @Test
    @Order(2)
    void uploadsFileStoresItAndCreatesPendingDocumentWithSha256() throws Exception {
        Path root = createWorkspace();

        String response = mockMvc.perform(uploadFile("migration-notes.txt"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.documentId").isNumber())
                .andExpect(jsonPath("$.data.fileName").value("migration-notes.txt"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.duplicate").value(false))
                .andReturn().getResponse().getContentAsString();
        long documentId = Long.parseLong(response.replaceAll(".*\"documentId\":(\\d+).*", "$1"));

        Path stored = root.resolve("inbox").resolve("migration-notes.txt");
        assertThat(stored).content().isEqualTo(CONTENT);

        String expectedSha256 = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(CONTENT.getBytes(StandardCharsets.UTF_8)));
        var row = jdbcClient.sql("""
                        SELECT status, sha256, source_path, file_size, workspace_id
                        FROM document WHERE id = :id
                        """)
                .param("id", documentId)
                .query((rs, rowNum) -> new Object[] {
                        rs.getString("status"),
                        rs.getString("sha256"),
                        rs.getString("source_path"),
                        rs.getLong("file_size"),
                        rs.getLong("workspace_id")
                })
                .single();

        assertThat((String) row[0]).isEqualTo("PENDING");
        assertThat((String) row[1]).isEqualTo(expectedSha256);
        assertThat((String) row[2]).isEqualTo("inbox/migration-notes.txt");
        assertThat((Long) row[3]).isEqualTo(CONTENT.getBytes(StandardCharsets.UTF_8).length);
        assertThat((Long) row[4]).isNotNull();
    }

    @Test
    @Order(3)
    void stripsPathTraversalSegmentsFromFilename() throws Exception {
        Path root = activeRoot();

        mockMvc.perform(uploadFile("../../etc/passwd.txt"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.fileName").value("passwd.txt"));

        assertThat(root.resolve("inbox").resolve("passwd.txt")).isRegularFile();
        assertThat(root.resolve("etc")).doesNotExist();
    }

    @Test
    @Order(4)
    void rejectsInvalidFilenames() throws Exception {
        mockMvc.perform(multipart("/api/v1/inbox/files")
                        .file(new MockMultipartFile("file", "..", "text/plain",
                                CONTENT.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        mockMvc.perform(multipart("/api/v1/inbox/files")
                        .file(new MockMultipartFile("file", "", "text/plain",
                                CONTENT.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(5)
    void doesNotOverwriteExistingInboxFiles() throws Exception {
        Path root = activeRoot();

        mockMvc.perform(uploadFile("dup.txt"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.fileName").value("dup.txt"));
        Files.writeString(root.resolve("inbox").resolve("dup.txt"), "original");

        mockMvc.perform(uploadFile("dup.txt"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.fileName").value("dup-1.txt"));

        assertThat(root.resolve("inbox").resolve("dup.txt")).content().isEqualTo("original");
        assertThat(root.resolve("inbox").resolve("dup-1.txt")).isRegularFile();
    }

    private static MockHttpServletRequestBuilder uploadFile(String filename) {
        return multipart("/api/v1/inbox/files")
                .file(new MockMultipartFile("file", filename, "text/plain",
                        CONTENT.getBytes(StandardCharsets.UTF_8)));
    }

    private Path createWorkspace() throws Exception {
        Path root = tempRoot();
        mockMvc.perform(post("/api/v1/workspaces")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "Inbox Test", "rootPath": "%s"}
                                """.formatted(root)))
                .andExpect(status().isCreated());
        return root;
    }

    private Path activeRoot() {
        return Path.of(jdbcClient.sql(
                        "SELECT root_path FROM workspace WHERE status = 'ACTIVE' ORDER BY id DESC LIMIT 1")
                .query(String.class)
                .single());
    }

    private static Path tempRoot() {
        return Path.of("target/test-data/inbox-" + UUID.randomUUID()).toAbsolutePath();
    }
}
