package org.km.llmwiki.ai.query;

import org.km.llmwiki.rag.EvidenceBundle;
import org.springframework.stereotype.Component;

/** Stable rollback target and production default. */
@Component
public final class DisabledQueryTransformationPolicy implements QueryTransformationPolicy {
    public static final String VERSION = "query-transform-disabled-v1";

    @Override public String version() { return VERSION; }
    @Override public boolean enabled() { return false; }
    @Override
    public QueryTransformationApplicability applicability(String query, EvidenceBundle evidence) {
        return QueryTransformationApplicability.POLICY_DISABLED;
    }
}
