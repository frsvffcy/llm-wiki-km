package org.km.llmwiki.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic local provider stub exercising the real production adapter
 * transport seam (Refs #429 §D).
 *
 * <p>Speaks the OpenAI-compatible {@code /chat/completions} and
 * {@code /embeddings} shapes over loopback HTTP. The application under test is
 * configured with {@code *_PROVIDER_BASE_URL=http://127.0.0.1:&lt;port&gt;/v1}
 * so the genuine {@code JdkOpenAiCompatible*Transport} path runs; nothing is
 * stubbed below {@code AskService} or inside the production adapter.
 *
 * <p>Answer content is fixed and grounded (cites {@code E1}); embedding vectors
 * are SHA-256-derived deterministic 8-dimensional floats. No secrets, no raw
 * canonical content, no network egress.
 */
public final class DeterministicAcceptanceProviderStub implements AutoCloseable {

    public static final String MODEL = "acceptance-deterministic-v1";
    public static final String PROVIDER = "openai-compatible";

    private final HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile String lastAnswerRequestBody;
    private volatile String lastEmbeddingRequestBody;
    private volatile String lastAnswerAuthorization;
    private volatile String lastEmbeddingAuthorization;

    public DeterministicAcceptanceProviderStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::handleAnswer);
        server.createContext("/v1/embeddings", this::handleEmbedding);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    public String lastAnswerRequestBody() {
        return lastAnswerRequestBody;
    }

    public String lastEmbeddingRequestBody() {
        return lastEmbeddingRequestBody;
    }

    public String lastAnswerAuthorization() {
        return lastAnswerAuthorization;
    }

    public String lastEmbeddingAuthorization() {
        return lastEmbeddingAuthorization;
    }

    private void handleAnswer(HttpExchange exchange) throws IOException {
        lastAnswerAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
        lastAnswerRequestBody = new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8);
        String inner = "{\"answerText\":\"Golden workspace acceptance answer referencing "
                + ProductAcceptanceCorpusV1.ANCHOR_TOKEN
                + ".\",\"citedEvidenceIds\":[\"E1\"],\"insufficientEvidence\":false}";
        String response = "{\"id\":\"acceptance-answer\",\"model\":\"" + MODEL + "\","
                + "\"choices\":[{\"message\":{\"content\":" + mapper.writeValueAsString(inner)
                + "}}]}";
        sendJson(exchange, response);
    }

    private void handleEmbedding(HttpExchange exchange) throws IOException {
        lastEmbeddingAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
        byte[] raw = exchange.getRequestBody().readAllBytes();
        lastEmbeddingRequestBody = new String(raw, StandardCharsets.UTF_8);
        List<String> inputs = extractInputs(lastEmbeddingRequestBody);
        StringBuilder data = new StringBuilder("[");
        for (int index = 0; index < inputs.size(); index++) {
            if (index > 0) {
                data.append(',');
            }
            data.append("{\"index\":").append(index).append(",\"embedding\":[");
            float[] vector = vectorFor(inputs.get(index));
            for (int dim = 0; dim < vector.length; dim++) {
                if (dim > 0) {
                    data.append(',');
                }
                data.append(String.format(Locale.ROOT, "%.6f", vector[dim]));
            }
            data.append("]}");
        }
        data.append(']');
        String response = "{\"model\":\"" + MODEL + "\",\"data\":" + data + "}";
        sendJson(exchange, response);
    }

    private List<String> extractInputs(String body) {
        List<String> inputs = new ArrayList<>();
        try {
            var root = mapper.readTree(body);
            var node = root.get("input");
            if (node != null && node.isArray()) {
                node.forEach(element -> inputs.add(element.asText("")));
            } else if (node != null && node.isTextual()) {
                inputs.add(node.asText(""));
            }
        } catch (Exception ignored) {
            // Fall back to a single deterministic vector; the production client
            // still validates the envelope shape.
        }
        if (inputs.isEmpty()) {
            inputs.add("acceptance-fallback");
        }
        return List.copyOf(inputs);
    }

    /** Deterministic 8-dim vector derived from SHA-256 hex (no randomness). */
    static float[] vectorFor(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(digest);
            float[] vector = new float[8];
            for (int dim = 0; dim < 8; dim++) {
                int chunk = Integer.parseUnsignedInt(hex.substring(dim * 4, dim * 4 + 4), 16);
                vector[dim] = (chunk / 65535.0f) * 2.0f - 1.0f;
            }
            return vector;
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private static void sendJson(HttpExchange exchange, String response) throws IOException {
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
