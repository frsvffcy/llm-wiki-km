package org.km.llmwiki.graph;

/** Durable control-plane operation kind. */
public enum GraphProjectionOperationKind {
    REBUILD(GraphProjectionReadinessStatus.BUILDING),
    REPAIR(GraphProjectionReadinessStatus.REPAIRING),
    CLEAR(GraphProjectionReadinessStatus.CLEARING);

    private final GraphProjectionReadinessStatus activeStatus;

    GraphProjectionOperationKind(GraphProjectionReadinessStatus activeStatus) {
        this.activeStatus = activeStatus;
    }

    public GraphProjectionReadinessStatus activeStatus() {
        return activeStatus;
    }
}
