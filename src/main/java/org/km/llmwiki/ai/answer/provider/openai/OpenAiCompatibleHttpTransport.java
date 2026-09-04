package org.km.llmwiki.ai.answer.provider.openai;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

/**
 * Narrow transport seam kept inside the provider adapter boundary for deterministic tests.
 *
 * <p>Implementations report expected transport failures with the declared checked exceptions.
 * Unchecked exceptions are not transport failures and must retain their original semantics.
 */
@FunctionalInterface
interface OpenAiCompatibleHttpTransport {

    OpenAiCompatibleHttpResponse post(URI endpoint, Duration connectTimeout, Duration readTimeout,
                                      String apiKey, String requestBody)
            throws IOException, InterruptedException;
}

record OpenAiCompatibleHttpResponse(int statusCode, String body) {
}
