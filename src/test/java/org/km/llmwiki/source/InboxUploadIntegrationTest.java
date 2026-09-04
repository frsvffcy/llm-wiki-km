package org.km.llmwiki.source;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
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

class InboxUploadIntegrationTest extends IsolatedIntegrationTest {

    private static final String CONTENT = "Spring Boot migration notes: javax to jakarta.";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsUploadWhenNoWorkspaceRegistered() throws Exception {
        mockMvc.perform(uploadFile("note.txt"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NO_ACTIVE_WORKSPACE"));
    }

    @Test
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
        var row = db().sql("""
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
    void stripsPathTraversalSegmentsFromFilename() throws Exception {
        Path root = createWorkspace();

        mockMvc.perform(uploadFile("../../etc/passwd.txt"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.fileName").value("passwd.txt"));

        assertThat(root.resolve("inbox").resolve("passwd.txt")).isRegularFile();
        assertThat(root.resolve("etc")).doesNotExist();
    }

    @Test
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
    void doesNotOverwriteExistingInboxFiles() throws Exception {
        Path root = createWorkspace();

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

    @Test
    void marksSameContentUnderDifferentFilenameAsDuplicate() throws Exception {
        createWorkspace();

        String originalResponse = mockMvc.perform(uploadFile("original-name.txt"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        long originalId = Long.parseLong(originalResponse.replaceAll(".*\"documentId\":(\\d+).*", "$1"));

        String duplicateResponse = mockMvc.perform(uploadFile("different-name.txt"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.duplicate").value(true))
                .andExpect(jsonPath("$.data.status").value("DUPLICATE"))
                .andReturn().getResponse().getContentAsString();
        long duplicateId = Long.parseLong(duplicateResponse.replaceAll(".*\"documentId\":(\\d+).*", "$1"));

        var row = db().sql("""
                        SELECT status, duplicate_of_document_id FROM document WHERE id = :id
                        """)
                .param("id", duplicateId)
                .query((rs, rowNum) -> new Object[] {
                        rs.getString("status"),
                        rs.getLong("duplicate_of_document_id")
                })
                .single();
        assertThat((String) row[0]).isEqualTo("DUPLICATE");
        assertThat((Long) row[1]).isEqualTo(originalId);
        assertThat(duplicateId).isNotEqualTo(originalId);
    }


    @Test
    void deletedDocumentContentCanBeRegisteredAsFreshDocument() throws Exception {
        createWorkspace();

        String first = mockMvc.perform(uploadFile("doomed.txt"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long doomedId = Long.parseLong(first.replaceAll(".*\"documentId\":(\\d+).*", "$1"));

        db().sql("UPDATE document SET status = 'DELETED' WHERE id = :id")
                .param("id", doomedId)
                .update();

        mockMvc.perform(uploadFile("reborn.txt"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.duplicate").value(false))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    void supersededVersionContentIsNotTreatedAsDuplicate() throws Exception {
        createWorkspace();
        Path root = activeRoot();

        Files.writeString(root.resolve("inbox").resolve("ver.txt"), "old-version");
        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(status().isOk());

        Files.writeString(root.resolve("inbox").resolve("ver.txt"), "new-version");
        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(status().isOk());

        Integer supersededCount = db().sql(
                        "SELECT COUNT(*) FROM document WHERE file_name = 'ver.txt' AND status = 'SUPERSEDED'")
                .query(Integer.class)
                .single();
        assertThat(supersededCount).isEqualTo(1);

        String reuploadResponse = uploadViaService("legacy-restore.txt", "old-version");
        assertThat(reuploadResponse).contains("\"status\":\"PENDING\"");
        assertThat(reuploadResponse).contains("\"duplicate\":false");
    }

    private String uploadViaService(String fileName, String content) throws Exception {
        return mockMvc.perform(multipart("/api/v1/inbox/files")
                        .file(new MockMultipartFile("file", fileName, "text/plain",
                                content.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
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
        return Path.of(db().sql(
                        "SELECT root_path FROM workspace WHERE status = 'ACTIVE' ORDER BY id DESC LIMIT 1")
                .query(String.class)
                .single());
    }

    private static Path tempRoot() {
        return Path.of("target/test-data/inbox-" + UUID.randomUUID()).toAbsolutePath();
    }
}
