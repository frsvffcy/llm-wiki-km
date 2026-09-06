package org.km.llmwiki.graph;

/** Safe readiness result; no path, record identity, query, or raw exception is exposed. */
public record GraphProjectionVerification(GraphWorkspaceScope workspace,
                                          GraphProjectionVerificationStatus status,
                                          GraphProjectionReadiness controlPlane,
                                          GraphProjectionFailure failure) {
    public GraphProjectionVerification {
        if (workspace == null || status == null
                || controlPlane != null && !workspace.equals(controlPlane.workspace())) {
            throw new IllegalArgumentException("Graph projection verification is incomplete");
        }
    }

    public boolean ready() {
        return status == GraphProjectionVerificationStatus.READY;
    }
}
