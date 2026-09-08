package org.km.llmwiki.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.km.llmwiki.graph.GraphProjectionException;
import org.km.llmwiki.graph.GraphProjectionFailureType;
import org.km.llmwiki.graph.GraphProjectionReadiness;
import org.km.llmwiki.graph.GraphProjectionReadinessReader;
import org.km.llmwiki.graph.GraphProjectionReadinessStatus;
import org.km.llmwiki.graph.GraphProjectionSnapshot;
import org.km.llmwiki.graph.GraphProjectionVerification;
import org.km.llmwiki.graph.GraphProjectionVerificationStatus;
import org.km.llmwiki.graph.GraphProjectionVersion;
import org.km.llmwiki.graph.GraphWorkspaceScope;
import org.km.llmwiki.search.SourceSearchAuthorityChunk;
import org.km.llmwiki.search.SourceSearchAuthorityDocument;
import org.km.llmwiki.search.SourceSearchAuthorityRepository;
import org.km.llmwiki.search.SourceSearchFreshness;
import org.km.llmwiki.wiki.PublishedWikiContentReader;
import org.km.llmwiki.wiki.PublishedWikiRepository;
import org.km.llmwiki.wiki.StoredPublishedWiki;
import org.km.llmwiki.wiki.WikiPageType;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * Deterministic adversarial evidence for the Ask-facing graph-grounded fused retrieval
 * orchestration: the last-mile handoff guard is exercised with explicit two-phase stubbing and
 * controlled doubles — never sleep, retry luck, or timing luck.
 */
@Tag("unit")
class FusedRetrievalOrchestratorTest {

    private static final long WORKSPACE_ID = 41L;
    private static final GraphWorkspaceScope WORKSPACE_SCOPE = new GraphWorkspaceScope(41);
    private static final GraphProjectionVersion VERSION = GraphProjectionVersion.initial();
    private static final GraphProjectionSnapshot SNAPSHOT_A = GraphProjectionSnapshot.fromProof(
            WORKSPACE_SCOPE, VERSION, 7, "a".repeat(64));
    private static final GraphProjectionSnapshot SNAPSHOT_B = GraphProjectionSnapshot.fromProof(
            WORKSPACE_SCOPE, VERSION, 8, "b".repeat(64));
    private static final String NOW = "2026-09-08T00:00:00Z";

    private FusedEvidenceService fusedEvidenceService;
    private PublishedWikiRepository wikiRepository;
    private PublishedWikiContentReader wikiContentReader;
    private SourceSearchAuthorityRepository sourceRepository;
    private GraphProjectionReadinessReader graphReadiness;
    private FusedRetrievalOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        fusedEvidenceService = Mockito.mock(FusedEvidenceService.class);
        wikiRepository = Mockito.mock(PublishedWikiRepository.class);
        wikiContentReader = Mockito.mock(PublishedWikiContentReader.class);
        sourceRepository = Mockito.mock(SourceSearchAuthorityRepository.class);
        graphReadiness = Mockito.mock(GraphProjectionReadinessReader.class);
        orchestrator = new FusedRetrievalOrchestrator(fusedEvidenceService, wikiRepository,
                wikiContentReader, sourceRepository, graphReadiness);
        Mockito.when(graphReadiness.readiness(any())).thenReturn(ready(SNAPSHOT_A));
        Mockito.when(wikiContentReader.readSearchableContent(any())).thenReturn("content");
    }

    @Test
    void fusedResultBecomesAuthoritativeBundlePreservingIdentityOrderModeAndBudget() {
        stubFusion(result(List.of(
                item("wiki-a", "AAAA"),
                item("wiki-b", "BBBBB")),
                Map.of("WIKI:wiki-a", Set.of(CandidateSignal.LEXICAL),
                        "WIKI:wiki-b", Set.of(CandidateSignal.VECTOR)),
                SNAPSHOT_A));
        stubCurrentWiki("wiki-a", "AAAA");
        stubCurrentWiki("wiki-b", "BBBBB");

        EvidenceBundle bundle = orchestrator.retrieveFused(request());

        assertThat(bundle.mode()).isEqualTo(RetrievalMode.HYBRID_GRAPH);
        assertThat(bundle.query()).isEqualTo("graph question");
        assertThat(bundle.workspace().id()).isEqualTo(WORKSPACE_ID);
        assertThat(bundle.items()).extracting(EvidenceItem::stableIdentity)
                .containsExactly("WIKI:wiki-a", "WIKI:wiki-b");
        assertThat(bundle.budget().maxItems()).isEqualTo(8);
        assertThat(bundle.budget().maxCharacters()).isEqualTo(12_000);
        assertThat(bundle.budget().usedItems()).isEqualTo(2);
        assertThat(bundle.budget().usedCharacters()).isEqualTo(9);
        assertThat(bundle.budget().estimatedTokens()).isEqualTo(3);
        assertThat(bundle.insufficientEvidence()).isFalse();
        assertThat(bundle.diagnostics().strategy()).isEqualTo(RetrievalStrategy.FUSED);
        assertThat(bundle.diagnostics().graphSignalUsed()).isTrue();
        assertThat(bundle.diagnostics().graphDegraded()).isFalse();
    }

    @Test
    void lastMileCanonicalRevisionDriftDropsStaleEvidenceWithoutSilentBackfill() {
        // Fusion published revision 1; the canonical revision moved to 2 before the Ask handoff.
        stubFusion(result(List.of(item("wiki-drift", "stale", sha256("v1"))),
                Map.of("WIKI:wiki-drift", Set.of(CandidateSignal.LEXICAL)), null));
        Mockito.when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE_ID, "wiki-drift"))
                .thenReturn(Optional.of(wikiPage("wiki-drift", 2, sha256("v2"))));

        EvidenceBundle bundle = orchestrator.retrieveFused(request());

        assertThat(bundle.items()).isEmpty();
        assertThat(bundle.insufficientEvidence()).isTrue();
        assertThat(bundle.rejectedCandidateCount()).isEqualTo(1);
        assertThat(bundle.budget().usedItems()).isZero();
        Mockito.verify(fusedEvidenceService, Mockito.times(1)).fuse(any());
    }

    @Test
    void lastMileSourceEligibilityDriftDropsStaleSourceEvidence() {
        stubFusion(result(List.of(sourceItem(77L, 9L, 1, "chunk", sha256("chunk"))),
                Map.of("SOURCE_CHUNK:77", Set.of(CandidateSignal.LEXICAL)), null));
        Mockito.when(sourceRepository.findDocument(WORKSPACE_ID, 9L)).thenReturn(
                Optional.of(sourceDocument(9L, "PENDING",
                        chunk(77L, 1, "chunk", sha256("chunk")))));

        EvidenceBundle bundle = orchestrator.retrieveFused(request());

        assertThat(bundle.items()).isEmpty();
        assertThat(bundle.insufficientEvidence()).isTrue();
        assertThat(bundle.rejectedCandidateCount()).isEqualTo(1);
    }

    @Test
    void lastMileProjectionDriftDropsGraphOnlyEvidenceAtTheHandoff() {
        // Graph-only evidence loses its only validity chain when the projection moves to
        // generation B between fusion publication and the Ask handoff.
        stubFusion(result(List.of(item("wiki-graph", "graph content")),
                Map.of("WIKI:wiki-graph", Set.of(CandidateSignal.GRAPH)), SNAPSHOT_A));
        Mockito.when(graphReadiness.readiness(any())).thenReturn(ready(SNAPSHOT_B));

        EvidenceBundle bundle = orchestrator.retrieveFused(request());

        assertThat(bundle.items()).isEmpty();
        assertThat(bundle.insufficientEvidence()).isTrue();
        assertThat(bundle.diagnostics().graphDegraded()).isTrue();
        assertThat(bundle.diagnostics().graphDetail()).contains("handoff");
        Mockito.verify(wikiRepository, Mockito.never())
                .findPublishedByKnowledgeId(anyLong(), anyString());
    }

    @Test
    void lastMileProjectionDriftKeepsCrossModalityEvidenceWithIndependentLexicalProof() {
        stubFusion(result(List.of(
                        item("wiki-shared", "shared"),
                        item("wiki-lexical", "lexical")),
                Map.of("WIKI:wiki-shared", Set.of(CandidateSignal.LEXICAL, CandidateSignal.GRAPH),
                        "WIKI:wiki-lexical", Set.of(CandidateSignal.LEXICAL)),
                SNAPSHOT_A));
        Mockito.when(graphReadiness.readiness(any())).thenReturn(ready(SNAPSHOT_B));
        stubCurrentWiki("wiki-shared", "shared");
        stubCurrentWiki("wiki-lexical", "lexical");

        EvidenceBundle bundle = orchestrator.retrieveFused(request());

        assertThat(bundle.items()).extracting(EvidenceItem::stableIdentity)
                .containsExactly("WIKI:wiki-shared", "WIKI:wiki-lexical");
        assertThat(bundle.diagnostics().graphDegraded()).isTrue();
        assertThat(bundle.diagnostics().graphUnavailable()).isFalse();
    }

    @Test
    void handoffReadinessInfrastructureFailureDropsGraphOnlyEvidenceAndKeepsTheBaseline() {
        // Deterministic fault injection: the handoff control-plane read fails operationally
        // after fusion published. Graph-only evidence loses its only validity chain and is
        // dropped; cross-modality evidence keeps its independent lexical proof; the baseline
        // continues instead of raw-propagating a generic runtime failure.
        stubFusion(result(List.of(
                        item("wiki-graph", "graph content"),
                        item("wiki-shared", "shared"),
                        item("wiki-lexical", "lexical")),
                Map.of("WIKI:wiki-graph", Set.of(CandidateSignal.GRAPH),
                        "WIKI:wiki-shared", Set.of(CandidateSignal.LEXICAL, CandidateSignal.GRAPH),
                        "WIKI:wiki-lexical", Set.of(CandidateSignal.LEXICAL)),
                SNAPSHOT_A));
        Mockito.when(graphReadiness.readiness(any()))
                .thenThrow(new org.jooq.exception.DataAccessException("control plane down"));
        stubCurrentWiki("wiki-shared", "shared");
        stubCurrentWiki("wiki-lexical", "lexical");

        EvidenceBundle bundle = orchestrator.retrieveFused(request());

        assertThat(bundle.items()).extracting(EvidenceItem::stableIdentity)
                .containsExactly("WIKI:wiki-shared", "WIKI:wiki-lexical");
        assertThat(bundle.insufficientEvidence()).isFalse();
        assertThat(bundle.diagnostics().graphUnavailable()).isTrue();
        assertThat(bundle.diagnostics().graphDegraded()).isFalse();
        assertThat(bundle.diagnostics().graphDetail()).contains("handoff");
        assertThat(bundle.rejectedCandidateCount()).isEqualTo(1);
        Mockito.verify(fusedEvidenceService, Mockito.times(1)).fuse(any());
    }

    @Test
    void handoffCorruptProjectionProofFailsClosedTypedInsteadOfDegrading() {
        stubFusion(result(List.of(item("wiki-graph", "graph content")),
                Map.of("WIKI:wiki-graph", Set.of(CandidateSignal.GRAPH)), SNAPSHOT_A));
        Mockito.when(graphReadiness.readiness(any())).thenThrow(new GraphProjectionException(
                GraphProjectionFailureType.PROJECTION_CORRUPT));

        assertThatThrownBy(() -> orchestrator.retrieveFused(request()))
                .isInstanceOfSatisfying(RetrievalUnavailableException.class, failure ->
                        assertThat(failure.dependency())
                                .isEqualTo(RetrievalUnavailableException.Dependency.GRAPH));
    }

    @Test
    void handoffUnexpectedRuntimeDefectPropagatesFailClosed() {
        stubFusion(result(List.of(item("wiki-graph", "graph content")),
                Map.of("WIKI:wiki-graph", Set.of(CandidateSignal.GRAPH)), SNAPSHOT_A));
        IllegalStateException defect = new IllegalStateException("programming defect");
        Mockito.when(graphReadiness.readiness(any())).thenThrow(defect);

        assertThatThrownBy(() -> orchestrator.retrieveFused(request()))
                .isSameAs(defect);
    }

    @Test
    void graphHandoffDegradationNeverDragsDownTheLexicalBaseline() {
        stubFusion(result(List.of(item("wiki-baseline", "baseline")),
                Map.of("WIKI:wiki-baseline", Set.of(CandidateSignal.LEXICAL)), SNAPSHOT_A));
        Mockito.when(graphReadiness.readiness(any())).thenReturn(ready(SNAPSHOT_B));
        stubCurrentWiki("wiki-baseline", "baseline");

        EvidenceBundle bundle = orchestrator.retrieveFused(request());

        assertThat(bundle.items()).extracting(EvidenceItem::stableId)
                .containsExactly("wiki-baseline");
        assertThat(bundle.insufficientEvidence()).isFalse();
        assertThat(bundle.diagnostics().graphDegraded()).isTrue();
        assertThat(bundle.diagnostics().graphSignalUsed()).isTrue();
    }

    @Test
    void handoffInfrastructureFailureIsTypedAndNeverInsufficientEvidence() {
        stubFusion(result(List.of(item("wiki-a", "content")),
                Map.of("WIKI:wiki-a", Set.of(CandidateSignal.LEXICAL)), null));
        Mockito.when(wikiRepository.findPublishedByKnowledgeId(anyLong(), anyString()))
                .thenThrow(new org.jooq.exception.DataAccessException("wiki authority down"));

        assertThatThrownBy(() -> orchestrator.retrieveFused(request()))
                .isInstanceOf(RetrievalUnavailableException.class)
                .extracting(failure -> ((RetrievalUnavailableException) failure).dependency())
                .isEqualTo(RetrievalUnavailableException.Dependency.WIKI_AUTHORITY);
    }

    @Test
    void crossWorkspaceIdentityAtHandoffIsRejectedBeforeContextAssembly() {
        // A foreign identity that does not exist in the active workspace fails the canonical
        // handoff revalidation and is dropped instead of entering the Answer context.
        stubFusion(result(List.of(item("wiki-foreign", "foreign content")),
                Map.of("WIKI:wiki-foreign", Set.of(CandidateSignal.GRAPH)), SNAPSHOT_A));
        Mockito.when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE_ID, "wiki-foreign"))
                .thenReturn(Optional.empty());

        EvidenceBundle bundle = orchestrator.retrieveFused(request());

        assertThat(bundle.items()).isEmpty();
        assertThat(bundle.insufficientEvidence()).isTrue();
        assertThat(bundle.rejectedCandidateCount()).isEqualTo(1);
    }

    @Test
    void budgetAndCountsReflectSurvivorsWhileSelectionTruncationIsPreserved() {
        stubFusion(new FusedEvidenceResult("graph question", workspace(),
                List.of(item("wiki-a", "AAAA"),
                        item("wiki-b", "BBBBB")),
                new EvidenceBudget(2, 12_000, 2, 9, 3, true),
                12, 1, false, SNAPSHOT_A,
                Map.of("WIKI:wiki-a", Set.of(CandidateSignal.LEXICAL),
                        "WIKI:wiki-b", Set.of(CandidateSignal.GRAPH)),
                diagnostics(ModalityOutcome.CONTRIBUTED, ModalityOutcome.CONTRIBUTED,
                        ModalityOutcome.CONTRIBUTED)));
        stubCurrentWiki("wiki-a", "AAAA");
        Mockito.when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE_ID, "wiki-b"))
                .thenReturn(Optional.of(wikiPage("wiki-b", 2, sha256("v2"))));

        EvidenceBundle bundle = orchestrator.retrieveFused(request());

        assertThat(bundle.items()).extracting(EvidenceItem::stableId).containsExactly("wiki-a");
        assertThat(bundle.budget().usedItems()).isEqualTo(1);
        assertThat(bundle.budget().usedCharacters()).isEqualTo(4);
        assertThat(bundle.budget().estimatedTokens()).isEqualTo(1);
        assertThat(bundle.budget().maxItems()).isEqualTo(2);
        assertThat(bundle.budget().truncated()).isTrue();
        assertThat(bundle.searchedCandidateCount()).isEqualTo(12);
        assertThat(bundle.rejectedCandidateCount()).isEqualTo(2);
    }

    @Test
    void repeatedHandoffWithIdenticalInputsIsDeterministic() {
        stubFusion(result(List.of(item("wiki-a", "AAAA")),
                Map.of("WIKI:wiki-a", Set.of(CandidateSignal.LEXICAL, CandidateSignal.GRAPH)),
                SNAPSHOT_A));
        stubCurrentWiki("wiki-a", "AAAA");

        EvidenceBundle first = orchestrator.retrieveFused(request());
        EvidenceBundle second = orchestrator.retrieveFused(request());

        assertThat(second).isEqualTo(first);
    }

    @Test
    void graphSnapshotWithoutGraphParticipationSkipsTheProjectionCheck() {
        // An empty graph channel (snapshot present, no admitted evidence, not CONTRIBUTED)
        // leaves no graph-derived evidence to protect; the handoff does not spend a readiness
        // read on it, exactly like the fusion terminal guard.
        stubFusion(new FusedEvidenceResult("graph question", workspace(),
                List.of(item("wiki-a", "content")),
                new EvidenceBudget(8, 12_000, 1, 7, 2, false),
                11, 0, false, SNAPSHOT_A,
                Map.of("WIKI:wiki-a", Set.of(CandidateSignal.LEXICAL)),
                diagnostics(ModalityOutcome.CONTRIBUTED, ModalityOutcome.CONTRIBUTED,
                        ModalityOutcome.EMPTY)));
        stubCurrentWiki("wiki-a", "content");

        EvidenceBundle bundle = orchestrator.retrieveFused(request());

        assertThat(bundle.items()).hasSize(1);
        Mockito.verifyNoInteractions(graphReadiness);
    }

    @Test
    void currentProjectionWithGraphDerivedEvidenceIsReCheckedExactlyOncePerHandoff() {
        stubFusion(result(List.of(item("wiki-a", "content")),
                Map.of("WIKI:wiki-a", Set.of(CandidateSignal.GRAPH)), SNAPSHOT_A));
        stubCurrentWiki("wiki-a", "content");

        EvidenceBundle bundle = orchestrator.retrieveFused(request());

        assertThat(bundle.items()).hasSize(1);
        Mockito.verify(graphReadiness, Mockito.times(1)).readiness(any());
    }

    @Test
    void fusedModeIsPreservedForOrthogonalCompatibilityRequests() {
        stubFusion(result(List.of(item("wiki-a", "content")),
                Map.of("WIKI:wiki-a", Set.of(CandidateSignal.LEXICAL)), null));
        stubCurrentWiki("wiki-a", "content");
        RetrievalRequest orthogonal = RetrievalRequest.of("graph question",
                RetrievalMode.HYBRID_VECTOR, RetrievalStrategy.FUSED, null, null);

        EvidenceBundle bundle = orchestrator.retrieveFused(orthogonal);

        assertThat(bundle.mode()).isEqualTo(RetrievalMode.HYBRID_VECTOR);
        assertThat(bundle.diagnostics().strategy()).isEqualTo(RetrievalStrategy.FUSED);
    }

    @ParameterizedTest
    @EnumSource(value = ModalityOutcome.class, names = {"CONTRIBUTED", "EMPTY"},
            mode = EnumSource.Mode.INCLUDE)
    void contributingOrEmptyGraphSignalIsReportedAsUsed(ModalityOutcome graphOutcome) {
        assertThat(mappedGraphFlags(graphOutcome))
                .containsExactly(true, false, false);
    }

    @ParameterizedTest
    @EnumSource(value = ModalityOutcome.class, names = {"UNAVAILABLE", "NOT_READY"},
            mode = EnumSource.Mode.INCLUDE)
    void unavailableOrNotReadyGraphSignalIsReportedAsUnavailable(ModalityOutcome graphOutcome) {
        assertThat(mappedGraphFlags(graphOutcome))
                .containsExactly(false, false, true);
    }

    @Test
    void degradedGraphSignalStaysDistinctFromANormalZeroResult() {
        assertThat(mappedGraphFlags(ModalityOutcome.DEGRADED))
                .containsExactly(true, true, false);
    }

    @Test
    void disabledGraphSignalIsReportedAsUnusedWithoutDegradation() {
        assertThat(mappedGraphFlags(ModalityOutcome.DISABLED))
                .containsExactly(false, false, false);
    }

    @Test
    void vectorUnavailableIsReflectedInTheFusedDiagnostics() {
        RetrievalDiagnostics diagnostics = RetrievalDiagnostics.fused(
                new FusedModalityDiagnostics(ModalityOutcome.CONTRIBUTED,
                        ModalityOutcome.UNAVAILABLE, ModalityOutcome.CONTRIBUTED,
                        "VECTOR_CAPABILITY: vector capability disabled", null, 0));

        assertThat(diagnostics.vectorSignalUsed()).isFalse();
        assertThat(diagnostics.vectorUnavailable()).isTrue();
        assertThat(diagnostics.vectorUnavailableReason()).contains("VECTOR_CAPABILITY");
        assertThat(diagnostics.degradedFallback()).isFalse();
        assertThat(diagnostics.graphSignalUsed()).isTrue();
        assertThat(diagnostics.graphDegraded()).isFalse();
    }

    @Test
    void lastMileRevisionOnlyDriftDropsStaleEvidenceEvenWhenContentHashStillMatches() {
        // The canonical revision advanced without a content change: the item is stale because
        // revision and content hash must both match, so it is dropped at the handoff.
        stubFusion(result(List.of(item("wiki-revonly", "same content")),
                Map.of("WIKI:wiki-revonly", Set.of(CandidateSignal.LEXICAL)), null));
        Mockito.when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE_ID, "wiki-revonly"))
                .thenReturn(Optional.of(wikiPage("wiki-revonly", 2,
                        sha256("same content"))));

        EvidenceBundle bundle = orchestrator.retrieveFused(request());

        assertThat(bundle.items()).isEmpty();
        assertThat(bundle.insufficientEvidence()).isTrue();
        assertThat(bundle.rejectedCandidateCount()).isEqualTo(1);
    }

    private List<Boolean> mappedGraphFlags(ModalityOutcome graphOutcome) {
        RetrievalDiagnostics diagnostics = RetrievalDiagnostics.fused(diagnostics(
                ModalityOutcome.CONTRIBUTED, ModalityOutcome.CONTRIBUTED, graphOutcome));
        return List.of(diagnostics.graphSignalUsed(), diagnostics.graphDegraded(),
                diagnostics.graphUnavailable());
    }

    private void stubFusion(FusedEvidenceResult result) {
        Mockito.when(fusedEvidenceService.fuse(any())).thenReturn(result);
    }

    private void stubCurrentWiki(String knowledgeId, String content) {
        Mockito.when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE_ID, knowledgeId))
                .thenReturn(Optional.of(wikiPage(knowledgeId, 1, sha256(content))));
    }

    private static RetrievalRequest request() {
        return RetrievalRequest.defaults("graph question", RetrievalMode.HYBRID_GRAPH);
    }

    private static FusedEvidenceResult result(List<EvidenceItem> items,
                                              Map<String, Set<CandidateSignal>> modalities,
                                              GraphProjectionSnapshot snapshot) {
        return new FusedEvidenceResult("graph question", workspace(), items,
                new EvidenceBudget(8, 12_000, items.size(), 100, 26, false),
                11, 0, items.isEmpty(), snapshot, modalities,
                diagnostics(ModalityOutcome.CONTRIBUTED, ModalityOutcome.CONTRIBUTED,
                        snapshot == null ? ModalityOutcome.DISABLED
                                : items.isEmpty() ? ModalityOutcome.EMPTY
                                : ModalityOutcome.CONTRIBUTED));
    }

    private static FusedModalityDiagnostics diagnostics(ModalityOutcome lexical,
                                                        ModalityOutcome vector,
                                                        ModalityOutcome graph) {
        return new FusedModalityDiagnostics(lexical, vector, graph, null, null, 0);
    }

    private static EvidenceItem item(String knowledgeId, String content) {
        return item(knowledgeId, content, sha256(content));
    }

    private static EvidenceItem item(String knowledgeId, String content, String contentHash) {
        return new EvidenceItem(EvidenceKind.WIKI, knowledgeId,
                new EvidenceWorkspace(WORKSPACE_ID, "test"), 1.0d, content, null, false,
                contentHash, knowledgeId, knowledgeId, "CONCEPT",
                "vault/concepts/" + knowledgeId + ".md", 1, null, null, null, null, null, null,
                null);
    }

    private static EvidenceItem sourceItem(long chunkId, long documentId, int chunkNo,
                                           String content, String contentHash) {
        return new EvidenceItem(EvidenceKind.SOURCE_CHUNK, Long.toString(chunkId),
                new EvidenceWorkspace(WORKSPACE_ID, "test"), 1.0d, content, null, false,
                contentHash, null, null, null, null, null, chunkId, documentId,
                "source-" + documentId + ".txt", chunkNo, null, null, null);
    }

    private static EvidenceWorkspace workspace() {
        return new EvidenceWorkspace(WORKSPACE_ID, "test");
    }

    private static StoredPublishedWiki wikiPage(String knowledgeId, int revision,
                                                String contentHash) {
        return new StoredPublishedWiki(1L, WORKSPACE_ID, knowledgeId, knowledgeId, knowledgeId,
                WikiPageType.CONCEPT, "vault/concepts/" + knowledgeId + ".md",
                org.km.llmwiki.wiki.PageStatus.PUBLISHED, contentHash, revision, NOW, NOW);
    }

    private static SourceSearchAuthorityChunk chunk(long chunkId, int chunkNo, String content,
                                                    String contentHash) {
        return new SourceSearchAuthorityChunk(chunkId, chunkNo, null, null, null, content,
                contentHash);
    }

    private static SourceSearchAuthorityDocument sourceDocument(long documentId,
                                                                String parseStatus,
                                                                SourceSearchAuthorityChunk... chunks) {
        return new SourceSearchAuthorityDocument(WORKSPACE_ID, documentId,
                "source-" + documentId + ".txt", sha256("document-" + documentId), "ACTIVE",
                parseStatus, List.of(chunks));
    }

    private static GraphProjectionVerification ready(GraphProjectionSnapshot snapshot) {
        return new GraphProjectionVerification(WORKSPACE_SCOPE,
                GraphProjectionVerificationStatus.READY, readyControl(snapshot), null);
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
