package org.km.llmwiki.search.embedding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.ai.embedding.EmbeddingClient;
import org.km.llmwiki.ai.embedding.EmbeddingClientException;
import org.km.llmwiki.ai.embedding.EmbeddingFailureType;
import org.km.llmwiki.ai.embedding.EmbeddingProviderMetadata;
import org.km.llmwiki.ai.embedding.EmbeddingRequest;
import org.km.llmwiki.ai.embedding.EmbeddingResult;
import org.km.llmwiki.ai.embedding.EmbeddingVector;
import org.km.llmwiki.search.SourceSearchAuthorityChunk;
import org.km.llmwiki.search.SourceSearchAuthorityDocument;
import org.km.llmwiki.search.SourceSearchAuthorityRepository;
import org.km.llmwiki.wiki.PageStatus;
import org.km.llmwiki.wiki.PublishedWikiContentReader;
import org.km.llmwiki.wiki.PublishedWikiRepository;
import org.km.llmwiki.wiki.StoredPublishedWiki;
import org.km.llmwiki.wiki.WikiPageType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
class EmbeddingProjectionServiceTest {

    private static final long WORKSPACE_ID = 7L;

    private PublishedWikiRepository wikiRepository;
    private PublishedWikiContentReader wikiContentReader;
    private SourceSearchAuthorityRepository sourceRepository;
    private EmbeddingClient embeddingClient;
    private EmbeddingProjectionRepository projectionRepository;
    private EmbeddingProjectionService service;

    @BeforeEach
    void setUp() {
        wikiRepository = mock(PublishedWikiRepository.class);
        wikiContentReader = mock(PublishedWikiContentReader.class);
        sourceRepository = mock(SourceSearchAuthorityRepository.class);
        embeddingClient = mock(EmbeddingClient.class);
        projectionRepository = mock(EmbeddingProjectionRepository.class);
        service = new EmbeddingProjectionService(wikiRepository, wikiContentReader, sourceRepository,
                embeddingClient, projectionRepository);
    }

    @Test
    void projectsAuthoritativeWikiContentAndChangesFreshnessOnContentUpdate() {
        StoredPublishedWiki first = wiki("wiki-703", "first canonical hash");
        when(wikiRepository.findPublishedById(WORKSPACE_ID, first.id()))
                .thenReturn(Optional.of(first));
        when(wikiContentReader.readSearchableContent(first)).thenReturn("authoritative wiki body");
        when(embeddingClient.embed(any(EmbeddingRequest.class))).thenAnswer(invocation ->
                resultFor(invocation.getArgument(0, EmbeddingRequest.class)));

        EmbeddingProjectionResult created = service.projectWiki(WORKSPACE_ID, first.id());

        assertThat(created.status()).isEqualTo(EmbeddingProjectionOperationStatus.FRESH);
        var firstIdentity = org.mockito.ArgumentCaptor.forClass(EmbeddingProjectionIdentity.class);

        StoredPublishedWiki updated = wiki("wiki-703", "second canonical hash");
        when(wikiRepository.findPublishedById(WORKSPACE_ID, first.id()))
                .thenReturn(Optional.of(updated));
        when(wikiContentReader.readSearchableContent(updated)).thenReturn("updated authoritative body");

        EmbeddingProjectionResult refreshed = service.projectWiki(WORKSPACE_ID, first.id());

        assertThat(refreshed.status()).isEqualTo(EmbeddingProjectionOperationStatus.FRESH);
        verify(projectionRepository, times(2)).upsertFresh(firstIdentity.capture(), any(), any());
        var identities = firstIdentity.getAllValues();
        assertThat(identities).hasSize(2);
        assertThat(identities.getFirst().stableId()).isEqualTo("wiki-703");
        assertThat(identities.getFirst().canonicalContentHash()).isEqualTo(first.contentHash());
        assertThat(identities.get(1).stableId()).isEqualTo(identities.getFirst().stableId());
        assertThat(identities.get(1).canonicalContentHash())
                .isNotEqualTo(identities.getFirst().canonicalContentHash());
    }

    @Test
    void projectsOnlyAuthoritativeNormalizedSourceContent() {
        String normalized = "# Heading\n\nCanonical normalized source";
        long chunkId = 31L;
        SourceSearchAuthorityDocument authority = new SourceSearchAuthorityDocument(
                WORKSPACE_ID, 9L, "source.pdf", "ACTIVE", "PROCESSED",
                List.of(new SourceSearchAuthorityChunk(chunkId, 1, 2, "Heading", "Heading",
                        normalized, sha256(normalized))));
        when(sourceRepository.findDocumentByChunk(WORKSPACE_ID, chunkId))
                .thenReturn(Optional.of(authority));
        when(embeddingClient.embed(any(EmbeddingRequest.class))).thenAnswer(invocation ->
                resultFor(invocation.getArgument(0, EmbeddingRequest.class)));

        EmbeddingProjectionResult result = service.projectSourceChunk(WORKSPACE_ID, chunkId);

        assertThat(result.status()).isEqualTo(EmbeddingProjectionOperationStatus.FRESH);
        var request = org.mockito.ArgumentCaptor.forClass(EmbeddingRequest.class);
        verify(embeddingClient).embed(request.capture());
        assertThat(request.getValue().inputs()).singleElement()
                .extracting(input -> input.text()).isEqualTo(normalized);
        var identity = org.mockito.ArgumentCaptor.forClass(EmbeddingProjectionIdentity.class);
        verify(projectionRepository).upsertFresh(identity.capture(), any(), any());
        assertThat(identity.getValue().evidenceKind()).isEqualTo(EmbeddingEvidenceKind.SOURCE_CHUNK);
        assertThat(identity.getValue().stableId()).isEqualTo(Long.toString(chunkId));
        assertThat(identity.getValue().canonicalContentHash()).isEqualTo(sha256(normalized));
    }

    @Test
    void recordsTypedProviderFailureWithoutWritingASeeminglyFreshProjection() {
        StoredPublishedWiki page = wiki("wiki-failure", "c".repeat(64));
        when(wikiRepository.findPublishedById(WORKSPACE_ID, page.id())).thenReturn(Optional.of(page));
        when(wikiContentReader.readSearchableContent(page)).thenReturn("canonical body");
        doThrow(new EmbeddingClientException(EmbeddingFailureType.TIMEOUT_OR_NETWORK_UNAVAILABLE,
                "upstream timeout")).when(embeddingClient).embed(any(EmbeddingRequest.class));

        EmbeddingProjectionResult result = service.projectWiki(WORKSPACE_ID, page.id());

        assertThat(result.status()).isEqualTo(EmbeddingProjectionOperationStatus.FAILED);
        assertThat(result.failureType()).isEqualTo(EmbeddingFailureType.TIMEOUT_OR_NETWORK_UNAVAILABLE.name());
        verify(projectionRepository).markFailed(WORKSPACE_ID, EmbeddingEvidenceKind.WIKI,
                page.knowledgeId(), page.contentHash(),
                EmbeddingFailureType.TIMEOUT_OR_NETWORK_UNAVAILABLE.name(), "upstream timeout");
    }

    @Test
    void missingAuthoritativeContentIsNotReportedAsMissingKnowledge() {
        long chunkId = 99L;
        when(sourceRepository.findDocumentByChunk(WORKSPACE_ID, chunkId)).thenReturn(Optional.empty());

        EmbeddingProjectionResult result = service.projectSourceChunk(WORKSPACE_ID, chunkId);

        assertThat(result.status()).isEqualTo(EmbeddingProjectionOperationStatus.NOT_FOUND);
        verify(projectionRepository).delete(WORKSPACE_ID, EmbeddingEvidenceKind.SOURCE_CHUNK,
                Long.toString(chunkId));
    }

    @Test
    void rebuildsWorkspaceFromCurrentWikiAndSourceAuthorities() {
        StoredPublishedWiki page = wiki("wiki-rebuild", "wiki rebuild hash");
        String normalized = "# Source heading\n\nCurrent authoritative source";
        SourceSearchAuthorityDocument authority = new SourceSearchAuthorityDocument(
                WORKSPACE_ID, 12L, "source.md", "ACTIVE", "PROCESSED",
                List.of(new SourceSearchAuthorityChunk(44L, 1, 1, "Notes", "Notes",
                        normalized, sha256(normalized))));
        when(wikiRepository.findAllPublished(WORKSPACE_ID)).thenReturn(List.of(page));
        when(wikiRepository.findPublishedById(WORKSPACE_ID, page.id())).thenReturn(Optional.of(page));
        when(wikiContentReader.readSearchableContent(page)).thenReturn("current wiki body");
        when(sourceRepository.findAllDocuments(WORKSPACE_ID)).thenReturn(List.of(authority));
        when(embeddingClient.embed(any(EmbeddingRequest.class))).thenAnswer(invocation ->
                resultFor(invocation.getArgument(0, EmbeddingRequest.class)));

        EmbeddingProjectionRebuildResult result = service.rebuildWorkspace(WORKSPACE_ID);

        assertThat(result).isEqualTo(new EmbeddingProjectionRebuildResult(WORKSPACE_ID,
                2, 2, 0, 0));
        verify(projectionRepository).clearWorkspace(WORKSPACE_ID);
        verify(projectionRepository, times(2)).upsertFresh(any(), any(), any());
        var requests = org.mockito.ArgumentCaptor.forClass(EmbeddingRequest.class);
        verify(embeddingClient, times(2)).embed(requests.capture());
        assertThat(requests.getAllValues()).extracting(request -> request.inputs().getFirst().text())
                .containsExactly("current wiki body", normalized);
    }

    private static EmbeddingResult resultFor(EmbeddingRequest request) {
        return new EmbeddingResult(List.of(new EmbeddingVector(
                request.inputs().getFirst().identity(), List.of(0.25d, 0.75d))),
                new EmbeddingProviderMetadata("test-provider", "test-model"), Optional.empty());
    }

    private static StoredPublishedWiki wiki(String knowledgeId, String contentHashMarker) {
        return new StoredPublishedWiki(101L, WORKSPACE_ID, knowledgeId, "Issue 703",
                "issue 703", WikiPageType.CONCEPT, "vault/concepts/issue-703.md",
                PageStatus.PUBLISHED, contentHashMarker.matches("[0-9a-f]{64}")
                        ? contentHashMarker : sha256(contentHashMarker), 1,
                "2026-09-02T00:00:00Z", "2026-09-02T00:00:00Z");
    }

    private static String sha256(String value) {
        return HexFormat.of().formatHex(digest(value));
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
