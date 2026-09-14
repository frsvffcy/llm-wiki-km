package org.km.llmwiki.ai.query.provider.openai;

import org.springframework.http.MediaType;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Java 21 query rewrite transport; no provider SDK is required. */
final class JdkQueryRewriteHttpTransport implements QueryRewriteHttpTransport {
    @Override
    public QueryRewriteHttpResponse post(URI endpoint, Duration connectTimeout,
                                         Duration readTimeout, String apiKey, String requestBody)
            throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(readTimeout)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return new QueryRewriteHttpResponse(response.statusCode(), response.body());
    }
}
