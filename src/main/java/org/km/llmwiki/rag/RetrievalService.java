package org.km.llmwiki.rag;

import org.jooq.exception.DataAccessException;
import org.km.llmwiki.search.SearchCandidate;
import org.km.llmwiki.search.SearchCandidatePage;
import org.km.llmwiki.search.SearchQuery;
import org.km.llmwiki.search.SearchService;
import org.km.llmwiki.search.SourceSearchAuthorityChunk;
import org.km.llmwiki.search.SourceSearchAuthorityDocument;
import org.km.llmwiki.search.SourceSearchAuthorityRepository;
import org.km.llmwiki.search.SourceSearchEligibilityPolicy;
import org.km.llmwiki.search.SourceSearchFreshness;
import org.km.llmwiki.search.vector.VectorCandidateSearchQuery;
import org.km.llmwiki.search.vector.VectorCandidateSearchService;
import org.km.llmwiki.search.vector.VectorCandidateSearchUnavailableException;
import org.km.llmwiki.wiki.PublishedWikiContentReader;
import org.km.llmwiki.wiki.PublishedWikiRepository;
import org.km.llmwiki.wiki.PublishedWikiUnavailableException;
import org.km.llmwiki.wiki.PublishedWikiValidationException;
import org.km.llmwiki.wiki.StoredPublishedWiki;
import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceResponse;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Application-level Retrieval contract: providers find candidates, authority reads build evidence.
 *
 * <p>The service has no dependency on controllers, REST DTOs, prompts, or LLM providers.
 */
@Service
public class RetrievalService {

    private static final Comparator<SearchCandidate> CANDIDATE_ORDER =
            Comparator.comparingDouble(SearchCandidate::score).reversed()
                    .thenComparing(candidate -> candidate.kind().name())
                    .thenComparing(SearchCandidate::stableId);

    private final WorkspaceService workspaceService;
    private final SearchService searchService;
    private final CandidateAuthorityRevalidator authorityRevalidator;
    private final VectorCandidateSearchService vectorCandidateSearchService;
    private final FusionRanker fusionRanker;
    private final FusedRetrievalOrchestrator fusedRetrievalOrchestrator;

    public RetrievalService(WorkspaceService workspaceService,
                            SearchService searchService,
                            PublishedWikiRepository publishedWikiRepository,
                            PublishedWikiContentReader publishedWikiContentReader,
                            SourceSearchAuthorityRepository sourceAuthorityRepository) {
        this(workspaceService, searchService, publishedWikiRepository, publishedWikiContentReader,
                sourceAuthorityRepository, null, new ReciprocalRankFusion());
    }

    public RetrievalService(WorkspaceService workspaceService,
                            SearchService searchService,
                            PublishedWikiRepository publishedWikiRepository,
                            PublishedWikiContentReader publishedWikiContentReader,
                            SourceSearchAuthorityRepository sourceAuthorityRepository,
                            VectorCandidateSearchService vectorCandidateSearchService) {
        this(workspaceService, searchService, publishedWikiRepository, publishedWikiContentReader,
                sourceAuthorityRepository, vectorCandidateSearchService, new ReciprocalRankFusion());
    }

    public RetrievalService(WorkspaceService workspaceService,
                            SearchService searchService,
                            PublishedWikiRepository publishedWikiRepository,
                            PublishedWikiContentReader publishedWikiContentReader,
                            SourceSearchAuthorityRepository sourceAuthorityRepository,
                            VectorCandidateSearchService vectorCandidateSearchService,
                            FusionRanker fusionRanker) {
        this(workspaceService, searchService, publishedWikiRepository, publishedWikiContentReader,
                sourceAuthorityRepository, vectorCandidateSearchService, fusionRanker, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RetrievalService(WorkspaceService workspaceService,
                            SearchService searchService,
                            PublishedWikiRepository publishedWikiRepository,
                            PublishedWikiContentReader publishedWikiContentReader,
                            SourceSearchAuthorityRepository sourceAuthorityRepository,
                            VectorCandidateSearchService vectorCandidateSearchService,
                            FusionRanker fusionRanker,
                            FusedRetrievalOrchestrator fusedRetrievalOrchestrator) {
        this.workspaceService = workspaceService;
        this.searchService = searchService;
        this.authorityRevalidator = new CandidateAuthorityRevalidator(publishedWikiRepository,
                publishedWikiContentReader, sourceAuthorityRepository);
        this.vectorCandidateSearchService = vectorCandidateSearchService;
        this.fusionRanker = fusionRanker == null ? new ReciprocalRankFusion() : fusionRanker;
        this.fusedRetrievalOrchestrator = fusedRetrievalOrchestrator;
    }

    public EvidenceBundle retrieve(RetrievalRequest request) {
        return retrieve(request, null);
    }

    /**
     * Retrieval with an optional read-only observation collector. The Ask flow passes no
     * collector; only the Retrieval Inspector observes. Collecting never influences selection,
     * ordering, or currentness semantics.
     */
    public EvidenceBundle retrieve(RetrievalRequest request,
                                   RetrievalInspectionCollector collector) {
        if (request == null) {
            throw new IllegalArgumentException("retrieval request must not be null");
        }
        return switch (request.strategy()) {
            case LEXICAL -> retrieveLexical(request, collector);
            case SEMANTIC -> retrieveSemantic(request, collector);
            case HYBRID -> retrieveHybrid(request, collector);
            case FUSED -> retrieveFused(request, collector);
        };
    }

    /**
     * Graph-grounded fused retrieval entry point. The application-owned fused orchestration
     * owns the lexical/vector/graph channels, deterministic fusion, and the last-mile Ask
     * handoff currentness guard; this boundary only dispatches to it.
     */
    private EvidenceBundle retrieveFused(RetrievalRequest request,
                                         RetrievalInspectionCollector collector) {
        if (fusedRetrievalOrchestrator == null) {
            throw new IllegalStateException(
                    "Graph-grounded fused retrieval orchestration is not configured");
        }
        return fusedRetrievalOrchestrator.retrieveFused(request, collector);
    }

    private EvidenceBundle retrieveLexical(RetrievalRequest request,
                                           RetrievalInspectionCollector collector) {
        RetrievalBudgetPolicy.ResolvedBudget limits = RetrievalBudgetPolicy.resolve(request);
        WorkspaceResponse active;
        try {
            active = workspaceService.findActiveWithoutValidation()
                    .orElseThrow(NoActiveWorkspaceException::new);
        } catch (DataAccessException infrastructureFailure) {
            throw new RetrievalUnavailableException(
                    RetrievalUnavailableException.Dependency.WORKSPACE_AUTHORITY,
                    infrastructureFailure);
        }
        SearchCandidatePage page;
        try {
            page = searchService.findCandidates(new SearchQuery(
                    request.query(), request.corpus(), null, null,
                    0, limits.candidateLimit()));
        } catch (DataAccessException infrastructureFailure) {
            throw new RetrievalUnavailableException(
                    RetrievalUnavailableException.Dependency.SEARCH_INDEX,
                    infrastructureFailure);
        }
        recordChannelCandidates(page, CandidateSignal.LEXICAL, collector);
        return assembleEvidence(request, active, page, RetrievalDiagnostics.lexical(), collector);
    }

    /**
     * Semantic candidate retrieval entry point. Vector search only supplies candidates; evidence
     * is assembled through the same authority revalidation path as FTS retrieval.
     */
    public EvidenceBundle retrieveSemantic(RetrievalRequest request) {
        return retrieveSemantic(request, null);
    }

    private EvidenceBundle retrieveSemantic(RetrievalRequest request,
                                            RetrievalInspectionCollector collector) {
        if (vectorCandidateSearchService == null) {
            throw new RetrievalUnavailableException(
                    RetrievalUnavailableException.Dependency.VECTOR_SEARCH,
                    new IllegalStateException("Vector candidate search is not configured"));
        }
        RetrievalBudgetPolicy.ResolvedBudget limits = RetrievalBudgetPolicy.resolve(request);
        WorkspaceResponse active = activeWorkspace();
        SearchCandidatePage page;
        try {
            page = vectorCandidateSearchService.findCandidates(
                            new VectorCandidateSearchQuery(request.query(), request.corpus(),
                            limits.candidateLimit()),
                    new org.km.llmwiki.search.SearchWorkspaceProvenance(active.id(), active.name()));
        } catch (VectorCandidateSearchUnavailableException unavailable) {
            throw new RetrievalUnavailableException(
                    RetrievalUnavailableException.Dependency.VECTOR_SEARCH, unavailable);
        }
        recordChannelCandidates(page, CandidateSignal.VECTOR, collector);
        return assembleEvidence(request, active, page, RetrievalDiagnostics.semantic(), collector);
    }

    private EvidenceBundle retrieveHybrid(RetrievalRequest request,
                                          RetrievalInspectionCollector collector) {
        RetrievalBudgetPolicy.ResolvedBudget limits = RetrievalBudgetPolicy.resolve(request);
        WorkspaceResponse active = activeWorkspace();
        SearchCandidatePage lexical = findLexicalCandidates(request, limits);
        if (vectorCandidateSearchService == null) {
            recordChannelCandidates(lexical, CandidateSignal.LEXICAL, collector);
            return assembleEvidence(request, active, lexical,
                    RetrievalDiagnostics.degradedHybrid("vector candidate search is not configured"),
                    collector);
        }
        if (collector != null) {
            collector.ensureChannel(CandidateSignal.LEXICAL);
        }
        try {
            SearchCandidatePage vector = vectorCandidateSearchService.findCandidates(
                    new VectorCandidateSearchQuery(request.query(), request.corpus(),
                            limits.candidateLimit()),
                    new org.km.llmwiki.search.SearchWorkspaceProvenance(active.id(), active.name()));
            if (collector != null) {
                collector.ensureChannel(CandidateSignal.VECTOR);
            }
            recordChannelCandidates(lexical, CandidateSignal.LEXICAL, collector);
            recordChannelCandidates(vector, CandidateSignal.VECTOR, collector);
            List<SearchCandidate> fused = fusionRanker.fuse(lexical.items(), vector.items(),
                    limits.candidateLimit());
            SearchCandidatePage fusedPage = new SearchCandidatePage(fused, 0,
                    limits.candidateLimit(), fused.size());
            return assembleEvidence(request, active, fusedPage, RetrievalDiagnostics.hybrid(),
                    collector);
        } catch (VectorCandidateSearchUnavailableException unavailable) {
            recordChannelCandidates(lexical, CandidateSignal.LEXICAL, collector);
            return assembleEvidence(request, active, lexical,
                    RetrievalDiagnostics.degradedHybrid(unavailable.getMessage()), collector);
        }
    }

    /** Records the fusion-input candidates of one modality in candidate order (dedup by identity). */
    private static void recordChannelCandidates(SearchCandidatePage page,
                                                CandidateSignal modality,
                                                RetrievalInspectionCollector collector) {
        if (collector == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (SearchCandidate candidate : page.items().stream().sorted(CANDIDATE_ORDER).toList()) {
            String identity = candidate.kind().name() + ":" + candidate.stableId();
            if (seen.add(identity)) {
                collector.channelCandidate(modality, identity);
            }
        }
    }

    private SearchCandidatePage findLexicalCandidates(RetrievalRequest request,
                                                       RetrievalBudgetPolicy.ResolvedBudget limits) {
        try {
            return searchService.findCandidates(new SearchQuery(
                    request.query(), request.corpus(), null, null,
                    0, limits.candidateLimit()));
        } catch (DataAccessException infrastructureFailure) {
            throw new RetrievalUnavailableException(
                    RetrievalUnavailableException.Dependency.SEARCH_INDEX,
                    infrastructureFailure);
        }
    }

    private WorkspaceResponse activeWorkspace() {
        try {
            return workspaceService.findActiveWithoutValidation()
                    .orElseThrow(NoActiveWorkspaceException::new);
        } catch (DataAccessException infrastructureFailure) {
            throw new RetrievalUnavailableException(
                    RetrievalUnavailableException.Dependency.WORKSPACE_AUTHORITY,
                    infrastructureFailure);
        }
    }

    /** Testable boundary that also makes the Search-to-authority revalidation race explicit. */
    EvidenceBundle assembleEvidence(RetrievalRequest request, WorkspaceResponse active,
                                    SearchCandidatePage page) {
        RetrievalDiagnostics diagnostics = switch (request.strategy()) {
            case LEXICAL -> RetrievalDiagnostics.lexical();
            case SEMANTIC -> RetrievalDiagnostics.semantic();
            case HYBRID -> RetrievalDiagnostics.hybrid();
            case FUSED -> RetrievalDiagnostics.fused();
        };
        return assembleEvidence(request, active, page, diagnostics);
    }

    EvidenceBundle assembleEvidence(RetrievalRequest request, WorkspaceResponse active,
                                    SearchCandidatePage page, RetrievalDiagnostics diagnostics) {
        return assembleEvidence(request, active, page, diagnostics, null);
    }

    EvidenceBundle assembleEvidence(RetrievalRequest request, WorkspaceResponse active,
                                    SearchCandidatePage page, RetrievalDiagnostics diagnostics,
                                    RetrievalInspectionCollector collector) {
        RetrievalBudgetPolicy.ResolvedBudget limits = RetrievalBudgetPolicy.resolve(request);
        EvidenceWorkspace workspace = new EvidenceWorkspace(active.id(), active.name());
        List<SearchCandidate> ordered = page.items().stream().sorted(CANDIDATE_ORDER).toList();

        List<EvidenceItem> evidence = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        Map<Long, Optional<SourceSearchAuthorityDocument>> sourceDocuments = new HashMap<>();
        int usedCharacters = 0;
        int rejected = 0;
        boolean budgetTruncated = false;

        for (int index = 0; index < ordered.size(); index++) {
            SearchCandidate candidate = ordered.get(index);
            String identity = candidate.kind().name() + ":" + candidate.stableId();
            if (!identities.add(identity)) {
                if (collector != null) {
                    collector.duplicateFolded(identity);
                }
                continue;
            }
            if (evidence.size() >= limits.maxItems() || usedCharacters >= limits.maxCharacters()) {
                budgetTruncated = true;
                if (collector != null) {
                    collector.budgetExcluded(identity);
                }
                break;
            }

            RevalidationOutcome outcome = authorityRevalidator
                    .revalidate(candidate, active.id(), sourceDocuments);
            if (outcome.wasRejected()) {
                rejected++;
                if (collector != null) {
                    collector.rejected(identity, outcome.rejectionReason().name());
                }
                continue;
            }

            int remaining = limits.maxCharacters() - usedCharacters;
            CandidateAuthorityRevalidator.BoundedText bounded =
                    CandidateAuthorityRevalidator.bound(outcome.evidence().get().content(),
                            remaining);
            if (bounded.text().isBlank()) {
                budgetTruncated = true;
                if (collector != null) {
                    collector.budgetExcluded(identity);
                }
                break;
            }
            CandidateAuthorityRevalidator.AuthorityEvidence trusted = outcome.evidence().get();
            evidence.add(trusted.toItem(workspace, candidate.score(), candidate.snippet(),
                    bounded.text(), bounded.truncated()));
            if (collector != null) {
                collector.selected(identity);
            }
            usedCharacters += bounded.characters();
            budgetTruncated |= bounded.truncated();
            if (bounded.truncated() || evidence.size() >= limits.maxItems()) {
                budgetTruncated |= hasFurtherUniqueCandidate(ordered, index + 1, identities);
                break;
            }
        }

        EvidenceBudget budget = new EvidenceBudget(limits.maxItems(), limits.maxCharacters(),
                evidence.size(), usedCharacters, (usedCharacters + 3) / 4, budgetTruncated);
        return new EvidenceBundle(request.query().strip(), request.mode(), workspace, evidence,
                budget, ordered.size(), rejected, evidence.isEmpty(), diagnostics);
    }

    private static boolean hasFurtherUniqueCandidate(List<SearchCandidate> candidates,
                                                     int fromIndex,
                                                     Set<String> seen) {
        for (int index = fromIndex; index < candidates.size(); index++) {
            SearchCandidate candidate = candidates.get(index);
            if (!seen.contains(candidate.kind().name() + ":" + candidate.stableId())) {
                return true;
            }
        }
        return false;
    }
}
