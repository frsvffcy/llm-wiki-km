package org.km.llmwiki.search.vector;

import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.ai.embedding.EmbeddingClient;
import org.km.llmwiki.ai.embedding.EmbeddingProviderMetadata;
import org.km.llmwiki.ai.embedding.EmbeddingRequest;
import org.km.llmwiki.ai.embedding.EmbeddingResult;
import org.km.llmwiki.ai.embedding.EmbeddingVector;
import org.km.llmwiki.search.SearchCandidatePage;
import org.km.llmwiki.search.SearchCorpus;
import org.km.llmwiki.search.SearchResultKind;
import org.km.llmwiki.search.SearchWorkspaceProvenance;
import org.km.llmwiki.search.SourceSearchAuthorityChunk;
import org.km.llmwiki.search.SourceSearchAuthorityDocument;
import org.km.llmwiki.search.SourceSearchAuthorityRepository;
import org.km.llmwiki.search.embedding.EmbeddingEvidenceKind;
import org.km.llmwiki.search.embedding.EmbeddingProjectionContract;
import org.km.llmwiki.wiki.PageStatus;
import org.km.llmwiki.wiki.PublishedWikiRepository;
import org.km.llmwiki.wiki.StoredPublishedWiki;
import org.km.llmwiki.wiki.WikiPageType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
class VectorCandidateSearchServiceTest {

    private static final SearchWorkspaceProvenance WORKSPACE =
            new SearchWorkspaceProvenance(7L, "test");
    private static final String HASH_A = "0123456789abcdef".repeat(4);
    private static final String HASH_B = "abcdef0123456789".repeat(4);

    private EmbeddingClient embeddingClient;
    private PublishedWikiRepository wikiRepository;
    private SourceSearchAuthorityRepository sourceRepository;
    private VectorSimilaritySearch similaritySearch;
    private VectorCandidateSearchService service;

    @BeforeEach
    void setUp() {
        embeddingClient = mock(EmbeddingClient.class);
        wikiRepository = mock(PublishedWikiRepository.class);
        sourceRepository = mock(SourceSearchAuthorityRepository.class);
        similaritySearch = mock(VectorSimilaritySearch.class);
        when(embeddingClient.embed(any(EmbeddingRequest.class))).thenAnswer(invocation -> {
            EmbeddingRequest request = invocation.getArgument(0, EmbeddingRequest.class);
            var input = request.inputs().getFirst();
            return new EmbeddingResult(List.of(new EmbeddingVector(input.identity(),
                    List.of(0.25d, 0.75d))),
                    new EmbeddingProviderMetadata("test-provider", "test-model"),
                    Optional.empty());
        });
        service = new VectorCandidateSearchService(embeddingClient, wikiRepository,
                sourceRepository, similaritySearch);
    }

    @Test
    void returnsWikiTopKAfterCanonicalAuthorityRevalidation() {
        when(similaritySearch.findNearest(any())).thenReturn(List.of(
                match(EmbeddingEvidenceKind.WIKI, "page-a", HASH_A, 0.91d),
                match(EmbeddingEvidenceKind.WIKI, "page-b", HASH_B, 0.89d)));
        when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE.id(), "page-a"))
                .thenReturn(Optional.of(wiki("page-a", HASH_A)));
        when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE.id(), "page-b"))
                .thenReturn(Optional.of(wiki("page-b", HASH_B)));

        SearchCandidatePage page = service.findCandidates(
                new VectorCandidateSearchQuery("query", SearchCorpus.WIKI, 2), WORKSPACE);

        assertThat(page.items()).extracting(candidate -> candidate.stableId())
                .containsExactly("page-a", "page-b");
        assertThat(page.items()).extracting(candidate -> candidate.score())
                .containsExactly(0.91d, 0.89d);
        assertThat(page.totalElements()).isEqualTo(2);
        var captor = org.mockito.ArgumentCaptor.forClass(VectorSimilarityQuery.class);
        verify(similaritySearch).findNearest(captor.capture());
        assertThat(captor.getValue().limit()).isEqualTo(2);
        assertThat(captor.getValue().offset()).isZero();
    }

    @Test
    void returnsSourceChunkTopKOnlyWhenDocumentIsEligibleAndHashIsCurrent() {
        String content = "A source chunk with deterministic content.";
        String hash = sha256(content);
        when(similaritySearch.findNearest(any())).thenReturn(List.of(
                match(EmbeddingEvidenceKind.SOURCE_CHUNK, "41", hash, 0.88d)));
        when(sourceRepository.findDocumentByChunk(WORKSPACE.id(), 41L)).thenReturn(Optional.of(
                new SourceSearchAuthorityDocument(WORKSPACE.id(), 9L, "source.md", "PROCESSED",
                        "PROCESSED", List.of(new SourceSearchAuthorityChunk(41L, 1, 2,
                        "Notes", "Notes", content, hash)))));

        SearchCandidatePage page = service.findCandidates(
                new VectorCandidateSearchQuery("query", SearchCorpus.SOURCE, 1), WORKSPACE);

        assertThat(page.items()).singleElement().satisfies(candidate -> {
            assertThat(candidate.kind()).isEqualTo(SearchResultKind.SOURCE_CHUNK);
            assertThat(candidate.stableId()).isEqualTo("41");
            assertThat(candidate.score()).isEqualTo(0.88d);
        });
    }

    @Test
    void allCorpusPassesBothEvidenceKindsAndKeepsWorkspaceInQuery() {
        when(similaritySearch.findNearest(any(VectorSimilarityQuery.class))).thenAnswer(invocation -> {
            VectorSimilarityQuery query = invocation.getArgument(0);
            return query.offset() == 0 ? List.of(
                    match(EmbeddingEvidenceKind.WIKI, "page-a", HASH_A, 0.9d),
                    match(EmbeddingEvidenceKind.SOURCE_CHUNK, "41", HASH_B, 0.8d)) : List.of();
        });
        when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE.id(), "page-a"))
                .thenReturn(Optional.of(wiki("page-a", HASH_A)));
        when(sourceRepository.findDocumentByChunk(WORKSPACE.id(), 41L)).thenReturn(Optional.empty());

        SearchCandidatePage page = service.findCandidates(
                new VectorCandidateSearchQuery("query", SearchCorpus.ALL, 2), WORKSPACE);

        assertThat(page.items()).extracting(candidate -> candidate.kind())
                .containsExactly(SearchResultKind.WIKI);
        var captor = org.mockito.ArgumentCaptor.forClass(VectorSimilarityQuery.class);
        verify(similaritySearch, org.mockito.Mockito.times(2)).findNearest(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(request -> {
            assertThat(request.workspaceId()).isEqualTo(WORKSPACE.id());
            assertThat(request.evidenceKinds())
                    .containsExactly(EmbeddingEvidenceKind.WIKI, EmbeddingEvidenceKind.SOURCE_CHUNK);
        });
    }

    @Test
    void rejectsAuthorityDriftAndStaleSourceWithoutReturningIllegalCandidates() {
        String content = "Current normalized source content.";
        String currentHash = sha256(content);
        when(similaritySearch.findNearest(any(VectorSimilarityQuery.class))).thenAnswer(invocation -> {
            VectorSimilarityQuery query = invocation.getArgument(0);
            return query.offset() == 0 ? List.of(
                    match(EmbeddingEvidenceKind.WIKI, "page-a", HASH_A, 0.95d),
                    match(EmbeddingEvidenceKind.SOURCE_CHUNK, "41", HASH_B, 0.94d)) : List.of();
        });
        when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE.id(), "page-a"))
                .thenReturn(Optional.of(wiki("page-a", HASH_B)));
        when(sourceRepository.findDocumentByChunk(WORKSPACE.id(), 41L)).thenReturn(Optional.of(
                new SourceSearchAuthorityDocument(WORKSPACE.id(), 9L, "source.md", "PROCESSED",
                        "PENDING", List.of(new SourceSearchAuthorityChunk(41L, 1, 2,
                        "Notes", "Notes", content, currentHash)))));

        assertThat(service.findCandidates(
                new VectorCandidateSearchQuery("query", SearchCorpus.ALL, 2), WORKSPACE).items())
                .isEmpty();
    }

    @Test
    void refillsWithBoundedOffsetsAfterAuthorityRejectsCandidates() {
        when(similaritySearch.findNearest(any(VectorSimilarityQuery.class)))
                .thenAnswer(invocation -> {
                    VectorSimilarityQuery query = invocation.getArgument(0);
                    if (query.offset() == 0) {
                        return List.of(match(EmbeddingEvidenceKind.WIKI, "drifted", HASH_A, 0.99d),
                                match(EmbeddingEvidenceKind.WIKI, "page-a", HASH_B, 0.8d));
                    }
                    return List.of(match(EmbeddingEvidenceKind.WIKI, "page-b", HASH_A, 0.7d));
                });
        when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE.id(), "drifted"))
                .thenReturn(Optional.of(wiki("drifted", HASH_B)));
        when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE.id(), "page-a"))
                .thenReturn(Optional.of(wiki("page-a", HASH_B)));
        when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE.id(), "page-b"))
                .thenReturn(Optional.of(wiki("page-b", HASH_A)));

        SearchCandidatePage page = service.findCandidates(
                new VectorCandidateSearchQuery("query", SearchCorpus.WIKI, 2), WORKSPACE);

        assertThat(page.items()).extracting(candidate -> candidate.stableId())
                .containsExactly("page-a", "page-b");
        var captor = org.mockito.ArgumentCaptor.forClass(VectorSimilarityQuery.class);
        verify(similaritySearch, org.mockito.Mockito.times(2)).findNearest(captor.capture());
        assertThat(captor.getAllValues()).extracting(VectorSimilarityQuery::limit)
                .containsExactly(2, 2);
        assertThat(captor.getAllValues()).extracting(VectorSimilarityQuery::offset)
                .containsExactly(0, 2);
    }

    @Test
    void capsAuthorityRevalidationAtFiveTimesRequestedLimit() {
        when(similaritySearch.findNearest(any(VectorSimilarityQuery.class))).thenAnswer(invocation -> {
            VectorSimilarityQuery query = invocation.getArgument(0);
            return java.util.stream.IntStream.range(query.offset(), query.offset() + query.limit())
                    .mapToObj(index -> match(EmbeddingEvidenceKind.WIKI, "page-" + index,
                            HASH_A, 0.9d - index / 100.0d)).toList();
        });
        for (int index = 0; index < 10; index++) {
            when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE.id(), "page-" + index))
                    .thenReturn(Optional.empty());
        }

        SearchCandidatePage page = service.findCandidates(
                new VectorCandidateSearchQuery("query", SearchCorpus.WIKI, 2), WORKSPACE);

        assertThat(page.items()).isEmpty();
        verify(similaritySearch, org.mockito.Mockito.times(5)).findNearest(any());
        for (int index = 0; index < 10; index++) {
            verify(wikiRepository).findPublishedByKnowledgeId(WORKSPACE.id(), "page-" + index);
        }
    }

    @Test
    void propagatesUnexpectedSimilarityRuntimeException() {
        IllegalStateException defect = new IllegalStateException("programming defect");
        when(similaritySearch.findNearest(any())).thenThrow(defect);

        assertThatThrownBy(() -> service.findCandidates(
                new VectorCandidateSearchQuery("query", SearchCorpus.WIKI, 5), WORKSPACE))
                .isSameAs(defect);
    }

    @Test
    void mapsSimilarityRepositoryDataAccessFailureToTypedUnavailable() {
        DataAccessException databaseFailure = new DataAccessException("database unavailable");
        when(similaritySearch.findNearest(any())).thenThrow(databaseFailure);

        VectorCandidateSearchUnavailableException failure = catchThrowableOfType(
                () -> service.findCandidates(
                        new VectorCandidateSearchQuery("query", SearchCorpus.WIKI, 5), WORKSPACE),
                VectorCandidateSearchUnavailableException.class);

        assertThat(failure).isNotNull();
        assertThat(failure.dependency())
                .isEqualTo(VectorCandidateSearchUnavailableException.Dependency.VECTOR_REPOSITORY);
        assertThat(failure.getCause()).isSameAs(databaseFailure);
    }

    private static VectorSimilarityMatch match(EmbeddingEvidenceKind kind, String id,
                                               String hash, double similarity) {
        return new VectorSimilarityMatch(kind, id, hash, "test-provider", "test-model", 2,
                EmbeddingProjectionContract.VERSION, similarity);
    }

    private static StoredPublishedWiki wiki(String knowledgeId, String hash) {
        return new StoredPublishedWiki(1L, WORKSPACE.id(), knowledgeId, knowledgeId,
                knowledgeId, WikiPageType.CONCEPT, "concepts/" + knowledgeId + ".md",
                PageStatus.PUBLISHED, hash, 1, "2026-09-03T00:00:00Z", "2026-09-03T00:00:00Z");
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
