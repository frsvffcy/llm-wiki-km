package org.km.llmwiki.graph;

/** Typed projection-boundary failure; programming exceptions must not be blanket-mapped here. */
public class GraphProjectionException extends RuntimeException {

    private final GraphProjectionFailure failure;

    public GraphProjectionException(GraphProjectionFailureType type) {
        this(GraphProjectionFailure.of(type));
    }

    public GraphProjectionException(GraphProjectionFailure failure) {
        super(failure == null ? "Graph projection operation failed" : failure.publicCode());
        if (failure == null) {
            throw new IllegalArgumentException("Graph projection failure is required");
        }
        this.failure = failure;
    }

    public GraphProjectionException(GraphProjectionFailure failure, Throwable cause) {
        super(failure == null ? "Graph projection operation failed" : failure.publicCode(), cause);
        if (failure == null) {
            throw new IllegalArgumentException("Graph projection failure is required");
        }
        this.failure = failure;
    }

    public GraphProjectionFailure failure() {
        return failure;
    }

    public GraphProjectionFailureType failureType() {
        return failure.type();
    }
}
