package org.km.llmwiki.ai.query.provider.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.km.llmwiki.ai.answer.AnswerUsageMetadata;
import org.km.llmwiki.ai.provider.ProviderEndpointSecurityPolicy;
import org.km.llmwiki.ai.query.QueryRewriteClient;
import org.km.llmwiki.ai.query.QueryRewriteException;
import org.km.llmwiki.ai.query.QueryRewriteResult;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** OpenAI-compatible adapter that returns one bounded structured rewrite candidate. */
public final class OpenAiCompatibleQueryRewriteClient implements QueryRewriteClient {
    static final String PROVIDER = "openai-compatible";
    private static final int MAX_MODEL_LENGTH = 128;
    private static final int MAX_API_KEY_LENGTH = 4_096;
    private static final int MAX_RESPONSE_CODE_POINTS = 16_384;
    private static final Duration MIN_TIMEOUT = Duration.ofMillis(50);
    private static final Duration MAX_CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration MAX_READ_TIMEOUT = Duration.ofSeconds(120);

    private final OpenAiCompatibleQueryRewriteProperties properties;
    private final QueryRewriteHttpTransport transport;
    private final ObjectMapper mapper;

    public OpenAiCompatibleQueryRewriteClient(OpenAiCompatibleQueryRewriteProperties properties,
                                               ObjectMapper mapper) {
        this(properties, new JdkQueryRewriteHttpTransport(), mapper);
    }

    OpenAiCompatibleQueryRewriteClient(OpenAiCompatibleQueryRewriteProperties properties,
                                       QueryRewriteHttpTransport transport, ObjectMapper mapper) {
        this.properties = properties;
        this.transport = transport;
        this.mapper = mapper;
    }

    @Override
    public String rewrite(String originalQuery, List<String> protectedTokens) {
        return rewriteWithMetadata(originalQuery, protectedTokens).rewrittenQuery();
    }

    @Override
    public QueryRewriteResult rewriteWithMetadata(String originalQuery,
                                                  List<String> protectedTokens) {
        validateConfiguration();
        URI endpoint;
        String requestBody;
        try {
            endpoint = ProviderEndpointSecurityPolicy.validateAndAppend(
                    properties.getBaseUrl(), "/chat/completions",
                    properties.isAllowInsecureTransport());
            requestBody = mapper.writeValueAsString(requestPayload(originalQuery, protectedTokens));
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw unavailable();
        }

        QueryRewriteHttpResponse response;
        try {
            response = transport.post(endpoint, properties.getConnectTimeout(),
                    properties.getReadTimeout(), properties.getApiKey(), requestBody);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException failure) {
            throw unavailable();
        }
        if (response == null || response.statusCode() < 200 || response.statusCode() >= 300) {
            throw unavailable();
        }
        return parseResponse(response.body());
    }

    private Map<String, Object> requestPayload(String originalQuery, List<String> protectedTokens)
            throws JsonProcessingException {
        String instruction = "Rewrite the user query as one concise search query. Preserve "
                + "every protected token verbatim. Return JSON only: {\"rewrite\":\"...\"}. "
                + "Protected tokens: " + mapper.writeValueAsString(protectedTokens);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", properties.getModel());
        payload.put("temperature", 0);
        payload.put("max_tokens", 256);
        payload.put("response_format", Map.of("type", "json_object"));
        payload.put("messages", List.of(
                Map.of("role", "system", "content", instruction),
                Map.of("role", "user", "content", originalQuery)));
        return payload;
    }

    private QueryRewriteResult parseResponse(String body) {
        if (body == null || body.isBlank()
                || body.codePointCount(0, body.length()) > MAX_RESPONSE_CODE_POINTS) {
            throw invalid();
        }
        try {
            JsonNode root = mapper.readTree(body);
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
            JsonNode structured = mapper.readTree(content.asText());
            JsonNode rewrite = structured == null ? null : structured.get("rewrite");
            if (rewrite == null || !rewrite.isTextual() || rewrite.asText().isBlank()) {
                throw new IllegalArgumentException();
            }
            return new QueryRewriteResult(rewrite.asText(), usage(root.get("usage")));
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private static Optional<AnswerUsageMetadata> usage(JsonNode usage) {
        if (usage == null || usage.isNull()) {
            return Optional.empty();
        }
        if (!usage.isObject()) {
            throw new IllegalArgumentException();
        }
        Integer input = usageValue(usage, "prompt_tokens");
        Integer output = usageValue(usage, "completion_tokens");
        Integer total = usageValue(usage, "total_tokens");
        if (input == null && output == null && total == null) {
            throw new IllegalArgumentException();
        }
        return Optional.of(new AnswerUsageMetadata(input, output, total));
    }

    private static Integer usageValue(JsonNode parent, String name) {
        JsonNode value = parent.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 0) {
            throw new IllegalArgumentException();
        }
        return value.asInt();
    }

    private void validateConfiguration() {
        if (properties == null || !properties.isEnabled()
                || !PROVIDER.equals(properties.getProvider())
                || properties.getModel() == null || properties.getModel().isBlank()
                || properties.getModel().length() > MAX_MODEL_LENGTH
                || properties.getModel().indexOf('\n') >= 0
                || properties.getModel().indexOf('\r') >= 0
                || properties.getApiKey() == null || properties.getApiKey().isBlank()
                || properties.getApiKey().length() > MAX_API_KEY_LENGTH
                || !validTimeout(properties.getConnectTimeout(), MAX_CONNECT_TIMEOUT)
                || !validTimeout(properties.getReadTimeout(), MAX_READ_TIMEOUT)) {
            throw unavailable();
        }
        try {
            ProviderEndpointSecurityPolicy.validateAndAppend(properties.getBaseUrl(),
                    "/chat/completions", properties.isAllowInsecureTransport());
        } catch (IllegalArgumentException failure) {
            throw unavailable();
        }
    }

    private static boolean validTimeout(Duration timeout, Duration maximum) {
        return timeout != null && timeout.compareTo(MIN_TIMEOUT) >= 0
                && timeout.compareTo(maximum) <= 0;
    }

    private static QueryRewriteException unavailable() {
        return new QueryRewriteException(QueryRewriteException.Type.UNAVAILABLE,
                "query rewrite provider is unavailable");
    }

    private static QueryRewriteException invalid() {
        return new QueryRewriteException(QueryRewriteException.Type.INVALID_RESPONSE,
                "query rewrite provider response is invalid");
    }
}
