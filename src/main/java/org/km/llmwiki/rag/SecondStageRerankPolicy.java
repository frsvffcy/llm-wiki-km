package org.km.llmwiki.rag;

/**
 * Versioned, deterministic, provider-neutral second-stage reranking boundary. A policy only
 * reorders evidence that has already passed authority/currentness qualification; it never
 * performs retrieval, graph traversal, vector queries, authority reads, or provider calls, and
 * it can never resurrect, drop, or re-identify evidence. The same question, canonical evidence,
 * and policy version must always produce the same final order.
 */
public interface SecondStageRerankPolicy {

    /** Stable policy version; changing behavior requires a new version, never a silent mutation. */
    String version();

    /**
     * Reorders the already-qualified evidence view. The implementation receives the qualified
     * bundle (canonical application-owned items only — never vendor ids, RIDs, raw backend
     * scores, or provider DTOs) and must return an ordered view with the identical identity
     * set, or a typed no-op decision that keeps the baseline order.
     */
    RerankResult apply(EvidenceBundle evidence);

    /** A policy that keeps the current deterministic baseline order (rollback target). */
    @org.springframework.stereotype.Component
    final class NoOp implements SecondStageRerankPolicy {

        public static final String VERSION = "rerank-policy-v1-noop";

        @Override
        public String version() {
            return VERSION;
        }

        @Override
        public RerankResult apply(EvidenceBundle evidence) {
            if (evidence.items().size() < 2) {
                return new RerankResult(evidence,
                        RerankStatus.NO_OP_INSUFFICIENT_CANDIDATES,
                        RerankNoOpReason.INSUFFICIENT_CANDIDATES, VERSION);
            }
            return new RerankResult(evidence, RerankStatus.APPLIED, null, VERSION);
        }
    }
}
