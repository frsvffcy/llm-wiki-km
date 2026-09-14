package org.km.llmwiki.ai.query;

import java.util.List;

/** Provider-neutral boundary for producing one semantic rewrite candidate. */
@FunctionalInterface
public interface QueryRewriteClient {

    String rewrite(String originalQuery, List<String> protectedTokens)
            throws QueryRewriteException;

    /** Additive usage-aware boundary; simple deterministic clients may keep the default. */
    default QueryRewriteResult rewriteWithMetadata(String originalQuery,
                                                   List<String> protectedTokens)
            throws QueryRewriteException {
        return QueryRewriteResult.withoutUsage(rewrite(originalQuery, protectedTokens));
    }
}
