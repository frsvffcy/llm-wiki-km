package org.km.llmwiki.source;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("integration")
@SpringBootTest(properties = {
        "app.persistence.sqlite.path=target/test-data/delete-${random.uuid}/knowledge.db"
})
@AutoConfigureMockMvc
class InboxDeleteIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private DocumentRepository documentRepository;

    @Test
    void deletesPendingDocumentAndKeepsFilesystemConsistent() throws Exception {
        Path root = createWorkspace();
        long documentId = upload(root, "doomed.txt");

        mockMvc.perform(delete("/api/v1/inbox/files/{id}", documentId))
                .andExpect(status().isNoContent());

        assertThat(root.resolve("inbox").resolve("doomed.txt")).doesNotExist();
        assertThat(stagedDeleteFileCount(root)).isZero();

        String status = db().sql("SELECT status FROM document WHERE id = :id")
                .param("id", documentId)
                .query(String.class)
                .single();
        assertThat(status).isEqualTo("DELETED");

        mockMvc.perform(get("/api/v1/inbox"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }


    @Test
    void deletingCanonicalPromotesDuplicateAndPreservesGroupInvariant() throws Exception {
        Path root = createWorkspace();
        Files.writeString(root.resolve("inbox").resolve("base.txt"), "shared");
        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(status().isOk());
        Files.writeString(root.resolve("inbox").resolve("copy1.txt"), "shared");
        Files.writeString(root.resolve("inbox").resolve("copy2.txt"), "shared");
        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(status().isOk());

        Long baseId = db().sql("SELECT id FROM document WHERE file_name = 'base.txt'")
                .query(Long.class)
                .single();
        Long copy1Id = db().sql("SELECT id FROM document WHERE file_name = 'copy1.txt'")
                .query(Long.class)
                .single();
        Long copy2Id = db().sql("SELECT id FROM document WHERE file_name = 'copy2.txt'")
                .query(Long.class)
                .single();

        mockMvc.perform(delete("/api/v1/inbox/files/{id}", baseId))
                .andExpect(status().isNoContent());

        assertThat(db().sql("SELECT status FROM document WHERE id = :id")
                .param("id", baseId).query(String.class).single()).isEqualTo("DELETED");

        Long successorId = db().sql("""
                        SELECT id FROM document
                        WHERE status = 'PENDING'
                          AND duplicate_of_document_id IS NULL
                          AND file_name IN ('copy1.txt', 'copy2.txt')
                        """)
                .query(Long.class)
                .single();
        assertThat(Set.of(copy1Id, copy2Id)).contains(successorId);

        long followerId = successorId.equals(copy1Id) ? copy2Id : copy1Id;
        assertThat(db().sql("SELECT status FROM document WHERE id = :id")
                .param("id", followerId).query(String.class).single()).isEqualTo("DUPLICATE");
        assertThat(db().sql("SELECT duplicate_of_document_id FROM document WHERE id = :id")
                .param("id", followerId).query(Long.class).single()).isEqualTo(successorId);

        Integer orphans = db().sql("""
                        SELECT COUNT(*) FROM document
                        WHERE duplicate_of_document_id = :deletedId AND status = 'DUPLICATE'
                        """)
                .param("deletedId", baseId)
                .query(Integer.class)
                .single();
        assertThat(orphans).isZero();

        String reupload = mockMvc.perform(multipart("/api/v1/inbox/files")
                        .file(new MockMultipartFile("file", "reupload.txt", "text/plain",
                                "shared".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.duplicate").value(true))
                .andReturn().getResponse().getContentAsString();
        long reuploadId = Long.parseLong(reupload.replaceAll(".*\"documentId\":(\\d+).*", "$1"));

        assertThat(db().sql("SELECT duplicate_of_document_id FROM document WHERE id = :id")
                .param("id", reuploadId).query(Long.class).single()).isEqualTo(successorId);

        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(status().isOk());

        Integer pendingCanonicals = db().sql("""
                        SELECT COUNT(*) FROM document
                        WHERE sha256 = (SELECT sha256 FROM document WHERE id = :anyId)
                          AND status NOT IN ('DELETED', 'SUPERSEDED', 'ARCHIVED', 'DUPLICATE')
                        """)
                .param("anyId", copy1Id)
                .query(Integer.class)
                .single();
        assertThat(pendingCanonicals).isEqualTo(1);
    }

    @Test
    void deletingDuplicateKeepsCanonicalUntouched() throws Exception {
        Path root = createWorkspace();
        Files.writeString(root.resolve("inbox").resolve("base.txt"), "shared");
        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(status().isOk());
        Files.writeString(root.resolve("inbox").resolve("copy.txt"), "shared");
        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(status().isOk());
    }

    @Test
    void allowsDeletingDuplicateDocumentsWhileKeepingOriginal() throws Exception {
        Path root = createWorkspace();
        Files.writeString(root.resolve("inbox").resolve("base.txt"), "shared");
        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(status().isOk());
        Files.writeString(root.resolve("inbox").resolve("copy.txt"), "shared");
        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(status().isOk());

        Long duplicateId = db().sql(
                        "SELECT id FROM document WHERE file_name = 'copy.txt' AND status = 'DUPLICATE'")
                .query(Long.class)
                .single();

        mockMvc.perform(delete("/api/v1/inbox/files/{id}", duplicateId))
                .andExpect(status().isNoContent());

        assertThat(root.resolve("inbox").resolve("base.txt")).isRegularFile();
        assertThat(root.resolve("inbox").resolve("copy.txt")).doesNotExist();

        String baseStatus = db().sql("SELECT status FROM document WHERE file_name = 'base.txt'")
                .query(String.class)
                .single();
        assertThat(baseStatus).isEqualTo("PENDING");
    }

    @Test
    void rejectsProcessedDocumentsWithConflictAndKeepsFile() throws Exception {
        Path root = createWorkspace();
        long documentId = upload(root, "processed.txt");

        jdbcUpdate("UPDATE document SET status = 'PROCESSED' WHERE id = :id", documentId);

        mockMvc.perform(delete("/api/v1/inbox/files/{id}", documentId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_ALREADY_PROCESSED"));

        assertThat(root.resolve("inbox").resolve("processed.txt")).isRegularFile();

        String status = db().sql("SELECT status FROM document WHERE id = :id")
                .param("id", documentId)
                .query(String.class)
                .single();
        assertThat(status).isEqualTo("PROCESSED");
    }

    @Test
    void rejectsArchivedDocumentsWithConflict() throws Exception {
        Path root = createWorkspace();
        long documentId = upload(root, "archived.txt");

        jdbcUpdate("""
                        UPDATE document SET status = 'ARCHIVED',
                            archive_path = 'archive/2026/archived.txt'
                        WHERE id = :id
                        """, documentId);

        mockMvc.perform(delete("/api/v1/inbox/files/{id}", documentId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_ALREADY_PROCESSED"));
    }

    @Test
    void returnsNotFoundForUnknownOrForeignDocuments() throws Exception {
        long foreignId = insertForeignWorkspaceDocument();

        Path root = createWorkspace();
        upload(root, "keep.txt");

        mockMvc.perform(delete("/api/v1/inbox/files/{id}", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_NOT_FOUND"));

        mockMvc.perform(delete("/api/v1/inbox/files/{id}", foreignId))
                .andExpect(status().isNotFound());

        assertThat(root.resolve("inbox").resolve("keep.txt")).isRegularFile();
    }

    @Test
    void neverDeletesFilesOutsideWorkspaceInboxBoundary() throws Exception {
        Path root = createWorkspace();
        long documentId = upload(root, "escape.txt");

        Path outsideFile = Files.createTempFile(Path.of("target/test-data"), "outside-", ".txt");
        Files.writeString(outsideFile, "must survive");

        try {
            db().sql("UPDATE document SET source_path = :sourcePath WHERE id = :id")
                    .param("sourcePath", "inbox/../" + outsideFile.getFileName())
                    .param("id", documentId)
                    .update();

            mockMvc.perform(delete("/api/v1/inbox/files/{id}", documentId))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

            assertThat(outsideFile).content().isEqualTo("must survive");

            String status = db().sql("SELECT status FROM document WHERE id = :id")
                    .param("id", documentId)
                    .query(String.class)
                    .single();
            assertThat(status).isEqualTo("PENDING");
        } finally {
            Files.deleteIfExists(outsideFile);
        }
    }


    @Test
    void rejectsDeletionWhenInboxSymlinkDirectoryPointsOutsideWorkspace() throws Exception {
        Path root = createWorkspace();
        long documentId = upload(root, "placeholder.txt");

        Path outsideDir = Files.createTempDirectory(Path.of("target/test-data").toAbsolutePath(), "outside-dir-");
        Path outsideFile = outsideDir.resolve("victim.txt");
        Files.writeString(outsideFile, "must survive");

        try {
            Path linkDir = root.resolve("inbox").resolve("link-dir");
            Files.createSymbolicLink(linkDir, outsideDir);
            assumeTrue(Files.isSymbolicLink(linkDir));
            Files.writeString(linkDir.resolve("file.txt"), "outside content");

            db().sql("""
                    UPDATE document SET source_path = 'inbox/link-dir/file.txt',
                        file_name = 'file.txt', sha256 = 'outside-hash'
                    WHERE id = :id
                    """)
                    .param("id", documentId)
                    .update();

            mockMvc.perform(delete("/api/v1/inbox/files/{id}", documentId))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

            assertThat(outsideFile).content().isEqualTo("must survive");
            assertThat(outsideDir.resolve("file.txt")).isRegularFile();

            String status = db().sql("SELECT status FROM document WHERE id = :id")
                    .param("id", documentId)
                    .query(String.class)
                    .single();
            assertThat(status).isEqualTo("PENDING");
        } finally {
            try (var walk = Files.walk(outsideDir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        if (!Files.isSymbolicLink(p)) {
                            Files.deleteIfExists(p);
                        }
                    } catch (Exception ignored) {
                    }
                });
            }
            Files.deleteIfExists(root.resolve("inbox").resolve("link-dir"));
        }
    }

    @Test
    void rejectsDeletionWhenTargetItselfIsASymlinkFile() throws Exception {
        Path root = createWorkspace();
        long documentId = upload(root, "placeholder2.txt");

        Path outsideFile = Files.createTempFile(Path.of("target/test-data").toAbsolutePath(), "outside-file-", ".txt");
        Files.writeString(outsideFile, "survive me");

        try {
            Path linkFile = root.resolve("inbox").resolve("alias.txt");
            Files.createSymbolicLink(linkFile, outsideFile);
            assumeTrue(Files.isSymbolicLink(linkFile));

            db().sql("UPDATE document SET source_path = 'inbox/alias.txt', file_name = 'alias.txt' WHERE id = :id")
                    .param("id", documentId)
                    .update();

            mockMvc.perform(delete("/api/v1/inbox/files/{id}", documentId))
                    .andExpect(status().isBadRequest());

            assertThat(outsideFile).content().isEqualTo("survive me");
        } finally {
            Files.deleteIfExists(outsideFile);
        }
    }


    @Test
    void failedDatabaseUpdateRestoresStagedFileAndRetainsPendingStatus() throws Exception {
        Path root = createWorkspace();
        long documentId = upload(root, "consistent.txt");
        Path storedFile = root.resolve("inbox").resolve("consistent.txt");

        doThrow(new IllegalStateException("simulated database failure"))
                .when(documentRepository).markDeleted(documentId);

        mockMvc.perform(delete("/api/v1/inbox/files/{id}", documentId))
                .andExpect(status().isInternalServerError());

        assertThat(storedFile).content().isEqualTo("content of consistent.txt");

        String status = db().sql("SELECT status FROM document WHERE id = :id")
                .param("id", documentId)
                .query(String.class)
                .single();
        assertThat(status).isEqualTo("PENDING");

        assertThat(stagedDeleteFileCount(root)).isZero();

        doCallRealMethod().when(documentRepository).markDeleted(documentId);

        mockMvc.perform(delete("/api/v1/inbox/files/{id}", documentId))
                .andExpect(status().isNoContent());

        assertThat(storedFile).doesNotExist();
        assertThat(db().sql("SELECT status FROM document WHERE id = :id")
                .param("id", documentId).query(String.class).single()).isEqualTo("DELETED");
    }

    @Test
    void promotionFailureRollsBackDatabaseAndRestoresStagedCanonicalFile() throws Exception {
        Path root = createWorkspace();
        Files.writeString(root.resolve("inbox").resolve("canonical.txt"), "shared");
        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(status().isOk());
        Files.writeString(root.resolve("inbox").resolve("duplicate.txt"), "shared");
        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(status().isOk());

        long canonicalId = db().sql("SELECT id FROM document WHERE file_name = 'canonical.txt'")
                .query(Long.class).single();
        long duplicateId = db().sql("SELECT id FROM document WHERE file_name = 'duplicate.txt'")
                .query(Long.class).single();
        Path canonicalFile = root.resolve("inbox").resolve("canonical.txt");

        doThrow(new IllegalStateException("simulated duplicate repoint failure"))
                .when(documentRepository).repointDuplicates(canonicalId, duplicateId);

        mockMvc.perform(delete("/api/v1/inbox/files/{id}", canonicalId))
                .andExpect(status().isInternalServerError());

        assertThat(canonicalFile).content().isEqualTo("shared");
        assertThat(stagedDeleteFileCount(root)).isZero();
        assertThat(db().sql("SELECT status FROM document WHERE id = :id")
                .param("id", canonicalId).query(String.class).single()).isEqualTo("PENDING");
        assertThat(db().sql("SELECT status FROM document WHERE id = :id")
                .param("id", duplicateId).query(String.class).single()).isEqualTo("DUPLICATE");
        assertThat(db().sql("SELECT duplicate_of_document_id FROM document WHERE id = :id")
                .param("id", duplicateId).query(Long.class).single()).isEqualTo(canonicalId);

        doCallRealMethod().when(documentRepository).repointDuplicates(canonicalId, duplicateId);
    }

    private long stagedDeleteFileCount(Path root) throws Exception {
        Path temp = root.resolve("temp");
        if (!Files.exists(temp)) {
            return 0;
        }
        try (var files = Files.list(temp)) {
            return files.filter(p -> p.getFileName().toString().startsWith("delete-")).count();
        }
    }

    private void jdbcUpdate(String sql, long id) {
        db().sql(sql).param("id", id).update();
    }

    private long insertForeignWorkspaceDocument() throws Exception {
        Path foreignRoot = Path.of("target/test-data/delete-foreign-" + UUID.randomUUID()).toAbsolutePath();
        mockMvc.perform(post("/api/v1/workspaces")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "Foreign", "rootPath": "%s"}
                                """.formatted(foreignRoot)))
                .andExpect(status().isCreated());

        Long workspaceId = db().sql(
                        "SELECT id FROM workspace WHERE root_path = :rootPath")
                .param("rootPath", foreignRoot.toString())
                .query(Long.class)
                .single();

        var keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        db().sql("""
                        INSERT INTO document (workspace_id, file_name, source_path, sha256,
                            status, created_at, updated_at)
                        VALUES (:workspaceId, 'foreign.txt', 'inbox/foreign.txt', 'foreign-hash',
                            'PENDING', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')
                        """)
                .param("workspaceId", workspaceId)
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    private long upload(Path root, String filename) throws Exception {
        String response = mockMvc.perform(multipart("/api/v1/inbox/files")
                        .file(new MockMultipartFile("file", filename, "text/plain",
                                ("content of " + filename).getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(response.replaceAll(".*\"documentId\":(\\d+).*", "$1"));
    }

    private MockHttpServletRequestBuilder get(String url) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url);
    }

    private Path createWorkspace() throws Exception {
        Path root = Path.of("target/test-data/delete-root-" + UUID.randomUUID()).toAbsolutePath();
        mockMvc.perform(post("/api/v1/workspaces")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "Delete Test", "rootPath": "%s"}
                                """.formatted(root)))
                .andExpect(status().isCreated());
        return root;
    }
}
