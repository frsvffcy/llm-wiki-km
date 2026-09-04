package org.km.llmwiki.ai.embedding.provider.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.ai.embedding.EmbeddingRequest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class OpenAiCompatibleEmbeddingClientHttpIntegrationTest {

    private HttpServer server;
    private String requestPath;
    private String authorization;
    private String requestBody;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", this::handleRequest);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void usesOfflineHttpFixtureAndKeepsCredentialOutsidePayloadAndResult() {
        OpenAiCompatibleEmbeddingProperties properties = new OpenAiCompatibleEmbeddingProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        properties.setModel("local-embedding-model");
        properties.setApiKey("local-fixture-credential");

        var result = new OpenAiCompatibleEmbeddingClient(properties, new ObjectMapper())
                .embed(EmbeddingRequest.single("offline fixture"));

        assertThat(requestPath).isEqualTo("/v1/embeddings");
        assertThat(authorization).isEqualTo("Bearer local-fixture-credential");
        assertThat(requestBody).contains("\"model\":\"local-embedding-model\"")
                .contains("\"input\":[\"offline fixture\"]")
                .doesNotContain("local-fixture-credential");
        assertThat(result.providerMetadata().model()).isEqualTo("local-embedding-model");
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        requestPath = exchange.getRequestURI().getPath();
        authorization = exchange.getRequestHeaders().getFirst("Authorization");
        requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String response = "{\"model\":\"local-embedding-model\",\"data\":["
                + "{\"index\":0,\"embedding\":[0.25,0.5,0.75]}]}";
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
