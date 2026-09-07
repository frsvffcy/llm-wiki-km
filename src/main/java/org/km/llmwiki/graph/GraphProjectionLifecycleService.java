package org.km.llmwiki.graph;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 協調 SQLite-authoritative lifecycle 與可重建的 Graph backend。
 *
 * <p>SQLite 先保留 monotonic generation，backend 完成寫入並提供 snapshot proof 後關閉
 * session，再以 canonical currentness guard 驗證 fingerprint 並提交 control-plane CAS。
 * 不建立跨資料庫交易，也不從記憶體鎖推論中斷後的 READY。
 */
public final class GraphProjectionLifecycleService
        implements GraphProjectionReadinessReader, AutoCloseable {

    private final boolean enabled;
    private final String configuredProvider;
    private final GraphProjectionVersion projectionVersion;
    private final GraphProjectionLifecycleRepository repository;
    private final GraphProjectionBackendFactory backendFactory;
    private final Supplier<String> ownerTokens;
    private final GraphCanonicalCurrentness currentness;

    /** 所有 lifecycle 都必須顯式提供 canonical currentness。 */
    public GraphProjectionLifecycleService(boolean enabled, String configuredProvider,
            GraphProjectionVersion projectionVersion, GraphProjectionLifecycleRepository repository,
            GraphProjectionBackendFactory backendFactory, GraphCanonicalCurrentness currentness) {
        this(enabled, configuredProvider, projectionVersion, repository, backendFactory,
                () -> UUID.randomUUID().toString(), java.util.Objects.requireNonNull(currentness));
    }

    GraphProjectionLifecycleService(boolean enabled, String configuredProvider,
            GraphProjectionVersion projectionVersion, GraphProjectionLifecycleRepository repository,
            GraphProjectionBackendFactory backendFactory, Supplier<String> ownerTokens,
            GraphCanonicalCurrentness currentness) {
        if (configuredProvider == null || projectionVersion == null || repository == null
                || ownerTokens == null || currentness == null) {
            throw new IllegalArgumentException("Graph projection lifecycle configuration is incomplete");
        }
        this.enabled = enabled;
        this.configuredProvider = configuredProvider.strip();
        this.projectionVersion = projectionVersion;
        this.repository = repository;
        this.backendFactory = backendFactory;
        this.ownerTokens = ownerTokens;
        this.currentness = currentness;
    }

    public GraphProjectionVerification rebuild(GraphProjectionInput input) {
        return build(input, GraphProjectionOperationKind.REBUILD);
    }

    public GraphProjectionVerification repair(GraphProjectionInput input) {
        return build(input, GraphProjectionOperationKind.REPAIR);
    }

    public GraphProjectionVerification clear(GraphWorkspaceScope workspace) {
        requireCapability();
        requireWorkspace(workspace);
        GraphProjectionReadiness readiness = repository.find(workspace)
                .orElseThrow(() -> new GraphProjectionException(
                        GraphProjectionFailureType.PROJECTION_NOT_READY));
        GraphProjectionSnapshot expected = readiness.appliedSnapshot();
        if (expected == null) {
            throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_NOT_READY);
        }
        GraphProjectionOperation operation = repository.reserve(workspace, configuredProvider,
                projectionVersion, GraphProjectionOperationKind.CLEAR,
                expected.sourceFingerprint(), ownerTokens.get());
        try {
            Optional<GraphProjectionBackend> existing = backendFactory.openExisting(workspace);
            if (existing.isPresent()) {
                try (GraphProjectionBackend backend = existing.get()) {
                    GraphProjectionWriteResult result = backend.clearWorkspace(workspace, expected);
                    if (result.status() == GraphProjectionWriteStatus.SUPERSEDED) {
                        throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_STALE);
                    }
                    GraphProjectionBackendProof proof = backend.readProof(workspace);
                    if (proof.currentSnapshot() != null
                            || (proof.clearedSnapshot() != null
                            && !expected.equals(proof.clearedSnapshot()))) {
                        throw new GraphProjectionException(
                                GraphProjectionFailureType.INVALID_PROJECTION_INPUT);
                    }
                }
            }
            if (!repository.markCleared(operation)) {
                throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_STALE);
            }
            GraphProjectionReadiness cleared = repository.find(workspace).orElseThrow();
            return verification(workspace, GraphProjectionVerificationStatus.NOT_READY,
                    cleared, null);
        } catch (GraphProjectionException failure) {
            markFailed(operation, failure.failure());
            throw failure;
        } catch (RuntimeException programmingFailure) {
            markFailed(operation, GraphProjectionFailure.of(
                    GraphProjectionFailureType.LOCAL_VALIDATION));
            throw programmingFailure;
        }
    }

    @Override
    public GraphProjectionVerification readiness(GraphWorkspaceScope workspace) {
        requireWorkspace(workspace);
        if (!enabled) {
            return verification(workspace, GraphProjectionVerificationStatus.DISABLED,
                    repository.find(workspace).orElse(null), null);
        }
        if (!factoryConfigured()) {
            return verification(workspace, GraphProjectionVerificationStatus.NOT_CONFIGURED,
                    repository.find(workspace).orElse(null),
                    GraphProjectionFailure.of(GraphProjectionFailureType.CONFIGURATION_INVALID));
        }

        Optional<GraphProjectionReadiness> stored = repository.find(workspace);
        if (stored.isEmpty()) {
            return verification(workspace, GraphProjectionVerificationStatus.NOT_READY, null,
                    GraphProjectionFailure.of(GraphProjectionFailureType.PROJECTION_NOT_READY));
        }
        GraphProjectionReadiness control = stored.get();
        if (!configuredProvider.equals(control.provider())
                || !projectionVersion.equals(control.projectionVersion())) {
            return verification(workspace,
                    GraphProjectionVerificationStatus.PROJECTION_INCOMPATIBLE, control,
                    GraphProjectionFailure.of(GraphProjectionFailureType.PROJECTION_INCOMPATIBLE));
        }
        GraphProjectionVerificationStatus active = activeStatus(control.status());
        if (active != null) {
            return verification(workspace, active, control, null);
        }
        if (control.status() == GraphProjectionReadinessStatus.NOT_READY) {
            return verification(workspace, GraphProjectionVerificationStatus.NOT_READY,
                    control, GraphProjectionFailure.of(
                            GraphProjectionFailureType.PROJECTION_NOT_READY));
        }
        if (control.status() != GraphProjectionReadinessStatus.READY) {
            GraphProjectionFailure failure = failureOrDefault(control);
            GraphProjectionVerificationStatus status =
                    control.status() == GraphProjectionReadinessStatus.FAILED
                            || control.status() == GraphProjectionReadinessStatus.DEGRADED
                            ? verificationStatus(failure.type())
                            : GraphProjectionVerificationStatus.REPAIR_REQUIRED;
            return verification(workspace, status, control, failure);
        }
        return verifyReady(control, true);
    }

    /** Reconciles every durable operation left active by an interrupted process. */
    public void reconcileInterruptedOperations() {
        if (!enabled) {
            return;
        }
        List<GraphProjectionReadiness> active = repository.findActive();
        for (GraphProjectionReadiness readiness : active) {
            reconcile(readiness);
        }
    }

    @Override
    public void close() {
        if (backendFactory != null) {
            backendFactory.close();
        }
    }

    private GraphProjectionVerification build(GraphProjectionInput input,
                                              GraphProjectionOperationKind kind) {
        requireCapability();
        if (input == null) {
            throw new IllegalArgumentException("Graph projection input is required");
        }
        if (!projectionVersion.equals(input.projectionVersion())) {
            throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_INCOMPATIBLE);
        }
        GraphProjectionOperation operation = repository.reserve(input.workspace(),
                configuredProvider, projectionVersion, kind, input.sourceFingerprint(),
                ownerTokens.get());
        try {
            GraphProjectionSnapshot expected = operation.targetSnapshot();
            try (GraphProjectionBackend backend = backendFactory.openForWrite(input.workspace())) {
                GraphProjectionSnapshot rebuilt = backend.rebuild(input, expected);
                GraphProjectionBackendProof proof = backend.readProof(input.workspace());
                if (!expected.equals(rebuilt) || !expected.equals(proof.currentSnapshot())
                        || proof.clearedSnapshot() != null) {
                    throw new GraphProjectionException(GraphProjectionFailureType.INVALID_PROJECTION_INPUT);
                }
            }
            // 先釋放 backend session，再取得 SQLite writer reservation；較新 operation 由 CAS 防護。
            return withCurrent(expected, () -> {
                if (!repository.markReady(operation, expected)) {
                    throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_STALE);
                }
                GraphProjectionReadiness ready = repository.find(input.workspace()).orElseThrow();
                if (ready.status() != GraphProjectionReadinessStatus.READY
                        || !expected.equals(ready.appliedSnapshot())) {
                    throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_STALE);
                }
                return verification(input.workspace(), GraphProjectionVerificationStatus.READY, ready, null);
            });
        } catch (GraphProjectionException failure) {
            markFailed(operation, failure.failure());
            throw failure;
        } catch (RuntimeException programmingFailure) {
            markFailed(operation, GraphProjectionFailure.of(
                    GraphProjectionFailureType.LOCAL_VALIDATION));
            throw programmingFailure;
        }
    }

    private GraphProjectionVerification verifyReady(GraphProjectionReadiness control,
                                                     boolean retryAfterLostCas) {
        GraphProjectionSnapshot expected = control.appliedSnapshot();
        try {
            Optional<GraphProjectionBackend> existing = backendFactory.openExisting(control.workspace());
            if (existing.isEmpty()) {
                return degrade(control, GraphProjectionFailureType.PROJECTION_NOT_READY,
                        GraphProjectionVerificationStatus.REPAIR_REQUIRED, retryAfterLostCas);
            }
            GraphProjectionBackendProof proof;
            try (GraphProjectionBackend backend = existing.get()) {
                proof = backend.readProof(control.workspace());
            }
            if (expected.equals(proof.currentSnapshot()) && proof.clearedSnapshot() == null) {
                return withCurrent(expected, () -> {
                    var latest = repository.find(control.workspace()).orElseThrow();
                    if (latest.status() != GraphProjectionReadinessStatus.READY
                            || !expected.equals(latest.appliedSnapshot())) {
                        throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_STALE);
                    }
                    return verification(control.workspace(), GraphProjectionVerificationStatus.READY,
                            latest, null);
                });
            }
            if (proof.currentSnapshot() != null
                    && !projectionVersion.equals(
                    proof.currentSnapshot().projectionVersion())) {
                return degrade(control, GraphProjectionFailureType.PROJECTION_INCOMPATIBLE,
                        GraphProjectionVerificationStatus.PROJECTION_INCOMPATIBLE,
                        retryAfterLostCas);
            }
            return degrade(control, GraphProjectionFailureType.PROJECTION_STALE,
                    GraphProjectionVerificationStatus.STALE, retryAfterLostCas);
        } catch (GraphProjectionException failure) {
            return degrade(control, failure.failureType(), verificationStatus(failure.failureType()),
                    retryAfterLostCas);
        }
    }

    private void reconcile(GraphProjectionReadiness readiness) {
        GraphProjectionOperation operation = readiness.activeOperation();
        if (operation == null) {
            return;
        }
        if (!factoryConfigured() || !configuredProvider.equals(operation.provider())
                || !projectionVersion.equals(operation.projectionVersion())) {
            markFailed(operation, GraphProjectionFailure.of(
                    GraphProjectionFailureType.CONFIGURATION_INVALID));
            return;
        }
        try {
            Optional<GraphProjectionBackend> existing = backendFactory.openExisting(
                    operation.workspace());
            if (existing.isEmpty()) {
                if (operation.kind() == GraphProjectionOperationKind.CLEAR) {
                    repository.markCleared(operation);
                } else {
                    markFailed(operation, GraphProjectionFailure.of(
                            GraphProjectionFailureType.PROJECTION_NOT_READY));
                }
                return;
            }
            GraphProjectionBackendProof proof;
            try (GraphProjectionBackend backend = existing.get()) {
                proof = backend.readProof(operation.workspace());
            }
            if (operation.kind() == GraphProjectionOperationKind.CLEAR) {
                GraphProjectionSnapshot expected = operation.expectedAppliedSnapshot();
                if (proof.currentSnapshot() == null
                        && (proof.clearedSnapshot() == null
                        || expected.equals(proof.clearedSnapshot()))) {
                    repository.markCleared(operation);
                } else {
                    markFailed(operation, GraphProjectionFailure.of(
                            conflictType(proof.currentSnapshot(), expected)));
                }
                return;
            }
            GraphProjectionSnapshot target = operation.targetSnapshot();
            if (target.equals(proof.currentSnapshot()) && proof.clearedSnapshot() == null) {
                markCurrentReady(operation, target);
            } else {
                markFailed(operation, GraphProjectionFailure.of(
                        conflictType(proof.currentSnapshot(), target)));
            }
        } catch (GraphProjectionException failure) {
            markFailed(operation, failure.failure());
        } catch (RuntimeException programmingFailure) {
            markFailed(operation, GraphProjectionFailure.of(
                    GraphProjectionFailureType.LOCAL_VALIDATION));
        }
    }

    private GraphProjectionVerification degrade(GraphProjectionReadiness control,
                                                GraphProjectionFailureType type,
                                                GraphProjectionVerificationStatus status,
                                                boolean retryAfterLostCas) {
        GraphProjectionFailure failure = GraphProjectionFailure.of(type);
        boolean changed = repository.markDegraded(
                control.workspace(), control.appliedSnapshot(), failure);
        GraphProjectionReadiness degraded = repository.find(control.workspace()).orElse(control);
        if (!changed && retryAfterLostCas && degraded.status() == GraphProjectionReadinessStatus.READY
                && !degraded.appliedSnapshot().equals(control.appliedSnapshot())) {
            // A newer generation won after backend verification but before the degradation CAS.
            // Revalidate that generation once rather than returning a stale failure paired with
            // newer READY control-plane proof.
            return verifyReady(degraded, false);
        }
        return verification(control.workspace(), status, degraded, failure);
    }

    private boolean markCurrentReady(GraphProjectionOperation operation, GraphProjectionSnapshot snapshot) {
        return withCurrent(snapshot, () -> repository.markReady(operation, snapshot));
    }

    private <T> T withCurrent(GraphProjectionSnapshot snapshot, Supplier<T> action) {
        return currentness.withCurrent(snapshot.workspace(), snapshot.sourceFingerprint(), action);
    }

    private void requireCapability() {
        if (!enabled) {
            throw new GraphProjectionException(GraphProjectionFailureType.CAPABILITY_UNAVAILABLE);
        }
        if (!factoryConfigured()) {
            throw new GraphProjectionException(GraphProjectionFailureType.CONFIGURATION_INVALID);
        }
    }

    private boolean factoryConfigured() {
        return backendFactory != null && !configuredProvider.isBlank()
                && configuredProvider.equals(backendFactory.provider())
                && projectionVersion.equals(backendFactory.projectionVersion());
    }

    private static GraphProjectionFailure failureOrDefault(GraphProjectionReadiness readiness) {
        GraphProjectionFailureType type = readiness.lastFailureType() == null
                ? GraphProjectionFailureType.PROJECTION_NOT_READY
                : readiness.lastFailureType();
        return GraphProjectionFailure.of(type);
    }

    private static GraphProjectionVerificationStatus activeStatus(
            GraphProjectionReadinessStatus status) {
        return switch (status) {
            case BUILDING -> GraphProjectionVerificationStatus.BUILDING;
            case REPAIRING -> GraphProjectionVerificationStatus.REPAIRING;
            case CLEARING -> GraphProjectionVerificationStatus.CLEARING;
            default -> null;
        };
    }

    private static GraphProjectionVerificationStatus verificationStatus(
            GraphProjectionFailureType type) {
        return switch (type) {
            case PROJECTION_INCOMPATIBLE ->
                    GraphProjectionVerificationStatus.PROJECTION_INCOMPATIBLE;
            case PROJECTION_STALE -> GraphProjectionVerificationStatus.STALE;
            case BACKEND_LOCKED, FILESYSTEM_UNAVAILABLE, CAPABILITY_UNAVAILABLE,
                    TRANSACTION_FAILURE, BACKEND_FAILURE ->
                    GraphProjectionVerificationStatus.BACKEND_UNAVAILABLE;
            default -> GraphProjectionVerificationStatus.REPAIR_REQUIRED;
        };
    }

    private static GraphProjectionFailureType conflictType(GraphProjectionSnapshot actual,
                                                           GraphProjectionSnapshot expected) {
        if (actual == null) {
            return GraphProjectionFailureType.PROJECTION_STALE;
        }
        if (!actual.projectionVersion().equals(expected.projectionVersion())) {
            return GraphProjectionFailureType.PROJECTION_INCOMPATIBLE;
        }
        if (actual.generation() == expected.generation() && !actual.equals(expected)) {
            return GraphProjectionFailureType.INVALID_PROJECTION_INPUT;
        }
        return GraphProjectionFailureType.PROJECTION_STALE;
    }

    private static GraphProjectionVerification verification(GraphWorkspaceScope workspace,
                                                             GraphProjectionVerificationStatus status,
                                                             GraphProjectionReadiness control,
                                                             GraphProjectionFailure failure) {
        return new GraphProjectionVerification(workspace, status, control, failure);
    }

    private static void markFailed(GraphProjectionLifecycleRepository repository,
                                   GraphProjectionOperation operation,
                                   GraphProjectionFailure failure) {
        repository.markFailed(operation, failure);
    }

    private void markFailed(GraphProjectionOperation operation, GraphProjectionFailure failure) {
        markFailed(repository, operation, failure);
    }

    private static void requireWorkspace(GraphWorkspaceScope workspace) {
        if (workspace == null) {
            throw new IllegalArgumentException("Graph workspace is required");
        }
    }
}
