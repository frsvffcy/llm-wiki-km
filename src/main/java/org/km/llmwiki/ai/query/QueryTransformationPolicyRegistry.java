package org.km.llmwiki.ai.query;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves a versioned policy and fails startup on blank, duplicate, or unknown versions. */
@Component
public final class QueryTransformationPolicyRegistry {
    private final Map<String, QueryTransformationPolicy> policies = new LinkedHashMap<>();
    private final String activeVersion;

    public QueryTransformationPolicyRegistry(List<QueryTransformationPolicy> candidates,
            @Value("${app.ai.query-transformation.policy-version:"
                    + DisabledQueryTransformationPolicy.VERSION + "}") String activeVersion) {
        for (QueryTransformationPolicy policy : candidates) {
            if (policy == null || policy.version() == null || policy.version().isBlank()) {
                throw new IllegalArgumentException("query transformation policy version must not be blank");
            }
            if (policies.putIfAbsent(policy.version(), policy) != null) {
                throw new IllegalArgumentException("重複的 query transformation policy version：" + policy.version());
            }
        }
        if (activeVersion == null || activeVersion.isBlank() || !policies.containsKey(activeVersion)) {
            throw new IllegalArgumentException("未知的 query transformation policy version：" + activeVersion);
        }
        this.activeVersion = activeVersion;
    }

    public QueryTransformationPolicy active() { return policies.get(activeVersion); }
    public String activeVersion() { return activeVersion; }
}
