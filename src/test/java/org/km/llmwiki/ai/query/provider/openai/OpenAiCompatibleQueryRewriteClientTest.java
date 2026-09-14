package org.km.llmwiki.ai.query.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.ai.query.QueryRewriteException;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class OpenAiCompatibleQueryRewriteClientTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    void sendsBoundedStructuredRequestAndParsesRewriteWithUsage() throws Exception {
        AtomicReference<URI> endpoint = new AtomicReference<>();
        AtomicReference<String> key = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        QueryRewriteHttpTransport transport = (uri, connect, read, apiKey, requestBody) -> {
            endpoint.set(uri);
            key.set(apiKey);
            body.set(requestBody);
            assertThat(connect).isEqualTo(Duration.ofSeconds(2));
            assertThat(read).isEqualTo(Duration.ofSeconds(10));
            return new QueryRewriteHttpResponse(200, response("資料庫 busy_timeout",
                    "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":3,\"total_tokens\":13}"));
        };
        var result = client(properties(), transport).rewriteWithMetadata(
                "busy_timeout 預設值要怎麼設定？", List.of("busy_timeout"));

        assertThat(endpoint.get()).hasToString("https://example.test/v1/chat/completions");
        assertThat(key.get()).isEqualTo("top-secret");
        assertThat(body.get()).doesNotContain("top-secret");
        JsonNode payload = mapper.readTree(body.get());
        assertThat(payload.get("model").asText()).isEqualTo("rewrite-model");
        assertThat(payload.get("temperature").asInt()).isZero();
        assertThat(payload.toString()).contains("busy_timeout");
        assertThat(result.rewrittenQuery()).isEqualTo("資料庫 busy_timeout");
        assertThat(result.usage()).hasValueSatisfying(usage -> {
            assertThat(usage.inputTokens()).isEqualTo(10);
            assertThat(usage.outputTokens()).isEqualTo(3);
            assertThat(usage.totalTokens()).isEqualTo(13);
        });
    }

    @Test
    void missingUsageIsExplicitlyUnavailable() {
        var result = client(properties(), success(response("資料庫 busy_timeout", null)))
                .rewriteWithMetadata("q", List.of("busy_timeout"));

        assertThat(result.usage()).isEmpty();
    }

    @Test
    void malformedProviderEnvelopesFailAsInvalidResponse() {
        List<String> invalidBodies = List.of(
                "not-json",
                "{}",
                "{\"choices\":{}}",
                "{\"choices\":[{}]}",
                "{\"choices\":[{\"message\":{\"content\":{}}}]}",
                "{\"choices\":[{\"message\":{\"content\":\"not-json\"}}]}",
                responseJson("{}", null),
                responseJson("{\"rewrite\":3}", null),
                responseJson("{\"rewrite\":\"  \"}", null),
                responseJson("{\"rewrite\":\"ok\"}", "\"usage\":{}"),
                responseJson("{\"rewrite\":\"ok\"}",
                        "\"usage\":{\"prompt_tokens\":-1}"));

        for (String body : invalidBodies) {
            assertFailure(client(properties(), success(body)),
                    QueryRewriteException.Type.INVALID_RESPONSE);
        }
        assertFailure(client(properties(), success(null)),
                QueryRewriteException.Type.INVALID_RESPONSE);
        assertFailure(client(properties(), success("  ")),
                QueryRewriteException.Type.INVALID_RESPONSE);
        assertFailure(client(properties(), success("x".repeat(16_385))),
                QueryRewriteException.Type.INVALID_RESPONSE);
    }

    @Test
    void transportAndHttpFailuresFailClosedAsUnavailable() {
        assertFailure(client(properties(), (uri, connect, read, key, body) -> {
            throw new IOException("offline");
        }), QueryRewriteException.Type.UNAVAILABLE);
        assertFailure(client(properties(), successWithStatus(429, "{}")),
                QueryRewriteException.Type.UNAVAILABLE);
        assertFailure(client(properties(), (uri, connect, read, key, body) -> null),
                QueryRewriteException.Type.UNAVAILABLE);
    }

    @Test
    void interruptionRestoresFlagAndFailsClosed() {
        assertFailure(client(properties(), (uri, connect, read, key, body) -> {
            throw new InterruptedException("stop");
        }), QueryRewriteException.Type.UNAVAILABLE);

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void invalidConfigurationNeverReachesTransport() {
        List<OpenAiCompatibleQueryRewriteProperties> invalid = List.of(
                mutate(p -> p.setEnabled(false)),
                mutate(p -> p.setProvider("other")),
                mutate(p -> p.setModel(" ")),
                mutate(p -> p.setModel("bad\nmodel")),
                mutate(p -> p.setModel("m".repeat(129))),
                mutate(p -> p.setApiKey(" ")),
                mutate(p -> p.setApiKey("k".repeat(4_097))),
                mutate(p -> p.setConnectTimeout(Duration.ofMillis(49))),
                mutate(p -> p.setReadTimeout(Duration.ofSeconds(121))),
                mutate(p -> p.setBaseUrl("http://remote.example/v1")));
        AtomicInteger calls = new AtomicInteger();
        QueryRewriteHttpTransport transport = (uri, connect, read, key, body) -> {
            calls.incrementAndGet();
            return new QueryRewriteHttpResponse(200, response("ok", null));
        };

        invalid.forEach(properties -> assertFailure(client(properties, transport),
                QueryRewriteException.Type.UNAVAILABLE));
        assertThat(calls).hasValue(0);
    }

    @Test
    void uncheckedTransportDefectsAreNotMisclassifiedAsProviderFailures() {
        var client = client(properties(), (uri, connect, read, key, body) -> {
            throw new IllegalStateException("programmer defect");
        });

        assertThatThrownBy(() -> client.rewrite("q", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("programmer defect");
    }

    private OpenAiCompatibleQueryRewriteClient client(
            OpenAiCompatibleQueryRewriteProperties properties,
            QueryRewriteHttpTransport transport) {
        return new OpenAiCompatibleQueryRewriteClient(properties, transport, mapper);
    }

    private static QueryRewriteHttpTransport success(String body) {
        return successWithStatus(200, body);
    }

    private static QueryRewriteHttpTransport successWithStatus(int status, String body) {
        return (uri, connect, read, key, request) -> new QueryRewriteHttpResponse(status, body);
    }

    private static OpenAiCompatibleQueryRewriteProperties properties() {
        OpenAiCompatibleQueryRewriteProperties properties =
                new OpenAiCompatibleQueryRewriteProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("https://example.test/v1");
        properties.setModel("rewrite-model");
        properties.setApiKey("top-secret");
        return properties;
    }

    private static OpenAiCompatibleQueryRewriteProperties mutate(
            java.util.function.Consumer<OpenAiCompatibleQueryRewriteProperties> mutation) {
        OpenAiCompatibleQueryRewriteProperties properties = properties();
        mutation.accept(properties);
        return properties;
    }

    private static String response(String rewrite, String usageField) {
        return responseJson("{\"rewrite\":\"" + rewrite + "\"}", usageField);
    }

    private static String responseJson(String contentJson, String usageField) {
        String escaped = contentJson.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"choices\":[{\"message\":{\"content\":\"" + escaped
                + "\"}}]" + (usageField == null ? "" : "," + usageField) + "}";
    }

    private static void assertFailure(OpenAiCompatibleQueryRewriteClient client,
                                      QueryRewriteException.Type type) {
        assertThatThrownBy(() -> client.rewrite("query", List.of()))
                .isInstanceOfSatisfying(QueryRewriteException.class,
                        failure -> assertThat(failure.type()).isEqualTo(type));
    }
}
