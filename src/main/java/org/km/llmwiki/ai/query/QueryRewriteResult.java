package org.km.llmwiki.ai.query;

import org.km.llmwiki.ai.answer.AnswerUsageMetadata;

import java.util.Optional;

/** Provider-neutral rewrite output plus optional provider-reported token usage. */
public record QueryRewriteResult(String rewrittenQuery, Optional<AnswerUsageMetadata> usage) {
    public QueryRewriteResult {
        usage = usage == null ? Optional.empty() : usage;
    }

    public static QueryRewriteResult withoutUsage(String rewrittenQuery) {
        return new QueryRewriteResult(rewrittenQuery, Optional.empty());
    }
}
