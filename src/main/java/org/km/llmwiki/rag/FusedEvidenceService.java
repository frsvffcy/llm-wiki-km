package org.km.llmwiki.rag;

import org.jooq.exception.DataAccessException;
import org.km.llmwiki.graph.GraphEntityIdentity;
import org.km.llmwiki.graph.GraphAuthorityKind;
import org.km.llmwiki.graph.GraphAuthorityReference;
import org.km.llmwiki.graph.GraphEntityType;
import org.km.llmwiki.graph.GraphProjectionReadinessReader;
import org.km.llmwiki.graph.GraphProjectionSnapshot;
import org.km.llmwiki.graph.GraphProjectionVerification;
import org.km.llmwiki.graph.GraphSnapshotCurrentness;
import org.km.llmwiki.graph.GraphTraversalBounds;
import org.km.llmwiki.graph.GraphTraversalOrdering;
import org.km.llmwiki.graph.GraphTraversalQuery;
import org.km.llmwiki.graph.GraphTraversalResult;
import org.km.llmwiki.graph.GraphTraversalService;
import org.km.llmwiki.graph.GraphWorkspaceScope;
import org.km.llmwiki.search.SearchCandidate;
import org.km.llmwiki.search.SearchCorpus;
import org.km.llmwiki.search.SearchQuery;
import org.km.llmwiki.search.SearchService;
import org.km.llmwiki.search.SearchWorkspaceProvenance;
import org.km.llmwiki.search.SourceSearchAuthorityDocument;
import org.km.llmwiki.search.SourceSearchAuthorityRepository;
import org.km.llmwiki.search.vector.VectorCandidateSearchQuery;
import org.km.llmwiki.search.vector.VectorCandidateSearchService;
import org.km.llmwiki.search.vector.VectorCandidateSearchUnavailableException;
import org.km.llmwiki.wiki.PublishedWikiContentReader;
import org.km.llmwiki.wiki.PublishedWikiRepository;
import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceResponse;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Application-owned deterministic fusion of the lexical, vector, and graph evidence channels.
 *
 * <p>Each channel produces authority-currentness-qualified evidence: lexical and vector
 * candidates pass the shared {@link CandidateAuthorityRevalidator} contract, graph candidates
 * pass the STORY-807 admission boundary. Fusion itself never adds raw cross-scale scores: final
 * ranking is identity-level reciprocal rank fusion over per-channel ranks with an
 * application-owned tie-break. Duplicates collapse onto one canonical evidence identity,
 * modality hits cannot amplify the global budget, and a terminal publication guard re-checks
 * every selected item (plus the graph projection snapshot when the graph channel participated)
 * before the result may leave this boundary. Degraded modalities are typed diagnostics; one
 * modality failing never invalidates the others, and infrastructure failures are never reported
 * as insufficient evidence.
 */
@Service
public class FusedEvidenceService {

    private static final GraphTraversalBounds GRAPH_BOUNDS =
            new GraphTraversalBounds(2, 8, 16, 32, 32, 16);
    private static final Set<org.km.llmwiki.graph.GraphRelationType> ADMITTED_RELATIONS =
            EnumSet.of(org.km.llmwiki.graph.GraphRelationType.CONTAINS,
                    org.km.llmwiki.graph.GraphRelationType.LINKS_TO,
                    org.km.llmwiki.graph.GraphRelationType.TAGGED_WITH,
                    org.km.llmwiki.graph.GraphRelationType.DERIVED_FROM);

    private final WorkspaceService workspaceService;
    private final SearchService searchService;
    private final VectorCandidateSearchService vectorCandidateSearchService;
    private final CandidateAuthorityRevalidator authorityRevalidator;
    private final GraphProjectionReadinessReader graphReadiness;
    private final GraphTraversalService graphTraversalService;
    private final GraphEvidenceAdmissionService graphAdmissionService;
    private final FusionRankingPolicy rankingPolicy;

    @org.springframework.beans.factory.annotation.Autowired
    public FusedEvidenceService(WorkspaceService workspaceService,
                                SearchService searchService,
                                VectorCandidateSearchService vectorCandidateSearchService,
                                PublishedWikiRepository publishedWikiRepository,
                                PublishedWikiContentReader publishedWikiContentReader,
                                SourceSearchAuthorityRepository sourceAuthorityRepository,
                                GraphProjectionReadinessReader graphReadiness,
                                GraphTraversalService graphTraversalService,
                                GraphEvidenceAdmissionService graphAdmissionService,
                                FusionRankingPolicyProvider rankingPolicyProvider) {
        this(workspaceService, searchService, vectorCandidateSearchService,
                publishedWikiRepository, publishedWikiContentReader, sourceAuthorityRepository,
                graphReadiness, graphTraversalService, graphAdmissionService,
                rankingPolicyProvider.policy());
    }

    public FusedEvidenceService(WorkspaceService workspaceService,
                                SearchService searchService,
                                VectorCandidateSearchService vectorCandidateSearchService,
                                PublishedWikiRepository publishedWikiRepository,
                                PublishedWikiContentReader publishedWikiContentReader,
                                SourceSearchAuthorityRepository sourceAuthorityRepository,
                                GraphProjectionReadinessReader graphReadiness,
                                GraphTraversalService graphTraversalService,
                                GraphEvidenceAdmissionService graphAdmissionService) {
        this(workspaceService, searchService, vectorCandidateSearchService,
                publishedWikiRepository, publishedWikiContentReader, sourceAuthorityRepository,
                graphReadiness, graphTraversalService, graphAdmissionService,
                FusionRankingPolicy.production());
    }

    FusedEvidenceService(WorkspaceService workspaceService,
                         SearchService searchService,
                         VectorCandidateSearchService vectorCandidateSearchService,
                         PublishedWikiRepository publishedWikiRepository,
                         PublishedWikiContentReader publishedWikiContentReader,
                         SourceSearchAuthorityRepository sourceAuthorityRepository,
                         GraphProjectionReadinessReader graphReadiness,
                         GraphTraversalService graphTraversalService,
                         GraphEvidenceAdmissionService graphAdmissionService,
                         FusionRankingPolicy rankingPolicy) {
        this.workspaceService = workspaceService;
        this.searchService = searchService;
        this.vectorCandidateSearchService = vectorCandidateSearchService;
        this.authorityRevalidator = new CandidateAuthorityRevalidator(publishedWikiRepository,
                publishedWikiContentReader, sourceAuthorityRepository);
        this.graphReadiness = graphReadiness;
        this.graphTraversalService = graphTraversalService;
        this.graphAdmissionService = graphAdmissionService;
        this.rankingPolicy = rankingPolicy == null ? FusionRankingPolicy.production() : rankingPolicy;
    }

    public FusedEvidenceResult fuse(FusedEvidenceRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Fusion request is required");
        }
        RetrievalBudgetPolicy.ResolvedBudget limits =
                RetrievalBudgetPolicy.resolve(request.maxItems(), request.maxCharacters());
        WorkspaceResponse active;
        try {
            active = workspaceService.findActiveWithoutValidation()
                    .orElseThrow(NoActiveWorkspaceException::new);
        } catch (DataAccessException infrastructureFailure) {
            throw new RetrievalUnavailableException(
                    RetrievalUnavailableException.Dependency.WORKSPACE_AUTHORITY,
                    infrastructureFailure);
        }
        EvidenceWorkspace workspace = new EvidenceWorkspace(active.id(), active.name());
        long workspaceId = active.id();

        // Lexical baseline: infrastructure failure is typed, never a silent empty fusion.
        List<SearchCandidate> lexicalCandidates;
        try {
            lexicalCandidates = List.copyOf(searchService.findCandidates(new SearchQuery(
                    request.query(), SearchCorpus.ALL, null, null, 0,
                    limits.candidateLimit())).items());
        } catch (DataAccessException infrastructureFailure) {
            throw new RetrievalUnavailableException(
                    RetrievalUnavailableException.Dependency.SEARCH_INDEX,
                    infrastructureFailure);
        }

        // Vector channel: typed unavailability keeps the lexical baseline working.
        List<SearchCandidate> vectorCandidates = List.of();
        ModalityOutcome vectorOutcome = ModalityOutcome.EMPTY;
        String vectorDetail = null;
        try {
            vectorCandidates = List.copyOf(vectorCandidateSearchService.findCandidates(
                    new VectorCandidateSearchQuery(request.query(), SearchCorpus.ALL,
                            limits.candidateLimit()),
                    new SearchWorkspaceProvenance(active.id(), active.name())).items());
        } catch (VectorCandidateSearchUnavailableException unavailable) {
            vectorOutcome = ModalityOutcome.UNAVAILABLE;
            vectorDetail = unavailable.getMessage();
        }

        Map<Long, Optional<SourceSearchAuthorityDocument>> channelSourceCache = new HashMap<>();
        RevalidatedChannel lexical = revalidateChannel(lexicalCandidates, workspaceId,
                channelSourceCache);
        RevalidatedChannel vector = revalidateChannel(vectorCandidates, workspaceId,
                channelSourceCache);
        int rejectedCount = lexical.rejected() + vector.rejected();

        GraphChannel graph = request.includeGraph()
                ? runGraphChannel(active, workspace, limits, lexical, vector)
                : GraphChannel.excluded();
        rejectedCount += graph.rejectedCount();

        Map<CandidateSignal, List<String>> channels = new EnumMap<>(CandidateSignal.class);
        channels.put(CandidateSignal.LEXICAL, lexical.order());
        channels.put(CandidateSignal.VECTOR, vector.order());
        if (!graph.order().isEmpty()) {
            channels.put(CandidateSignal.GRAPH, graph.order());
        }
        List<ModalityRankFusion.FusedIdentity> fused = ModalityRankFusion.fuse(channels, rankingPolicy);

        Map<String, FusionEntry> entries = mergeEntries(lexical, vector, graph, workspace);

        // Budget selection in fused order: global hard caps plus an explicit per-modality
        // contribution cap; duplicate hits and multi-path graph evidence cannot amplify either.
        List<SelectedEntry> selected = new ArrayList<>();
        Map<CandidateSignal, Integer> contributions = new EnumMap<>(CandidateSignal.class);
        Set<String> selectedIdentities = new java.util.HashSet<>();
        int usedCharacters = 0;
        boolean truncated = false;
        for (ModalityRankFusion.FusedIdentity identity : fused) {
            FusionEntry entry = entries.get(identity.identity());
            if (entry == null || !selectedIdentities.add(identity.identity())) {
                continue;
            }
            if (selected.size() >= limits.maxItems()
                    || usedCharacters >= limits.maxCharacters()) {
                truncated = true;
                break;
            }
            CandidateSignal primary = primaryModality(entry.modalities());
            if (contributions.getOrDefault(primary, 0) >= limits.maxItems()) {
                continue;
            }
            contributions.merge(primary, 1, Integer::sum);
            int remaining = limits.maxCharacters() - usedCharacters;
            CandidateAuthorityRevalidator.BoundedText bounded =
                    CandidateAuthorityRevalidator.bound(entry.item().content(), remaining);
            if (bounded.text().isBlank()) {
                continue;
            }
            boolean contentTruncated = entry.contentTruncated() || bounded.truncated();
            EvidenceItem item = rebuilt(entry.item(), identity.score(), entry.snippet(),
                    bounded.text(), contentTruncated);
            selected.add(new SelectedEntry(item, entry.modalities(), bounded.characters()));
            usedCharacters += bounded.characters();
            if (bounded.truncated()) {
                truncated = true;
                break;
            }
        }

        // Terminal publication guard: channel-time currentness does not survive to publication
        // automatically. Drift drops the affected items without silent substitution. The graph
        // projection check runs first so projection drift is diagnosed even when per-item
        // canonical checks would otherwise drop the graph-derived items earlier.
        ModalityOutcome graphOutcome = graph.outcome();
        String graphDetail = graph.detail();
        int terminalRejected = 0;
        if (graph.snapshot() != null
                && (graphOutcome == ModalityOutcome.CONTRIBUTED || !graph.order().isEmpty())) {
            try {
                GraphSnapshotCurrentness.requireCurrent(
                        graphReadiness.readiness(new GraphWorkspaceScope(workspaceId)),
                        graph.snapshot(), true);
            } catch (RuntimeException failure) {
                // Terminal graph currentness cannot be re-proven: graph-only evidence loses its
                // only validity chain and must not be published; cross-modality items keep
                // their independent lexical/vector proof, which the per-item canonical check
                // below still verifies. Operational failures degrade the graph modality only;
                // integrity violations fail closed typed and programming defects propagate.
                GraphRetrievalFailurePolicy.NormalizedFailure normalized =
                        GraphRetrievalFailurePolicy.normalize(failure);
                switch (normalized.verdict()) {
                    case FAIL_CLOSED ->
                            throw GraphRetrievalFailurePolicy.typedFailure(failure);
                    case PROPAGATE -> throw failure;
                    case DEGRADE -> {
                        graphOutcome = normalized.outcome();
                        graphDetail = "terminal graph currentness check failed: "
                                + normalized.detail();
                    }
                }
                var graphIterator = selected.iterator();
                while (graphIterator.hasNext()) {
                    SelectedEntry entry = graphIterator.next();
                    if (entry.modalities().equals(EnumSet.of(CandidateSignal.GRAPH))) {
                        graphIterator.remove();
                        usedCharacters -= entry.characters();
                        terminalRejected++;
                    }
                }
            }
        }
        Map<Long, Optional<SourceSearchAuthorityDocument>> terminalSourceCache = new HashMap<>();
        var iterator = selected.iterator();
        while (iterator.hasNext()) {
            SelectedEntry entry = iterator.next();
            if (!authorityRevalidator.publicationCurrent(entry.item(), workspaceId,
                    terminalSourceCache)) {
                iterator.remove();
                usedCharacters -= entry.characters();
                terminalRejected++;
            }
        }

        ModalityOutcome lexicalOutcome = outcome(lexical.pairs().size());
        if (vectorOutcome == ModalityOutcome.EMPTY) {
            vectorOutcome = outcome(vector.pairs().size());
        }
        List<EvidenceItem> items = selected.stream().map(SelectedEntry::item).toList();
        // Modality provenance and the admitted graph snapshot are diagnostics for the Ask-facing
        // handoff guard; they never become citation identity, authority, or ranking input.
        Map<String, Set<CandidateSignal>> itemModalities = new java.util.LinkedHashMap<>();
        for (SelectedEntry entry : selected) {
            itemModalities.put(entry.item().stableIdentity(),
                    Set.copyOf(entry.modalities()));
        }
        return new FusedEvidenceResult(request.query(), workspace, items,
                new EvidenceBudget(limits.maxItems(), limits.maxCharacters(), items.size(),
                        usedCharacters, (usedCharacters + 3) / 4, truncated),
                lexicalCandidates.size() + vectorCandidates.size() + graph.candidateCount(),
                rejectedCount, items.isEmpty(), graph.snapshot(),
                Map.copyOf(itemModalities),
                new FusedModalityDiagnostics(lexicalOutcome, vectorOutcome, graphOutcome,
                        vectorDetail, graphDetail, terminalRejected));
    }

    private RevalidatedChannel revalidateChannel(List<SearchCandidate> candidates,
                                                 long workspaceId,
                                                 Map<Long, Optional<SourceSearchAuthorityDocument>>
                                                         sourceCache) {
        List<RevalidatedPair> pairs = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        int rejected = 0;
        for (SearchCandidate candidate : candidates) {
            String identity = candidate.kind().name() + ":" + candidate.stableId();
            if (!seen.add(identity)) {
                continue;
            }
            Optional<CandidateAuthorityRevalidator.AuthorityEvidence> revalidated =
                    authorityRevalidator.revalidate(candidate, workspaceId, sourceCache);
            if (revalidated.isEmpty()) {
                rejected++;
                continue;
            }
            pairs.add(new RevalidatedPair(identity, candidate, revalidated.get()));
        }
        List<String> order = pairs.stream().map(RevalidatedPair::identity).toList();
        return new RevalidatedChannel(pairs, order, rejected);
    }

    private GraphChannel runGraphChannel(WorkspaceResponse active, EvidenceWorkspace workspace,
                                         RetrievalBudgetPolicy.ResolvedBudget limits,
                                         RevalidatedChannel lexical, RevalidatedChannel vector) {
        GraphProjectionVerification readiness;
        try {
            readiness = graphReadiness.readiness(new GraphWorkspaceScope(active.id()));
        } catch (RuntimeException readinessFailure) {
            return graphChannelFailure(readinessFailure);
        }
        if (!readiness.ready()) {
            return GraphChannel.failed(classify(readiness), statusDetail(readiness));
        }
        GraphProjectionSnapshot snapshot = readiness.controlPlane() == null
                ? null : readiness.controlPlane().appliedSnapshot();
        if (snapshot == null) {
            return GraphChannel.failed(ModalityOutcome.UNAVAILABLE,
                    "readiness control plane has no applied snapshot");
        }
        List<GraphEntityIdentity> seeds = graphSeeds(lexical, vector, active.id());
        if (seeds.isEmpty()) {
            return GraphChannel.empty(snapshot);
        }
        try {
            GraphTraversalResult traversal = graphTraversalService.traverse(
                    new GraphTraversalQuery(new GraphWorkspaceScope(active.id()), seeds,
                            ADMITTED_RELATIONS, GRAPH_BOUNDS,
                            GraphTraversalOrdering.DEPTH_SEED_ENTITY_PATH_V1, snapshot));
            GraphEvidenceAdmissionResult admitted = graphAdmissionService.admit(
                    GraphEvidenceAdmissionRequest.of(workspace, traversal));
            List<String> order = admitted.evidenceItems().stream()
                    .map(EvidenceItem::stableIdentity).toList();
            return new GraphChannel(
                    admitted.evidenceItems().isEmpty()
                            ? ModalityOutcome.EMPTY : ModalityOutcome.CONTRIBUTED,
                    null, snapshot, admitted.evidenceItems(), order,
                    admitted.candidateCount(), admitted.rejectedCandidateCount());
        } catch (RuntimeException failure) {
            return graphChannelFailure(failure);
        }
    }

    /**
     * Normalizes one failure observed at the graph channel boundary with the shared failure
     * policy: operational faults degrade the graph modality only, integrity violations fail
     * closed typed, and unrecognized programming defects propagate unchanged.
     */
    private static GraphChannel graphChannelFailure(RuntimeException failure) {
        GraphRetrievalFailurePolicy.NormalizedFailure normalized =
                GraphRetrievalFailurePolicy.normalize(failure);
        return switch (normalized.verdict()) {
            case DEGRADE -> GraphChannel.failed(normalized.outcome(), normalized.detail());
            case FAIL_CLOSED -> throw GraphRetrievalFailurePolicy.typedFailure(failure);
            case PROPAGATE -> throw failure;
        };
    }

    /** Seeds are the fused, authority-revalidated canonical hits; stale seeds simply miss. */
    private List<GraphEntityIdentity> graphSeeds(RevalidatedChannel lexical,
                                                 RevalidatedChannel vector, long workspaceId) {
        // Seed ordering stays on the uniform baseline policy: calibration may re-rank the
        // fused evidence order, but traversal seeding keeps its canonical uniform semantics.
        List<ModalityRankFusion.FusedIdentity> fusedOrder = ModalityRankFusion.fuse(Map.of(
                CandidateSignal.LEXICAL, lexical.order(),
                CandidateSignal.VECTOR, vector.order()), FusionRankingPolicy.baseline());
        Map<String, RevalidatedPair> pairs = new LinkedHashMap<>();
        lexical.pairs().forEach(pair -> pairs.putIfAbsent(pair.identity(), pair));
        vector.pairs().forEach(pair -> pairs.putIfAbsent(pair.identity(), pair));
        GraphWorkspaceScope scope = new GraphWorkspaceScope(workspaceId);
        List<GraphEntityIdentity> seeds = new ArrayList<>();
        for (ModalityRankFusion.FusedIdentity fused : fusedOrder) {
            if (seeds.size() >= GraphTraversalQuery.HARD_MAX_SEEDS) {
                break;
            }
            RevalidatedPair pair = pairs.get(fused.identity());
            if (pair == null) {
                continue;
            }
            GraphEntityIdentity seed = seedIdentity(scope, pair.candidate());
            if (seed != null) {
                seeds.add(seed);
            }
        }
        return List.copyOf(seeds);
    }

    private static GraphEntityIdentity seedIdentity(GraphWorkspaceScope scope,
                                                    SearchCandidate candidate) {
        try {
            return switch (candidate.kind()) {
                case WIKI -> GraphEntityIdentity.fromAuthority(new GraphAuthorityReference(scope,
                        GraphAuthorityKind.WIKI_PAGE, candidate.stableId()),
                        GraphEntityType.WIKI_PAGE);
                case SOURCE_CHUNK -> candidate.documentId() == null || candidate.chunkNo() == null
                        ? null
                        : GraphEntityIdentity.fromAuthority(new GraphAuthorityReference(scope,
                        GraphAuthorityKind.SOURCE_CHUNK, "document:" + candidate.documentId()
                        + ":chunk:" + candidate.chunkNo()), GraphEntityType.SOURCE_CHUNK);
            };
        } catch (IllegalArgumentException malformedCandidate) {
            return null;
        }
    }

    /** Fixed channel priority for merged evidence material; never modality completion order. */
    private Map<String, FusionEntry> mergeEntries(RevalidatedChannel lexical,
                                                  RevalidatedChannel vector, GraphChannel graph,
                                                  EvidenceWorkspace workspace) {
        Map<String, FusionEntry> entries = new LinkedHashMap<>();
        for (RevalidatedPair pair : lexical.pairs()) {
            entries.put(pair.identity(), FusionEntry.fromAuthority(pair, workspace,
                    CandidateSignal.LEXICAL));
        }
        for (RevalidatedPair pair : vector.pairs()) {
            entries.merge(pair.identity(), FusionEntry.fromAuthority(pair, workspace,
                            CandidateSignal.VECTOR),
                    (existing, duplicate) -> existing.withSignal(CandidateSignal.VECTOR));
        }
        for (EvidenceItem item : graph.items()) {
            entries.merge(item.stableIdentity(), FusionEntry.fromGraph(item),
                    (existing, duplicate) -> existing.withSignal(CandidateSignal.GRAPH));
        }
        return Map.copyOf(entries);
    }

    private static CandidateSignal primaryModality(EnumSet<CandidateSignal> modalities) {
        return modalities.stream().min(Enum::compareTo).orElseThrow();
    }

    private static ModalityOutcome outcome(int revalidatedCount) {
        return revalidatedCount > 0 ? ModalityOutcome.CONTRIBUTED : ModalityOutcome.EMPTY;
    }

    private static ModalityOutcome classify(GraphProjectionVerification verification) {
        return switch (verification.status()) {
            case DISABLED -> ModalityOutcome.DISABLED;
            case NOT_CONFIGURED -> ModalityOutcome.UNAVAILABLE;
            case BUILDING, REPAIRING, CLEARING, NOT_READY -> ModalityOutcome.NOT_READY;
            case STALE -> ModalityOutcome.DEGRADED;
            case PROJECTION_INCOMPATIBLE, BACKEND_UNAVAILABLE, REPAIR_REQUIRED ->
                    ModalityOutcome.UNAVAILABLE;
            case READY -> ModalityOutcome.CONTRIBUTED;
        };
    }

    private static String statusDetail(GraphProjectionVerification verification) {
        return verification.failure() == null
                ? verification.status().name()
                : verification.failure().type().publicCode();
    }

    private static EvidenceItem rebuilt(EvidenceItem item, double score, String snippet,
                                        String boundedContent, boolean truncated) {
        return new EvidenceItem(item.kind(), item.stableId(), item.workspace(), score,
                boundedContent, snippet, truncated, item.contentHash(), item.knowledgeId(),
                item.title(), item.pageType(), item.path(), item.revision(),
                item.sourceChunkId(), item.documentId(), item.documentName(), item.chunkNo(),
                item.pageNo(), item.section(), item.headingPath());
    }

    private record RevalidatedPair(String identity, SearchCandidate candidate,
                                   CandidateAuthorityRevalidator.AuthorityEvidence evidence) {
    }

    private record RevalidatedChannel(List<RevalidatedPair> pairs, List<String> order,
                                      int rejected) {
    }

    private record FusionEntry(EvidenceItem item, EnumSet<CandidateSignal> modalities,
                               String snippet, boolean contentTruncated) {

        static FusionEntry fromAuthority(RevalidatedPair pair, EvidenceWorkspace workspace,
                                         CandidateSignal signal) {
            EvidenceItem unbounded = pair.evidence().toItem(workspace, 0.0d,
                    pair.candidate().snippet(), pair.evidence().content(), false);
            return new FusionEntry(unbounded, EnumSet.of(signal), pair.candidate().snippet(),
                    false);
        }

        static FusionEntry fromGraph(EvidenceItem item) {
            return new FusionEntry(item, EnumSet.of(CandidateSignal.GRAPH), null,
                    item.contentTruncated());
        }

        FusionEntry withSignal(CandidateSignal signal) {
            EnumSet<CandidateSignal> merged = EnumSet.copyOf(modalities);
            merged.add(signal);
            return new FusionEntry(item, merged, snippet, contentTruncated);
        }
    }

    private record SelectedEntry(EvidenceItem item, EnumSet<CandidateSignal> modalities,
                                 int characters) {
    }

    private record GraphChannel(ModalityOutcome outcome, String detail,
                                GraphProjectionSnapshot snapshot, List<EvidenceItem> items,
                                List<String> order, int candidateCount, int rejectedCount) {

        private GraphChannel {
            items = List.copyOf(items);
            order = List.copyOf(order);
        }

        static GraphChannel failed(ModalityOutcome outcome, String detail) {
            return new GraphChannel(outcome, detail, null, List.of(), List.of(), 0, 0);
        }

        static GraphChannel excluded() {
            return failed(ModalityOutcome.DISABLED, "graph channel excluded by request");
        }

        static GraphChannel empty(GraphProjectionSnapshot snapshot) {
            return new GraphChannel(ModalityOutcome.EMPTY, null, snapshot, List.of(), List.of(),
                    0, 0);
        }
    }
}
