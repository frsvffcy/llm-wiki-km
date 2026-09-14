package org.km.llmwiki.source;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the active normalization policy (#412).
 *
 * <p>Fails fast on blank, duplicate or unknown policy versions. Rollback to the
 * baseline is a configuration change only
 * ({@code app.extraction.normalization.policy-version}); historical bytes still
 * require explicit re-extraction — changing the version never rewrites stored
 * content in place.
 */
@Component
public class NormalizationPolicyRegistry {

    private final Map<String, NormalizationPolicy> policiesByVersion;
    private final String activeVersion;

    public NormalizationPolicyRegistry(
            List<NormalizationPolicy> policies,
            @Value("${app.extraction.normalization.policy-version:"
                    + NormalizationPolicyV2SelectedCfStrip.VERSION + "}") String activeVersion) {
        this.policiesByVersion = new LinkedHashMap<>();
        for (NormalizationPolicy policy : policies) {
            if (policy == null || policy.version() == null || policy.version().isBlank()) {
                throw new IllegalArgumentException("normalization policy version must not be blank");
            }
            if (policiesByVersion.containsKey(policy.version())) {
                throw new IllegalArgumentException("重複的 normalization policy version：" + policy.version());
            }
            policiesByVersion.put(policy.version(), policy);
        }
        if (!policiesByVersion.containsKey(activeVersion)) {
            throw new IllegalArgumentException("未知的 normalization policy version：" + activeVersion);
        }
        this.activeVersion = activeVersion;
    }

    public NormalizationPolicy active() {
        return policiesByVersion.get(activeVersion);
    }

    public String activeVersion() {
        return activeVersion;
    }
}
