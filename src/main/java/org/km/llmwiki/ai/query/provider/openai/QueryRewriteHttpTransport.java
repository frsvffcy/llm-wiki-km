package org.km.llmwiki.ai.query.provider.openai;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

/** Narrow transport seam for deterministic query rewrite adapter tests. */
@FunctionalInterface
interface QueryRewriteHttpTransport {
    QueryRewriteHttpResponse post(URI endpoint, Duration connectTimeout, Duration readTimeout,
                                  String apiKey, String requestBody)
            throws IOException, InterruptedException;
}

record QueryRewriteHttpResponse(int statusCode, String body) {
}
