package org.km.llmwiki.search.vector;

/** Normalized similarity result; larger is better and the valid range is zero through one. */
public record VectorSimilarityMatch(String identity, double similarity) {

    public VectorSimilarityMatch {
        if (identity == null || identity.isBlank() || !Double.isFinite(similarity)
                || similarity < 0.0d || similarity > 1.0d) {
            throw new IllegalArgumentException("Vector similarity match is invalid");
        }
    }
}
