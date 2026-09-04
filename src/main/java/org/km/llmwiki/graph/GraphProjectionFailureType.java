package org.km.llmwiki.graph;

/** Stable, provider-neutral failure taxonomy for projection boundaries. */
public enum GraphProjectionFailureType {
    CAPABILITY_UNAVAILABLE("GRAPH_CAPABILITY_UNAVAILABLE", true),
    PROJECTION_NOT_READY("GRAPH_PROJECTION_NOT_READY", true),
    PROJECTION_STALE("GRAPH_PROJECTION_STALE", true),
    PROJECTION_INCOMPATIBLE("GRAPH_PROJECTION_INCOMPATIBLE", false),
    INVALID_PROJECTION_INPUT("GRAPH_INVALID_PROJECTION_INPUT", false),
    INVALID_PROVENANCE("GRAPH_INVALID_PROVENANCE", false),
    CROSS_WORKSPACE("GRAPH_CROSS_WORKSPACE", false),
    BACKEND_FAILURE("GRAPH_BACKEND_FAILURE", true),
    LOCAL_VALIDATION("GRAPH_LOCAL_VALIDATION", false);

    private final String publicCode;
    private final boolean retryable;

    GraphProjectionFailureType(String publicCode, boolean retryable) {
        this.publicCode = publicCode;
        this.retryable = retryable;
    }

    public String publicCode() {
        return publicCode;
    }

    public boolean retryable() {
        return retryable;
    }
}
