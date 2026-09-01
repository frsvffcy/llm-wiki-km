package org.km.llmwiki.ai.answer;

/** Optional provider-neutral usage counters; any counter may be unavailable. */
public record AnswerUsageMetadata(Integer inputTokens, Integer outputTokens, Integer totalTokens) {

    public AnswerUsageMetadata {
        if (inputTokens == null && outputTokens == null && totalTokens == null) {
            throw new IllegalArgumentException("at least one usage counter is required");
        }
        requireNonNegative(inputTokens, "inputTokens");
        requireNonNegative(outputTokens, "outputTokens");
        requireNonNegative(totalTokens, "totalTokens");
    }

    private static void requireNonNegative(Integer value, String field) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }
}
