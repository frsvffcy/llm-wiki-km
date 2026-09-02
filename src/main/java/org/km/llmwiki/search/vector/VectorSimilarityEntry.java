package org.km.llmwiki.search.vector;

import java.util.List;

/** One opaque candidate identity and its provider-neutral vector values. */
public record VectorSimilarityEntry(String identity, List<Double> values) {

    public VectorSimilarityEntry {
        if (identity == null || identity.isBlank() || values == null || values.isEmpty()
                || values.stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
            throw new IllegalArgumentException("Vector similarity entry is invalid");
        }
        values = List.copyOf(values);
    }
}
