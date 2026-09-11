package org.km.llmwiki.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fails fast on unknown or duplicate rerank policy versions and resolves the active
 * second-stage reranking policy. Rollback to the current deterministic baseline is a policy
 * version change only; it never requires rebuilding FTS, embedding, or graph projections.
 */
@Component
public class SecondStageRerankPolicyRegistry {

    private final Map<String, SecondStageRerankPolicy> policiesByVersion;
    private final String activeVersion;

    public SecondStageRerankPolicyRegistry(
            List<SecondStageRerankPolicy> policies,
            @Value("${km.rag.rerank.policy-version:"
                    + ExactAnchorRerankPolicyV1.VERSION + "}") String activeVersion) {
        this.policiesByVersion = new LinkedHashMap<>();
        for (SecondStageRerankPolicy policy : policies) {
            if (policy == null || policy.version() == null || policy.version().isBlank()) {
                throw new IllegalArgumentException("rerank policy version must not be blank");
            }
            if (policiesByVersion.containsKey(policy.version())) {
                throw new IllegalArgumentException("重複的 rerank policy version：" + policy.version());
            }
            policiesByVersion.put(policy.version(), policy);
        }
        if (!policiesByVersion.containsKey(activeVersion)) {
            throw new IllegalArgumentException("未知的 rerank policy version：" + activeVersion);
        }
        this.activeVersion = activeVersion;
    }

    public SecondStageRerankPolicy active() {
        return policiesByVersion.get(activeVersion);
    }

    public String activeVersion() {
        return activeVersion;
    }
}
