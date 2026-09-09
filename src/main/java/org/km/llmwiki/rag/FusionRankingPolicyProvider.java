package org.km.llmwiki.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves the production fusion ranking policy for the fused retrieval boundary. The policy
 * version is configuration-selected from the versioned registry inside
 * {@link FusionRankingPolicy}; unknown versions fail fast at startup instead of silently
 * serving an unvalidated parameterization.
 */
@Component
public class FusionRankingPolicyProvider {

    private final FusionRankingPolicy policy;

    public FusionRankingPolicyProvider(
            @Value("${km.rag.fusion.policy-version:fusion-rrf-v2-graph-damped}")
            String policyVersion) {
        this.policy = FusionRankingPolicy.byVersion(policyVersion);
    }

    public FusionRankingPolicy policy() {
        return policy;
    }
}
