package org.km.llmwiki.search.vector;

/** Provider-neutral requirements for a vector capability probe. */
public record VectorCapabilityRequest(int dimension, VectorEncoding encoding) {

    public VectorCapabilityRequest {
        if (encoding == null) {
            throw new IllegalArgumentException("Vector encoding is required");
        }
    }
}
