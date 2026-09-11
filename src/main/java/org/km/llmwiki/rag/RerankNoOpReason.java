package org.km.llmwiki.rag;

/** Safe no-op reason code; carries no evidence content and no raw scores. */
public enum RerankNoOpReason {
    /** Fewer than two qualified candidates were available to order. */
    INSUFFICIENT_CANDIDATES,
    /** The query shape is not covered by this policy version. */
    UNSUPPORTED_QUERY_SHAPE
}
