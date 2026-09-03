package org.km.llmwiki.search.vector;

import org.jooq.exception.DataAccessException;
import org.km.llmwiki.ai.embedding.EmbeddingClient;
import org.km.llmwiki.ai.embedding.EmbeddingClientException;
import org.km.llmwiki.ai.embedding.EmbeddingInput;
import org.km.llmwiki.ai.embedding.EmbeddingRequest;
import org.km.llmwiki.ai.embedding.EmbeddingResult;
import org.km.llmwiki.ai.embedding.EmbeddingVector;
import org.km.llmwiki.search.SearchCandidate;
import org.km.llmwiki.search.SearchCandidatePage;
import org.km.llmwiki.search.SearchCorpus;
import org.km.llmwiki.search.SearchResultKind;
import org.km.llmwiki.search.SearchWorkspaceProvenance;
import org.km.llmwiki.search.SourceSearchAuthorityDocument;
import org.km.llmwiki.search.SourceSearchAuthorityRepository;
import org.km.llmwiki.search.SourceSearchEligibilityPolicy;
import org.km.llmwiki.search.SourceSearchFreshness;
import org.km.llmwiki.search.embedding.EmbeddingEvidenceKind;
import org.km.llmwiki.search.embedding.EmbeddingProjectionContract;
import org.km.llmwiki.search.embedding.EmbeddingProjectionReadinessRepository;
import org.km.llmwiki.search.embedding.EmbeddingProjectionReadinessStatus;
import org.km.llmwiki.wiki.PublishedWikiRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Application boundary for semantic candidates. Storage returns only bounded KNN rows; this
 * service revalidates each returned row against canonical authority before exposing a candidate.
 */
@Service
public class VectorCandidateSearchService {

    private static final int OVERFETCH_FACTOR = 5;
    private static final int MAX_OVERFETCH_ROWS = 200;
    private static final int MAX_REFILL_ROUNDS = 5;
    private static final Comparator<SearchCandidate> RESULT_ORDER =
            Comparator.comparingDouble(SearchCandidate::score).reversed()
                    .thenComparing(candidate -> candidate.kind().name())
                    .thenComparing(SearchCandidate::stableId);

    private final EmbeddingClient embeddingClient;
    private final PublishedWikiRepository publishedWikiRepository;
    private final SourceSearchAuthorityRepository sourceAuthorityRepository;
    private final VectorSimilaritySearch similaritySearch;
    private final EmbeddingProjectionReadinessRepository readinessRepository;

    public VectorCandidateSearchService(EmbeddingClient embeddingClient,
                                        PublishedWikiRepository publishedWikiRepository,
                                        SourceSearchAuthorityRepository sourceAuthorityRepository,
                                        VectorSimilaritySearch similaritySearch) {
        this(embeddingClient, publishedWikiRepository, sourceAuthorityRepository,
                similaritySearch, null);
    }

    @Autowired
    public VectorCandidateSearchService(EmbeddingClient embeddingClient,
                                        PublishedWikiRepository publishedWikiRepository,
                                        SourceSearchAuthorityRepository sourceAuthorityRepository,
                                        VectorSimilaritySearch similaritySearch,
                                        EmbeddingProjectionReadinessRepository readinessRepository) {
        this.embeddingClient = embeddingClient;
        this.publishedWikiRepository = publishedWikiRepository;
        this.sourceAuthorityRepository = sourceAuthorityRepository;
        this.similaritySearch = similaritySearch;
        this.readinessRepository = readinessRepository;
    }

    public SearchCandidatePage findCandidates(VectorCandidateSearchQuery query,
                                              SearchWorkspaceProvenance activeWorkspace) {
        if (query == null || activeWorkspace == null || activeWorkspace.id() <= 0) {
            throw new IllegalArgumentException("Vector candidate query and active workspace are required");
        }
        EmbeddingInput input = new EmbeddingInput(Normalizer.normalize(
                query.query().strip(), Normalizer.Form.NFC));
        ensureProjectionReady(query.corpus(), activeWorkspace.id());
        EmbeddingResult result = embed(input);
        EmbeddingVector queryVector = validateSingleResult(result, input);
        ensureProjectionMetadata(query.corpus(), activeWorkspace.id(), result, queryVector.values().size());

        List<EmbeddingEvidenceKind> kinds = kinds(query.corpus());
        int fetchBudget = Math.min(MAX_OVERFETCH_ROWS,
                Math.multiplyExact(query.limit(), OVERFETCH_FACTOR));
        int batchSize = Math.min(query.limit(), fetchBudget);
        int fetched = 0;
        int rounds = 0;
        Set<String> seen = new HashSet<>();
        List<SearchCandidate> candidates = new ArrayList<>();
        Map<Long, Optional<SourceSearchAuthorityDocument>> sourceDocuments = new HashMap<>();

        while (candidates.size() < query.limit() && fetched < fetchBudget
                && rounds < MAX_REFILL_ROUNDS) {
            int requestSize = Math.min(batchSize, fetchBudget - fetched);
            VectorSimilarityQuery knn = new VectorSimilarityQuery(activeWorkspace.id(), kinds,
                    result.providerMetadata().provider(), result.providerMetadata().model(),
                    queryVector.values().size(), EmbeddingProjectionContract.VERSION,
                    queryVector.values(), requestSize, fetched, true);
            List<VectorSimilarityMatch> matches = findNearest(knn);
            if (matches.isEmpty()) {
                break;
            }
            fetched += matches.size();
            for (VectorSimilarityMatch match : matches) {
                if (match == null) {
                    throw new IllegalStateException("Vector adapter returned a null match");
                }
                validateMatch(match, activeWorkspace, result, queryVector.values().size());
                if (!seen.add(match.identity())) {
                    throw new IllegalStateException("Vector adapter returned duplicate candidate identity");
                }
                authoritySnapshot(match, activeWorkspace, sourceDocuments)
                        .map(candidate -> withScore(candidate, match.similarity()))
                        .ifPresent(candidates::add);
            }
            rounds++;
            if (matches.size() < requestSize) {
                break;
            }
        }

        candidates.sort(RESULT_ORDER);
        if (candidates.size() > query.limit()) {
            candidates = new ArrayList<>(candidates.subList(0, query.limit()));
        }
        // The native query is intentionally bounded, so this is the count of legal returned
        // candidates, not a misleading count of all projections in the workspace.
        return new SearchCandidatePage(List.copyOf(candidates), 0, query.limit(), candidates.size());
    }

    private List<VectorSimilarityMatch> findNearest(VectorSimilarityQuery query) {
        try {
            List<VectorSimilarityMatch> matches = similaritySearch.findNearest(query);
            if (matches == null) {
                throw new IllegalStateException("Vector adapter returned no match list");
            }
            return matches;
        } catch (VectorCandidateSearchUnavailableException unavailable) {
            throw unavailable;
        } catch (DataAccessException failure) {
            throw unavailable(VectorCandidateSearchUnavailableException.Dependency.VECTOR_REPOSITORY,
                    failure);
        }
    }

    private static void validateMatch(VectorSimilarityMatch match,
                                      SearchWorkspaceProvenance workspace,
                                      EmbeddingResult result,
                                      int dimension) {
        if (match.embeddingProvider().equals(result.providerMetadata().provider())
                && match.embeddingModel().equals(result.providerMetadata().model())
                && match.dimension() == dimension
                && match.projectionVersion().equals(EmbeddingProjectionContract.VERSION)) {
            return;
        }
        throw new IllegalStateException("Vector adapter returned a mismatched projection row for workspace "
                + workspace.id());
    }

    private void ensureProjectionReady(SearchCorpus corpus, long workspaceId) {
        if (readinessRepository == null) return;
        for (EmbeddingEvidenceKind kind : kinds(corpus)) {
            var state = readinessRepository.find(workspaceId, kind);
            if (state.isEmpty() || state.get().status() != EmbeddingProjectionReadinessStatus.READY) {
                String status = state.map(value -> value.status().name()).orElse("NOT_BUILT");
                throw unavailable(VectorCandidateSearchUnavailableException.Dependency.PROJECTION_READINESS,
                        new IllegalStateException("Embedding projection is not ready: "
                                + kind.name() + "/" + status));
            }
        }
    }

    private void ensureProjectionMetadata(SearchCorpus corpus, long workspaceId,
                                          EmbeddingResult result, int dimension) {
        if (readinessRepository == null) return;
        for (EmbeddingEvidenceKind kind : kinds(corpus)) {
            var state = readinessRepository.find(workspaceId, kind).orElse(null);
            if (state == null || state.status() != EmbeddingProjectionReadinessStatus.READY) continue;
            boolean mismatch = state.provider() != null
                    && !state.provider().equals(result.providerMetadata().provider())
                    || state.model() != null && !state.model().equals(result.providerMetadata().model())
                    || state.dimension() != null && state.dimension() != dimension
                    || state.projectionVersion() != null
                    && !EmbeddingProjectionContract.VERSION.equals(state.projectionVersion());
            if (mismatch) {
                readinessRepository.markStale(workspaceId, kind,
                        "Embedding provider/model/dimension changed; rebuild required");
                throw unavailable(VectorCandidateSearchUnavailableException.Dependency.PROJECTION_READINESS,
                        new IllegalStateException("Embedding projection is stale: " + kind.name()));
            }
        }
    }

    private static List<EmbeddingEvidenceKind> kinds(SearchCorpus corpus) {
        return corpus == SearchCorpus.WIKI ? List.of(EmbeddingEvidenceKind.WIKI)
                : corpus == SearchCorpus.SOURCE ? List.of(EmbeddingEvidenceKind.SOURCE_CHUNK)
                : List.of(EmbeddingEvidenceKind.WIKI, EmbeddingEvidenceKind.SOURCE_CHUNK);
    }

    private EmbeddingResult embed(EmbeddingInput input) {
        try {
            EmbeddingResult result = embeddingClient.embed(EmbeddingRequest.single(input.text()));
            if (result == null) {
                throw new IllegalArgumentException("Embedding client returned no result");
            }
            return result;
        } catch (EmbeddingClientException | IllegalArgumentException failure) {
            throw unavailable(VectorCandidateSearchUnavailableException.Dependency.EMBEDDING_PROVIDER,
                    failure);
        }
    }

    private static EmbeddingVector validateSingleResult(EmbeddingResult result,
                                                        EmbeddingInput input) {
        if (result.vectors().size() != 1 || result.vectors().getFirst() == null
                || !input.identity().equals(result.vectors().getFirst().inputIdentity())) {
            throw unavailable(VectorCandidateSearchUnavailableException.Dependency.EMBEDDING_PROVIDER,
                    new IllegalArgumentException("Embedding result does not match the query"));
        }
        return result.vectors().getFirst();
    }

    private Optional<SearchCandidate> authoritySnapshot(
            VectorSimilarityMatch match,
            SearchWorkspaceProvenance workspace,
            Map<Long, Optional<SourceSearchAuthorityDocument>> sourceDocuments) {
        try {
            return switch (match.evidenceKind()) {
                case WIKI -> wikiSnapshot(match, workspace);
                case SOURCE_CHUNK -> sourceSnapshot(match, workspace, sourceDocuments);
            };
        } catch (DataAccessException failure) {
            throw unavailable(VectorCandidateSearchUnavailableException.Dependency.CONTENT_AUTHORITY,
                    failure);
        }
    }

    private Optional<SearchCandidate> wikiSnapshot(VectorSimilarityMatch match,
                                                   SearchWorkspaceProvenance workspace) {
        return publishedWikiRepository.findPublishedByKnowledgeId(workspace.id(), match.stableId())
                .filter(page -> match.canonicalContentHash().equals(page.contentHash()))
                .map(page -> new SearchCandidate(SearchResultKind.WIKI, page.knowledgeId(), 0.0d,
                        "", workspace, page.knowledgeId(), page.title(), page.pageType().name(),
                        page.markdownPath(), page.revision(), page.contentHash(), null, null,
                        null, null, null, null, null, null, null,
                        match.embeddingProvider(), match.embeddingModel(), match.dimension(),
                        match.projectionVersion()));
    }

    private Optional<SearchCandidate> sourceSnapshot(
            VectorSimilarityMatch match,
            SearchWorkspaceProvenance workspace,
            Map<Long, Optional<SourceSearchAuthorityDocument>> documents) {
        final long sourceChunkId;
        try {
            sourceChunkId = Long.parseLong(match.stableId());
        } catch (NumberFormatException malformedIdentity) {
            return Optional.empty();
        }
        Optional<SourceSearchAuthorityDocument> located =
                sourceAuthorityRepository.findDocumentByChunk(workspace.id(), sourceChunkId);
        if (located.isEmpty()) return Optional.empty();
        SourceSearchAuthorityDocument document = documents.computeIfAbsent(
                located.get().documentId(), ignored -> located).orElseThrow();
        if (!SourceSearchEligibilityPolicy.documentEligible(document)) return Optional.empty();
        var eligible = SourceSearchFreshness.eligibleDocuments(document);
        return eligible.stream()
                .filter(chunk -> chunk.sourceChunkId() == sourceChunkId)
                .filter(chunk -> match.canonicalContentHash().equals(chunk.contentHash()))
                .findFirst()
                .map(chunk -> new SearchCandidate(SearchResultKind.SOURCE_CHUNK,
                        match.stableId(), 0.0d, "", workspace, null, null, null, null, null,
                        chunk.contentHash(), SourceSearchFreshness.fingerprint(document),
                        eligible.size(), chunk.sourceChunkId(), document.documentId(),
                        document.documentName(), chunk.chunkNo(), chunk.pageNo(), chunk.section(),
                        chunk.headingPath(), match.embeddingProvider(), match.embeddingModel(),
                        match.dimension(), match.projectionVersion()));
    }

    private static SearchCandidate withScore(SearchCandidate candidate, double score) {
        return candidate.withScore(score);
    }

    private static VectorCandidateSearchUnavailableException unavailable(
            VectorCandidateSearchUnavailableException.Dependency dependency, Throwable cause) {
        return new VectorCandidateSearchUnavailableException(dependency, cause);
    }
}
