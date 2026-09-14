package org.km.llmwiki.ai.query;

import java.util.List;

/** Fail-closed provider boundary used when semantic rewriting is not configured. */
public final class DisabledQueryRewriteClient implements QueryRewriteClient {

    @Override
    public String rewrite(String originalQuery, List<String> protectedTokens) {
        throw new QueryRewriteException(QueryRewriteException.Type.UNAVAILABLE,
                "query rewrite provider is disabled");
    }
}
