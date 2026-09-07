package org.km.llmwiki.graph;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("unit")
class GraphProjectionLifecycleServiceTest {

    private static final GraphWorkspaceScope WORKSPACE = new GraphWorkspaceScope(41);
    private static final GraphProjectionVersion VERSION = GraphProjectionVersion.initial();
    private static final String PROVIDER = "arcadedb";
    private static final String FIRST_FINGERPRINT = "a".repeat(64);
    private static final String SECOND_FINGERPRINT = "b".repeat(64);
    private static final String NOW = "2026-09-05T00:00:00Z";

    @Test
    void disabledAndMissingCapabilityRemainDistinctWithoutOpeningBackend() {
        GraphProjectionLifecycleRepository repository = mock(GraphProjectionLifecycleRepository.class);
        GraphProjectionBackendFactory factory = mockFactory();
        when(repository.find(WORKSPACE)).thenReturn(Optional.empty());

        try (var disabled = service(false, repository, factory)) {
            assertThat(disabled.readiness(WORKSPACE).status())
                    .isEqualTo(GraphProjectionVerificationStatus.DISABLED);
            assertFailure(GraphProjectionFailureType.CAPABILITY_UNAVAILABLE,
                    () -> disabled.rebuild(input()));
        }
        verify(factory).close();
        verify(factory, never()).openForWrite(WORKSPACE);

        var notConfigured = service(true, repository, null);
        assertThat(notConfigured.readiness(WORKSPACE).status())
                .isEqualTo(GraphProjectionVerificationStatus.NOT_CONFIGURED);
        assertFailure(GraphProjectionFailureType.CONFIGURATION_INVALID,
                () -> notConfigured.rebuild(input()));
    }

    @Test
    void rebuildPublishesBackendProofBeforeCommittingSqliteReady() {
        GraphProjectionLifecycleRepository repository = mock(GraphProjectionLifecycleRepository.class);
        GraphProjectionBackendFactory factory = mockFactory();
        GraphProjectionBackend backend = mock(GraphProjectionBackend.class);
        GraphProjectionInput input = input();
        GraphProjectionOperation operation = operation(GraphProjectionOperationKind.REBUILD, 1,
                input.sourceFingerprint(), null);
        GraphProjectionReadiness ready = ready(operation.targetSnapshot());
        when(repository.reserve(WORKSPACE, PROVIDER, VERSION,
                GraphProjectionOperationKind.REBUILD, input.sourceFingerprint(), "owner"))
                .thenReturn(operation);
        when(factory.openForWrite(WORKSPACE)).thenReturn(backend);
        when(backend.rebuild(input, operation.targetSnapshot())).thenReturn(operation.targetSnapshot());
        when(backend.readProof(WORKSPACE)).thenReturn(
                new GraphProjectionBackendProof(WORKSPACE, operation.targetSnapshot(), null));
        when(repository.markReady(operation, operation.targetSnapshot())).thenReturn(true);
        when(repository.find(WORKSPACE)).thenReturn(Optional.of(ready));

        GraphProjectionVerification result = service(true, repository, factory).rebuild(input);

        assertThat(result.ready()).isTrue();
        InOrder ordering = inOrder(repository, factory, backend);
        ordering.verify(repository).reserve(WORKSPACE, PROVIDER, VERSION,
                GraphProjectionOperationKind.REBUILD, input.sourceFingerprint(), "owner");
        ordering.verify(factory).openForWrite(WORKSPACE);
        ordering.verify(backend).rebuild(input, operation.targetSnapshot());
        ordering.verify(backend).readProof(WORKSPACE);
        ordering.verify(backend).close();
        ordering.verify(repository).markReady(operation, operation.targetSnapshot());
        ordering.verify(repository).find(WORKSPACE);
    }

    @Test
    void programmingFailureIsRecordedButNotDisguisedAsBackendUnavailable() {
        GraphProjectionLifecycleRepository repository = mock(GraphProjectionLifecycleRepository.class);
        GraphProjectionBackendFactory factory = mockFactory();
        GraphProjectionBackend backend = mock(GraphProjectionBackend.class);
        GraphProjectionInput input = input();
        GraphProjectionOperation operation = operation(GraphProjectionOperationKind.REBUILD, 1,
                input.sourceFingerprint(), null);
        var programmingFailure = new IllegalStateException("application invariant failed");
        when(repository.reserve(WORKSPACE, PROVIDER, VERSION,
                GraphProjectionOperationKind.REBUILD, input.sourceFingerprint(), "owner"))
                .thenReturn(operation);
        when(factory.openForWrite(WORKSPACE)).thenReturn(backend);
        when(backend.rebuild(input, operation.targetSnapshot())).thenThrow(programmingFailure);

        assertThatThrownBy(() -> service(true, repository, factory).rebuild(input))
                .isSameAs(programmingFailure);
        verify(repository).markFailed(operation,
                GraphProjectionFailure.of(GraphProjectionFailureType.LOCAL_VALIDATION));
    }

    @Test
    void missingBackendDegradesSqliteReadyProofAndRequiresRepair() {
        GraphProjectionLifecycleRepository repository = mock(GraphProjectionLifecycleRepository.class);
        GraphProjectionBackendFactory factory = mockFactory();
        GraphProjectionSnapshot snapshot = snapshot(1, FIRST_FINGERPRINT);
        GraphProjectionReadiness ready = ready(snapshot);
        GraphProjectionReadiness degraded = failed(ready, GraphProjectionReadinessStatus.DEGRADED,
                GraphProjectionFailureType.PROJECTION_NOT_READY);
        when(repository.find(WORKSPACE)).thenReturn(Optional.of(ready), Optional.of(degraded));
        when(factory.openExisting(WORKSPACE)).thenReturn(Optional.empty());
        when(repository.markDegraded(WORKSPACE, snapshot,
                GraphProjectionFailure.of(GraphProjectionFailureType.PROJECTION_NOT_READY)))
                .thenReturn(true);

        GraphProjectionVerification result = service(true, repository, factory).readiness(WORKSPACE);

        assertThat(result.status()).isEqualTo(GraphProjectionVerificationStatus.REPAIR_REQUIRED);
        assertThat(result.controlPlane()).isEqualTo(degraded);
        assertThat(result.ready()).isFalse();
    }

    @Test
    void persistedBackendFailureRemainsBackendUnavailableUntilRepairSucceeds() {
        GraphProjectionLifecycleRepository repository = mock(GraphProjectionLifecycleRepository.class);
        GraphProjectionBackendFactory factory = mockFactory();
        GraphProjectionReadiness failed = failed(ready(snapshot(1, FIRST_FINGERPRINT)),
                GraphProjectionReadinessStatus.DEGRADED,
                GraphProjectionFailureType.BACKEND_LOCKED);
        when(repository.find(WORKSPACE)).thenReturn(Optional.of(failed));

        GraphProjectionVerification result = service(true, repository, factory)
                .readiness(WORKSPACE);

        assertThat(result.status())
                .isEqualTo(GraphProjectionVerificationStatus.BACKEND_UNAVAILABLE);
        assertThat(result.failure().type()).isEqualTo(GraphProjectionFailureType.BACKEND_LOCKED);
        assertThat(result.ready()).isFalse();
        verify(factory, never()).openExisting(WORKSPACE);
    }

    @Test
    void lostDegradationCasRevalidatesTheNewerReadyGeneration() {
        GraphProjectionLifecycleRepository repository = mock(GraphProjectionLifecycleRepository.class);
        GraphProjectionBackendFactory factory = mockFactory();
        GraphProjectionBackend backend = mock(GraphProjectionBackend.class);
        GraphProjectionSnapshot oldSnapshot = snapshot(1, FIRST_FINGERPRINT);
        GraphProjectionSnapshot newerSnapshot = snapshot(2, SECOND_FINGERPRINT);
        GraphProjectionReadiness oldReady = ready(oldSnapshot);
        GraphProjectionReadiness newerReady = ready(newerSnapshot);
        when(repository.find(WORKSPACE)).thenReturn(Optional.of(oldReady), Optional.of(newerReady));
        when(factory.openExisting(WORKSPACE)).thenReturn(Optional.empty(), Optional.of(backend));
        when(repository.markDegraded(WORKSPACE, oldSnapshot,
                GraphProjectionFailure.of(GraphProjectionFailureType.PROJECTION_NOT_READY)))
                .thenReturn(false);
        when(backend.readProof(WORKSPACE)).thenReturn(
                new GraphProjectionBackendProof(WORKSPACE, newerSnapshot, null));

        GraphProjectionVerification result = service(true, repository, factory).readiness(WORKSPACE);

        assertThat(result.status()).isEqualTo(GraphProjectionVerificationStatus.READY);
        assertThat(result.controlPlane()).isEqualTo(newerReady);
        assertThat(result.ready()).isTrue();
    }

    @Test
    void restartReconcilesPublishedGenerationButFailsClosedBeforePublish() {
        GraphProjectionLifecycleRepository repository = mock(GraphProjectionLifecycleRepository.class);
        GraphProjectionBackendFactory factory = mockFactory();
        GraphProjectionBackend publishedBackend = mock(GraphProjectionBackend.class);
        GraphProjectionBackend stagedBackend = mock(GraphProjectionBackend.class);
        GraphProjectionOperation published = operation(GraphProjectionOperationKind.REBUILD, 1,
                FIRST_FINGERPRINT, null);
        GraphProjectionOperation staged = operation(GraphProjectionOperationKind.REPAIR, 2,
                SECOND_FINGERPRINT, null);
        when(repository.findActive()).thenReturn(List.of(active(published), active(staged)));
        when(factory.openExisting(WORKSPACE)).thenReturn(
                Optional.of(publishedBackend), Optional.of(stagedBackend));
        when(publishedBackend.readProof(WORKSPACE)).thenReturn(
                new GraphProjectionBackendProof(WORKSPACE, published.targetSnapshot(), null));
        when(stagedBackend.readProof(WORKSPACE)).thenReturn(GraphProjectionBackendProof.empty(WORKSPACE));

        service(true, repository, factory).reconcileInterruptedOperations();

        verify(repository).markReady(published, published.targetSnapshot());
        verify(repository).markFailed(staged,
                GraphProjectionFailure.of(GraphProjectionFailureType.PROJECTION_STALE));
    }

    @Test
    void clearRejectsConflictingBackendProofBeforeSqliteCommit() {
        GraphProjectionLifecycleRepository repository = mock(GraphProjectionLifecycleRepository.class);
        GraphProjectionBackendFactory factory = mockFactory();
        GraphProjectionBackend backend = mock(GraphProjectionBackend.class);
        GraphProjectionSnapshot expected = snapshot(1, FIRST_FINGERPRINT);
        GraphProjectionOperation clear = operation(GraphProjectionOperationKind.CLEAR, 2,
                FIRST_FINGERPRINT, expected);
        when(repository.find(WORKSPACE)).thenReturn(Optional.of(ready(expected)));
        when(repository.reserve(WORKSPACE, PROVIDER, VERSION, GraphProjectionOperationKind.CLEAR,
                FIRST_FINGERPRINT, "owner")).thenReturn(clear);
        when(factory.openExisting(WORKSPACE)).thenReturn(Optional.of(backend));
        when(backend.clearWorkspace(WORKSPACE, expected)).thenReturn(
                new GraphProjectionWriteResult(GraphProjectionWriteStatus.APPLIED, WORKSPACE, 1));
        when(backend.readProof(WORKSPACE)).thenReturn(
                new GraphProjectionBackendProof(WORKSPACE, snapshot(2, SECOND_FINGERPRINT), null));

        assertFailure(GraphProjectionFailureType.INVALID_PROJECTION_INPUT,
                () -> service(true, repository, factory).clear(WORKSPACE));
        verify(repository, never()).markCleared(clear);
        verify(repository).markFailed(clear,
                GraphProjectionFailure.of(GraphProjectionFailureType.INVALID_PROJECTION_INPUT));
    }

    @Test
    void incompatibleSqliteAuthorityNeverOpensBackend() {
        GraphProjectionLifecycleRepository repository = mock(GraphProjectionLifecycleRepository.class);
        GraphProjectionBackendFactory factory = mockFactory();
        GraphProjectionSnapshot snapshot = snapshot(1, FIRST_FINGERPRINT);
        GraphProjectionReadiness incompatible = new GraphProjectionReadiness(WORKSPACE, "other",
                VERSION, GraphProjectionReadinessStatus.READY, 1, 1,
                snapshot.sourceFingerprint(), snapshot.snapshotToken(), null, null, null, null,
                null, null, NOW, NOW);
        when(repository.find(WORKSPACE)).thenReturn(Optional.of(incompatible));

        assertThat(service(true, repository, factory).readiness(WORKSPACE).status())
                .isEqualTo(GraphProjectionVerificationStatus.PROJECTION_INCOMPATIBLE);
        verify(factory, never()).openExisting(WORKSPACE);
    }

    private static GraphProjectionLifecycleService service(boolean enabled,
                                                           GraphProjectionLifecycleRepository repository,
                                                           GraphProjectionBackendFactory factory) {
        return new GraphProjectionLifecycleService(enabled, PROVIDER, VERSION, repository, factory,
                () -> "owner", new org.km.llmwiki.testsupport.AssumedCurrentGraphFixture());
    }

    private static GraphProjectionBackendFactory mockFactory() {
        GraphProjectionBackendFactory factory = mock(GraphProjectionBackendFactory.class);
        when(factory.provider()).thenReturn(PROVIDER);
        when(factory.projectionVersion()).thenReturn(VERSION);
        return factory;
    }

    private static GraphProjectionInput input() {
        return new GraphProjectionInput(WORKSPACE, VERSION, List.of(), List.of());
    }

    private static GraphProjectionSnapshot snapshot(long generation, String fingerprint) {
        return GraphProjectionSnapshot.fromProof(WORKSPACE, VERSION, generation, fingerprint);
    }

    private static GraphProjectionOperation operation(GraphProjectionOperationKind kind,
                                                       long generation, String fingerprint,
                                                       GraphProjectionSnapshot expected) {
        return new GraphProjectionOperation(WORKSPACE, PROVIDER, VERSION, kind, generation,
                "owner", fingerprint, NOW, expected);
    }

    private static GraphProjectionReadiness active(GraphProjectionOperation operation) {
        GraphProjectionSnapshot applied = operation.expectedAppliedSnapshot();
        return new GraphProjectionReadiness(WORKSPACE, PROVIDER, VERSION, kindStatus(operation.kind()),
                operation.generation(), applied == null ? 0 : applied.generation(),
                applied == null ? null : applied.sourceFingerprint(),
                applied == null ? null : applied.snapshotToken(), operation.kind(),
                operation.ownerToken(), operation.sourceFingerprint(), operation.startedAt(),
                null, null, null, NOW);
    }

    private static GraphProjectionReadinessStatus kindStatus(GraphProjectionOperationKind kind) {
        return kind.activeStatus();
    }

    private static GraphProjectionReadiness ready(GraphProjectionSnapshot snapshot) {
        return new GraphProjectionReadiness(WORKSPACE, PROVIDER, VERSION,
                GraphProjectionReadinessStatus.READY, snapshot.generation(), snapshot.generation(),
                snapshot.sourceFingerprint(), snapshot.snapshotToken(), null, null, null, null,
                null, null, NOW, NOW);
    }

    private static GraphProjectionReadiness failed(GraphProjectionReadiness baseline,
                                                    GraphProjectionReadinessStatus status,
                                                    GraphProjectionFailureType failure) {
        return new GraphProjectionReadiness(WORKSPACE, PROVIDER, VERSION, status,
                baseline.targetGeneration(), baseline.appliedGeneration(),
                baseline.sourceFingerprint(), baseline.snapshotToken(), null, null, null, null,
                failure, failure.publicCode(), baseline.lastSuccessfulAt(), NOW);
    }

    private static void assertFailure(GraphProjectionFailureType type,
                                      org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(GraphProjectionException.class)
                .extracting(failure -> ((GraphProjectionException) failure).failureType())
                .isEqualTo(type);
    }
}
