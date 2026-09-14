package org.km.llmwiki.ai.query;

import org.km.llmwiki.ai.answer.ProviderUsageStatus;

/** Safe execution metadata. Query text and provider diagnostics are deliberately absent. */
public record QueryTransformationExecution(
        String policyVersion,
        QueryTransformationStatus status,
        QueryTransformationApplicability applicability,
        ProviderUsageStatus providerUsageStatus,
        int retrievalInputCount,
        Long providerLatencyMs,
        Integer providerInputTokens,
        Integer providerOutputTokens,
        Integer providerTotalTokens
) {
    public QueryTransformationExecution {
        if (policyVersion == null || !policyVersion.matches("[a-z0-9][a-z0-9._-]*")) {
            throw new IllegalArgumentException("query transformation policy version is invalid");
        }
        if (status == null || applicability == null || providerUsageStatus == null
                || retrievalInputCount < 1 || retrievalInputCount > 2) {
            throw new IllegalArgumentException("query transformation execution is invalid");
        }
        if (providerUsageStatus != ProviderUsageStatus.AVAILABLE
                && (providerInputTokens != null || providerOutputTokens != null
                || providerTotalTokens != null)) {
            throw new IllegalArgumentException("query transformation token usage requires available status");
        }
        if (providerUsageStatus == ProviderUsageStatus.AVAILABLE
                && providerInputTokens == null && providerOutputTokens == null
                && providerTotalTokens == null) {
            throw new IllegalArgumentException("available query transformation usage requires a counter");
        }
        if (providerLatencyMs != null && providerLatencyMs < 0) {
            throw new IllegalArgumentException("query transformation latency must not be negative");
        }
        requireNonNegative(providerInputTokens, "providerInputTokens");
        requireNonNegative(providerOutputTokens, "providerOutputTokens");
        requireNonNegative(providerTotalTokens, "providerTotalTokens");
    }

    /** Compatibility constructor for callers that predate typed applicability metadata. */
    public QueryTransformationExecution(String policyVersion, QueryTransformationStatus status,
                                        ProviderUsageStatus providerUsageStatus,
                                        int retrievalInputCount, Long providerLatencyMs) {
        this(policyVersion, status,
                status == QueryTransformationStatus.NO_OP_POLICY_DISABLED
                        ? QueryTransformationApplicability.POLICY_DISABLED
                        : status == QueryTransformationStatus.NO_OP_NOT_APPLICABLE
                        ? QueryTransformationApplicability.QUERY_SHAPE_UNSUPPORTED
                        : QueryTransformationApplicability.LEXICAL_MISS_CROWD_OUT,
                providerUsageStatus, retrievalInputCount, providerLatencyMs, null, null, null);
    }

    private static void requireNonNegative(Integer value, String field) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }
}
