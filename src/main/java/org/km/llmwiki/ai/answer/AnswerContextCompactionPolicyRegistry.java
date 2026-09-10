package org.km.llmwiki.ai.answer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fails fast on unknown or duplicate policy versions and resolves the active context policy. */
@Component
public class AnswerContextCompactionPolicyRegistry {

    private final Map<String, AnswerContextCompactionPolicy> policiesByVersion;
    private final String activeVersion;

    public AnswerContextCompactionPolicyRegistry(
            List<AnswerContextCompactionPolicy> policies,
            @Value("${app.ai.answer.context.compaction.policy-version:"
                    + ContextPolicyV1Current.VERSION + "}") String activeVersion) {
        this.policiesByVersion = new LinkedHashMap<>();
        for (AnswerContextCompactionPolicy policy : policies) {
            if (policy == null || policy.version() == null || policy.version().isBlank()) {
                throw new IllegalArgumentException("context policy version must not be blank");
            }
            if (policiesByVersion.containsKey(policy.version())) {
                throw new IllegalArgumentException(
                        "重複的 context compaction policy version：" + policy.version());
            }
            policiesByVersion.put(policy.version(), policy);
        }
        if (!policiesByVersion.containsKey(activeVersion)) {
            throw new IllegalArgumentException(
                    "未知的 context compaction policy version：" + activeVersion);
        }
        this.activeVersion = activeVersion;
    }

    public AnswerContextCompactionPolicy active() {
        return policiesByVersion.get(activeVersion);
    }

    public String activeVersion() {
        return activeVersion;
    }
}
