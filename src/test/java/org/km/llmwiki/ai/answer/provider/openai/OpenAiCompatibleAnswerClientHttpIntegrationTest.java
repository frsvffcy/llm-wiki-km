package org.km.llmwiki.ai.answer.provider.openai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.ai.answer.AnswerContext;
import org.km.llmwiki.ai.answer.AnswerContextReference;
import org.km.llmwiki.ai.answer.AnswerGenerationOptions;
import org.km.llmwiki.ai.answer.AnswerRequest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class OpenAiCompatibleAnswerClientHttpIntegrationTest {

    private HttpServer server;
    private String requestPath;
    private String authorization;
    private String requestBody;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::handleRequest);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void usesLocalHttpTransportAndKeepsCredentialInAuthorizationBoundary() {
        OpenAiCompatibleAnswerProperties properties = new OpenAiCompatibleAnswerProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        properties.setModel("local-model");
        properties.setApiKey("local-" + "fixture-credential");

        new OpenAiCompatibleAnswerClient(properties,
                new com.fasterxml.jackson.databind.ObjectMapper()).generate(request());

        assertThat(requestPath).isEqualTo("/v1/chat/completions");
        assertThat(authorization).isEqualTo("Bearer local-fixture-credential");
        assertThat(requestBody).contains("GROUNDED_ANSWER_PROMPT_V2")
                .doesNotContain("local-fixture-credential");
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        requestPath = exchange.getRequestURI().getPath();
        authorization = exchange.getRequestHeaders().getFirst("Authorization");
        requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String response = "{\"id\":\"local-request\",\"model\":\"local-model\","
                + "\"choices\":[{\"message\":{\"content\":"
                + quote("{\"answerText\":\"Local fake answer.\",\"citedEvidenceIds\":[\"E1\"],"
                + "\"insufficientEvidence\":false}")
                + "}}]}";
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static String quote(String value) {
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new AssertionError(exception);
        }
    }

    private static AnswerRequest request() {
        return new AnswerRequest("What is local?", AnswerContext.fromReferences(
                java.util.List.of(new AnswerContextReference("WIKI:local", "hash-local"))),
                AnswerGenerationOptions.defaults());
    }
}
