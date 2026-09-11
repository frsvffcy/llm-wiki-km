package org.km.llmwiki.rag;

/**
 * Typed applicability decision for one Ask execution. Policies are deterministic and
 * provider-free; there is deliberately no "force a reorder to look like reranking" state.
 */
public enum RerankStatus {
    /** The policy produced an ordered view; the identity set is unchanged. */
    APPLIED,
    /** Fewer than two qualified candidates exist, so no ordering decision is possible. */
    NO_OP_INSUFFICIENT_CANDIDATES,
    /** The policy does not support this query shape and keeps the baseline order. */
    NO_OP_UNSUPPORTED_SHAPE
}
