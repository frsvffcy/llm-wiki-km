package org.km.llmwiki.ai.embedding.provider.openai;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

/** Narrow transport seam kept private to the provider adapter boundary. */
@FunctionalInterface
interface OpenAiCompatibleEmbeddingHttpTransport {

    OpenAiCompatibleEmbeddingHttpResponse post(URI endpoint, Duration connectTimeout,
                                               Duration readTimeout, String apiKey,
                                               String requestBody)
            throws IOException, InterruptedException;
}

record OpenAiCompatibleEmbeddingHttpResponse(int statusCode, String body) {
}
