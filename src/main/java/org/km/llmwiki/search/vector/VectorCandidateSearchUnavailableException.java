package org.km.llmwiki.search.vector;

/** Typed failure that must never be represented as zero semantic matches. */
public final class VectorCandidateSearchUnavailableException extends RuntimeException {

    public enum Dependency {
        EMBEDDING_PROVIDER,
        VECTOR_CAPABILITY,
        VECTOR_REPOSITORY,
        CONTENT_AUTHORITY
    }

    private final Dependency dependency;
    private final VectorCapabilityFailure capabilityFailure;

    public VectorCandidateSearchUnavailableException(Dependency dependency, Throwable cause) {
        this(dependency, VectorCapabilityFailure.NONE, cause);
    }

    public VectorCandidateSearchUnavailableException(Dependency dependency,
                                                     VectorCapabilityFailure capabilityFailure,
                                                     Throwable cause) {
        super("Vector candidate search dependency is unavailable: "
                + String.valueOf(dependency), cause);
        if (dependency == null || capabilityFailure == null) {
            throw new IllegalArgumentException("Vector candidate failure fields are required");
        }
        this.dependency = dependency;
        this.capabilityFailure = capabilityFailure;
    }

    public Dependency dependency() {
        return dependency;
    }

    public VectorCapabilityFailure capabilityFailure() {
        return capabilityFailure;
    }
}
