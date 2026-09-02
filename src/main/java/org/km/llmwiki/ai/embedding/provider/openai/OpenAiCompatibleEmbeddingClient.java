package org.km.llmwiki.ai.embedding.provider.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.km.llmwiki.ai.embedding.EmbeddingClient;
import org.km.llmwiki.ai.embedding.EmbeddingClientException;
import org.km.llmwiki.ai.embedding.EmbeddingFailureType;
import org.km.llmwiki.ai.embedding.EmbeddingInput;
import org.km.llmwiki.ai.embedding.EmbeddingProviderMetadata;
import org.km.llmwiki.ai.embedding.EmbeddingRequest;
import org.km.llmwiki.ai.embedding.EmbeddingResult;
import org.km.llmwiki.ai.embedding.EmbeddingUsageMetadata;
import org.km.llmwiki.ai.embedding.EmbeddingVector;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Production-capable OpenAI-compatible embeddings adapter.
 *
 * <p>The OpenAI HTTP path, payload, response envelope, authorization header, and JSON parsing are
 * intentionally confined here. No provider DTO or JSON shape crosses the embedding boundary.
 */
public final class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    public static final String PROVIDER = "openai-compatible";
    private static final int MAX_BASE_URL_LENGTH = 2_048;
    private static final int MAX_MODEL_LENGTH = 128;
    private static final int MAX_API_KEY_LENGTH = 4_096;
    private static final int MAX_RESPONSE_CODE_POINTS = 2_000_000;
    private static final Duration MIN_TIMEOUT = Duration.ofMillis(50);
    private static final Duration MAX_CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration MAX_READ_TIMEOUT = Duration.ofSeconds(120);

    private final OpenAiCompatibleEmbeddingProperties properties;
    private final OpenAiCompatibleEmbeddingHttpTransport transport;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleEmbeddingClient(OpenAiCompatibleEmbeddingProperties properties,
                                           ObjectMapper objectMapper) {
        this(properties, new JdkOpenAiCompatibleEmbeddingHttpTransport(), objectMapper);
    }

    OpenAiCompatibleEmbeddingClient(OpenAiCompatibleEmbeddingProperties properties,
                                    OpenAiCompatibleEmbeddingHttpTransport transport,
                                    ObjectMapper objectMapper) {
        this.properties = properties;
        this.transport = transport;
        this.objectMapper = objectMapper;
    }

    @Override
    public EmbeddingResult embed(EmbeddingRequest request) {
        if (request == null) {
            throw failure(EmbeddingFailureType.LOCAL_VALIDATION, "embedding request is required");
        }
        validateConfiguration();

        URI endpoint;
        String requestBody;
        try {
            endpoint = endpoint(properties.getBaseUrl());
            requestBody = objectMapper.writeValueAsString(requestPayload(request));
        } catch (JsonProcessingException | IllegalArgumentException | URISyntaxException exception) {
            throw failure(EmbeddingFailureType.LOCAL_VALIDATION,
                    "embedding provider request configuration or encoding is invalid");
        }

        OpenAiCompatibleEmbeddingHttpResponse response;
        try {
            response = transport.post(endpoint, properties.getConnectTimeout(),
                    properties.getReadTimeout(), properties.getApiKey(), requestBody);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure(EmbeddingFailureType.TIMEOUT_OR_NETWORK_UNAVAILABLE,
                    "embedding provider request was interrupted");
        } catch (IOException exception) {
            throw failure(EmbeddingFailureType.TIMEOUT_OR_NETWORK_UNAVAILABLE,
                    "embedding provider network request failed");
        }

        if (response == null) {
            throw failure(EmbeddingFailureType.INVALID_PROVIDER_RESPONSE,
                    "embedding provider transport returned no response");
        }
        mapHttpFailure(response.statusCode());
        String responseBody = response.body();
        if (responseBody == null || responseBody.isBlank()
                || responseBody.codePointCount(0, responseBody.length()) > MAX_RESPONSE_CODE_POINTS) {
            throw failure(EmbeddingFailureType.INVALID_PROVIDER_RESPONSE,
                    "embedding provider response is empty or too large");
        }
        return parseProviderResponse(responseBody, request);
    }

    private void validateConfiguration() {
        if (properties == null || !properties.isEnabled()) {
            throw failure(EmbeddingFailureType.CONFIGURATION_UNAVAILABLE_OR_DISABLED,
                    "embedding provider is disabled or unavailable");
        }
        if (!PROVIDER.equals(properties.getProvider())) {
            throw failure(EmbeddingFailureType.CONFIGURATION_UNAVAILABLE_OR_DISABLED,
                    "unsupported embedding provider selector");
        }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()
                || properties.getApiKey().length() > MAX_API_KEY_LENGTH) {
            throw failure(EmbeddingFailureType.CONFIGURATION_UNAVAILABLE_OR_DISABLED,
                    "embedding provider credential is unavailable");
        }
        if (properties.getModel() == null || properties.getModel().isBlank()
                || properties.getModel().length() > MAX_MODEL_LENGTH
                || hasLineBreak(properties.getModel())) {
            throw failure(EmbeddingFailureType.CONFIGURATION_UNAVAILABLE_OR_DISABLED,
                    "embedding provider model is invalid or unavailable");
        }
        validateTimeout(properties.getConnectTimeout(), MAX_CONNECT_TIMEOUT, "connect timeout");
        validateTimeout(properties.getReadTimeout(), MAX_READ_TIMEOUT, "read timeout");
        if (properties.getDimension() < 0 || properties.getDimension() > EmbeddingVector.MAX_DIMENSION) {
            throw failure(EmbeddingFailureType.CONFIGURATION_UNAVAILABLE_OR_DISABLED,
                    "embedding provider dimension is outside the allowed bound");
        }
        try {
            endpoint(properties.getBaseUrl());
        } catch (URISyntaxException | IllegalArgumentException exception) {
            throw failure(EmbeddingFailureType.CONFIGURATION_UNAVAILABLE_OR_DISABLED,
                    "embedding provider endpoint is invalid or unavailable");
        }
    }

    private static void validateTimeout(Duration timeout, Duration maximum, String name) {
        if (timeout == null || timeout.compareTo(MIN_TIMEOUT) < 0 || timeout.compareTo(maximum) > 0) {
            throw failure(EmbeddingFailureType.CONFIGURATION_UNAVAILABLE_OR_DISABLED,
                    "embedding provider " + name + " is outside the allowed bound");
        }
    }

    private Map<String, Object> requestPayload(EmbeddingRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", properties.getModel());
        payload.put("input", request.inputs().stream().map(EmbeddingInput::text).toList());
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
        return URI.create(normalized + "/embeddings");
    }

    private void mapHttpFailure(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) {
            return;
        }
        EmbeddingFailureType type;
        String diagnostic;
        if (statusCode == 401 || statusCode == 403) {
            type = EmbeddingFailureType.AUTHENTICATION_OR_AUTHORIZATION;
            diagnostic = "embedding provider authentication or authorization failed";
        } else if (statusCode == 408 || statusCode == 504) {
            type = EmbeddingFailureType.TIMEOUT_OR_NETWORK_UNAVAILABLE;
            diagnostic = "embedding provider request timed out";
        } else if (statusCode == 429) {
            type = EmbeddingFailureType.RATE_LIMIT_OR_QUOTA;
            diagnostic = "embedding provider rate limit or quota was reached";
        } else if (statusCode >= 500 && statusCode <= 599) {
            type = EmbeddingFailureType.PROVIDER_SERVER_FAILURE;
            diagnostic = "embedding provider server failure";
        } else if (statusCode == 404) {
            type = EmbeddingFailureType.CONFIGURATION_UNAVAILABLE_OR_DISABLED;
            diagnostic = "embedding provider endpoint is unavailable";
        } else {
            type = EmbeddingFailureType.LOCAL_VALIDATION;
            diagnostic = "embedding provider rejected the request";
        }
        throw failure(type, diagnostic);
    }

    private EmbeddingResult parseProviderResponse(String body, EmbeddingRequest request) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException();
            }
            JsonNode data = root.get("data");
            if (data == null || !data.isArray() || data.size() != request.inputs().size()) {
                throw new IllegalArgumentException();
            }

            List<EmbeddingVector> vectors = new ArrayList<>(data.size());
            for (int position = 0; position < data.size(); position++) {
                JsonNode item = data.get(position);
                if (item == null || !item.isObject() || !hasExactIndex(item, position)) {
                    throw new IllegalArgumentException();
                }
                JsonNode embedding = item.get("embedding");
                if (embedding == null || !embedding.isArray() || embedding.isEmpty()
                        || embedding.size() > EmbeddingVector.MAX_DIMENSION) {
                    throw new IllegalArgumentException();
                }
                List<Double> values = new ArrayList<>(embedding.size());
                for (JsonNode value : embedding) {
                    if (value == null || !value.isNumber() || !Double.isFinite(value.asDouble())) {
                        throw new IllegalArgumentException();
                    }
                    values.add(value.asDouble());
                }
                if (properties.getDimension() > 0 && values.size() != properties.getDimension()) {
                    throw new IllegalArgumentException();
                }
                vectors.add(new EmbeddingVector(request.inputs().get(position).identity(), values));
            }

            Optional<String> model = optionalText(root, "model");
            Optional<EmbeddingUsageMetadata> usage = usage(root.get("usage"));
            EmbeddingProviderMetadata metadata = new EmbeddingProviderMetadata(PROVIDER,
                    model.orElse(properties.getModel()));
            return new EmbeddingResult(vectors, metadata, usage);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw failure(EmbeddingFailureType.INVALID_PROVIDER_RESPONSE,
                    "embedding provider response envelope or vector data is malformed");
        }
    }

    private static boolean hasExactIndex(JsonNode item, int expected) {
        JsonNode index = item.get("index");
        return index != null && index.isIntegralNumber() && index.canConvertToInt()
                && index.asInt() == expected;
    }

    private static Optional<String> optionalText(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > MAX_MODEL_LENGTH
                || hasLineBreak(value.asText())) {
            throw new IllegalArgumentException();
        }
        return Optional.of(value.asText());
    }

    private static Optional<EmbeddingUsageMetadata> usage(JsonNode usage) {
        if (usage == null || usage.isNull()) {
            return Optional.empty();
        }
        if (!usage.isObject()) {
            throw new IllegalArgumentException();
        }
        Integer input = usageValue(usage, "prompt_tokens");
        Integer total = usageValue(usage, "total_tokens");
        if (input == null && total == null) {
            throw new IllegalArgumentException();
        }
        return Optional.of(new EmbeddingUsageMetadata(input, total));
    }

    private static Integer usageValue(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 0) {
            throw new IllegalArgumentException();
        }
        return value.asInt();
    }

    private static boolean hasLineBreak(String value) {
        return value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
    }

    private static EmbeddingClientException failure(EmbeddingFailureType type, String diagnostic) {
        return new EmbeddingClientException(type, diagnostic);
    }
}
