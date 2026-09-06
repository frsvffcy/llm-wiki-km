package org.km.llmwiki.graph;

import java.util.List;
import java.util.Optional;

/** Durable control-plane port for workspace-scoped Graph projection ownership and readiness. */
public interface GraphProjectionLifecycleRepository {

    GraphProjectionOperation reserve(GraphWorkspaceScope workspace, String provider,
                                     GraphProjectionVersion projectionVersion,
                                     GraphProjectionOperationKind kind,
                                     String sourceFingerprint, String ownerToken);

    Optional<GraphProjectionReadiness> find(GraphWorkspaceScope workspace);

    List<GraphProjectionReadiness> findActive();

    boolean markReady(GraphProjectionOperation operation, GraphProjectionSnapshot snapshot);

    boolean markCleared(GraphProjectionOperation operation);

    boolean markFailed(GraphProjectionOperation operation, GraphProjectionFailure failure);

    boolean markDegraded(GraphWorkspaceScope workspace, GraphProjectionSnapshot expectedSnapshot,
                         GraphProjectionFailure failure);
}
