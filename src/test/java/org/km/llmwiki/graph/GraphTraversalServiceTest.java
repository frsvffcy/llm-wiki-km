package org.km.llmwiki.graph;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
class GraphTraversalServiceTest {

    private static final GraphWorkspaceScope WORKSPACE = new GraphWorkspaceScope(41);
    private static final GraphProjectionVersion VERSION = GraphProjectionVersion.initial();
    private static final GraphProjectionSnapshot SNAPSHOT_A = GraphProjectionSnapshot.fromProof(
            WORKSPACE, VERSION, 7, "a".repeat(64));
    private static final GraphProjectionSnapshot SNAPSHOT_B = GraphProjectionSnapshot.fromProof(
            WORKSPACE, VERSION, 8, "b".repeat(64));
    private static final String NOW = "2026-09-07T00:00:00Z";

    @Test
    void exactSnapshotIsCheckedBeforeAndAfterMaterializationAndBeforeServing() {
        GraphProjectionReadinessReader lifecycle = mock(GraphProjectionReadinessReader.class);
        GraphTraversalBackendFactory factory = mock(GraphTraversalBackendFactory.class);
        GraphTraversalBackend backend = mock(GraphTraversalBackend.class);
        GraphTraversalQuery query = query(SNAPSHOT_A);
        GraphTraversalResult expected = emptyResult(SNAPSHOT_A);
        when(lifecycle.readiness(WORKSPACE)).thenReturn(ready(SNAPSHOT_A), ready(SNAPSHOT_A));
        when(factory.projectionVersion()).thenReturn(VERSION);
        when(factory.openTraversal(WORKSPACE)).thenReturn(Optional.of(backend));
        when(backend.readProof(WORKSPACE)).thenReturn(proof(SNAPSHOT_A));
        when(backend.traverse(query)).thenReturn(expected);

        GraphTraversalResult actual = new GraphTraversalService(lifecycle, factory).traverse(query);

        assertThat(actual).isSameAs(expected);
        InOrder order = inOrder(lifecycle, factory, backend);
        order.verify(lifecycle).readiness(WORKSPACE);
        order.verify(factory).openTraversal(WORKSPACE);
        order.verify(backend).readProof(WORKSPACE);
        order.verify(backend).traverse(query);
        order.verify(backend).readProof(WORKSPACE);
        order.verify(backend).close();
        order.verify(lifecycle).readiness(WORKSPACE);
    }

    @Test
    void generationPublishedDuringTraversalInvalidatesMaterializedTopology() {
        GraphProjectionReadinessReader lifecycle = mock(GraphProjectionReadinessReader.class);
        GraphTraversalBackendFactory factory = mock(GraphTraversalBackendFactory.class);
        GraphTraversalBackend backend = mock(GraphTraversalBackend.class);
        GraphTraversalQuery query = query(SNAPSHOT_A);
        when(lifecycle.readiness(WORKSPACE)).thenReturn(ready(SNAPSHOT_A), ready(SNAPSHOT_B));
        when(factory.projectionVersion()).thenReturn(VERSION);
        when(factory.openTraversal(WORKSPACE)).thenReturn(Optional.of(backend));
        when(backend.readProof(WORKSPACE)).thenReturn(proof(SNAPSHOT_A));
        when(backend.traverse(query)).thenReturn(emptyResult(SNAPSHOT_A));

        assertFailure(GraphProjectionFailureType.PROJECTION_STALE,
                () -> new GraphTraversalService(lifecycle, factory).traverse(query));
        verify(backend).close();
    }

    @Test
    void backendProofChangingAfterTraversalFailsBeforeFinalReadiness() {
        GraphProjectionReadinessReader lifecycle = mock(GraphProjectionReadinessReader.class);
        GraphTraversalBackendFactory factory = mock(GraphTraversalBackendFactory.class);
        GraphTraversalBackend backend = mock(GraphTraversalBackend.class);
        GraphTraversalQuery query = query(SNAPSHOT_A);
        when(lifecycle.readiness(WORKSPACE)).thenReturn(ready(SNAPSHOT_A));
        when(factory.projectionVersion()).thenReturn(VERSION);
        when(factory.openTraversal(WORKSPACE)).thenReturn(Optional.of(backend));
        when(backend.readProof(WORKSPACE)).thenReturn(proof(SNAPSHOT_A), proof(SNAPSHOT_B));
        when(backend.traverse(query)).thenReturn(emptyResult(SNAPSHOT_A));

        assertFailure(GraphProjectionFailureType.PROJECTION_STALE,
                () -> new GraphTraversalService(lifecycle, factory).traverse(query));
        verify(lifecycle).readiness(WORKSPACE);
        verify(backend).close();
    }

    @Test
    void sameGenerationConflictingProofIsReportedAsCorrupt() {
        GraphProjectionSnapshot conflicting = GraphProjectionSnapshot.fromProof(WORKSPACE, VERSION,
                SNAPSHOT_A.generation(), "c".repeat(64));
        GraphProjectionReadinessReader lifecycle = mock(GraphProjectionReadinessReader.class);
        GraphTraversalBackendFactory factory = mock(GraphTraversalBackendFactory.class);
        GraphTraversalBackend backend = mock(GraphTraversalBackend.class);
        when(lifecycle.readiness(WORKSPACE)).thenReturn(ready(SNAPSHOT_A));
        when(factory.projectionVersion()).thenReturn(VERSION);
        when(factory.openTraversal(WORKSPACE)).thenReturn(Optional.of(backend));
        when(backend.readProof(WORKSPACE)).thenReturn(proof(conflicting));

        assertFailure(GraphProjectionFailureType.PROJECTION_CORRUPT,
                () -> new GraphTraversalService(lifecycle, factory).traverse(query(SNAPSHOT_A)));
        verify(backend, never()).traverse(query(SNAPSHOT_A));
    }

    @Test
    void sameGenerationConflictingFinalControlPlaneProofIsReportedAsCorrupt() {
        GraphProjectionSnapshot conflicting = GraphProjectionSnapshot.fromProof(WORKSPACE, VERSION,
                SNAPSHOT_A.generation(), "c".repeat(64));
        GraphProjectionReadinessReader lifecycle = mock(GraphProjectionReadinessReader.class);
        GraphTraversalBackendFactory factory = mock(GraphTraversalBackendFactory.class);
        GraphTraversalBackend backend = mock(GraphTraversalBackend.class);
        GraphTraversalQuery query = query(SNAPSHOT_A);
        when(lifecycle.readiness(WORKSPACE)).thenReturn(ready(SNAPSHOT_A), ready(conflicting));
        when(factory.projectionVersion()).thenReturn(VERSION);
        when(factory.openTraversal(WORKSPACE)).thenReturn(Optional.of(backend));
        when(backend.readProof(WORKSPACE)).thenReturn(proof(SNAPSHOT_A));
        when(backend.traverse(query)).thenReturn(emptyResult(SNAPSHOT_A));

        assertFailure(GraphProjectionFailureType.PROJECTION_CORRUPT,
                () -> new GraphTraversalService(lifecycle, factory).traverse(query));
        verify(backend).close();
    }

    @Test
    void malformedMaterializedTopologyAndDuplicateCandidateFailClosed() {
        GraphEntity seed = entity("seed");
        GraphEntity target = entity("target");
        GraphRelation relation = relation(seed, target, VERSION);
        GraphTraversalCandidate candidate = new GraphTraversalCandidate(seed.identity(), target,
                1, List.of(relation));

        assertMaterializedFailure(new GraphTraversalResult(SNAPSHOT_A,
                List.of(candidate, candidate), 3, 1, Set.of()));

        GraphProjectionVersion incompatible = GraphProjectionVersion.legacyV1();
        GraphRelation wrongVersion = relation(seed, target, incompatible);
        assertMaterializedFailure(new GraphTraversalResult(SNAPSHOT_A,
                List.of(new GraphTraversalCandidate(seed.identity(), target, 1,
                        List.of(wrongVersion))), 2, 1, Set.of()));

        assertMaterializedFailure(new GraphTraversalResult(SNAPSHOT_A,
                List.of(candidate), 1, 1, Set.of()));
        assertMaterializedFailure(new GraphTraversalResult(SNAPSHOT_A,
                List.of(candidate), 2, 0, Set.of()));
    }

    @Test
    void disabledAndBackendUnavailableRemainDistinctTypedFailures() {
        GraphProjectionReadinessReader lifecycle = mock(GraphProjectionReadinessReader.class);
        GraphTraversalBackendFactory factory = mock(GraphTraversalBackendFactory.class);
        when(lifecycle.readiness(WORKSPACE)).thenReturn(verification(
                GraphProjectionVerificationStatus.DISABLED, null, null));
        assertFailure(GraphProjectionFailureType.CAPABILITY_DISABLED,
                () -> new GraphTraversalService(lifecycle, factory).traverse(query(SNAPSHOT_A)));
        verify(factory, never()).openTraversal(WORKSPACE);

        when(lifecycle.readiness(WORKSPACE)).thenReturn(verification(
                GraphProjectionVerificationStatus.BACKEND_UNAVAILABLE, readyControl(SNAPSHOT_A),
                GraphProjectionFailure.of(GraphProjectionFailureType.BACKEND_LOCKED)));
        assertFailure(GraphProjectionFailureType.BACKEND_LOCKED,
                () -> new GraphTraversalService(lifecycle, factory).traverse(query(SNAPSHOT_A)));

        when(lifecycle.readiness(WORKSPACE)).thenReturn(verification(
                GraphProjectionVerificationStatus.NOT_READY, null,
                GraphProjectionFailure.of(GraphProjectionFailureType.PROJECTION_NOT_READY)));
        assertFailure(GraphProjectionFailureType.PROJECTION_NOT_READY,
                () -> new GraphTraversalService(lifecycle, factory).traverse(query(SNAPSHOT_A)));

        when(lifecycle.readiness(WORKSPACE)).thenReturn(verification(
                GraphProjectionVerificationStatus.STALE, readyControl(SNAPSHOT_A),
                GraphProjectionFailure.of(GraphProjectionFailureType.PROJECTION_STALE)));
        assertFailure(GraphProjectionFailureType.PROJECTION_STALE,
                () -> new GraphTraversalService(lifecycle, factory).traverse(query(SNAPSHOT_A)));
    }

    @Test
    void missingReadCapabilityAndProjectionVersionDriftFailBeforeBackendOpen() {
        GraphProjectionReadinessReader lifecycle = mock(GraphProjectionReadinessReader.class);
        when(lifecycle.readiness(WORKSPACE)).thenReturn(ready(SNAPSHOT_A));

        assertFailure(GraphProjectionFailureType.CAPABILITY_UNAVAILABLE,
                () -> new GraphTraversalService(lifecycle, null).traverse(query(SNAPSHOT_A)));

        GraphTraversalBackendFactory incompatible = mock(GraphTraversalBackendFactory.class);
        when(incompatible.projectionVersion()).thenReturn(GraphProjectionVersion.legacyV1());
        assertFailure(GraphProjectionFailureType.PROJECTION_INCOMPATIBLE,
                () -> new GraphTraversalService(lifecycle, incompatible)
                        .traverse(query(SNAPSHOT_A)));
        verify(incompatible, never()).openTraversal(WORKSPACE);
    }

    private static GraphTraversalQuery query(GraphProjectionSnapshot snapshot) {
        return new GraphTraversalQuery(WORKSPACE, List.of(identity("seed")),
                Set.of(GraphRelationType.LINKS_TO),
                new GraphTraversalBounds(2, 4, 8, 16, 16, 8),
                GraphTraversalOrdering.DEPTH_SEED_ENTITY_PATH_V1, snapshot);
    }

    private static GraphEntityIdentity identity(String id) {
        return GraphEntityIdentity.fromAuthority(new GraphAuthorityReference(WORKSPACE,
                GraphAuthorityKind.WIKI_PAGE, id), GraphEntityType.WIKI_PAGE);
    }

    private static GraphEntity entity(String id) {
        GraphAuthorityReference authority = new GraphAuthorityReference(WORKSPACE,
                GraphAuthorityKind.WIKI_PAGE, id);
        return new GraphEntity(GraphEntityIdentity.fromAuthority(authority,
                GraphEntityType.WIKI_PAGE), id,
                new GraphProvenance(authority, GraphFreshness.revision(1),
                        GraphAuthorityEligibility.ELIGIBLE, GraphMetadata.empty()),
                GraphMetadata.empty(), VERSION);
    }

    private static GraphRelation relation(GraphEntity source, GraphEntity target,
                                          GraphProjectionVersion version) {
        return new GraphRelation(GraphRelationIdentity.of(source.identity(),
                GraphRelationType.LINKS_TO, target.identity()), source.identity(),
                GraphRelationType.LINKS_TO, target.identity(), source.provenance(),
                GraphMetadata.empty(), version);
    }

    private static void assertMaterializedFailure(GraphTraversalResult result) {
        GraphProjectionReadinessReader lifecycle = mock(GraphProjectionReadinessReader.class);
        GraphTraversalBackendFactory factory = mock(GraphTraversalBackendFactory.class);
        GraphTraversalBackend backend = mock(GraphTraversalBackend.class);
        GraphTraversalQuery query = query(SNAPSHOT_A);
        when(lifecycle.readiness(WORKSPACE)).thenReturn(ready(SNAPSHOT_A));
        when(factory.projectionVersion()).thenReturn(VERSION);
        when(factory.openTraversal(WORKSPACE)).thenReturn(Optional.of(backend));
        when(backend.readProof(WORKSPACE)).thenReturn(proof(SNAPSHOT_A));
        when(backend.traverse(query)).thenReturn(result);

        assertFailure(GraphProjectionFailureType.PROJECTION_CORRUPT,
                () -> new GraphTraversalService(lifecycle, factory).traverse(query));
    }

    private static GraphTraversalResult emptyResult(GraphProjectionSnapshot snapshot) {
        return new GraphTraversalResult(snapshot, List.of(), 1, 0, Set.of());
    }

    private static GraphProjectionBackendProof proof(GraphProjectionSnapshot snapshot) {
        return new GraphProjectionBackendProof(WORKSPACE, snapshot, null);
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

    private static void assertFailure(GraphProjectionFailureType expected,
                                      org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable).isInstanceOf(GraphProjectionException.class)
                .extracting(failure -> ((GraphProjectionException) failure).failureType())
                .isEqualTo(expected);
    }
}
