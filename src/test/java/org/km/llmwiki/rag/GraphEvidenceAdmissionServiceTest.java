package org.km.llmwiki.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.graph.GraphAuthorityEligibility;
import org.km.llmwiki.graph.GraphAuthorityKind;
import org.km.llmwiki.graph.GraphAuthorityReference;
import org.km.llmwiki.graph.GraphEntity;
import org.km.llmwiki.graph.GraphEntityIdentity;
import org.km.llmwiki.graph.GraphEntityType;
import org.km.llmwiki.graph.GraphFreshness;
import org.km.llmwiki.graph.GraphMetadata;
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
import org.km.llmwiki.graph.GraphProvenance;
import org.km.llmwiki.graph.GraphRelation;
import org.km.llmwiki.graph.GraphRelationIdentity;
import org.km.llmwiki.graph.GraphRelationType;
import org.km.llmwiki.graph.GraphTraversalCandidate;
import org.km.llmwiki.graph.GraphTraversalResult;
import org.km.llmwiki.graph.GraphWorkspaceScope;
import org.km.llmwiki.search.SourceSearchAuthorityChunk;
import org.km.llmwiki.search.SourceSearchAuthorityDocument;
import org.km.llmwiki.search.SourceSearchAuthorityRepository;
import org.km.llmwiki.wiki.PageStatus;
import org.km.llmwiki.wiki.PublishedWikiContentReader;
import org.km.llmwiki.wiki.PublishedWikiRepository;
import org.km.llmwiki.wiki.PublishedWikiUnavailableException;
import org.km.llmwiki.wiki.PublishedWikiValidationException;
import org.km.llmwiki.wiki.StoredPublishedWiki;
import org.km.llmwiki.wiki.WikiPageType;
import org.mockito.Mockito;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Deterministic admission semantics for graph candidates: race cases use explicit two-phase
 * readiness stubbing instead of sleep or timing luck.
 */
@Tag("unit")
class GraphEvidenceAdmissionServiceTest {

    private static final GraphWorkspaceScope WORKSPACE = new GraphWorkspaceScope(41);
    private static final GraphWorkspaceScope OTHER_WORKSPACE = new GraphWorkspaceScope(42);
    private static final GraphProjectionVersion VERSION = GraphProjectionVersion.initial();
    private static final GraphProjectionSnapshot SNAPSHOT_A = GraphProjectionSnapshot.fromProof(
            WORKSPACE, VERSION, 7, "a".repeat(64));
    private static final GraphProjectionSnapshot SNAPSHOT_B = GraphProjectionSnapshot.fromProof(
            WORKSPACE, VERSION, 8, "b".repeat(64));
    private static final GraphProjectionSnapshot FOREIGN_SNAPSHOT =
            GraphProjectionSnapshot.fromProof(OTHER_WORKSPACE, VERSION, 7, "c".repeat(64));
    private static final String NOW = "2026-09-07T00:00:00Z";

    private GraphProjectionReadinessReader readinessReader;
    private PublishedWikiRepository wikiRepository;
    private PublishedWikiContentReader wikiContentReader;
    private SourceSearchAuthorityRepository sourceRepository;
    private GraphEvidenceAdmissionService service;

    @BeforeEach
    void setUp() {
        readinessReader = Mockito.mock(GraphProjectionReadinessReader.class);
        wikiRepository = Mockito.mock(PublishedWikiRepository.class);
        wikiContentReader = Mockito.mock(PublishedWikiContentReader.class);
        sourceRepository = Mockito.mock(SourceSearchAuthorityRepository.class);
        service = new GraphEvidenceAdmissionService(readinessReader, wikiRepository,
                wikiContentReader, sourceRepository);
        when(readinessReader.readiness(WORKSPACE)).thenReturn(ready(SNAPSHOT_A));
    }

    @Test
    void admittedWikiCandidateCarriesCanonicalIdentityAndDepthDerivedScore() {
        when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE.id(), "wiki-target"))
                .thenReturn(Optional.of(wikiPage("wiki-target", "Target Page", 3, sha256("v3"))));
        when(wikiContentReader.readSearchableContent(anyPage()))
                .thenReturn("Target Page canonical content");
        GraphTraversalResult traversal = result(SNAPSHOT_A, wikiCandidate("wiki-target", 1,
                new GraphFreshness(3, sha256("v3")), GraphRelationType.LINKS_TO));

        GraphEvidenceAdmissionResult admitted = service.admit(request(traversal));

        assertThat(admitted.rejections()).isEmpty();
        assertThat(admitted.evidenceItems()).hasSize(1);
        EvidenceItem item = admitted.evidenceItems().getFirst();
        assertThat(item.kind()).isEqualTo(EvidenceKind.WIKI);
        assertThat(item.stableId()).isEqualTo("wiki-target");
        assertThat(item.stableIdentity()).isEqualTo("WIKI:wiki-target");
        assertThat(item.score()).isEqualTo(1.0d);
        assertThat(item.content()).isEqualTo("Target Page canonical content");
        assertThat(item.contentHash()).isEqualTo(sha256("v3"));
        assertThat(item.revision()).isEqualTo(3);
        assertThat(item.knowledgeId()).isEqualTo("wiki-target");
        assertThat(item.title()).isEqualTo("Target Page");
        assertThat(item.workspace().id()).isEqualTo(41L);
        assertThat(admitted.budget().usedItems()).isEqualTo(1);
        assertThat(admitted.budget().truncated()).isFalse();
        verify(readinessReader, times(2)).readiness(WORKSPACE);
    }

    @Test
    void admittedSourceChunkCandidateUsesCanonicalChunkIdentityNotGraphIdentity() {
        SourceSearchAuthorityDocument document = sourceDocument(9L, "PROCESSED",
                chunk(77L, 1, "chunk content", sha256("chunk content")));
        when(sourceRepository.findDocument(WORKSPACE.id(), 9L))
                .thenReturn(Optional.of(document));
        GraphTraversalResult traversal = result(SNAPSHOT_A,
                sourceChunkCandidate(9L, 1, sha256("chunk content")));

        GraphEvidenceAdmissionResult admitted = service.admit(request(traversal));

        assertThat(admitted.rejections()).isEmpty();
        assertThat(admitted.evidenceItems()).hasSize(1);
        EvidenceItem item = admitted.evidenceItems().getFirst();
        assertThat(item.kind()).isEqualTo(EvidenceKind.SOURCE_CHUNK);
        assertThat(item.stableId()).isEqualTo("77");
        assertThat(item.stableIdentity()).isEqualTo("SOURCE_CHUNK:77");
        assertThat(item.contentHash()).isEqualTo(sha256("chunk content"));
        assertThat(item.documentId()).isEqualTo(9L);
        assertThat(item.sourceChunkId()).isEqualTo(77L);
        assertThat(item.chunkNo()).isEqualTo(1);
        assertThat(item.stableId()).doesNotStartWith("ge1_");
    }

    @Test
    void traversalResultCurrentAtReturnButDriftingBeforeAdmissionFailsClosed() {
        when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE.id(), "wiki-target"))
                .thenReturn(Optional.of(wikiPage("wiki-target", "Target Page", 3, sha256("v3"))));
        when(wikiContentReader.readSearchableContent(anyPage()))
                .thenReturn("Target Page canonical content");
        // Generation B already won when admission starts: the traversal result is stale on arrival.
        when(readinessReader.readiness(WORKSPACE)).thenReturn(ready(SNAPSHOT_B));

        assertThatThrownBy(() -> service.admit(request(result(SNAPSHOT_A,
                wikiCandidate("wiki-target", 1, new GraphFreshness(3, sha256("v3")),
                        GraphRelationType.LINKS_TO)))))
                .isInstanceOf(GraphProjectionException.class)
                .extracting(failure -> ((GraphProjectionException) failure).failureType())
                .isEqualTo(GraphProjectionFailureType.PROJECTION_STALE);
        verify(wikiRepository, never()).findPublishedByKnowledgeId(WORKSPACE.id(), "wiki-target");
    }

    @Test
    void driftDuringAuthorityReadsDiscardsEveryValidatedCandidateAtFinalCheck() {
        when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE.id(), "wiki-target"))
                .thenReturn(Optional.of(wikiPage("wiki-target", "Target Page", 3, sha256("v3"))));
        when(wikiContentReader.readSearchableContent(anyPage()))
                .thenReturn("Target Page canonical content");
        // The canonical fingerprint flips only after candidates were revalidated, so the final
        // consumption-window check is the deterministic barrier that rejects the whole batch.
        when(readinessReader.readiness(WORKSPACE)).thenReturn(ready(SNAPSHOT_A), ready(SNAPSHOT_B));

        assertThatThrownBy(() -> service.admit(request(result(SNAPSHOT_A,
                wikiCandidate("wiki-target", 1, new GraphFreshness(3, sha256("v3")),
                        GraphRelationType.LINKS_TO)))))
                .isInstanceOf(GraphProjectionException.class)
                .extracting(failure -> ((GraphProjectionException) failure).failureType())
                .isEqualTo(GraphProjectionFailureType.PROJECTION_STALE);
    }

    @Test
    void readinessDegradationBetweenChecksKeepsTypedFailureSemantics() {
        GraphEvidenceAdmissionRequest request = request(result(SNAPSHOT_A,
                wikiCandidate("wiki-target", 1, new GraphFreshness(3, sha256("v3")),
                        GraphRelationType.LINKS_TO)));
        when(readinessReader.readiness(WORKSPACE)).thenReturn(ready(SNAPSHOT_A),
                verification(GraphProjectionVerificationStatus.DISABLED, null, null));
        assertFailure(GraphProjectionFailureType.CAPABILITY_DISABLED, () -> service.admit(request));

        when(readinessReader.readiness(WORKSPACE)).thenReturn(ready(SNAPSHOT_A),
                verification(GraphProjectionVerificationStatus.NOT_READY, null,
                        GraphProjectionFailure.of(GraphProjectionFailureType.PROJECTION_NOT_READY)));
        assertFailure(GraphProjectionFailureType.PROJECTION_STALE, () -> service.admit(request));

        when(readinessReader.readiness(WORKSPACE)).thenReturn(ready(SNAPSHOT_A),
                verification(GraphProjectionVerificationStatus.BACKEND_UNAVAILABLE,
                        readyControl(SNAPSHOT_A),
                        GraphProjectionFailure.of(GraphProjectionFailureType.BACKEND_LOCKED)));
        assertFailure(GraphProjectionFailureType.BACKEND_LOCKED, () -> service.admit(request));
    }

    @Test
    void canonicalAuthorityDriftRejectsTheCandidateInsteadOfAdmittingIt() {
        when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE.id(), "wiki-target"))
                .thenReturn(Optional.of(wikiPage("wiki-target", "Target Page", 4, sha256("v4"))));
        GraphTraversalResult traversal = result(SNAPSHOT_A, wikiCandidate("wiki-target", 1,
                new GraphFreshness(3, sha256("v3")), GraphRelationType.LINKS_TO));

        GraphEvidenceAdmissionResult admitted = service.admit(request(traversal));

        assertThat(admitted.evidenceItems()).isEmpty();
        assertThat(admitted.rejectedCandidateCount()).isEqualTo(1);
        assertThat(admitted.rejections().getFirst().reason())
                .isEqualTo(GraphEvidenceRejectionReason.AUTHORITY_STALE);
    }

    @Test
    void missingOrInvalidatedWikiAuthorityIsRejectedWithTypedReason() {
        when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE.id(), "wiki-missing"))
                .thenReturn(Optional.empty());
        when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE.id(), "wiki-validation"))
                .thenReturn(Optional.of(wikiPage("wiki-validation", "Validation Page", 3,
                        sha256("v3"))));
        when(wikiContentReader.readSearchableContent(anyPage()))
                .thenThrow(new PublishedWikiValidationException("content drifted"));
        GraphTraversalResult traversal = result(SNAPSHOT_A,
                wikiCandidate("wiki-missing", 1, new GraphFreshness(3, sha256("v3")),
                        GraphRelationType.LINKS_TO),
                wikiCandidate("wiki-validation", 1, new GraphFreshness(3, sha256("v3")),
                        GraphRelationType.LINKS_TO));

        GraphEvidenceAdmissionResult admitted = service.admit(request(traversal));

        assertThat(admitted.evidenceItems()).isEmpty();
        assertThat(admitted.rejections()).extracting(GraphCandidateRejection::reason)
                .containsExactly(GraphEvidenceRejectionReason.AUTHORITY_MISSING,
                        GraphEvidenceRejectionReason.AUTHORITY_STALE);
    }

    @Test
    void nonEvidenceNavigationEntitiesNeverGenerateCitations() {
        GraphTraversalResult traversal = result(SNAPSHOT_A,
                metadataCandidate(GraphEntityType.TAG, "tag:rag", "rag"),
                conceptCandidate("concept-x"));

        GraphEvidenceAdmissionResult admitted = service.admit(request(traversal));

        assertThat(admitted.evidenceItems()).isEmpty();
        assertThat(admitted.rejections()).extracting(GraphCandidateRejection::reason)
                .containsExactly(GraphEvidenceRejectionReason.NON_EVIDENCE_ENTITY,
                        GraphEvidenceRejectionReason.NON_EVIDENCE_ENTITY);
        verify(wikiRepository, never()).findPublishedByKnowledgeId(WORKSPACE.id(), "wiki-target");
        verify(sourceRepository, never()).findDocument(WORKSPACE.id(), 9L);
    }

    @Test
    void currentSourceDocumentIsRevalidatedThenRejectedAsNonCitationAuthority() {
        when(sourceRepository.findDocument(WORKSPACE.id(), 9L)).thenReturn(Optional.of(
                sourceDocument(9L, "PROCESSED",
                        chunk(77L, 1, "chunk content", sha256("chunk content")))));
        GraphTraversalResult traversal = result(SNAPSHOT_A,
                sourceDocumentCandidate(9L, sha256("document-9")));

        GraphEvidenceAdmissionResult admitted = service.admit(request(traversal));

        assertThat(admitted.evidenceItems()).isEmpty();
        assertThat(admitted.rejections().getFirst().reason())
                .isEqualTo(GraphEvidenceRejectionReason.NON_CITATION_AUTHORITY);
        assertThat(admitted.rejections().getFirst().detail()).contains("document:9");
    }

    @Test
    void staleIneligibleOrMissingSourceAuthorityIsRejectedWithTypedReasons() {
        when(sourceRepository.findDocument(WORKSPACE.id(), 9L)).thenReturn(Optional.of(
                sourceDocument(9L, "PROCESSED",
                        chunk(77L, 1, "chunk content", sha256("chunk content")))));
        when(sourceRepository.findDocument(WORKSPACE.id(), 10L))
                .thenReturn(Optional.empty());
        when(sourceRepository.findDocument(WORKSPACE.id(), 11L)).thenReturn(Optional.of(
                sourceDocument(11L, "PENDING",
                        chunk(78L, 1, "pending content", sha256("pending content")))));
        when(sourceRepository.findDocument(WORKSPACE.id(), 12L)).thenReturn(Optional.of(
                sourceDocument(12L, "PROCESSED",
                        chunk(79L, 1, "eligible content", sha256("eligible content")))));
        GraphTraversalResult traversal = result(SNAPSHOT_A,
                sourceChunkCandidate(9L, 1, sha256("drifted-chunk")),
                sourceChunkCandidate(10L, 1, sha256("chunk")),
                sourceChunkCandidate(11L, 1, sha256("pending content")),
                sourceChunkCandidate(12L, 1, sha256("stale-content")));

        GraphEvidenceAdmissionResult admitted = service.admit(request(traversal));

        assertThat(admitted.evidenceItems()).isEmpty();
        assertThat(admitted.rejections()).extracting(GraphCandidateRejection::reason)
                .containsExactly(GraphEvidenceRejectionReason.AUTHORITY_STALE,
                        GraphEvidenceRejectionReason.AUTHORITY_MISSING,
                        GraphEvidenceRejectionReason.AUTHORITY_INELIGIBLE,
                        GraphEvidenceRejectionReason.AUTHORITY_STALE);
    }

    @Test
    void ineligibleProvenanceAndProjectionVersionDriftFailClosed() {
        GraphTraversalResult traversal = result(SNAPSHOT_A,
                wikiCandidate("wiki-superseded", 1, new GraphFreshness(3, sha256("v3")),
                        GraphAuthorityEligibility.SUPERSEDED, GraphRelationType.LINKS_TO),
                legacyWikiCandidate("wiki-legacy"));

        GraphEvidenceAdmissionResult admitted = service.admit(request(traversal));

        assertThat(admitted.evidenceItems()).isEmpty();
        assertThat(admitted.rejections()).extracting(GraphCandidateRejection::reason)
                .containsExactly(GraphEvidenceRejectionReason.AUTHORITY_INELIGIBLE,
                        GraphEvidenceRejectionReason.STALE_PROVENANCE);
    }

    @Test
    void nonAdmittedRelationVocabularyNeverBecomesAnEvidencePath() {
        GraphTraversalResult traversal = result(SNAPSHOT_A,
                wikiCandidate("wiki-target", 1, new GraphFreshness(3, sha256("v3")),
                        GraphRelationType.MENTIONS),
                wikiCandidate("wiki-other", 1, new GraphFreshness(3, sha256("v3")),
                        GraphRelationType.RELATED_TO));

        GraphEvidenceAdmissionResult admitted = service.admit(request(traversal));

        assertThat(admitted.evidenceItems()).isEmpty();
        assertThat(admitted.rejections()).extracting(GraphCandidateRejection::reason)
                .containsOnly(GraphEvidenceRejectionReason.DISALLOWED_RELATION);
        verify(wikiRepository, never()).findPublishedByKnowledgeId(WORKSPACE.id(), "wiki-target");
    }

    @Test
    void malformedProvenanceIsRejectedInsteadOfResolved() {
        when(sourceRepository.findDocument(WORKSPACE.id(), 9L)).thenReturn(Optional.of(
                sourceDocument(9L, "PROCESSED",
                        chunk(77L, 1, "chunk content", sha256("chunk content")))));
        GraphTraversalResult traversal = result(SNAPSHOT_A,
                sourceChunkCandidate(9L, 1, "document:not-a-number:chunk:1",
                        sha256("chunk content")),
                sourceChunkCandidateWithMetadata(9L, 1, 9),
                sourceChunkCandidate(9L, 1, sha256("chunk content")));

        GraphEvidenceAdmissionResult admitted = service.admit(request(traversal));

        assertThat(admitted.evidenceItems()).hasSize(1);
        assertThat(admitted.rejections()).extracting(GraphCandidateRejection::reason)
                .containsExactly(GraphEvidenceRejectionReason.MALFORMED_PROVENANCE,
                        GraphEvidenceRejectionReason.MALFORMED_PROVENANCE);
    }

    @Test
    void workspaceMismatchIsRejectedPerCandidateAndAcrossRequests() {
        GraphTraversalResult traversal = result(SNAPSHOT_A,
                wikiCandidate(OTHER_WORKSPACE, "foreign-page", 1,
                        new GraphFreshness(3, sha256("v3")), GraphRelationType.LINKS_TO));

        GraphEvidenceAdmissionResult admitted = service.admit(request(traversal));

        assertThat(admitted.evidenceItems()).isEmpty();
        assertThat(admitted.rejections().getFirst().reason())
                .isEqualTo(GraphEvidenceRejectionReason.WORKSPACE_MISMATCH);

        GraphTraversalResult foreignSnapshot = result(FOREIGN_SNAPSHOT,
                wikiCandidate("wiki-target", 1, new GraphFreshness(3, sha256("v3")),
                        GraphRelationType.LINKS_TO));
        assertFailure(GraphProjectionFailureType.CROSS_WORKSPACE,
                () -> service.admit(request(foreignSnapshot)));
    }

    @Test
    void rejectedCandidatesDoNotDisturbDeterministicOrderingOfTheRemainingEvidence() {
        when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE.id(), "wiki-kept"))
                .thenReturn(Optional.of(wikiPage("wiki-kept", "Kept Page", 1, sha256("v1"))));
        when(wikiContentReader.readSearchableContent(anyPage())).thenReturn("kept content");
        GraphTraversalResult traversal = result(SNAPSHOT_A,
                wikiCandidate("wiki-rejected", 1, new GraphFreshness(9, sha256("v9")),
                        GraphRelationType.LINKS_TO),
                wikiCandidate("wiki-kept", 1, new GraphFreshness(1, sha256("v1")),
                        GraphRelationType.LINKS_TO));

        GraphEvidenceAdmissionResult admitted = service.admit(request(traversal));

        assertThat(admitted.evidenceItems()).extracting(EvidenceItem::stableId)
                .containsExactly("wiki-kept");
        assertThat(admitted.rejectedCandidateCount()).isEqualTo(1);
    }

    @Test
    void depthDerivedScoresStayDeterministicAndIndependentOfTraversalPathVolume() {
        when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE.id(), "wiki-shallow"))
                .thenReturn(Optional.of(wikiPage("wiki-shallow", "Shallow", 1, sha256("v1"))));
        when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE.id(), "wiki-deep"))
                .thenReturn(Optional.of(wikiPage("wiki-deep", "Deep", 1, sha256("v1"))));
        when(wikiContentReader.readSearchableContent(anyPage())).thenReturn("deterministic");
        GraphTraversalResult traversal = result(SNAPSHOT_A,
                wikiCandidate("wiki-shallow", 1, new GraphFreshness(1, sha256("v1")),
                        GraphRelationType.LINKS_TO),
                wikiCandidate("wiki-deep", 2, new GraphFreshness(1, sha256("v1")),
                        GraphRelationType.LINKS_TO));

        GraphEvidenceAdmissionResult admitted = service.admit(request(traversal));

        assertThat(admitted.evidenceItems()).extracting(EvidenceItem::score)
                .containsExactly(1.0d, 0.5d);
    }

    @Test
    void duplicateCanonicalIdentityIsDeduplicatedWithTypedRejection() {
        when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE.id(), "wiki-target"))
                .thenReturn(Optional.of(wikiPage("wiki-target", "Target Page", 3, sha256("v3"))));
        when(wikiContentReader.readSearchableContent(anyPage())).thenReturn("content");
        GraphTraversalResult traversal = result(SNAPSHOT_A,
                wikiCandidate("wiki-target", 1, new GraphFreshness(3, sha256("v3")),
                        GraphRelationType.LINKS_TO),
                wikiCandidate("wiki-target", 2, new GraphFreshness(3, sha256("v3")),
                        GraphRelationType.LINKS_TO));

        GraphEvidenceAdmissionResult admitted = service.admit(request(traversal));

        assertThat(admitted.evidenceItems()).hasSize(1);
        assertThat(admitted.rejections().getFirst().reason())
                .isEqualTo(GraphEvidenceRejectionReason.DUPLICATE_IDENTITY);
    }

    @Test
    void graphContributionNeverExceedsTheHardAdmissionBudget() {
        when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE.id(), "wiki-one"))
                .thenReturn(Optional.of(wikiPage("wiki-one", "One", 1, sha256("v1"))));
        when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE.id(), "wiki-two"))
                .thenReturn(Optional.of(wikiPage("wiki-two", "Two", 1, sha256("v1"))));
        when(wikiContentReader.readSearchableContent(anyPage())).thenReturn("0123456789ABCDEF");
        GraphTraversalResult traversal = result(SNAPSHOT_A,
                wikiCandidate("wiki-one", 1, new GraphFreshness(1, sha256("v1")),
                        GraphRelationType.LINKS_TO),
                wikiCandidate("wiki-two", 1, new GraphFreshness(1, sha256("v1")),
                        GraphRelationType.LINKS_TO));

        GraphEvidenceAdmissionResult itemBounded = service.admit(request(traversal,
                new GraphEvidenceAdmissionBudget(1, 100)));
        assertThat(itemBounded.evidenceItems()).hasSize(1);
        assertThat(itemBounded.budgetTruncated()).isTrue();

        GraphEvidenceAdmissionResult characterBounded = service.admit(request(traversal,
                new GraphEvidenceAdmissionBudget(4, 10)));
        assertThat(characterBounded.evidenceItems()).hasSize(1);
        assertThat(characterBounded.evidenceItems().getFirst().contentTruncated()).isTrue();
        assertThat(characterBounded.evidenceItems().getFirst().content())
                .hasSize("0123456789".length());
        assertThat(characterBounded.budget().usedCharacters()).isEqualTo(10);
        assertThat(characterBounded.budget().estimatedTokens()).isEqualTo((10 + 3) / 4);
        assertThat(characterBounded.budgetTruncated()).isTrue();
    }

    @Test
    void authorityReadFailureIsTypedAndNeverMaskedAsEmptyEvidence() {
        when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE.id(), "wiki-target"))
                .thenThrow(new org.jooq.exception.DataAccessException("database unavailable"));
        assertUnavailable(RetrievalUnavailableException.Dependency.WIKI_AUTHORITY, () ->
                service.admit(request(result(SNAPSHOT_A, wikiCandidate("wiki-target", 1,
                        new GraphFreshness(3, sha256("v3")), GraphRelationType.LINKS_TO)))));

        when(sourceRepository.findDocument(WORKSPACE.id(), 9L))
                .thenThrow(new org.jooq.exception.DataAccessException("database unavailable"));
        assertUnavailable(RetrievalUnavailableException.Dependency.SOURCE_AUTHORITY, () ->
                service.admit(request(result(SNAPSHOT_A,
                        sourceChunkCandidate(9L, 1, sha256("chunk content"))))));
    }

    @Test
    void deterministicRevalidationProducesIdenticalOutcomesAcrossRepeatedAdmission() {
        when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE.id(), "wiki-target"))
                .thenReturn(Optional.of(wikiPage("wiki-target", "Target Page", 3, sha256("v3"))));
        when(wikiContentReader.readSearchableContent(anyPage())).thenReturn("stable content");
        when(sourceRepository.findDocument(WORKSPACE.id(), 9L)).thenReturn(Optional.of(
                sourceDocument(9L, "PROCESSED",
                        chunk(77L, 1, "chunk content", sha256("chunk content")))));
        GraphTraversalResult traversal = result(SNAPSHOT_A,
                wikiCandidate("wiki-target", 1, new GraphFreshness(3, sha256("v3")),
                        GraphRelationType.LINKS_TO),
                sourceChunkCandidate(9L, 1, sha256("chunk content")));

        GraphEvidenceAdmissionResult first = service.admit(request(traversal));
        GraphEvidenceAdmissionResult second = service.admit(request(traversal));

        assertThat(second).isEqualTo(first);
    }

    private static void assertFailure(GraphProjectionFailureType expected,
                                      org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(GraphProjectionException.class)
                .extracting(failure -> ((GraphProjectionException) failure).failureType())
                .isEqualTo(expected);
    }

    private static void assertUnavailable(RetrievalUnavailableException.Dependency dependency,
                                          org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(RetrievalUnavailableException.class)
                .extracting(failure -> ((RetrievalUnavailableException) failure).dependency())
                .isEqualTo(dependency);
    }

    private GraphEvidenceAdmissionRequest request(GraphTraversalResult traversal) {
        return GraphEvidenceAdmissionRequest.of(new EvidenceWorkspace(WORKSPACE.id(), "test"),
                traversal);
    }

    private GraphEvidenceAdmissionRequest request(GraphTraversalResult traversal,
                                                  GraphEvidenceAdmissionBudget budget) {
        return new GraphEvidenceAdmissionRequest(new EvidenceWorkspace(WORKSPACE.id(), "test"),
                traversal, budget);
    }

    private static StoredPublishedWiki wikiPage(String knowledgeId, String title, int revision,
                                                String contentHash) {
        return new StoredPublishedWiki(1L, WORKSPACE.id(), knowledgeId, title, title,
                WikiPageType.CONCEPT, "vault/concepts/" + title + ".md", PageStatus.PUBLISHED,
                contentHash, revision, NOW, NOW);
    }

    private static SourceSearchAuthorityDocument sourceDocument(long documentId, String parseStatus,
                                                                SourceSearchAuthorityChunk... chunks) {
        return new SourceSearchAuthorityDocument(WORKSPACE.id(), documentId,
                "source-" + documentId + ".txt", sha256("document-" + documentId), "ACTIVE",
                parseStatus, List.of(chunks));
    }

    private static SourceSearchAuthorityChunk chunk(long sourceChunkId, int chunkNo,
                                                    String content, String contentHash) {
        return new SourceSearchAuthorityChunk(sourceChunkId, chunkNo, null, null, null, content,
                contentHash);
    }

    private static GraphTraversalResult result(GraphProjectionSnapshot snapshot,
                                               GraphTraversalCandidate... candidates) {
        int visitedEdges = 0;
        for (GraphTraversalCandidate candidate : candidates) {
            visitedEdges += candidate.path().size();
        }
        return new GraphTraversalResult(snapshot, List.of(candidates),
                1 + candidates.length, visitedEdges, Set.of());
    }

    private static GraphTraversalCandidate wikiCandidate(String knowledgeId, int depth,
                                                         GraphFreshness freshness,
                                                         GraphRelationType... relationTypes) {
        return wikiCandidate(WORKSPACE, knowledgeId, depth, freshness,
                GraphAuthorityEligibility.ELIGIBLE, relationTypes);
    }

    private static GraphTraversalCandidate wikiCandidate(GraphWorkspaceScope workspace,
                                                         String knowledgeId, int depth,
                                                         GraphFreshness freshness,
                                                         GraphRelationType... relationTypes) {
        return wikiCandidate(workspace, knowledgeId, depth, freshness,
                GraphAuthorityEligibility.ELIGIBLE, relationTypes);
    }

    private static GraphTraversalCandidate wikiCandidate(String knowledgeId, int depth,
                                                         GraphFreshness freshness,
                                                         GraphAuthorityEligibility eligibility,
                                                         GraphRelationType... relationTypes) {
        return wikiCandidate(WORKSPACE, knowledgeId, depth, freshness, eligibility, relationTypes);
    }

    private static GraphTraversalCandidate wikiCandidate(GraphWorkspaceScope workspace,
                                                         String knowledgeId, int depth,
                                                         GraphFreshness freshness,
                                                         GraphAuthorityEligibility eligibility,
                                                         GraphRelationType... relationTypes) {
        GraphEntity entity = wikiEntity(workspace, knowledgeId, freshness, eligibility, VERSION);
        return chainCandidate(workspace, entity, depth, relationTypes);
    }

    private static GraphTraversalCandidate legacyWikiCandidate(String knowledgeId) {
        GraphEntity entity = wikiEntity(WORKSPACE, knowledgeId,
                new GraphFreshness(3, sha256("v3")), GraphAuthorityEligibility.ELIGIBLE,
                GraphProjectionVersion.legacyV1());
        return chainCandidate(WORKSPACE, entity, 1, GraphRelationType.LINKS_TO);
    }

    private static GraphTraversalCandidate sourceChunkCandidate(long documentId, int chunkNo,
                                                                String contentHash) {
        return sourceChunkCandidate(documentId, chunkNo,
                "document:" + documentId + ":chunk:" + chunkNo, contentHash);
    }

    /** Stable id and metadata disagree; the cross-check must fail closed. */
    private static GraphTraversalCandidate sourceChunkCandidateWithMetadata(long documentId,
                                                                            int chunkNo,
                                                                            int metadataChunkNo) {
        GraphAuthorityReference authority = new GraphAuthorityReference(WORKSPACE,
                GraphAuthorityKind.SOURCE_CHUNK,
                "document:" + documentId + ":chunk:" + chunkNo);
        GraphEntity entity = new GraphEntity(GraphEntityIdentity.fromAuthority(authority,
                GraphEntityType.SOURCE_CHUNK), "Chunk " + chunkNo,
                new GraphProvenance(authority, GraphFreshness.contentHash(sha256("chunk content")),
                        GraphAuthorityEligibility.ELIGIBLE, GraphMetadata.empty()),
                GraphMetadata.of(java.util.Map.of("chunk_no", Integer.toString(metadataChunkNo))),
                VERSION);
        GraphEntity seed = sourceDocumentEntity(documentId);
        GraphRelation relation = relation(seed, entity, GraphRelationType.CONTAINS, VERSION);
        return new GraphTraversalCandidate(seed.identity(), entity, 1, List.of(relation));
    }

    private static GraphTraversalCandidate sourceChunkCandidate(long documentId, int chunkNo,
                                                                String stableId,
                                                                String contentHash) {
        GraphAuthorityReference authority = new GraphAuthorityReference(WORKSPACE,
                GraphAuthorityKind.SOURCE_CHUNK, stableId);
        GraphEntity entity = new GraphEntity(GraphEntityIdentity.fromAuthority(authority,
                GraphEntityType.SOURCE_CHUNK), "Chunk " + chunkNo,
                new GraphProvenance(authority, GraphFreshness.contentHash(contentHash),
                        GraphAuthorityEligibility.ELIGIBLE, GraphMetadata.empty()),
                GraphMetadata.of(java.util.Map.of("chunk_no", Integer.toString(chunkNo))),
                VERSION);
        GraphEntity seed = sourceDocumentEntity(documentId);
        GraphRelation relation = relation(seed, entity, GraphRelationType.CONTAINS, VERSION);
        return new GraphTraversalCandidate(seed.identity(), entity, 1, List.of(relation));
    }

    private static GraphTraversalCandidate sourceDocumentCandidate(long documentId,
                                                                   String contentHash) {
        GraphEntity entity = sourceDocumentEntity(documentId, contentHash);
        return chainCandidate(WORKSPACE, entity, 1, GraphRelationType.DERIVED_FROM);
    }

    private static GraphTraversalCandidate metadataCandidate(GraphEntityType type, String stableId,
                                                             String displayName) {
        GraphAuthorityReference authority = new GraphAuthorityReference(WORKSPACE,
                GraphAuthorityKind.CANONICAL_METADATA, stableId);
        GraphEntity entity = new GraphEntity(GraphEntityIdentity.fromAuthority(authority, type),
                displayName,
                new GraphProvenance(authority, GraphFreshness.contentHash("f".repeat(64)),
                        GraphAuthorityEligibility.ELIGIBLE, GraphMetadata.empty()),
                GraphMetadata.empty(), VERSION);
        return chainCandidate(WORKSPACE, entity, 1, GraphRelationType.TAGGED_WITH);
    }

    private static GraphTraversalCandidate conceptCandidate(String value) {
        GraphAuthorityReference authority = new GraphAuthorityReference(WORKSPACE,
                GraphAuthorityKind.CANONICAL_METADATA, "concept:" + value);
        GraphEntity entity = new GraphEntity(GraphEntityIdentity.of(WORKSPACE,
                GraphEntityType.CONCEPT, "concept:" + value), value,
                new GraphProvenance(authority, GraphFreshness.contentHash("f".repeat(64)),
                        GraphAuthorityEligibility.ELIGIBLE, GraphMetadata.empty()),
                GraphMetadata.empty(), VERSION);
        return chainCandidate(WORKSPACE, entity, 1, GraphRelationType.LINKS_TO);
    }

    private static GraphTraversalCandidate chainCandidate(GraphWorkspaceScope workspace,
                                                          GraphEntity entity, int depth,
                                                          GraphRelationType... relationTypes) {
        GraphEntity previous = wikiEntity(workspace, "wiki-seed",
                new GraphFreshness(1, sha256("seed")), GraphAuthorityEligibility.ELIGIBLE,
                VERSION);
        GraphEntityIdentity seedIdentity = previous.identity();
        List<GraphRelation> path = new java.util.ArrayList<>();
        for (int hop = 0; hop < depth; hop++) {
            GraphEntity target = hop == depth - 1
                    ? entity
                    : wikiEntity(workspace, "wiki-hop-" + hop,
                    new GraphFreshness(1, sha256("hop" + hop)),
                    GraphAuthorityEligibility.ELIGIBLE, VERSION);
            path.add(relation(previous, target,
                    relationTypes.length == 1 ? relationTypes[0]
                            : relationTypes[Math.min(hop, relationTypes.length - 1)],
                    VERSION));
            previous = target;
        }
        return new GraphTraversalCandidate(seedIdentity, entity, depth, path);
    }

    private static GraphEntity wikiEntity(GraphWorkspaceScope workspace, String knowledgeId,
                                          GraphFreshness freshness,
                                          GraphAuthorityEligibility eligibility,
                                          GraphProjectionVersion version) {
        GraphAuthorityReference authority = new GraphAuthorityReference(workspace,
                GraphAuthorityKind.WIKI_PAGE, knowledgeId);
        return new GraphEntity(GraphEntityIdentity.fromAuthority(authority,
                GraphEntityType.WIKI_PAGE), knowledgeId,
                new GraphProvenance(authority, freshness, eligibility, GraphMetadata.empty()),
                GraphMetadata.empty(), version);
    }

    private static GraphEntity sourceDocumentEntity(long documentId) {
        return sourceDocumentEntity(documentId, sha256("document-" + documentId));
    }

    private static GraphEntity sourceDocumentEntity(long documentId, String contentHash) {
        GraphAuthorityReference authority = new GraphAuthorityReference(WORKSPACE,
                GraphAuthorityKind.SOURCE_DOCUMENT, "document:" + documentId);
        return new GraphEntity(GraphEntityIdentity.fromAuthority(authority,
                GraphEntityType.SOURCE_DOCUMENT), "doc-" + documentId,
                new GraphProvenance(authority, GraphFreshness.contentHash(contentHash),
                        GraphAuthorityEligibility.ELIGIBLE, GraphMetadata.empty()),
                GraphMetadata.empty(), VERSION);
    }

    private static GraphRelation relation(GraphEntity source, GraphEntity target,
                                          GraphRelationType type, GraphProjectionVersion version) {
        return new GraphRelation(GraphRelationIdentity.of(source.identity(), type,
                target.identity()), source.identity(), type, target.identity(),
                source.provenance(), GraphMetadata.empty(), version);
    }

    private static org.km.llmwiki.wiki.StoredPublishedWiki anyPage() {
        return org.mockito.ArgumentMatchers.any(StoredPublishedWiki.class);
    }

    private static GraphProjectionVerification ready(GraphProjectionSnapshot snapshot) {
        return verification(GraphProjectionVerificationStatus.READY, readyControl(snapshot), null);
    }

    private static GraphProjectionVerification verification(
            GraphProjectionVerificationStatus status, GraphProjectionReadiness control,
            GraphProjectionFailure failure) {
        return new GraphProjectionVerification(WORKSPACE, status, control, failure);
    }

    private static GraphProjectionReadiness readyControl(GraphProjectionSnapshot snapshot) {
        return new GraphProjectionReadiness(WORKSPACE, "arcadedb", VERSION,
                GraphProjectionReadinessStatus.READY, snapshot.generation(), snapshot.generation(),
                snapshot.sourceFingerprint(), snapshot.snapshotToken(), null, null, null, null,
                null, null, NOW, NOW);
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content.getBytes()));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
