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
import org.km.llmwiki.search.SearchCorpus;
import org.km.llmwiki.search.SourceSearchAuthorityRepository;
import org.km.llmwiki.search.SearchWorkspaceProvenance;
import org.km.llmwiki.search.embedding.EmbeddingProjectionRepository;
import org.km.llmwiki.wiki.PublishedWikiRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("unit")
class VectorCandidateSearchServiceTest {

    private static final SearchWorkspaceProvenance WORKSPACE =
            new SearchWorkspaceProvenance(7L, "test");

    private EmbeddingClient embeddingClient;
    private EmbeddingProjectionRepository projectionRepository;
    private VectorSimilaritySearch similaritySearch;
    private VectorCandidateSearchService service;

    @BeforeEach
    void setUp() {
        embeddingClient = mock(EmbeddingClient.class);
        projectionRepository = mock(EmbeddingProjectionRepository.class);
        similaritySearch = mock(VectorSimilaritySearch.class);
        when(projectionRepository.findAll(WORKSPACE.id())).thenReturn(List.of());
        when(embeddingClient.embed(any(EmbeddingRequest.class))).thenAnswer(invocation -> {
            EmbeddingRequest request = invocation.getArgument(0, EmbeddingRequest.class);
            var input = request.inputs().getFirst();
            return new EmbeddingResult(List.of(new EmbeddingVector(input.identity(),
                    List.of(0.25d, 0.75d))),
                    new EmbeddingProviderMetadata("test-provider", "test-model"),
                    Optional.empty());
        });
        service = new VectorCandidateSearchService(embeddingClient, projectionRepository,
                mock(PublishedWikiRepository.class), mock(SourceSearchAuthorityRepository.class),
                similaritySearch);
    }

    @Test
    void propagatesUnexpectedSimilarityRuntimeException() {
        IllegalStateException defect = new IllegalStateException("programming defect");
        when(similaritySearch.findNearest(any(), any(), any(Integer.class))).thenThrow(defect);

        assertThatThrownBy(() -> service.findCandidates(
                new VectorCandidateSearchQuery("query", SearchCorpus.WIKI, 5), WORKSPACE))
                .isSameAs(defect);
    }

    @Test
    void mapsSimilarityRepositoryDataAccessFailureToTypedUnavailable() {
        DataAccessException databaseFailure = new DataAccessException("database unavailable");
        when(similaritySearch.findNearest(any(), any(), any(Integer.class)))
                .thenThrow(databaseFailure);

        VectorCandidateSearchUnavailableException failure = catchThrowableOfType(
                () -> service.findCandidates(
                        new VectorCandidateSearchQuery("query", SearchCorpus.WIKI, 5), WORKSPACE),
                VectorCandidateSearchUnavailableException.class);

        assertThat(failure).isNotNull();
        assertThat(failure.dependency())
                .isEqualTo(VectorCandidateSearchUnavailableException.Dependency.VECTOR_REPOSITORY);
        assertThat(failure.getCause()).isSameAs(databaseFailure);
    }
}
