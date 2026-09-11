package org.km.llmwiki.mcp;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Minimal, bounded JSON-RPC 2.0 / MCP Streamable HTTP envelope codec for the read-only local
 * adapter. This is a transport codec only: it carries no application semantics and never
 * includes credentials or raw provider details. Requests are single JSON objects
 * (application/json; no SSE stream in this initial loopback-only adapter), and oversized or
 * malformed envelopes are rejected by the guard before reaching the dispatcher.
 *
 * <p>Floating-point literals parse as exact {@link java.math.BigDecimal} values
 * ({@code USE_BIG_DECIMAL_FOR_FLOATS}): the JSON text's mathematical value is preserved for
 * the tool-input contract (#348), so integral-float representations such as {@code 2.0} and
 * {@code 1e2} are validated as the integers they mathematically are, sub-double-precision
 * fractions are never silently rounded away, and no numeric comparison can overflow a
 * {@code double}.
 */
public final class McpJsonRpc {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private McpJsonRpc() {
    }

    public static JsonNode valueToTree(Map<String, Object> value) {
        return MAPPER.valueToTree(value);
    }

    /**
     * Parses any bounded JSON value exactly as the wire plane does; malformed input is a
     * typed null return. Exposed for parity/unit tests so every fixture is parsed by the
     * same mapper configuration the runtime validates.
     */
    public static JsonNode parseValue(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception failure) {
            return null;
        }
    }

    /** Parses a bounded JSON-RPC request envelope; malformed input is a typed null return. */
    public static JsonNode parse(String body) {
        try {
            JsonNode node = MAPPER.readTree(body);
            return node != null && node.isObject() ? node : null;
        } catch (Exception failure) {
            return null;
        }
    }

    public static String method(JsonNode request) {
        JsonNode method = request == null ? null : request.get("method");
        return method != null && method.isTextual() ? method.textValue() : null;
    }

    public static JsonNode id(JsonNode request) {
        return request == null || !request.has("id") ? null : request.get("id");
    }

    public static JsonNode params(JsonNode request) {
        return request == null || !request.hasNonNull("params") || !request.get("params").isObject()
                ? MAPPER.createObjectNode() : request.get("params");
    }

    public static String result(JsonNode id, Object payload) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id == null ? MAPPER.nullNode() : id);
        response.set("result", MAPPER.valueToTree(payload));
        return render(response);
    }

    public static String error(JsonNode id, int code, String message) {
        return error(id, code, message, null);
    }

    public static String error(JsonNode id, int code, String message, Object data) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id == null ? MAPPER.nullNode() : id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message.length() <= 256 ? message : message.substring(0, 256));
        if (data != null) {
            error.set("data", MAPPER.valueToTree(data));
        }
        return render(response);
    }

    public static String renderValue(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("mcp value serialization failed", failure);
        }
    }

    private static String render(ObjectNode response) {
        try {
            return MAPPER.writeValueAsString(response);
        } catch (Exception failure) {
            throw new IllegalStateException("mcp response serialization failed", failure);
        }
    }
}
