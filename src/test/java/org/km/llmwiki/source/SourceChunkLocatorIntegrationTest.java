package org.km.llmwiki.source;

import org.junit.jupiter.api.Test;
import org.km.llmwiki.search.SourceSearchAuthorityRepository;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Production evidence for the cited Source Chunk locator: canonical/current semantics,
 * workspace isolation with uniform safe not-found, drift handling, and read-only guarantees
 * over real SQLite.
 */
class SourceChunkLocatorIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private SourceChunkLocatorService locatorService;

    @Test
    void currentChunkProducesAuthoritativeLocatorWithBoundedPreview() throws Exception {
        long workspaceId = createWorkspace("locator-current");
        long documentId = insertDocument(workspaceId, "design.pdf", "PROCESSED");
        long chunkId = insertChunk(documentId, 3, 17, "Graph lifecycle",
                "Projection > Generation ownership",
                "authoritative chunk content with <script>alert(1)</script> text");

        SourceLocator locator = locatorService.locate(chunkId);

        assertThat(locator.currentness()).isEqualTo(ChunkCurrentness.CURRENT);
        assertThat(locator.documentId()).isEqualTo(documentId);
        assertThat(locator.documentName()).isEqualTo("design.pdf");
        assertThat(locator.chunkNo()).isEqualTo(3);
        assertThat(locator.pageNo()).isEqualTo(17);
        assertThat(locator.section()).isEqualTo("Graph lifecycle");
        assertThat(locator.headingPath()).isEqualTo("Projection > Generation ownership");
        assertThat(locator.preview()).contains("authoritative chunk content");
        assertThat(locator.previewTruncated()).isFalse();
    }

    @Test
    void ineligibleDocumentIsNotCurrentWithoutContent() throws Exception {
        long workspaceId = createWorkspace("locator-ineligible");
        long documentId = insertDocument(workspaceId, "duplicate.pdf", "DUPLICATE");
        long chunkId = insertChunk(documentId, 1, null, null, null, "stale content");

        SourceLocator locator = locatorService.locate(chunkId);

        assertThat(locator.currentness()).isEqualTo(ChunkCurrentness.NOT_CURRENT);
        assertThat(locator.notCurrentReason()).isEqualTo("INELIGIBLE");
        assertThat(locator.preview()).isNull();
    }

    @Test
    void deletedDocumentsShareTheSafeNotFoundSemantics() throws Exception {
        long workspaceId = createWorkspace("locator-deleted");
        long documentId = insertDocument(workspaceId, "deleted.pdf", "DELETED");
        long chunkId = insertChunk(documentId, 1, null, null, null, "deleted content");

        assertThatThrownBy(() -> locatorService.locate(chunkId))
                .isInstanceOf(SourceChunkNotFoundException.class);
    }

    @Test
    void unknownAndOtherWorkspaceChunksShareTheSameSafeNotFound() throws Exception {
        long activeWorkspaceId = createWorkspace("locator-active", "ACTIVE");
        long otherWorkspaceId = createWorkspace("locator-other", "INACTIVE");
        long otherDocumentId = insertDocument(otherWorkspaceId, "other.pdf", "PROCESSED");
        long otherChunkId = insertChunk(otherDocumentId, 1, null, null, null, "other content");

        assertThatThrownBy(() -> locatorService.locate(999_999))
                .isInstanceOf(SourceChunkNotFoundException.class);
        assertThatThrownBy(() -> locatorService.locate(otherChunkId))
                .isInstanceOf(SourceChunkNotFoundException.class);
        assertThat(activeWorkspaceId).isNotEqualTo(otherWorkspaceId);
    }

    @Test
    void reExtractionThatReplacesChunkRowsInvalidatesOldCitationsSafely() throws Exception {
        long workspaceId = createWorkspace("locator-rechunk");
        long documentId = insertDocument(workspaceId, "rechunk.pdf", "PROCESSED");
        long oldChunkId = insertChunk(documentId, 1, null, null, null, "old chunk content");

        db().sql("DELETE FROM source_chunk WHERE id = :id").param("id", oldChunkId).update();
        insertChunk(documentId, 1, null, null, null, "new chunk content");

        assertThatThrownBy(() -> locatorService.locate(oldChunkId))
                .isInstanceOf(SourceChunkNotFoundException.class);
    }

    @Test
    void locatingIsReadOnlyAcrossRepeatedCalls() throws Exception {
        long workspaceId = createWorkspace("locator-readonly");
        long documentId = insertDocument(workspaceId, "readonly.pdf", "PROCESSED");
        long chunkId = insertChunk(documentId, 1, null, null, null, "readonly chunk content");
        long chunksBefore = count("source_chunk");
        long documentsBefore = count("document");

        SourceLocator first = locatorService.locate(chunkId);
        SourceLocator second = locatorService.locate(chunkId);

        assertThat(first).isEqualTo(second);
        assertThat(count("source_chunk")).isEqualTo(chunksBefore);
        assertThat(count("document")).isEqualTo(documentsBefore);
    }

    private long count(String table) {
        return db().sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    private long createWorkspace(String name) throws Exception {
        return createWorkspace(name, "ACTIVE");
    }

    private long createWorkspace(String name, String status) throws Exception {
        Path root = Path.of("target/test-data/locator-" + name + "-" + UUID.randomUUID())
                .toAbsolutePath();
        Files.createDirectories(root.resolve("vault"));
        Files.createDirectories(root.resolve("inbox"));
        Files.createDirectories(root.resolve("archive"));
        Files.createDirectories(root.resolve("data"));
        Files.createDirectories(root.resolve("config"));
        KeyHolder key = new GeneratedKeyHolder();
        db().sql("""
                        INSERT INTO workspace (name, root_path, inbox_path, archive_path, vault_path,
                            data_path, config_path, status, created_at, updated_at)
                        VALUES (:name, :root, :inbox, :archive, :vault, :data, :config, :status,
                                '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z')
                        """)
                .param("name", name).param("root", root.toString())
                .param("inbox", root.resolve("inbox").toString())
                .param("archive", root.resolve("archive").toString())
                .param("vault", root.resolve("vault").toString())
                .param("data", root.resolve("data").toString())
                .param("config", root.resolve("config").toString())
                .param("status", status).update(key);
        return key.getKey().longValue();
    }

    private long insertDocument(long workspaceId, String fileName, String status)
            throws Exception {
        KeyHolder key = new GeneratedKeyHolder();
        db().sql("""
                        INSERT INTO document (workspace_id, file_name, original_file_name, source_path,
                            sha256, status, parse_status, created_at, updated_at)
                        VALUES (:workspace, :name, :name, :path, :hash, :status, 'PROCESSED',
                                '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z')
                        """)
                .param("workspace", workspaceId).param("name", fileName)
                .param("path", "archive/" + fileName)
                .param("hash", HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(fileName.getBytes(StandardCharsets.UTF_8))))
                .param("status", status).update(key);
        return key.getKey().longValue();
    }

    private long insertChunk(long documentId, int chunkNo, Integer pageNo, String section,
                             String headingPath, String content) throws Exception {
        String hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        KeyHolder key = new GeneratedKeyHolder();
        db().sql("""
                        INSERT INTO source_chunk (document_id, chunk_no, page_no, section, heading_path,
                            content, normalized_content, content_hash, created_at, updated_at)
                        VALUES (:document, :chunkNo, :pageNo, :section, :headingPath, :content,
                                :content, :hash, '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z')
                        """)
                .param("document", documentId).param("chunkNo", chunkNo).param("pageNo", pageNo)
                .param("section", section).param("headingPath", headingPath)
                .param("content", content).param("hash", hash).update(key);
        return key.getKey().longValue();
    }
}
