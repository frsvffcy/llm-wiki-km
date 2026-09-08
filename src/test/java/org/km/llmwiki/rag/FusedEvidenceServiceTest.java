package org.km.llmwiki.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.km.llmwiki.graph.GraphEntityIdentity;
import org.km.llmwiki.graph.GraphProjectionBackendProof;
import org.km.llmwiki.graph.GraphProjectionException;
import org.km.llmwiki.graph.GraphProjectionFailure;
import org.km.llmwiki.graph.GraphProjectionFailureType;
import org.km.llmwiki.graph.GraphProjectionReadiness;
import org.km.llmwiki.graph.GraphProjectionReadinessReader;
import org.km.llmwiki.graph.GraphProjectionReadinessStatus;
import org.km.llmwiki.graph.GraphProjectionSnapshot;
import org.km.llmwiki.graph.GraphProjectionVerification;
import org.km.llmwiki.graph.GraphProjectionVerificationStatus;
import org.km.llmwiki.graph.GraphProjectionVersion;
import org.km.llmwiki.graph.GraphRelationType;
import org.km.llmwiki.graph.GraphTraversalBackend;
import org.km.llmwiki.graph.GraphTraversalBackendFactory;
import org.km.llmwiki.graph.GraphTraversalQuery;
import org.km.llmwiki.graph.GraphTraversalResult;
import org.km.llmwiki.graph.GraphTraversalService;
import org.km.llmwiki.graph.GraphWorkspaceScope;
import org.km.llmwiki.search.SearchCandidate;
import org.km.llmwiki.search.SearchCandidatePage;
import org.km.llmwiki.search.SearchResultKind;
import org.km.llmwiki.search.SearchService;
import org.km.llmwiki.search.SearchWorkspaceProvenance;
import org.km.llmwiki.search.SourceSearchAuthorityChunk;
import org.km.llmwiki.search.SourceSearchAuthorityDocument;
import org.km.llmwiki.search.SourceSearchAuthorityRepository;
import org.km.llmwiki.search.SourceSearchFreshness;
import org.km.llmwiki.search.vector.VectorCandidateSearchQuery;
import org.km.llmwiki.search.vector.VectorCandidateSearchService;
import org.km.llmwiki.search.vector.VectorCandidateSearchUnavailableException;
import org.km.llmwiki.wiki.PublishedWikiContentReader;
import org.km.llmwiki.wiki.PublishedWikiRepository;
import org.km.llmwiki.wiki.StoredPublishedWiki;
import org.km.llmwiki.wiki.WikiPageType;
import org.km.llmwiki.workspace.WorkspaceResponse;
import org.km.llmwiki.workspace.WorkspaceService;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * Deterministic adversarial evidence for three-modality fusion: race cases use explicit two-phase
 * stubbing and controlled doubles, never sleep or timing luck.
 */
@Tag("unit")
class FusedEvidenceServiceTest {

    private static final long WORKSPACE_ID = 41L;
    private static final GraphWorkspaceScope WORKSPACE_SCOPE = new GraphWorkspaceScope(41);
    private static final GraphProjectionVersion VERSION = GraphProjectionVersion.initial();
    private static final GraphProjectionSnapshot SNAPSHOT_A = GraphProjectionSnapshot.fromProof(
            WORKSPACE_SCOPE, VERSION, 7, "a".repeat(64));
    private static final GraphProjectionSnapshot SNAPSHOT_B = GraphProjectionSnapshot.fromProof(
            WORKSPACE_SCOPE, VERSION, 8, "b".repeat(64));
    private static final String NOW = "2026-09-08T00:00:00Z";

    private WorkspaceService workspaceService;
    private SearchService searchService;
    private VectorCandidateSearchService vectorCandidateSearchService;
    private PublishedWikiRepository wikiRepository;
    private PublishedWikiContentReader wikiContentReader;
    private SourceSearchAuthorityRepository sourceRepository;
    private GraphProjectionReadinessReader graphReadiness;
    private GraphTraversalBackend graphBackend;
    private GraphTraversalService graphTraversalService;
    private GraphEvidenceAdmissionService graphAdmissionService;
    private FusedEvidenceService service;

    @BeforeEach
    void setUp() {
        workspaceService = Mockito.mock(WorkspaceService.class);
        searchService = Mockito.mock(SearchService.class);
        vectorCandidateSearchService = Mockito.mock(VectorCandidateSearchService.class);
        wikiRepository = Mockito.mock(PublishedWikiRepository.class);
        wikiContentReader = Mockito.mock(PublishedWikiContentReader.class);
        sourceRepository = Mockito.mock(SourceSearchAuthorityRepository.class);
        graphReadiness = Mockito.mock(GraphProjectionReadinessReader.class);
        graphAdmissionService = Mockito.mock(GraphEvidenceAdmissionService.class);
        // GraphTraversalService is a final class: drive a real instance through mock ports.
        graphBackend = Mockito.mock(GraphTraversalBackend.class);
        GraphTraversalBackendFactory graphBackendFactory =
                Mockito.mock(GraphTraversalBackendFactory.class);
        Mockito.when(graphBackendFactory.projectionVersion()).thenReturn(VERSION);
        Mockito.when(graphBackendFactory.openTraversal(any()))
                .thenReturn(Optional.of(graphBackend));
        Mockito.when(graphBackend.readProof(any())).thenReturn(
                new GraphProjectionBackendProof(WORKSPACE_SCOPE, SNAPSHOT_A, null));
        // A backend result must stay invariant-consistent with the query it is answering;
        // visitedNodeCount below the seed count is an impossible result and fails closed.
        Mockito.when(graphBackend.traverse(any())).thenAnswer(invocation -> {
            GraphTraversalQuery query = invocation.getArgument(0);
            int seedCount = query == null ? 1 : query.seeds().size();
            return new GraphTraversalResult(SNAPSHOT_A, List.of(), Math.max(1, seedCount), 0,
                    Set.of());
        });
        graphTraversalService = new GraphTraversalService(graphReadiness, graphBackendFactory);
        service = new FusedEvidenceService(workspaceService, searchService,
                vectorCandidateSearchService, wikiRepository, wikiContentReader,
                sourceRepository, graphReadiness, graphTraversalService, graphAdmissionService);

        Mockito.when(workspaceService.findActiveWithoutValidation())
                .thenReturn(Optional.of(workspace()));
        Mockito.when(searchService.findCandidates(any()))
                .thenReturn(page(List.of()));
        Mockito.when(vectorCandidateSearchService.findCandidates(any(), any()))
                .thenReturn(page(List.of()));
        Mockito.when(graphReadiness.readiness(any())).thenReturn(ready(SNAPSHOT_A));
        Mockito.when(graphAdmissionService.admit(any())).thenReturn(admission(SNAPSHOT_A));
    }

    @Test
    void fusedOrderFollowsReciprocalRanksNeverRawScores() {
        // The channel contract delivers rank-ordered candidates; raw score scale is ignored by
        // fusion. wiki-weak carries score 0.001 at rank 1 and still wins over a score-1e6 hit.
        SearchCandidate weakScoreTopRank = wikiCandidate("wiki-weak", 0.001d, 1);
        SearchCandidate strongScoreLowRank = wikiCandidate("wiki-strong", 1_000_000.0d, 1);
        stubLexical(List.of(weakScoreTopRank, strongScoreLowRank));
        Mockito.when(wikiRepository.findPublishedByKnowledgeId(anyLong(), anyString()))
                .thenAnswer(invocation -> Optional.of(wikiPage(
                        invocation.getArgument(1), 1, sha256("v1"))));
        Mockito.when(wikiContentReader.readSearchableContent(any())).thenReturn("content");

        FusedEvidenceResult result = service.fuse(FusedEvidenceRequest.of("query"));

        assertThat(result.items()).extracting(EvidenceItem::stableId)
                .containsExactly("wiki-weak", "wiki-strong");
        assertThat(result.items().get(0).score()).isEqualTo(1.0d / 61);
        assertThat(result.items().get(1).score()).isEqualTo(1.0d / 62);
    }

    @Test
    void crossModalityHitsCollapseOntoOneCanonicalEvidenceIdentity() {
        SearchCandidate lexical = wikiCandidate("wiki-shared", 1.0d, 3);
        stubLexical(List.of(lexical));
        stubVector(List.of(lexical));
        Mockito.when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE_ID, "wiki-shared"))
                .thenReturn(Optional.of(wikiPage("wiki-shared", 3, sha256("v3"))));
        Mockito.when(wikiContentReader.readSearchableContent(any())).thenReturn("shared content");
        Mockito.when(graphAdmissionService.admit(any())).thenReturn(admission(SNAPSHOT_A,
                graphItem("wiki-shared", "shared content", sha256("v3"))));

        FusedEvidenceResult result = service.fuse(FusedEvidenceRequest.of("query"));

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().stableIdentity()).isEqualTo("WIKI:wiki-shared");
        assertThat(result.diagnostics().lexical()).isEqualTo(ModalityOutcome.CONTRIBUTED);
        assertThat(result.diagnostics().vector()).isEqualTo(ModalityOutcome.CONTRIBUTED);
        assertThat(result.diagnostics().graph()).isEqualTo(ModalityOutcome.CONTRIBUTED);
        assertThat(result.searchedCandidateCount()).isEqualTo(3);
    }

    @Test
    void terminalGraphProjectionDriftDropsGraphEvidenceAndKeepsTheBaseline() {
        stubLexical(List.of(wikiCandidate("wiki-lexical", 1.0d, 1)));
        Mockito.when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE_ID, "wiki-lexical"))
                .thenReturn(Optional.of(wikiPage("wiki-lexical", 1, sha256("v1"))));
        Mockito.when(wikiContentReader.readSearchableContent(any())).thenReturn("lexical content");
        Mockito.when(graphAdmissionService.admit(any())).thenReturn(admission(SNAPSHOT_A,
                graphItem("wiki-graph", "graph content", sha256("v1"))));
        // Deterministic barrier with an exact readiness call sequence: channel check, traversal
        // pre-check, traversal post-check, then the terminal publication guard sees generation B.
        Mockito.when(graphReadiness.readiness(any()))
                .thenReturn(ready(SNAPSHOT_A), ready(SNAPSHOT_A), ready(SNAPSHOT_A),
                        ready(SNAPSHOT_B));

        FusedEvidenceResult result = service.fuse(FusedEvidenceRequest.of("query"));

        assertThat(result.items()).extracting(EvidenceItem::stableId)
                .containsExactly("wiki-lexical");
        assertThat(result.diagnostics().graph()).isEqualTo(ModalityOutcome.DEGRADED);
        assertThat(result.diagnostics().graphDetail()).contains("terminal");
        assertThat(result.diagnostics().terminalRejectedCount()).isEqualTo(1);
        assertThat(result.diagnostics().lexical()).isEqualTo(ModalityOutcome.CONTRIBUTED);
    }

    @Test
    void terminalCanonicalRevisionDriftDropsStaleWikiEvidence() {
        stubLexical(List.of(wikiCandidate("wiki-drift", 1.0d, 3)));
        // Channel revalidation sees revision 3; the canonical revision moves to 4 before the
        // terminal publication guard runs, so the stale evidence is dropped, not published.
        Mockito.when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE_ID, "wiki-drift"))
                .thenReturn(Optional.of(wikiPage("wiki-drift", 3, sha256("v3"))))
                .thenReturn(Optional.of(wikiPage("wiki-drift", 4, sha256("v4"))));
        Mockito.when(wikiContentReader.readSearchableContent(any())).thenReturn("content");

        FusedEvidenceResult result = service.fuse(FusedEvidenceRequest.of("query"));

        assertThat(result.items()).isEmpty();
        assertThat(result.insufficientEvidence()).isTrue();
        assertThat(result.diagnostics().terminalRejectedCount()).isEqualTo(1);
        assertThat(result.diagnostics().lexical()).isEqualTo(ModalityOutcome.CONTRIBUTED);
    }

    @Test
    void terminalSourceEligibilityDriftDropsStaleSourceEvidence() {
        stubLexical(List.of(sourceCandidate(77L, 9L, 1, sha256("chunk"))));
        Mockito.when(sourceRepository.findDocument(WORKSPACE_ID, 9L)).thenReturn(
                Optional.of(sourceDocument(9L, "PROCESSED",
                        chunk(77L, 1, "chunk", sha256("chunk")))),
                Optional.of(sourceDocument(9L, "PENDING",
                        chunk(77L, 1, "chunk", sha256("chunk")))));

        FusedEvidenceResult result = service.fuse(FusedEvidenceRequest.of("query"));

        assertThat(result.items()).isEmpty();
        assertThat(result.diagnostics().terminalRejectedCount()).isEqualTo(1);
    }

    @Test
    void terminalRejectionNeverSubstitutesLowerRankedCandidates() {
        stubLexical(List.of(wikiCandidate("wiki-a", 3.0d, 1), wikiCandidate("wiki-b", 2.0d, 1),
                wikiCandidate("wiki-c", 1.0d, 1)));
        Mockito.when(wikiRepository.findPublishedByKnowledgeId(anyLong(), anyString()))
                .thenAnswer(invocation -> Optional.of(wikiPage(
                        invocation.getArgument(1), 1, sha256("v1"))));
        Mockito.when(wikiContentReader.readSearchableContent(any())).thenReturn("content");
        // wiki-b passes channel revalidation at revision 1, then drifts to revision 2 before the
        // terminal guard: the budget already closed, so fusion publishes one item instead of
        // silently promoting wiki-c.
        Mockito.when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE_ID, "wiki-b"))
                .thenReturn(Optional.of(wikiPage("wiki-b", 1, sha256("v1"))))
                .thenReturn(Optional.of(wikiPage("wiki-b", 2, sha256("v2"))));

        FusedEvidenceResult result = service.fuse(FusedEvidenceRequest.of("query", 2, null,
                true));

        assertThat(result.items()).extracting(EvidenceItem::stableId)
                .containsExactly("wiki-a");
        assertThat(result.diagnostics().terminalRejectedCount()).isEqualTo(1);
        assertThat(result.budget().truncated()).isTrue();
    }

    @Test
    void graphDisabledAndStaleStatesNeverDragDownTheBaseline() {
        stubLexical(List.of(wikiCandidate("wiki-baseline", 1.0d, 1)));
        Mockito.when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE_ID, "wiki-baseline"))
                .thenReturn(Optional.of(wikiPage("wiki-baseline", 1, sha256("v1"))));
        Mockito.when(wikiContentReader.readSearchableContent(any())).thenReturn("content");

        Mockito.when(graphReadiness.readiness(any())).thenReturn(
                verification(GraphProjectionVerificationStatus.DISABLED, null, null));
        FusedEvidenceResult disabled = service.fuse(FusedEvidenceRequest.of("query"));
        assertThat(disabled.items()).extracting(EvidenceItem::stableId)
                .containsExactly("wiki-baseline");
        assertThat(disabled.diagnostics().graph()).isEqualTo(ModalityOutcome.DISABLED);

        Mockito.when(graphReadiness.readiness(any())).thenReturn(
                verification(GraphProjectionVerificationStatus.STALE, readyControl(SNAPSHOT_A),
                        GraphProjectionFailure.of(GraphProjectionFailureType.PROJECTION_STALE)));
        FusedEvidenceResult stale = service.fuse(FusedEvidenceRequest.of("query"));
        assertThat(stale.items()).extracting(EvidenceItem::stableId)
                .containsExactly("wiki-baseline");
        assertThat(stale.diagnostics().graph()).isEqualTo(ModalityOutcome.DEGRADED);
    }

    @Test
    void graphInfrastructureFailureIsTypedDegradationNotAFusedFailure() {
        stubLexical(List.of(wikiCandidate("wiki-baseline", 1.0d, 1)));
        Mockito.when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE_ID, "wiki-baseline"))
                .thenReturn(Optional.of(wikiPage("wiki-baseline", 1, sha256("v1"))));
        Mockito.when(wikiContentReader.readSearchableContent(any())).thenReturn("content");
        Mockito.when(graphBackend.traverse(any())).thenThrow(new GraphProjectionException(
                GraphProjectionFailureType.BACKEND_LOCKED));

        FusedEvidenceResult result = service.fuse(FusedEvidenceRequest.of("query"));

        assertThat(result.items()).extracting(EvidenceItem::stableId)
                .containsExactly("wiki-baseline");
        assertThat(result.diagnostics().graph()).isEqualTo(ModalityOutcome.UNAVAILABLE);
    }

    @Test
    void initialReadinessInfrastructureFailureDegradesGraphAndKeepsTheBaseline() {
        stubLexical(List.of(wikiCandidate("wiki-baseline", 1.0d, 1)));
        Mockito.when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE_ID, "wiki-baseline"))
                .thenReturn(Optional.of(wikiPage("wiki-baseline", 1, sha256("v1"))));
        Mockito.when(wikiContentReader.readSearchableContent(any())).thenReturn("content");
        Mockito.when(graphReadiness.readiness(any()))
                .thenThrow(new org.jooq.exception.DataAccessException("control plane down"));

        FusedEvidenceResult result = service.fuse(FusedEvidenceRequest.of("query"));

        assertThat(result.items()).extracting(EvidenceItem::stableId)
                .containsExactly("wiki-baseline");
        assertThat(result.diagnostics().graph()).isEqualTo(ModalityOutcome.UNAVAILABLE);
        assertThat(result.diagnostics().graphDetail()).isEqualTo("graph infrastructure failure");
        assertThat(result.diagnostics().lexical()).isEqualTo(ModalityOutcome.CONTRIBUTED);
    }

    @Test
    void traversalReadinessInfrastructureFailureBehavesLikeTheInitialReadinessOne() {
        stubLexical(List.of(wikiCandidate("wiki-baseline", 1.0d, 1)));
        Mockito.when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE_ID, "wiki-baseline"))
                .thenReturn(Optional.of(wikiPage("wiki-baseline", 1, sha256("v1"))));
        Mockito.when(wikiContentReader.readSearchableContent(any())).thenReturn("content");
        // The channel readiness check succeeds; the traversal pre-check control-plane read
        // fails. Both boundaries must normalize identically: typed graph degradation, baseline
        // untouched.
        Mockito.when(graphReadiness.readiness(any()))
                .thenReturn(ready(SNAPSHOT_A))
                .thenThrow(new org.jooq.exception.DataAccessException("control plane down"));

        FusedEvidenceResult result = service.fuse(FusedEvidenceRequest.of("query"));

        assertThat(result.items()).extracting(EvidenceItem::stableId)
                .containsExactly("wiki-baseline");
        assertThat(result.diagnostics().graph()).isEqualTo(ModalityOutcome.UNAVAILABLE);
        assertThat(result.diagnostics().lexical()).isEqualTo(ModalityOutcome.CONTRIBUTED);
    }

    @Test
    void traversalBackendInfrastructureFailureIsTypedDegradation() {
        stubLexical(List.of(wikiCandidate("wiki-baseline", 1.0d, 1)));
        Mockito.when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE_ID, "wiki-baseline"))
                .thenReturn(Optional.of(wikiPage("wiki-baseline", 1, sha256("v1"))));
        Mockito.when(wikiContentReader.readSearchableContent(any())).thenReturn("content");
        Mockito.when(graphBackend.traverse(any())).thenThrow(
                new org.jooq.exception.DataAccessException("backend i/o failure"));

        FusedEvidenceResult result = service.fuse(FusedEvidenceRequest.of("query"));

        assertThat(result.items()).extracting(EvidenceItem::stableId)
                .containsExactly("wiki-baseline");
        assertThat(result.diagnostics().graph()).isEqualTo(ModalityOutcome.UNAVAILABLE);
        assertThat(result.diagnostics().graph()).isNotEqualTo(ModalityOutcome.EMPTY);
    }

    @Test
    void traversalBackendUnexpectedRuntimeDefectPropagatesFailClosed() {
        stubLexical(List.of(wikiCandidate("wiki-baseline", 1.0d, 1)));
        Mockito.when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE_ID, "wiki-baseline"))
                .thenReturn(Optional.of(wikiPage("wiki-baseline", 1, sha256("v1"))));
        Mockito.when(wikiContentReader.readSearchableContent(any())).thenReturn("content");
        IllegalStateException defect = new IllegalStateException("programming defect");
        Mockito.when(graphBackend.traverse(any())).thenThrow(defect);

        assertThatThrownBy(() -> service.fuse(FusedEvidenceRequest.of("query")))
                .isSameAs(defect);
    }

    @Test
    void crossWorkspaceTraversalFailureFailsClosedTypedInsteadOfDegrading() {
        stubLexical(List.of(wikiCandidate("wiki-baseline", 1.0d, 1)));
        Mockito.when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE_ID, "wiki-baseline"))
                .thenReturn(Optional.of(wikiPage("wiki-baseline", 1, sha256("v1"))));
        Mockito.when(wikiContentReader.readSearchableContent(any())).thenReturn("content");
        Mockito.when(graphAdmissionService.admit(any())).thenThrow(new GraphProjectionException(
                GraphProjectionFailureType.CROSS_WORKSPACE));

        assertThatThrownBy(() -> service.fuse(FusedEvidenceRequest.of("query")))
                .isInstanceOfSatisfying(RetrievalUnavailableException.class, failure ->
                        assertThat(failure.dependency())
                                .isEqualTo(RetrievalUnavailableException.Dependency.GRAPH));
    }

    @Test
    void terminalReadinessInfrastructureFailureDropsGraphOnlyEvidenceAndKeepsTheBaseline() {
        stubLexical(List.of(wikiCandidate("wiki-lexical", 1.0d, 1)));
        Mockito.when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE_ID, "wiki-lexical"))
                .thenReturn(Optional.of(wikiPage("wiki-lexical", 1, sha256("v1"))));
        Mockito.when(wikiContentReader.readSearchableContent(any())).thenReturn("lexical content");
        Mockito.when(graphAdmissionService.admit(any())).thenReturn(admission(SNAPSHOT_A,
                graphItem("wiki-graph", "graph content", sha256("v1"))));
        // Channel, traversal pre/post checks all see snapshot A; the terminal publication guard
        // control-plane read fails operationally. Graph-only evidence must not be published,
        // cross-modality/lexical evidence keeps its independent proof, and the failure is a
        // typed degradation instead of crashing the fusion.
        Mockito.when(graphReadiness.readiness(any()))
                .thenReturn(ready(SNAPSHOT_A), ready(SNAPSHOT_A), ready(SNAPSHOT_A))
                .thenThrow(new org.jooq.exception.DataAccessException("control plane down"));

        FusedEvidenceResult result = service.fuse(FusedEvidenceRequest.of("query"));

        assertThat(result.items()).extracting(EvidenceItem::stableId)
                .containsExactly("wiki-lexical");
        assertThat(result.insufficientEvidence()).isFalse();
        assertThat(result.diagnostics().graph()).isEqualTo(ModalityOutcome.UNAVAILABLE);
        assertThat(result.diagnostics().graphDetail()).contains("terminal");
        assertThat(result.diagnostics().terminalRejectedCount()).isEqualTo(1);
        assertThat(result.diagnostics().lexical()).isEqualTo(ModalityOutcome.CONTRIBUTED);
    }

    @Test
    void terminalCorruptProofFailsClosedTypedInsteadOfDegrading() {
        stubLexical(List.of(wikiCandidate("wiki-lexical", 1.0d, 1)));
        Mockito.when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE_ID, "wiki-lexical"))
                .thenReturn(Optional.of(wikiPage("wiki-lexical", 1, sha256("v1"))));
        Mockito.when(wikiContentReader.readSearchableContent(any())).thenReturn("lexical content");
        Mockito.when(graphAdmissionService.admit(any())).thenReturn(admission(SNAPSHOT_A,
                graphItem("wiki-graph", "graph content", sha256("v1"))));
        // Same generation, different fingerprint/token: the terminal guard must classify this
        // as a corrupt proof and fail the whole request closed, never serve graph-only evidence
        // or disguise the corruption as an optional-modality degradation.
        GraphProjectionSnapshot corrupt = GraphProjectionSnapshot.fromProof(WORKSPACE_SCOPE,
                VERSION, SNAPSHOT_A.generation(), "c".repeat(64));
        Mockito.when(graphReadiness.readiness(any()))
                .thenReturn(ready(SNAPSHOT_A), ready(SNAPSHOT_A), ready(SNAPSHOT_A),
                        ready(corrupt));

        assertThatThrownBy(() -> service.fuse(FusedEvidenceRequest.of("query")))
                .isInstanceOfSatisfying(RetrievalUnavailableException.class, failure -> {
                    assertThat(failure.dependency())
                            .isEqualTo(RetrievalUnavailableException.Dependency.GRAPH);
                    assertThat(failure.getCause())
                            .isInstanceOf(GraphProjectionException.class);
                });
    }

    @Test
    void vectorUnavailableKeepsTheLexicalBaselineWorking() {
        stubLexical(List.of(wikiCandidate("wiki-lexical", 1.0d, 1)));
        Mockito.when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE_ID, "wiki-lexical"))
                .thenReturn(Optional.of(wikiPage("wiki-lexical", 1, sha256("v1"))));
        Mockito.when(wikiContentReader.readSearchableContent(any())).thenReturn("content");
        Mockito.when(vectorCandidateSearchService.findCandidates(any(), any()))
                .thenThrow(new VectorCandidateSearchUnavailableException(
                        VectorCandidateSearchUnavailableException.Dependency.VECTOR_CAPABILITY,
                        new IllegalStateException("vector capability disabled")));

        FusedEvidenceResult result = service.fuse(FusedEvidenceRequest.of("query"));

        assertThat(result.items()).extracting(EvidenceItem::stableId)
                .containsExactly("wiki-lexical");
        assertThat(result.diagnostics().vector()).isEqualTo(ModalityOutcome.UNAVAILABLE);
        assertThat(result.diagnostics().vectorDetail()).contains("VECTOR_CAPABILITY");
        assertThat(result.diagnostics().lexical()).isEqualTo(ModalityOutcome.CONTRIBUTED);
    }

    @Test
    void lexicalInfrastructureFailureIsTypedAndNeverInsufficientEvidence() {
        Mockito.when(searchService.findCandidates(any())).thenThrow(
                new org.jooq.exception.DataAccessException("fts down"));

        assertThatThrownBy(() -> service.fuse(FusedEvidenceRequest.of("query")))
                .isInstanceOf(RetrievalUnavailableException.class)
                .extracting(failure -> ((RetrievalUnavailableException) failure).dependency())
                .isEqualTo(RetrievalUnavailableException.Dependency.SEARCH_INDEX);
    }

    @Test
    void crossWorkspaceCandidatesAreRejectedBeforeFusion() {
        SearchCandidate foreign = new SearchCandidate(SearchResultKind.WIKI, "wiki-foreign",
                9.0d, null, new SearchWorkspaceProvenance(42L, "other"), "wiki-foreign",
                "Foreign", "CONCEPT", "vault/concepts/foreign.md", 1, sha256("v1"),
                null, null, null, null, null, null, null, null, null);
        stubLexical(List.of(foreign));

        FusedEvidenceResult result = service.fuse(FusedEvidenceRequest.of("query"));

        assertThat(result.items()).isEmpty();
        assertThat(result.rejectedCandidateCount()).isEqualTo(1);
        assertThat(result.diagnostics().lexical()).isEqualTo(ModalityOutcome.EMPTY);
    }

    @Test
    void duplicateHitsAndMultipathVolumeCannotBypassTheGlobalBudget() {
        List<SearchCandidate> many = new java.util.ArrayList<>();
        for (int index = 1; index <= 10; index++) {
            many.add(wikiCandidate("wiki-" + index, 1.0d, 1));
        }
        many.add(wikiCandidate("wiki-1", 1.0d, 1));
        stubLexical(many);
        Mockito.when(wikiRepository.findPublishedByKnowledgeId(anyLong(), anyString()))
                .thenAnswer(invocation -> Optional.of(wikiPage(
                        invocation.getArgument(1), 1, sha256("v1"))));
        Mockito.when(wikiContentReader.readSearchableContent(any())).thenReturn("content");

        FusedEvidenceResult result = service.fuse(FusedEvidenceRequest.of("query", 3, null,
                true));

        assertThat(result.items()).hasSize(3);
        assertThat(result.budget().usedItems()).isEqualTo(3);
        assertThat(result.budget().truncated()).isTrue();
        assertThat(result.rejectedCandidateCount()).isZero();
    }

    @Test
    void graphTraversalQueryIsRestrictedToTheAdmittedProductionRelationProfile() {
        stubLexical(List.of(wikiCandidate("wiki-seed", 1.0d, 1)));
        Mockito.when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE_ID, "wiki-seed"))
                .thenReturn(Optional.of(wikiPage("wiki-seed", 1, sha256("v1"))));
        Mockito.when(wikiContentReader.readSearchableContent(any())).thenReturn("content");

        service.fuse(FusedEvidenceRequest.of("query"));

        ArgumentCaptor<GraphTraversalQuery> query = ArgumentCaptor
                .forClass(GraphTraversalQuery.class);
        Mockito.verify(graphBackend).traverse(query.capture());
        assertThat(query.getValue().allowedRelationTypes())
                .containsExactlyInAnyOrder(GraphRelationType.CONTAINS,
                        GraphRelationType.LINKS_TO, GraphRelationType.TAGGED_WITH,
                        GraphRelationType.DERIVED_FROM);
        assertThat(query.getValue().allowedRelationTypes())
                .doesNotContain(GraphRelationType.MENTIONS, GraphRelationType.RELATED_TO);
    }

    @Test
    void repeatedFusionWithIdenticalInputsIsDeterministic() {
        stubLexical(List.of(wikiCandidate("wiki-a", 2.0d, 1), wikiCandidate("wiki-b", 1.0d, 1)));
        Mockito.when(wikiRepository.findPublishedByKnowledgeId(anyLong(), anyString()))
                .thenAnswer(invocation -> Optional.of(wikiPage(
                        invocation.getArgument(1), 1, sha256("v1"))));
        Mockito.when(wikiContentReader.readSearchableContent(any())).thenReturn("content");
        Mockito.when(graphAdmissionService.admit(any())).thenReturn(admission(SNAPSHOT_A,
                graphItem("wiki-graph", "graph content", sha256("v1"))));

        FusedEvidenceResult first = service.fuse(FusedEvidenceRequest.of("query"));
        FusedEvidenceResult second = service.fuse(FusedEvidenceRequest.of("query"));

        assertThat(second).isEqualTo(first);
    }

    @Test
    void fusedResultCarriesModalityProvenanceAndTheAdmittedGraphSnapshot() {
        stubLexical(List.of(wikiCandidate("wiki-shared", 1.0d, 3)));
        stubVector(List.of(wikiCandidate("wiki-shared", 0.9d, 3)));
        Mockito.when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE_ID, "wiki-shared"))
                .thenReturn(Optional.of(wikiPage("wiki-shared", 3, sha256("v3"))));
        Mockito.when(wikiContentReader.readSearchableContent(any())).thenReturn("shared content");
        Mockito.when(graphAdmissionService.admit(any())).thenReturn(admission(SNAPSHOT_A,
                graphItem("wiki-shared", "shared content", sha256("v3"))));

        FusedEvidenceResult result = service.fuse(FusedEvidenceRequest.of("query"));

        assertThat(result.itemModalities().keySet()).containsExactly("WIKI:wiki-shared");
        assertThat(result.itemModalities().get("WIKI:wiki-shared"))
                .containsExactlyInAnyOrder(CandidateSignal.LEXICAL, CandidateSignal.VECTOR,
                        CandidateSignal.GRAPH);
        assertThat(result.graphSnapshot()).isEqualTo(SNAPSHOT_A);
        assertThat(result.items()).singleElement()
                .satisfies(item -> assertThat(item.stableIdentity()).isEqualTo("WIKI:wiki-shared"));
    }

    @Test
    void graphSeedsAreCanonicalHardBoundedAndPermutationInvariant() {
        List<SearchCandidate> candidates = new java.util.ArrayList<>();
        for (int index = 1; index <= 20; index++) {
            candidates.add(wikiCandidate("wiki-" + index, 1.0d, 1));
        }
        Mockito.when(wikiRepository.findPublishedByKnowledgeId(anyLong(), anyString()))
                .thenAnswer(invocation -> Optional.of(wikiPage(
                        invocation.getArgument(1), 1, sha256("v1"))));
        Mockito.when(wikiContentReader.readSearchableContent(any())).thenReturn("content");

        // More revalidated candidates than the traversal seed cap: the seed set is hard bounded
        // and every seed is a canonical workspace-scoped authority identity, never a backend row.
        stubLexical(candidates);
        service.fuse(FusedEvidenceRequest.of("query"));
        ArgumentCaptor<GraphTraversalQuery> bounded = ArgumentCaptor
                .forClass(GraphTraversalQuery.class);
        Mockito.verify(graphBackend, Mockito.times(1)).traverse(bounded.capture());
        assertThat(bounded.getValue().seeds()).hasSize(GraphTraversalQuery.HARD_MAX_SEEDS);
        assertThat(bounded.getValue().workspace()).isEqualTo(WORKSPACE_SCOPE);
        assertThat(bounded.getValue().seeds())
                .allSatisfy(seed -> assertThat(seed.canonicalKey()).startsWith("wiki-page:"));

        // Permuting the same equal-relevance backend hits must not change the canonical seed
        // identity set or order: reciprocal ranks tie-break on the canonical identity itself.
        stubLexical(List.of(candidates.get(2), candidates.get(0), candidates.get(1)));
        stubVector(List.of(candidates.get(1), candidates.get(2), candidates.get(0)));
        service.fuse(FusedEvidenceRequest.of("query"));
        stubLexical(List.of(candidates.get(1), candidates.get(2), candidates.get(0)));
        stubVector(List.of(candidates.get(0), candidates.get(1), candidates.get(2)));
        service.fuse(FusedEvidenceRequest.of("query"));
        ArgumentCaptor<GraphTraversalQuery> permuted = ArgumentCaptor
                .forClass(GraphTraversalQuery.class);
        Mockito.verify(graphBackend, Mockito.times(3)).traverse(permuted.capture());
        List<GraphEntityIdentity> firstSeeds = permuted.getAllValues().get(1).seeds();
        List<GraphEntityIdentity> secondSeeds = permuted.getAllValues().get(2).seeds();
        assertThat(secondSeeds).isEqualTo(firstSeeds);
        assertThat(firstSeeds).hasSize(3);
        assertThat(firstSeeds).extracting(GraphEntityIdentity::canonicalKey)
                .containsExactlyInAnyOrder("wiki-page:wiki-1", "wiki-page:wiki-2",
                        "wiki-page:wiki-3");
    }

    @Test
    void excludedGraphChannelIsATypedDisabledOutcome() {
        stubLexical(List.of(wikiCandidate("wiki-a", 1.0d, 1)));
        Mockito.when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE_ID, "wiki-a"))
                .thenReturn(Optional.of(wikiPage("wiki-a", 1, sha256("v1"))));
        Mockito.when(wikiContentReader.readSearchableContent(any())).thenReturn("content");

        FusedEvidenceResult result = service.fuse(FusedEvidenceRequest.of("query", null, null,
                false));

        assertThat(result.diagnostics().graph()).isEqualTo(ModalityOutcome.DISABLED);
        assertThat(result.diagnostics().graphDetail()).isNotBlank();
        Mockito.verify(graphBackend, Mockito.never()).traverse(any());
        Mockito.verify(graphReadiness, Mockito.never()).readiness(any());
    }

    @Test
    void fusionItemsKeepCanonicalIdentityBudgetAndInsufficientEvidenceInvariants() {
        stubLexical(List.of(wikiCandidate("wiki-a", 1.0d, 1)));
        Mockito.when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE_ID, "wiki-a"))
                .thenReturn(Optional.of(wikiPage("wiki-a", 1, sha256("v1"))));
        Mockito.when(wikiContentReader.readSearchableContent(any())).thenReturn("content");

        FusedEvidenceResult result = service.fuse(FusedEvidenceRequest.of("query", 2, 5_000,
                true));

        assertThat(result.insufficientEvidence()).isFalse();
        assertThat(result.items().getFirst().stableIdentity()).isEqualTo("WIKI:wiki-a");
        assertThat(result.budget().maxItems()).isEqualTo(2);
        assertThat(result.budget().maxCharacters()).isEqualTo(5_000);
        assertThat(result.workspace().id()).isEqualTo(WORKSPACE_ID);
    }

    private void stubLexical(List<SearchCandidate> candidates) {
        Mockito.when(searchService.findCandidates(any())).thenReturn(page(candidates));
    }

    private void stubVector(List<SearchCandidate> candidates) {
        Mockito.when(vectorCandidateSearchService.findCandidates(any(), any()))
                .thenReturn(page(candidates));
    }

    private static SearchCandidatePage page(List<SearchCandidate> items) {
        return new SearchCandidatePage(items, 0, Math.max(1, items.size()), items.size());
    }

    private static SearchCandidate wikiCandidate(String knowledgeId, double score, int revision) {
        return new SearchCandidate(SearchResultKind.WIKI, knowledgeId, score, "snippet",
                new SearchWorkspaceProvenance(WORKSPACE_ID, "test"), knowledgeId, knowledgeId,
                "CONCEPT", "vault/concepts/" + knowledgeId + ".md", revision, sha256("v"
                + revision), null, null, null, null, null, null, null, null, null);
    }

    private static SearchCandidate sourceCandidate(long chunkId, long documentId, int chunkNo,
                                                   String contentHash) {
        return new SearchCandidate(SearchResultKind.SOURCE_CHUNK, Long.toString(chunkId), 1.0d,
                null, new SearchWorkspaceProvenance(WORKSPACE_ID, "test"), null, null, null,
                null, null, contentHash,
                SourceSearchFreshness.fingerprint(sourceDocument(documentId, "PROCESSED",
                        chunk(chunkId, chunkNo, "chunk", contentHash))),
                1, chunkId, documentId, "source-" + documentId + ".txt", chunkNo, null, null,
                null);
    }

    private static SourceSearchAuthorityChunk chunk(long chunkId, int chunkNo, String content,
                                                    String contentHash) {
        return new SourceSearchAuthorityChunk(chunkId, chunkNo, null, null, null, content,
                contentHash);
    }

    private static StoredPublishedWiki wikiPage(String knowledgeId, int revision,
                                                String contentHash) {
        return new StoredPublishedWiki(1L, WORKSPACE_ID, knowledgeId, knowledgeId, knowledgeId,
                WikiPageType.CONCEPT, "vault/concepts/" + knowledgeId + ".md",
                org.km.llmwiki.wiki.PageStatus.PUBLISHED, contentHash, revision, NOW, NOW);
    }

    private static SourceSearchAuthorityDocument sourceDocument(long documentId,
                                                                String parseStatus,
                                                                SourceSearchAuthorityChunk... chunks) {
        return new SourceSearchAuthorityDocument(WORKSPACE_ID, documentId,
                "source-" + documentId + ".txt", sha256("document-" + documentId), "ACTIVE",
                parseStatus, List.of(chunks));
    }

    private static EvidenceItem graphItem(String knowledgeId, String content, String contentHash) {
        return new EvidenceItem(EvidenceKind.WIKI, knowledgeId,
                new EvidenceWorkspace(WORKSPACE_ID, "test"), 1.0d, content, null, false,
                contentHash, knowledgeId, knowledgeId, "CONCEPT",
                "vault/concepts/" + knowledgeId + ".md", 1, null, null, null, null, null, null,
                null);
    }

    private static GraphEvidenceAdmissionResult admission(GraphProjectionSnapshot snapshot,
                                                          EvidenceItem... items) {
        List<EvidenceItem> itemList = List.of(items);
        return new GraphEvidenceAdmissionResult(snapshot,
                new EvidenceWorkspace(WORKSPACE_ID, "test"), itemList,
                new EvidenceBudget(4, 4_000, itemList.size(), 100,
                        (100 + 3) / 4, false), itemList.size(), 0, List.of());
    }

    private static WorkspaceResponse workspace() {
        return new WorkspaceResponse(WORKSPACE_ID, "test", "/root", "/root/inbox",
                "/root/archive", "/root/vault", "/root/data", "/root/config", "ACTIVE", NOW, NOW);
    }

    private static GraphProjectionVerification ready(GraphProjectionSnapshot snapshot) {
        return new GraphProjectionVerification(WORKSPACE_SCOPE,
                GraphProjectionVerificationStatus.READY, readyControl(snapshot), null);
    }

    private static GraphProjectionVerification verification(
            GraphProjectionVerificationStatus status,
            GraphProjectionReadiness control,
            GraphProjectionFailure failure) {
        return new GraphProjectionVerification(WORKSPACE_SCOPE, status, control, failure);
    }

    private static GraphProjectionReadiness readyControl(GraphProjectionSnapshot snapshot) {
        return new GraphProjectionReadiness(WORKSPACE_SCOPE, "arcadedb", VERSION,
                GraphProjectionReadinessStatus.READY, snapshot.generation(), snapshot.generation(),
                snapshot.sourceFingerprint(), snapshot.snapshotToken(), null, null, null, null,
                null, null, NOW, NOW);
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
