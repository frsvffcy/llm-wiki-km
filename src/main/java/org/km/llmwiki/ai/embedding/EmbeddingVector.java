package org.km.llmwiki.ai.embedding;

import java.util.List;

/** One provider-neutral vector associated with one request input identity. */
public record EmbeddingVector(String inputIdentity, List<Double> values) {

    public static final int MAX_DIMENSION = 8_192;

    public EmbeddingVector {
        if (inputIdentity == null || !inputIdentity.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("inputIdentity must be a SHA-256 hex identity");
        }
        if (values == null || values.isEmpty() || values.size() > MAX_DIMENSION) {
            throw new IllegalArgumentException("embedding vector must have a bounded non-zero dimension");
        }
        if (values.stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
            throw new IllegalArgumentException("embedding vector values must be finite");
        }
        values = List.copyOf(values);
    }
}
