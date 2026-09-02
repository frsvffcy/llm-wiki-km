package org.km.llmwiki.ai.embedding.provider.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.km.llmwiki.ai.embedding.EmbeddingClientException;
import org.km.llmwiki.ai.embedding.EmbeddingFailureType;
import org.km.llmwiki.ai.embedding.EmbeddingInput;
import org.km.llmwiki.ai.embedding.EmbeddingRequest;
import org.km.llmwiki.ai.embedding.EmbeddingResult;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("contract")
class OpenAiCompatibleEmbeddingClientTest {

    private static final String VECTOR = "[0.1, 0.2, 0.3]";

    @Test
    void mapsSingleInputAndProviderEnvelopeMetadata() {
        AtomicReference<String> endpoint = new AtomicReference<>();
        AtomicReference<String> apiKey = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        OpenAiCompatibleEmbeddingHttpTransport transport = (uri, connect, read, key, body) -> {
            endpoint.set(uri.toString());
            apiKey.set(key);
            requestBody.set(body);
            return response(200, envelope("provider-model", List.of(VECTOR), "12", "12"));
        };

        EmbeddingResult result = client(transport).embed(EmbeddingRequest.single("hello"));

        assertThat(result.vectors()).hasSize(1);
        assertThat(result.vectors().getFirst().values()).containsExactly(0.1d, 0.2d, 0.3d);
        assertThat(result.dimension()).isEqualTo(3);
        assertThat(result.providerMetadata().provider()).isEqualTo("openai-compatible");
        assertThat(result.providerMetadata().model()).isEqualTo("provider-model");
        assertThat(result.usage()).hasValueSatisfying(usage -> {
            assertThat(usage.inputTokens()).isEqualTo(12);
            assertThat(usage.totalTokens()).isEqualTo(12);
        });
        assertThat(endpoint).hasValue("https://provider.example/v1/embeddings");
        assertThat(apiKey).hasValue(fakeCredential());
        assertThat(requestBody).hasValueSatisfying(body -> {
            assertThat(body).contains("\"model\":\"configured-model\"")
                    .contains("\"input\":[\"hello\"]")
                    .doesNotContain(fakeCredential());
        });
    }

    @Test
    void mapsBatchInRequestOrderAndAssignsDeterministicInputIdentities() {
        EmbeddingRequest request = EmbeddingRequest.ofTexts(List.of("one", "two"));
        AtomicReference<String> requestBody = new AtomicReference<>();
        OpenAiCompatibleEmbeddingHttpTransport transport = (uri, connect, read, key, body) -> {
            requestBody.set(body);
            return response(200, "{\"model\":\"batch-model\",\"data\":["
                    + entry(0, VECTOR) + "," + entry(1, "[0.4,0.5,0.6]") + "]}");
        };

        EmbeddingResult result = client(transport).embed(request);

        assertThat(result.vectors()).extracting(vector -> vector.inputIdentity())
                .containsExactly(request.inputs().get(0).identity(), request.inputs().get(1).identity());
        assertThat(result.vectors().get(0).values()).containsExactly(0.1d, 0.2d, 0.3d);
        assertThat(result.vectors().get(1).values()).containsExactly(0.4d, 0.5d, 0.6d);
        assertThat(requestBody).hasValueSatisfying(body -> assertThat(body)
                .contains("\"input\":[\"one\",\"two\"]"));
    }

    @Test
    void fallsBackToConfiguredModelOnlyWhenEnvelopeOmitsModel() {
        OpenAiCompatibleEmbeddingHttpTransport transport = (uri, connect, read, key, body) ->
                response(200, envelope(null, List.of(VECTOR), null, null));

        EmbeddingResult result = client(transport).embed(EmbeddingRequest.single("hello"));

        assertThat(result.providerMetadata().model()).isEqualTo("configured-model");
        assertThat(result.usage()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "401,AUTHENTICATION_OR_AUTHORIZATION",
            "403,AUTHENTICATION_OR_AUTHORIZATION",
            "408,TIMEOUT_OR_NETWORK_UNAVAILABLE",
            "429,RATE_LIMIT_OR_QUOTA",
            "500,PROVIDER_SERVER_FAILURE",
            "503,PROVIDER_SERVER_FAILURE",
            "404,CONFIGURATION_UNAVAILABLE_OR_DISABLED",
            "400,LOCAL_VALIDATION"
    })
    void mapsProviderStatusesWithoutEchoingProviderBody(int status, EmbeddingFailureType type) {
        String secret = "fixture-provider-secret";
        OpenAiCompatibleEmbeddingHttpTransport transport = (uri, connect, read, key, body) ->
                response(status, "authorization: Bearer " + secret + " private-body");

        assertThatThrownBy(() -> client(transport).embed(EmbeddingRequest.single("hello")))
                .isInstanceOf(EmbeddingClientException.class)
                .satisfies(thrown -> {
                    EmbeddingClientException exception = (EmbeddingClientException) thrown;
                    assertThat(exception.failureType()).isEqualTo(type);
                    assertThat(exception.getMessage()).doesNotContain(secret).doesNotContain("private-body");
                });
    }

    @Test
    void mapsCheckedNetworkFailuresAndRestoresInterruptFlag() {
        for (IOException networkFailure : List.of(new ConnectException("connection failed"),
                new HttpTimeoutException("request timed out"))) {
            OpenAiCompatibleEmbeddingHttpTransport transport = (uri, connect, read, key, body) -> {
                throw networkFailure;
            };
            assertThatThrownBy(() -> client(transport).embed(EmbeddingRequest.single("hello")))
                    .isInstanceOf(EmbeddingClientException.class)
                    .extracting(thrown -> ((EmbeddingClientException) thrown).failureType())
                    .isEqualTo(EmbeddingFailureType.TIMEOUT_OR_NETWORK_UNAVAILABLE);
        }

        Thread.interrupted();
        try {
            OpenAiCompatibleEmbeddingHttpTransport transport = (uri, connect, read, key, body) -> {
                throw new InterruptedException("interrupted");
            };
            assertThatThrownBy(() -> client(transport).embed(EmbeddingRequest.single("hello")))
                    .isInstanceOf(EmbeddingClientException.class)
                    .extracting(thrown -> ((EmbeddingClientException) thrown).failureType())
                    .isEqualTo(EmbeddingFailureType.TIMEOUT_OR_NETWORK_UNAVAILABLE);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void doesNotReclassifyUnexpectedTransportRuntimeException() {
        IllegalStateException defect = new IllegalStateException("transport programming defect");
        OpenAiCompatibleEmbeddingHttpTransport transport = (uri, connect, read, key, body) -> {
            throw defect;
        };

        assertThatThrownBy(() -> client(transport).embed(EmbeddingRequest.single("hello")))
                .isSameAs(defect);
    }

    @Test
    void failsClosedForInvalidVectorsDimensionsShapesAndBatchOrder() {
        assertInvalid("""
                {"data":[{"index":0,"embedding":[]}]}
                """, 0);
        assertInvalid("""
                {"data":[{"index":0,"embedding":[1.0,NaN,2.0]}]}
                """, 0);
        assertInvalid("""
                {"data":[{"index":0,"embedding":[1.0,Infinity,2.0]}]}
                """, 0);
        assertInvalid("""
                {"data":[{"index":0,"embedding":[1.0,2.0]}]}
                """, 3);
        assertInvalid("not-json", 0);
        assertInvalid("""
                {"data":[{"index":0,"embedding":[1,2,3]}]}
                """, 0,
                EmbeddingRequest.ofTexts(List.of("one", "two")));
        assertInvalid("""
                {"data":[{"index":1,"embedding":[1,2,3]},
                {"index":0,"embedding":[1,2,3]}]}
                """, 0,
                EmbeddingRequest.ofTexts(List.of("one", "two")));
        assertInvalid("""
                {"data":[{"index":0,"embedding":[1,2,3]},
                {"index":1,"embedding":[4,5]}]}
                """, 0,
                EmbeddingRequest.ofTexts(List.of("one", "two")));
    }

    @Test
    void doesNotCallProviderWhenDisabledOrLocalConfigurationIsInvalid() {
        AtomicInteger calls = new AtomicInteger();
        OpenAiCompatibleEmbeddingProperties properties = properties();
        properties.setEnabled(false);
        OpenAiCompatibleEmbeddingClient client = client(properties, (uri, connect, read, key, body) -> {
            calls.incrementAndGet();
            return response(200, envelope("model", List.of(VECTOR), null, null));
        });

        assertFailure(client, EmbeddingRequest.single("hello"),
                EmbeddingFailureType.CONFIGURATION_UNAVAILABLE_OR_DISABLED);
        properties.setEnabled(true);
        properties.setApiKey("");
        assertFailure(client, EmbeddingRequest.single("hello"),
                EmbeddingFailureType.CONFIGURATION_UNAVAILABLE_OR_DISABLED);
        properties.setApiKey(fakeCredential());
        properties.setDimension(-1);
        assertFailure(client, EmbeddingRequest.single("hello"),
                EmbeddingFailureType.CONFIGURATION_UNAVAILABLE_OR_DISABLED);
        assertThat(calls).hasValue(0);
    }

    private void assertInvalid(String body, int dimension) {
        assertInvalid(body, dimension, EmbeddingRequest.single("hello"));
    }

    private void assertInvalid(String body, int dimension, EmbeddingRequest request) {
        OpenAiCompatibleEmbeddingProperties properties = properties();
        properties.setDimension(dimension);
        assertFailure(client(properties, (uri, connect, read, key, requestBody) -> response(200, body)),
                request, EmbeddingFailureType.INVALID_PROVIDER_RESPONSE);
    }

    private static void assertFailure(OpenAiCompatibleEmbeddingClient client,
                                      EmbeddingRequest request, EmbeddingFailureType expected) {
        assertThatThrownBy(() -> client.embed(request)).isInstanceOf(EmbeddingClientException.class)
                .extracting(thrown -> ((EmbeddingClientException) thrown).failureType())
                .isEqualTo(expected);
    }

    private static OpenAiCompatibleEmbeddingClient client(OpenAiCompatibleEmbeddingHttpTransport transport) {
        return client(properties(), transport);
    }

    private static OpenAiCompatibleEmbeddingClient client(OpenAiCompatibleEmbeddingProperties properties,
                                                          OpenAiCompatibleEmbeddingHttpTransport transport) {
        return new OpenAiCompatibleEmbeddingClient(properties, transport, new ObjectMapper());
    }

    private static OpenAiCompatibleEmbeddingProperties properties() {
        OpenAiCompatibleEmbeddingProperties properties = new OpenAiCompatibleEmbeddingProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("https://provider.example/v1");
        properties.setModel("configured-model");
        properties.setApiKey(fakeCredential());
        properties.setConnectTimeout(Duration.ofSeconds(1));
        properties.setReadTimeout(Duration.ofSeconds(5));
        return properties;
    }

    private static OpenAiCompatibleEmbeddingHttpResponse response(int status, String body) {
        return new OpenAiCompatibleEmbeddingHttpResponse(status, body);
    }

    private static String envelope(String model, List<String> vectors, String input, String total) {
        String modelField = model == null ? "" : ",\"model\":" + quote(model);
        String usage = input == null && total == null ? ""
                : ",\"usage\":{\"prompt_tokens\":" + input + ",\"total_tokens\":" + total + "}";
        String data = java.util.stream.IntStream.range(0, vectors.size())
                .mapToObj(index -> entry(index, vectors.get(index))).collect(java.util.stream.Collectors.joining(","));
        return "{\"data\":[" + data + "]" + modelField + usage + "}";
    }

    private static String entry(int index, String vector) {
        return "{\"index\":" + index + ",\"embedding\":" + vector + "}";
    }

    private static String quote(String value) {
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String fakeCredential() {
        return "fixture-credential";
    }
}
