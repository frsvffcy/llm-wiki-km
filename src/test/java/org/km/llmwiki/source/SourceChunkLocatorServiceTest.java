package org.km.llmwiki.source;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.rag.AuthorityRejectionReason;
import org.km.llmwiki.search.SourceSearchAuthorityChunk;
import org.km.llmwiki.search.SourceSearchAuthorityDocument;
import org.km.llmwiki.search.SourceSearchAuthorityRepository;
import org.km.llmwiki.search.SourceSearchEligibilityPolicy;
import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceResponse;
import org.km.llmwiki.workspace.WorkspaceService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("unit")
class SourceChunkLocatorServiceTest {

    private static final long WORKSPACE_ID = 7L;

    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final SourceChunkRepository sourceChunkRepository = mock(SourceChunkRepository.class);
    private final SourceSearchAuthorityRepository sourceAuthorityRepository =
            mock(SourceSearchAuthorityRepository.class);
    private final SourceChunkLocatorService service = new SourceChunkLocatorService(
            workspaceService, sourceChunkRepository, sourceAuthorityRepository);

    @Test
    void currentLocatorCarriesAuthoritativeMetadataAndBoundedPreview() {
        activeWorkspace();
        chunkRow(42L, 900L);
        String content = "authoritative chunk content <script>alert(1)</script>";
        authorityDocument(authority(42L, content));

        SourceLocator locator = service.locate(42L);

        assertThat(locator.currentness()).isEqualTo(ChunkCurrentness.CURRENT);
        assertThat(locator.notCurrentReason()).isNull();
        assertThat(locator.documentId()).isEqualTo(900L);
        assertThat(locator.documentName()).isEqualTo("design.pdf");
        assertThat(locator.chunkNo()).isEqualTo(3);
        assertThat(locator.pageNo()).isEqualTo(17);
        assertThat(locator.section()).isEqualTo("Graph lifecycle");
        assertThat(locator.headingPath()).isEqualTo("Projection > Generation ownership");
        assertThat(locator.preview()).isEqualTo(content);
        assertThat(locator.previewTruncated()).isFalse();
    }

    @Test
    void previewIsBoundedByCodePointsAndFlagsTruncation() {
        activeWorkspace();
        chunkRow(42L, 900L);
        String content = "字".repeat(SourceChunkLocatorService.MAX_PREVIEW_CODE_POINTS + 500);
        authorityDocument(authority(42L, content));

        SourceLocator locator = service.locate(42L);

        assertThat(locator.preview())
                .hasSize(SourceChunkLocatorService.MAX_PREVIEW_CODE_POINTS);
        assertThat(locator.previewTruncated()).isTrue();
    }

    @Test
    void missingAuthorityDocumentIsNotCurrentWithoutContentOrRawDetails() {
        activeWorkspace();
        chunkRow(42L, 900L);
        when(sourceAuthorityRepository.findDocument(WORKSPACE_ID, 900L))
                .thenReturn(Optional.empty());

        SourceLocator locator = service.locate(42L);

        assertThat(locator.currentness()).isEqualTo(ChunkCurrentness.NOT_CURRENT);
        assertThat(locator.notCurrentReason())
                .isEqualTo(AuthorityRejectionReason.AUTHORITY_MISSING.name());
        assertThat(locator.preview()).isNull();
        assertThat(locator.documentName()).isNull();
        assertThat(locator.documentId()).isEqualTo(900L);
        assertThat(locator.sourceChunkId()).isEqualTo(42L);
    }

    @Test
    void ineligibleDocumentIsNotCurrent() {
        activeWorkspace();
        chunkRow(42L, 900L);
        authorityDocument(new SourceSearchAuthorityDocument(WORKSPACE_ID, 900L, "design.pdf",
                "a".repeat(64), "DUPLICATE", "PROCESSED",
                List.of(chunk(42L, "content"))));

        SourceLocator locator = service.locate(42L);

        assertThat(locator.currentness()).isEqualTo(ChunkCurrentness.NOT_CURRENT);
        assertThat(locator.notCurrentReason()).isEqualTo(AuthorityRejectionReason.INELIGIBLE.name());
        assertThat(locator.preview()).isNull();
    }

    @Test
    void chunkMissingFromAuthoritySnapshotIsNotCurrent() {
        activeWorkspace();
        chunkRow(42L, 900L);
        authorityDocument(new SourceSearchAuthorityDocument(WORKSPACE_ID, 900L, "design.pdf",
                "a".repeat(64), "PROCESSED", "PROCESSED", List.of()));

        SourceLocator locator = service.locate(42L);

        assertThat(locator.currentness()).isEqualTo(ChunkCurrentness.NOT_CURRENT);
        assertThat(locator.notCurrentReason())
                .isEqualTo(AuthorityRejectionReason.AUTHORITY_MISSING.name());
        assertThat(locator.preview()).isNull();
    }

    @Test
    void ineligibleChunkInSnapshotIsNotCurrent() {
        activeWorkspace();
        chunkRow(42L, 900L);
        SourceSearchAuthorityChunk corrupted = chunkWithHash(42L, "drifted content",
                "0".repeat(64));
        authorityDocument(new SourceSearchAuthorityDocument(WORKSPACE_ID, 900L, "design.pdf",
                "a".repeat(64), "PROCESSED", "PROCESSED", List.of(corrupted)));

        SourceLocator locator = service.locate(42L);

        assertThat(locator.currentness()).isEqualTo(ChunkCurrentness.NOT_CURRENT);
        assertThat(locator.notCurrentReason()).isEqualTo(AuthorityRejectionReason.INELIGIBLE.name());
        assertThat(locator.preview()).isNull();
    }

    @Test
    void unknownAndOtherWorkspaceChunksShareTheSameSafeNotFound() {
        activeWorkspace();
        when(sourceChunkRepository.findByIdAndWorkspaceId(anyLong(), anyLong()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.locate(42L))
                .isInstanceOf(SourceChunkNotFoundException.class);
    }

    @Test
    void missingActiveWorkspaceIsTyped() {
        when(workspaceService.findActiveWithoutValidation()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.locate(42L))
                .isInstanceOf(NoActiveWorkspaceException.class);
    }

    private void activeWorkspace() {
        when(workspaceService.findActiveWithoutValidation())
                .thenReturn(Optional.of(new WorkspaceResponse(WORKSPACE_ID, "ws", "/root",
                        "/root/inbox", "/root/archive", "/root/vault", "/root/data", "/root/config",
                        "ACTIVE", "2026-09-01T00:00:00Z", "2026-09-01T00:00:00Z")));
    }

    private void chunkRow(long chunkId, long documentId) {
        when(sourceChunkRepository.findByIdAndWorkspaceId(chunkId, WORKSPACE_ID))
                .thenReturn(Optional.of(new SourceChunk(chunkId, documentId, 3, 17,
                        "Graph lifecycle", "Projection > Generation ownership", "raw",
                        "normalized", "0".repeat(64), "chunk-policy-v1-current")));
    }

    private void authorityDocument(SourceSearchAuthorityDocument document) {
        when(sourceAuthorityRepository.findDocument(WORKSPACE_ID, 900L))
                .thenReturn(Optional.of(document));
    }

    private static SourceSearchAuthorityDocument authority(long chunkId, String content) {
        return new SourceSearchAuthorityDocument(WORKSPACE_ID, 900L, "design.pdf",
                "a".repeat(64), "PROCESSED", "PROCESSED", List.of(chunk(chunkId, content)));
    }

    private static SourceSearchAuthorityChunk chunk(long chunkId, String content) {
        return chunkWithHash(chunkId, content, sha256(content));
    }

    private static SourceSearchAuthorityChunk chunkWithHash(long chunkId, String content,
                                                            String contentHash) {
        return new SourceSearchAuthorityChunk(chunkId, 3, 17, "Graph lifecycle",
                "Projection > Generation ownership", content, contentHash);
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
