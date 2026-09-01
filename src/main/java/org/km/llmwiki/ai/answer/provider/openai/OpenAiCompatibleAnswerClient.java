package org.km.llmwiki.ai.answer.provider.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.km.llmwiki.ai.answer.AnswerClient;
import org.km.llmwiki.ai.answer.AnswerClientException;
import org.km.llmwiki.ai.answer.AnswerFailureType;
import org.km.llmwiki.ai.answer.AnswerGenerationOptions;
import org.km.llmwiki.ai.answer.AnswerProviderMetadata;
import org.km.llmwiki.ai.answer.AnswerRequest;
import org.km.llmwiki.ai.answer.AnswerResult;
import org.km.llmwiki.ai.answer.AnswerUsageMetadata;
import org.km.llmwiki.ai.answer.GroundedAnswerPrompt;
import org.km.llmwiki.ai.answer.GroundedAnswerPromptContract;
import org.km.llmwiki.ai.answer.GroundedAnswerResponse;
import org.km.llmwiki.ai.answer.GroundedAnswerResponseContract;
import org.km.llmwiki.ai.answer.GroundedAnswerValidationException;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Production-capable provider adapter for OpenAI-compatible chat-completions APIs.
 *
 * <p>Only this boundary knows the provider envelope, endpoint, authorization header, and
 * generation fields. The application receives and returns provider-neutral Answer contracts.
 */
public final class OpenAiCompatibleAnswerClient implements AnswerClient {

    public static final String PROVIDER = "openai-compatible";
    private static final int MAX_BASE_URL_LENGTH = 2_048;
    private static final int MAX_MODEL_LENGTH = 128;
    private static final int MAX_API_KEY_LENGTH = 4_096;
    private static final int MAX_RESPONSE_CODE_POINTS = 160_000;
    private static final Duration MIN_TIMEOUT = Duration.ofMillis(50);
    private static final Duration MAX_CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration MAX_READ_TIMEOUT = Duration.ofSeconds(120);
    private static final int MAX_OUTPUT_TOKENS = 16_000;

    private final OpenAiCompatibleAnswerProperties properties;
    private final OpenAiCompatibleHttpTransport transport;
    private final ObjectMapper objectMapper;
    private final GroundedAnswerPromptContract promptContract;
    private final GroundedAnswerResponseContract responseContract;

    public OpenAiCompatibleAnswerClient(OpenAiCompatibleAnswerProperties properties,
                                        ObjectMapper objectMapper) {
        this(properties, new JdkOpenAiCompatibleHttpTransport(), objectMapper,
                new GroundedAnswerPromptContract(), new GroundedAnswerResponseContract(objectMapper));
    }

    OpenAiCompatibleAnswerClient(OpenAiCompatibleAnswerProperties properties,
                                 OpenAiCompatibleHttpTransport transport,
                                 ObjectMapper objectMapper,
                                 GroundedAnswerPromptContract promptContract,
                                 GroundedAnswerResponseContract responseContract) {
        this.properties = properties;
        this.transport = transport;
        this.objectMapper = objectMapper;
        this.promptContract = promptContract;
        this.responseContract = responseContract;
    }

    @Override
    public AnswerResult generate(AnswerRequest request) {
        if (request == null) {
            throw failure(AnswerFailureType.LOCAL_VALIDATION, "answer request is required");
        }
        validateConfiguration();

        GroundedAnswerPrompt prompt;
        String requestBody;
        URI endpoint;
        try {
            prompt = promptContract.render(request);
            requestBody = objectMapper.writeValueAsString(requestPayload(prompt, request.options()));
            endpoint = endpoint(properties.getBaseUrl());
        } catch (JsonProcessingException | IllegalArgumentException | URISyntaxException exception) {
            throw failure(AnswerFailureType.LOCAL_VALIDATION,
                    "answer provider request configuration or encoding is invalid");
        }

        OpenAiCompatibleHttpResponse httpResponse;
        try {
            httpResponse = transport.post(endpoint, properties.getConnectTimeout(),
                    properties.getReadTimeout(), properties.getApiKey(), requestBody);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure(AnswerFailureType.TIMEOUT_OR_NETWORK_UNAVAILABLE,
                    "answer provider request was interrupted");
        } catch (java.io.IOException | RuntimeException exception) {
            throw failure(AnswerFailureType.TIMEOUT_OR_NETWORK_UNAVAILABLE,
                    "answer provider network request failed");
        }

        if (httpResponse == null) {
            throw failure(AnswerFailureType.INVALID_PROVIDER_RESPONSE,
                    "answer provider transport returned no response");
        }
        mapHttpFailure(httpResponse.statusCode());
        String responseBody = httpResponse.body();
        if (responseBody == null || responseBody.isBlank()
                || responseBody.codePointCount(0, responseBody.length()) > MAX_RESPONSE_CODE_POINTS) {
            throw failure(AnswerFailureType.INVALID_PROVIDER_RESPONSE,
                    "answer provider response is empty or too large");
        }

        ProviderResponse providerResponse = parseProviderResponse(responseBody);
        GroundedAnswerResponse validated;
        try {
            // STORY-603 owns acceptance of the provider's structured answer and citation ids.
            validated = responseContract.parse(providerResponse.structuredContent(), request.context(),
                    request.options().maxOutputCodePoints());
        } catch (GroundedAnswerValidationException exception) {
            throw failure(AnswerFailureType.INVALID_PROVIDER_RESPONSE,
                    "structured answer response was rejected: " + exception.errorCode());
        }

        String model = providerResponse.model().orElse(properties.getModel());
        AnswerProviderMetadata metadata;
        try {
            metadata = new AnswerProviderMetadata(PROVIDER, model);
        } catch (IllegalArgumentException exception) {
            throw failure(AnswerFailureType.INVALID_PROVIDER_RESPONSE,
                    "answer provider response did not contain valid model metadata");
        }
        return new AnswerResult(validated.answerText(), validated.citedEvidenceIds(),
                validated.insufficientEvidence(), metadata, providerResponse.usage());
    }

    private void validateConfiguration() {
        if (properties == null || !properties.isEnabled()) {
            throw failure(AnswerFailureType.CONFIGURATION_UNAVAILABLE_OR_DISABLED,
                    "answer provider is disabled or unavailable");
        }
        if (!PROVIDER.equals(properties.getProvider())) {
            throw failure(AnswerFailureType.CONFIGURATION_UNAVAILABLE_OR_DISABLED,
                    "unsupported answer provider selector");
        }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()
                || properties.getApiKey().length() > MAX_API_KEY_LENGTH) {
            throw failure(AnswerFailureType.CONFIGURATION_UNAVAILABLE_OR_DISABLED,
                    "answer provider credential is unavailable");
        }
        if (properties.getModel() == null || properties.getModel().isBlank()
                || properties.getModel().length() > MAX_MODEL_LENGTH
                || properties.getModel().indexOf('\n') >= 0 || properties.getModel().indexOf('\r') >= 0) {
            throw failure(AnswerFailureType.CONFIGURATION_UNAVAILABLE_OR_DISABLED,
                    "answer provider model is invalid or unavailable");
        }
        validateTimeout(properties.getConnectTimeout(), MAX_CONNECT_TIMEOUT, "connect timeout");
        validateTimeout(properties.getReadTimeout(), MAX_READ_TIMEOUT, "read timeout");
        if (properties.getMaxOutputTokens() < 1 || properties.getMaxOutputTokens() > MAX_OUTPUT_TOKENS) {
            throw failure(AnswerFailureType.CONFIGURATION_UNAVAILABLE_OR_DISABLED,
                    "answer provider generation limit is invalid");
        }
        if (!Double.isFinite(properties.getTemperature())
                || properties.getTemperature() < 0 || properties.getTemperature() > 2) {
            throw failure(AnswerFailureType.CONFIGURATION_UNAVAILABLE_OR_DISABLED,
                    "answer provider temperature is invalid");
        }
        try {
            endpoint(properties.getBaseUrl());
        } catch (URISyntaxException | IllegalArgumentException exception) {
            throw failure(AnswerFailureType.CONFIGURATION_UNAVAILABLE_OR_DISABLED,
                    "answer provider endpoint is invalid or unavailable");
        }
    }

    private static void validateTimeout(Duration timeout, Duration maximum, String name) {
        if (timeout == null || timeout.compareTo(MIN_TIMEOUT) < 0 || timeout.compareTo(maximum) > 0) {
            throw failure(AnswerFailureType.CONFIGURATION_UNAVAILABLE_OR_DISABLED,
                    "answer provider " + name + " is outside the allowed bound");
        }
    }

    private Map<String, Object> requestPayload(GroundedAnswerPrompt prompt,
                                                AnswerGenerationOptions options) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", properties.getModel());
        payload.put("messages", java.util.List.of(Map.of(
                "role", "user",
                "content", prompt.content())));
        payload.put("response_format", Map.of("type", "json_object"));
        payload.put("max_tokens", properties.getMaxOutputTokens());
        payload.put("temperature", properties.getTemperature());
        return payload;
    }

    private static URI endpoint(String baseUrl) throws URISyntaxException {
        if (baseUrl == null || baseUrl.isBlank() || baseUrl.length() > MAX_BASE_URL_LENGTH) {
            throw new IllegalArgumentException("base URL is invalid");
        }
        URI base = new URI(baseUrl.trim());
        if (!("https".equalsIgnoreCase(base.getScheme()) || "http".equalsIgnoreCase(base.getScheme()))
                || base.getHost() == null || base.getUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null) {
            throw new IllegalArgumentException("base URL is invalid");
        }
        String normalized = base.toString().replaceFirst("/+$", "");
        return URI.create(normalized + "/chat/completions");
    }

    private void mapHttpFailure(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) {
            return;
        }
        AnswerFailureType type;
        String diagnostic;
        if (statusCode == 401 || statusCode == 403) {
            type = AnswerFailureType.AUTHENTICATION_OR_AUTHORIZATION;
            diagnostic = "answer provider authentication or authorization failed";
        } else if (statusCode == 408 || statusCode == 504) {
            type = AnswerFailureType.TIMEOUT_OR_NETWORK_UNAVAILABLE;
            diagnostic = "answer provider request timed out";
        } else if (statusCode == 429) {
            type = AnswerFailureType.RATE_LIMIT_OR_QUOTA;
            diagnostic = "answer provider rate limit or quota was reached";
        } else if (statusCode >= 500 && statusCode <= 599) {
            type = AnswerFailureType.PROVIDER_SERVER_FAILURE;
            diagnostic = "answer provider server failure";
        } else if (statusCode == 404) {
            type = AnswerFailureType.CONFIGURATION_UNAVAILABLE_OR_DISABLED;
            diagnostic = "answer provider endpoint is unavailable";
        } else {
            type = AnswerFailureType.LOCAL_VALIDATION;
            diagnostic = "answer provider rejected the request";
        }
        throw failure(type, diagnostic);
    }

    private ProviderResponse parseProviderResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException();
            }
            JsonNode choices = root.get("choices");
            JsonNode message = choices != null && choices.isArray() && !choices.isEmpty()
                    ? choices.get(0).get("message") : null;
            JsonNode content = message == null ? null : message.get("content");
            if (content == null || !content.isTextual() || content.asText().isBlank()) {
                throw new IllegalArgumentException();
            }

            Optional<String> model = optionalText(root, "model");
            Optional<AnswerUsageMetadata> usage = usage(root.get("usage"));
            return new ProviderResponse(content.asText(), model, usage);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw failure(AnswerFailureType.INVALID_PROVIDER_RESPONSE,
                    "answer provider response envelope is malformed");
        }
    }

    private static Optional<String> optionalText(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > MAX_MODEL_LENGTH
                || value.asText().indexOf('\n') >= 0 || value.asText().indexOf('\r') >= 0) {
            throw new IllegalArgumentException();
        }
        return Optional.of(value.asText());
    }

    private static Optional<AnswerUsageMetadata> usage(JsonNode usage) {
        if (usage == null || usage.isNull()) {
            return Optional.empty();
        }
        if (!usage.isObject()) {
            throw new IllegalArgumentException();
        }
        Integer input = optionalUsageValue(usage, "prompt_tokens");
        Integer output = optionalUsageValue(usage, "completion_tokens");
        Integer total = optionalUsageValue(usage, "total_tokens");
        if (input == null && output == null && total == null) {
            throw new IllegalArgumentException();
        }
        return Optional.of(new AnswerUsageMetadata(input, output, total));
    }

    private static Integer optionalUsageValue(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 0) {
            throw new IllegalArgumentException();
        }
        return value.asInt();
    }

    private static AnswerClientException failure(AnswerFailureType type, String diagnostic) {
        return new AnswerClientException(type, diagnostic);
    }

    private record ProviderResponse(String structuredContent, Optional<String> model,
                                    Optional<AnswerUsageMetadata> usage) {
    }
}
