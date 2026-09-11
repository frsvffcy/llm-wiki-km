package org.km.llmwiki.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Application-owned execution boundary for the active second-stage rerank policy. The qualified
 * {@link EvidenceBundle} is only ever reordered into a new view: the executor revalidates the
 * blocking invariants (identical canonical identity set, no resurrection or drop, no
 * re-identification) against the policy output and falls back deterministically to the baseline
 * order with a typed failure if a policy ever violates them. Rerank features can never become
 * authority or citation identity, and no lower candidate can silently backfill a currentness
 * reject because the policy can never add evidence at all.
 */
@org.springframework.stereotype.Component
public class SecondStageRerankService {

    private static final Logger LOG = LoggerFactory.getLogger(SecondStageRerankService.class);

    private final SecondStageRerankPolicyRegistry registry;

    public SecondStageRerankService(SecondStageRerankPolicyRegistry registry) {
        this.registry = registry;
    }

    /**
     * Applies the active versioned policy to the already-qualified bundle. The returned items
     * are an ordered view with the identical canonical identity set; on any policy defect the
     * baseline order is returned unchanged with the noop policy's own version for the record.
     */
    public RerankResult apply(EvidenceBundle evidence) {
        SecondStageRerankPolicy policy = registry.active();
        try {
            RerankResult candidate = policy.apply(evidence);
            candidate = requireValid(candidate, evidence, policy);
            return withVersion(candidate, policy.version());
        } catch (RuntimeException failure) {
            LOG.warn("second-stage rerank policy failed; keeping the deterministic baseline order",
                    failure);
            return withVersion(new RerankResult(evidence,
                    RerankStatus.NO_OP_UNSUPPORTED_SHAPE,
                    RerankNoOpReason.UNSUPPORTED_QUERY_SHAPE, policy.version()),
                    policy.version());
        }
    }

    private RerankResult withVersion(RerankResult result, String version) {
        if (result.status() == RerankStatus.APPLIED) {
            return new RerankResult(result.orderedBundle(), result.status(), null, version);
        }
        return new RerankResult(result.orderedBundle(), result.status(), result.noOpReason(),
                version);
    }

    private RerankResult requireValid(RerankResult candidate, EvidenceBundle evidence,
                                      SecondStageRerankPolicy policy) {
        if (candidate == null) {
            throw new IllegalStateException("rerank policy returned no result");
        }
        boolean sameIdentitySet = candidate.orderedItems().size() == evidence.items().size();
        if (sameIdentitySet) {
            java.util.Set<String> baseline = new java.util.LinkedHashSet<>();
            evidence.items().forEach(item -> baseline.add(item.stableIdentity()));
            java.util.Set<String> ordered = new java.util.LinkedHashSet<>();
            candidate.orderedItems().forEach(item -> ordered.add(item.stableIdentity()));
            sameIdentitySet = baseline.equals(ordered);
        }
        if (!sameIdentitySet) {
            LOG.warn("rerank policy {} changed the qualified identity set; keeping the "
                    + "deterministic baseline order", policy.version());
            return new RerankResult(evidence,
                    RerankStatus.NO_OP_UNSUPPORTED_SHAPE,
                    RerankNoOpReason.UNSUPPORTED_QUERY_SHAPE, policy.version());
        }
        return candidate;
    }
}
