package org.km.llmwiki.ai.query;

/** Typed execution result for the single-rewrite production boundary. */
public enum QueryTransformationStatus {
    REWRITE_APPLIED,
    NO_OP_POLICY_DISABLED,
    NO_OP_NOT_APPLICABLE,
    NO_OP_DUPLICATE,
    FALLBACK_PROVIDER_UNAVAILABLE,
    FALLBACK_PROVIDER_INVALID,
    FALLBACK_OUTPUT_OVER_LIMIT,
    FALLBACK_EXACT_TOKEN_LOSS,
    FALLBACK_RETRIEVAL_UNAVAILABLE,
    FALLBACK_RETRIEVAL_INVALID
}
