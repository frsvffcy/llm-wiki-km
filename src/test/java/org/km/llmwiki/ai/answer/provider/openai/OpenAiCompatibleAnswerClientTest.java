package org.km.llmwiki.ai.answer.provider.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.km.llmwiki.ai.answer.AnswerClientException;
import org.km.llmwiki.ai.answer.AnswerContext;
import org.km.llmwiki.ai.answer.AnswerContextReference;
import org.km.llmwiki.ai.answer.AnswerFailureType;
import org.km.llmwiki.ai.answer.AnswerGenerationOptions;
import org.km.llmwiki.ai.answer.AnswerRequest;
import org.km.llmwiki.ai.answer.AnswerResult;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("contract")
class OpenAiCompatibleAnswerClientTest {

    private static final String STRUCTURED_RESPONSE = """
            {"answerText":"The answer is grounded.","citedEvidenceIds":["E1"],
             "insufficientEvidence":false,"metadata":{"provider":"model-output","model":"model-output"}}
            """;

    @Test
    void mapsOpenAiEnvelopeAndRunsApplicationOwnedStructuredValidation() {
        AtomicReference<String> endpoint = new AtomicReference<>();
        AtomicReference<String> apiKey = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        OpenAiCompatibleHttpTransport transport = (uri, connect, read, key, body) -> {
            endpoint.set(uri.toString());
            apiKey.set(key);
            requestBody.set(body);
            return response(200, envelope("provider-model", STRUCTURED_RESPONSE,
                    "request-123", "12", "8", "20"));
        };

        AnswerResult result = client(transport).generate(request());

        assertThat(result.answerText()).isEqualTo("The answer is grounded.");
        assertThat(result.providerMetadata().provider()).isEqualTo("openai-compatible");
        assertThat(result.providerMetadata().model()).isEqualTo("provider-model");
        assertThat(result.usage()).hasValueSatisfying(usage -> {
            assertThat(usage.inputTokens()).isEqualTo(12);
            assertThat(usage.outputTokens()).isEqualTo(8);
            assertThat(usage.totalTokens()).isEqualTo(20);
        });
        assertThat(endpoint).hasValue("https://provider.example/v1/chat/completions");
        assertThat(apiKey).hasValue(fakeCredential());
        assertThat(requestBody).hasValueSatisfying(body -> {
            assertThat(body).contains("\"model\":\"configured-model\"")
                    .contains("\"max_tokens\":4000")
                    .contains("GROUNDED_ANSWER_PROMPT_V1")
                    .doesNotContain(fakeCredential());
        });
    }

    @Test
    void fallsBackToConfiguredModelAndOptionalUsageWhenEnvelopeOmitsMetadata() {
        OpenAiCompatibleHttpTransport transport = (uri, connect, read, key, body) ->
                response(200, envelope(null, STRUCTURED_RESPONSE, null, null, null, null));

        AnswerResult result = client(transport).generate(request());

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
    void mapsProviderStatusesToTypedFailuresWithoutEchoingProviderBody(int status,
                                                                         AnswerFailureType type) {
        String generatedCredential = "fixture-provider-secret";
        OpenAiCompatibleHttpTransport transport = (uri, connect, read, key, body) ->
                response(status, "authorization: Bearer " + generatedCredential
                        + " evidence-content-that-must-not-be-echoed");

        assertThatThrownBy(() -> client(transport).generate(request()))
                .isInstanceOf(AnswerClientException.class)
                .satisfies(thrown -> {
                    AnswerClientException exception = (AnswerClientException) thrown;
                    assertThat(exception.failureType()).isEqualTo(type);
                    assertThat(exception.getMessage()).doesNotContain(generatedCredential)
                            .doesNotContain("evidence-content");
                });
    }

    @Test
    void mapsNetworkAndTimeoutFailuresWithoutRetrying() {
        AtomicInteger calls = new AtomicInteger();
        OpenAiCompatibleHttpTransport transport = (uri, connect, read, key, body) -> {
            calls.incrementAndGet();
            throw new IOException("Bearer " + fakeCredential());
        };

        assertThatThrownBy(() -> client(transport).generate(request()))
                .isInstanceOf(AnswerClientException.class)
                .satisfies(thrown -> assertThat(((AnswerClientException) thrown).failureType())
                        .isEqualTo(AnswerFailureType.TIMEOUT_OR_NETWORK_UNAVAILABLE));
        assertThat(calls).hasValue(1);
    }

    @Test
    void rejectsMalformedEnvelopeAndStructuredResponseThroughTypedFailure() {
        OpenAiCompatibleHttpTransport malformedEnvelope = (uri, connect, read, key, body) ->
                response(200, "{\"choices\":[]}");
        assertInvalidResponse(malformedEnvelope);

        OpenAiCompatibleHttpTransport malformedStructuredResponse = (uri, connect, read, key, body) ->
                response(200, envelope("provider-model",
                        "{not-json}", null, null, null, null));
        assertInvalidResponse(malformedStructuredResponse);

        OpenAiCompatibleHttpTransport unknownCitation = (uri, connect, read, key, body) ->
                response(200, envelope("provider-model", STRUCTURED_RESPONSE.replace("E1", "E9"),
                        null, null, null, null));
        assertInvalidResponse(unknownCitation);

        OpenAiCompatibleHttpTransport emptyCitation = (uri, connect, read, key, body) ->
                response(200, envelope("provider-model", STRUCTURED_RESPONSE.replace("[\"E1\"]", "[]"),
                        null, null, null, null));
        assertInvalidResponse(emptyCitation);
    }

    @Test
    void missingOrDisabledConfigurationFailsClosedWithoutCallingTransport() {
        AtomicInteger calls = new AtomicInteger();
        OpenAiCompatibleHttpTransport transport = (uri, connect, read, key, body) -> {
            calls.incrementAndGet();
            return response(200, envelope("model", STRUCTURED_RESPONSE, null, null, null, null));
        };
        OpenAiCompatibleAnswerProperties properties = properties();
        properties.setEnabled(false);
        OpenAiCompatibleAnswerClient client = new OpenAiCompatibleAnswerClient(properties,
                transport, new ObjectMapper(), new org.km.llmwiki.ai.answer.GroundedAnswerPromptContract(),
                new org.km.llmwiki.ai.answer.GroundedAnswerResponseContract(new ObjectMapper()));

        assertThatThrownBy(() -> client.generate(request()))
                .isInstanceOf(AnswerClientException.class)
                .extracting(thrown -> ((AnswerClientException) thrown).failureType())
                .isEqualTo(AnswerFailureType.CONFIGURATION_UNAVAILABLE_OR_DISABLED);
        assertThat(calls).hasValue(0);

        properties.setEnabled(true);
        properties.setApiKey("");
        assertThatThrownBy(() -> client.generate(request()))
                .isInstanceOf(AnswerClientException.class)
                .satisfies(thrown -> {
                    AnswerClientException exception = (AnswerClientException) thrown;
                    assertThat(exception.failureType()).isEqualTo(
                            AnswerFailureType.CONFIGURATION_UNAVAILABLE_OR_DISABLED);
                    assertThat(exception.getMessage()).doesNotContain("api-key");
                });
        assertThat(calls).hasValue(0);
    }

    @Test
    void rejectsUnsupportedConfigurationAndBoundsBeforeTransport() {
        OpenAiCompatibleAnswerProperties properties = properties();
        properties.setProvider("another-provider");
        OpenAiCompatibleAnswerClient client = new OpenAiCompatibleAnswerClient(properties,
                (uri, connect, read, key, body) -> response(200, envelope("m", STRUCTURED_RESPONSE,
                        null, null, null, null)), new ObjectMapper(),
                new org.km.llmwiki.ai.answer.GroundedAnswerPromptContract(),
                new org.km.llmwiki.ai.answer.GroundedAnswerResponseContract(new ObjectMapper()));

        assertThatThrownBy(() -> client.generate(request()))
                .isInstanceOf(AnswerClientException.class)
                .extracting(thrown -> ((AnswerClientException) thrown).failureType())
                .isEqualTo(AnswerFailureType.CONFIGURATION_UNAVAILABLE_OR_DISABLED);
    }

    private void assertInvalidResponse(OpenAiCompatibleHttpTransport transport) {
        assertThatThrownBy(() -> client(transport).generate(request()))
                .isInstanceOf(AnswerClientException.class)
                .extracting(thrown -> ((AnswerClientException) thrown).failureType())
                .isEqualTo(AnswerFailureType.INVALID_PROVIDER_RESPONSE);
    }

    private static OpenAiCompatibleAnswerClient client(OpenAiCompatibleHttpTransport transport) {
        ObjectMapper mapper = new ObjectMapper();
        return new OpenAiCompatibleAnswerClient(properties(), transport, mapper,
                new org.km.llmwiki.ai.answer.GroundedAnswerPromptContract(),
                new org.km.llmwiki.ai.answer.GroundedAnswerResponseContract(mapper));
    }

    private static OpenAiCompatibleAnswerProperties properties() {
        OpenAiCompatibleAnswerProperties properties = new OpenAiCompatibleAnswerProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("https://provider.example/v1");
        properties.setModel("configured-model");
        properties.setApiKey(fakeCredential());
        properties.setConnectTimeout(Duration.ofSeconds(1));
        properties.setReadTimeout(Duration.ofSeconds(5));
        properties.setMaxOutputTokens(4_000);
        properties.setTemperature(0);
        return properties;
    }

    private static AnswerRequest request() {
        return new AnswerRequest("What is the answer?", AnswerContext.fromReferences(
                java.util.List.of(new AnswerContextReference("WIKI:answer", "hash-answer"))),
                AnswerGenerationOptions.defaults());
    }

    private static OpenAiCompatibleHttpResponse response(int status, String body) {
        return new OpenAiCompatibleHttpResponse(status, body);
    }

    private static String envelope(String model, String content, String id, String input,
                                   String output, String total) {
        String modelField = model == null ? "" : ",\"model\":\"" + model + "\"";
        String idField = id == null ? "" : ",\"id\":\"" + id + "\"";
        String usage = input == null && output == null && total == null ? "" :
                ",\"usage\":{\"prompt_tokens\":" + input + ",\"completion_tokens\":"
                        + output + ",\"total_tokens\":" + total + "}";
        return "{\"choices\":[{\"message\":{\"content\":"
                + quote(content) + "}}]" + modelField + idField + usage + "}";
    }

    private static String quote(String value) {
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String fakeCredential() {
        return "fixture-" + "credential";
    }
}
