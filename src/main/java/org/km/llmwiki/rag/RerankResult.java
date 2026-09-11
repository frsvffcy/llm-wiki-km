package org.km.llmwiki.rag;

import java.util.List;
import java.util.Objects;

/**
 * Application-owned typed outcome of the second-stage reranking boundary. A rerank result is an
 * ordered VIEW of the already-qualified evidence: the canonical identity set, citation
 * identity, content hashes, provenance, and evidence kinds are carried through untouched, and
 * the original {@link EvidenceBundle} is never mutated. Rerank features can never become
 * authority or citation identity.
 */
public record RerankResult(
        EvidenceBundle orderedBundle,
        RerankStatus status,
        RerankNoOpReason noOpReason,
        String policyVersion
) {
    public RerankResult {
        Objects.requireNonNull(orderedBundle, "rerank ordered bundle must not be null");
        Objects.requireNonNull(status, "rerank status must not be null");
        if (status != RerankStatus.APPLIED && noOpReason == null) {
            throw new IllegalArgumentException(
                    "a no-op rerank decision requires a typed no-op reason");
        }
        if (status == RerankStatus.APPLIED && noOpReason != null) {
            throw new IllegalArgumentException("an applied rerank decision must not carry a no-op reason");
        }
    }

    /** Ordered view items of the qualified evidence; the identity set is unchanged. */
    public List<EvidenceItem> orderedItems() {
        return orderedBundle.items();
    }

}
