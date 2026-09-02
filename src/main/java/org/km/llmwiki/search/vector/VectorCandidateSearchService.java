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
import org.km.llmwiki.search.embedding.EmbeddingProjectionFreshness;
import org.km.llmwiki.search.embedding.EmbeddingProjectionIdentity;
import org.km.llmwiki.search.embedding.EmbeddingProjectionRepository;
import org.km.llmwiki.search.embedding.EmbeddingVectorCodec;
import org.km.llmwiki.search.embedding.StoredEmbeddingProjection;
import org.km.llmwiki.search.embedding.EmbeddingProjectionReadinessRepository;
import org.km.llmwiki.search.embedding.EmbeddingProjectionReadinessStatus;
import org.km.llmwiki.wiki.PublishedWikiRepository;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;

/**
 * Application boundary for semantic candidates. A candidate is an authority snapshot, never
 * Evidence; Retrieval must read authority again while assembling Evidence.
 */
@Service
public class VectorCandidateSearchService {

    private static final Comparator<SearchCandidate> RESULT_ORDER =
            Comparator.comparingDouble(SearchCandidate::score).reversed()
                    .thenComparing(candidate -> candidate.kind().name())
                    .thenComparing(SearchCandidate::stableId);

    private final EmbeddingClient embeddingClient;
    private final EmbeddingProjectionRepository projectionRepository;
    private final PublishedWikiRepository publishedWikiRepository;
    private final SourceSearchAuthorityRepository sourceAuthorityRepository;
    private final VectorSimilaritySearch similaritySearch;
    private final EmbeddingProjectionReadinessRepository readinessRepository;

    public VectorCandidateSearchService(EmbeddingClient embeddingClient,
                                        EmbeddingProjectionRepository projectionRepository,
                                        PublishedWikiRepository publishedWikiRepository,
                                        SourceSearchAuthorityRepository sourceAuthorityRepository,
                                        VectorSimilaritySearch similaritySearch) {
        this(embeddingClient, projectionRepository, publishedWikiRepository, sourceAuthorityRepository,
                similaritySearch, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public VectorCandidateSearchService(EmbeddingClient embeddingClient,
                                        EmbeddingProjectionRepository projectionRepository,
                                        PublishedWikiRepository publishedWikiRepository,
                                        SourceSearchAuthorityRepository sourceAuthorityRepository,
                                        VectorSimilaritySearch similaritySearch,
                                        EmbeddingProjectionReadinessRepository readinessRepository) {
        this.embeddingClient = embeddingClient;
        this.projectionRepository = projectionRepository;
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

        List<StoredEmbeddingProjection> projections;
        try {
            projections = projectionRepository.findAll(activeWorkspace.id());
        } catch (DataAccessException failure) {
            throw unavailable(VectorCandidateSearchUnavailableException.Dependency.VECTOR_REPOSITORY,
                    failure);
        }

        Map<String, CandidateVector> searchable = new LinkedHashMap<>();
        Map<Long, Optional<SourceSearchAuthorityDocument>> sourceDocuments = new HashMap<>();
        for (StoredEmbeddingProjection projection : projections) {
            if (projection == null) {
                throw unavailable(VectorCandidateSearchUnavailableException.Dependency.VECTOR_REPOSITORY,
                        new IllegalStateException("Embedding projection repository returned a null row"));
            }
            if (!includes(query.corpus(), projection.evidenceKind())) {
                continue;
            }
            EmbeddingProjectionIdentity expected;
            try {
                expected = new EmbeddingProjectionIdentity(
                        activeWorkspace.id(), projection.evidenceKind(), projection.stableId(),
                        projection.canonicalContentHash(), result.providerMetadata().provider(),
                        result.providerMetadata().model(), queryVector.values().size(),
                        EmbeddingProjectionContract.VERSION);
            } catch (IllegalArgumentException malformedProjection) {
                continue;
            }
            if (!EmbeddingProjectionFreshness.isFresh(projection, expected)) {
                continue;
            }
            Optional<SearchCandidate> candidate = authoritySnapshot(projection, activeWorkspace,
                    sourceDocuments);
            if (candidate.isEmpty()) {
                continue;
            }
            EmbeddingVector stored = EmbeddingVectorCodec.decode(projection.canonicalContentHash(),
                    projection.vectorBlob(), projection.dimension());
            String identity = projection.evidenceKind().name() + ":" + projection.stableId();
            searchable.put(identity, new CandidateVector(candidate.get(), stored.values()));
        }

        List<VectorSimilarityEntry> entries = searchable.entrySet().stream()
                .map(entry -> new VectorSimilarityEntry(entry.getKey(), entry.getValue().values()))
                .toList();
        List<VectorSimilarityMatch> matches;
        try {
            matches = similaritySearch.findNearest(queryVector.values(), entries, query.limit());
            if (matches == null) {
                throw new IllegalStateException("Vector adapter returned no match list");
            }
        } catch (VectorCandidateSearchUnavailableException unavailable) {
            throw unavailable;
        } catch (RuntimeException failure) {
            throw unavailable(VectorCandidateSearchUnavailableException.Dependency.VECTOR_REPOSITORY,
                    failure);
        }

        List<SearchCandidate> candidates = new ArrayList<>();
        Set<String> matchedIdentities = new HashSet<>();
        for (VectorSimilarityMatch match : matches) {
            if (match == null) {
                throw unavailable(VectorCandidateSearchUnavailableException.Dependency.VECTOR_REPOSITORY,
                        new IllegalStateException("Vector adapter returned a null match"));
            }
            if (!matchedIdentities.add(match.identity())) {
                throw unavailable(VectorCandidateSearchUnavailableException.Dependency.VECTOR_REPOSITORY,
                        new IllegalStateException("Vector adapter returned duplicate candidate identity"));
            }
            CandidateVector candidate = searchable.get(match.identity());
            if (candidate == null) {
                throw unavailable(VectorCandidateSearchUnavailableException.Dependency.VECTOR_REPOSITORY,
                        new IllegalStateException("Vector adapter returned an unknown candidate identity"));
            }
            candidates.add(withScore(candidate.candidate(), match.similarity()));
        }
        candidates.sort(RESULT_ORDER);
        if (candidates.size() > query.limit()) {
            candidates = new ArrayList<>(candidates.subList(0, query.limit()));
        }
        return new SearchCandidatePage(List.copyOf(candidates), 0, query.limit(), searchable.size());
    }

    private void ensureProjectionReady(SearchCorpus corpus, long workspaceId) {
        if (readinessRepository == null) return;
        for (EmbeddingEvidenceKind kind : kinds(corpus)) {
            var state = readinessRepository.find(workspaceId, kind);
            if (state.isEmpty() || state.get().status() != EmbeddingProjectionReadinessStatus.READY) {
                String status = state.map(value -> value.status().name()).orElse("NOT_BUILT");
                throw unavailable(VectorCandidateSearchUnavailableException.Dependency.PROJECTION_READINESS,
                        new IllegalStateException("Embedding projection is not ready: " + kind.name() + "/" + status));
            }
        }
    }

    private void ensureProjectionMetadata(SearchCorpus corpus, long workspaceId,
                                          EmbeddingResult result, int dimension) {
        if (readinessRepository == null) return;
        for (EmbeddingEvidenceKind kind : kinds(corpus)) {
            var state = readinessRepository.find(workspaceId, kind).orElse(null);
            if (state == null || state.status() != EmbeddingProjectionReadinessStatus.READY) continue;
            boolean mismatch = state.provider() != null && !state.provider().equals(result.providerMetadata().provider())
                    || state.model() != null && !state.model().equals(result.providerMetadata().model())
                    || state.dimension() != null && state.dimension() != dimension
                    || state.projectionVersion() != null
                    && !EmbeddingProjectionContract.VERSION.equals(state.projectionVersion());
            if (mismatch) {
                readinessRepository.markStale(workspaceId, kind, "Embedding provider/model/dimension changed; rebuild required");
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
            StoredEmbeddingProjection projection,
            SearchWorkspaceProvenance workspace,
            Map<Long, Optional<SourceSearchAuthorityDocument>> sourceDocuments) {
        try {
            return switch (projection.evidenceKind()) {
                case WIKI -> wikiSnapshot(projection, workspace);
                case SOURCE_CHUNK -> sourceSnapshot(projection, workspace, sourceDocuments);
            };
        } catch (DataAccessException failure) {
            throw unavailable(VectorCandidateSearchUnavailableException.Dependency.CONTENT_AUTHORITY,
                    failure);
        }
    }

    private Optional<SearchCandidate> wikiSnapshot(StoredEmbeddingProjection projection,
                                                   SearchWorkspaceProvenance workspace) {
        return publishedWikiRepository.findPublishedByKnowledgeId(
                        workspace.id(), projection.stableId())
                .filter(page -> projection.canonicalContentHash().equals(page.contentHash()))
                .map(page -> new SearchCandidate(SearchResultKind.WIKI, page.knowledgeId(), 0.0d,
                        "", workspace, page.knowledgeId(), page.title(), page.pageType().name(),
                        page.markdownPath(), page.revision(), page.contentHash(), null, null,
                        null, null, null, null, null, null, null,
                        projection.embeddingProvider(), projection.embeddingModel(),
                        projection.dimension(), projection.projectionVersion()));
    }

    private Optional<SearchCandidate> sourceSnapshot(
            StoredEmbeddingProjection projection,
            SearchWorkspaceProvenance workspace,
            Map<Long, Optional<SourceSearchAuthorityDocument>> documents) {
        final long sourceChunkId;
        try {
            sourceChunkId = Long.parseLong(projection.stableId());
        } catch (NumberFormatException malformedIdentity) {
            return Optional.empty();
        }
        Optional<SourceSearchAuthorityDocument> located =
                sourceAuthorityRepository.findDocumentByChunk(workspace.id(), sourceChunkId);
        if (located.isEmpty()) {
            return Optional.empty();
        }
        SourceSearchAuthorityDocument document = documents.computeIfAbsent(
                located.get().documentId(), ignored -> located).orElseThrow();
        if (!SourceSearchEligibilityPolicy.documentEligible(document)) {
            return Optional.empty();
        }
        var eligible = SourceSearchFreshness.eligibleDocuments(document);
        return eligible.stream()
                .filter(chunk -> chunk.sourceChunkId() == sourceChunkId)
                .filter(chunk -> projection.canonicalContentHash().equals(chunk.contentHash()))
                .findFirst()
                .map(chunk -> new SearchCandidate(SearchResultKind.SOURCE_CHUNK,
                        projection.stableId(), 0.0d, "", workspace, null, null, null, null, null,
                        chunk.contentHash(), SourceSearchFreshness.fingerprint(document),
                        eligible.size(), chunk.sourceChunkId(), document.documentId(),
                        document.documentName(), chunk.chunkNo(), chunk.pageNo(), chunk.section(),
                        chunk.headingPath(), projection.embeddingProvider(),
                        projection.embeddingModel(), projection.dimension(),
                        projection.projectionVersion()));
    }

    private static SearchCandidate withScore(SearchCandidate candidate, double score) {
        return new SearchCandidate(candidate.kind(), candidate.stableId(), score,
                candidate.snippet(), candidate.workspace(), candidate.knowledgeId(),
                candidate.title(), candidate.pageType(), candidate.path(), candidate.revision(),
                candidate.indexedContentHash(), candidate.sourceDocumentFingerprint(),
                candidate.sourceEligibleChunkCount(), candidate.sourceChunkId(),
                candidate.documentId(), candidate.documentName(), candidate.chunkNo(),
                candidate.pageNo(), candidate.section(), candidate.headingPath(),
                candidate.embeddingProvider(), candidate.embeddingModel(),
                candidate.embeddingDimension(), candidate.embeddingProjectionVersion());
    }

    private static boolean includes(SearchCorpus corpus, EmbeddingEvidenceKind kind) {
        return corpus == SearchCorpus.ALL
                || corpus == SearchCorpus.WIKI && kind == EmbeddingEvidenceKind.WIKI
                || corpus == SearchCorpus.SOURCE && kind == EmbeddingEvidenceKind.SOURCE_CHUNK;
    }

    private static VectorCandidateSearchUnavailableException unavailable(
            VectorCandidateSearchUnavailableException.Dependency dependency, Throwable cause) {
        return new VectorCandidateSearchUnavailableException(dependency, cause);
    }

    private record CandidateVector(SearchCandidate candidate, List<Double> values) {
    }
}
