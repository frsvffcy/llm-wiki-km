package org.km.llmwiki.ai.embedding;

/** Optional usage counters, populated only when the provider transport supplies reliable values. */
public record EmbeddingUsageMetadata(Integer inputTokens, Integer totalTokens) {

    public EmbeddingUsageMetadata {
        if (inputTokens == null && totalTokens == null) {
            throw new IllegalArgumentException("at least one usage counter is required");
        }
        requireNonNegative(inputTokens, "inputTokens");
        requireNonNegative(totalTokens, "totalTokens");
    }

    private static void requireNonNegative(Integer value, String field) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }
}
