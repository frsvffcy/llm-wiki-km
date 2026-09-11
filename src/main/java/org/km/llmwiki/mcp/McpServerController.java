package org.km.llmwiki.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loopback-only, read-only MCP Streamable HTTP boundary. Transport security, authentication,
 * decoded-body bounds, protocol-era validation, and dispatch run in that fixed order.
 */
@RestController
@RequestMapping("/api/mcp")
public class McpServerController {

    private static final int JSONRPC_PARSE_ERROR = -32700;
    private static final int JSONRPC_INVALID_REQUEST = -32600;
    private static final int JSONRPC_METHOD_NOT_FOUND = -32601;
    private static final int JSONRPC_PAYLOAD_TOO_LARGE = -32001;

    private final McpProperties properties;
    private final McpToolExecutor executor;

    @Autowired
    public McpServerController(McpProperties properties, @Lazy McpToolExecutor executor) {
        this.properties = properties;
        this.executor = executor;
    }

    @PostMapping
    public ResponseEntity<String> handle(HttpServletRequest http) throws IOException {
        if (!McpTransportSecurityGuard.allows(http)) {
            return error(HttpStatus.FORBIDDEN, null, JSONRPC_INVALID_REQUEST,
                    "transport origin or host rejected", null);
        }
        if (!properties.enabled() || !properties.authConfigured()) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, null, JSONRPC_INVALID_REQUEST,
                    McpToolError.MCP_DISABLED.name(), null);
        }
        if (!tokenMatches(http)) {
            return error(HttpStatus.UNAUTHORIZED, null, JSONRPC_INVALID_REQUEST,
                    McpToolError.AUTHENTICATION_FAILED.name(), null);
        }
        if (!acceptsJsonRequest(http.getContentType())) {
            return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, null, JSONRPC_INVALID_REQUEST,
                    "application/json content type required", null);
        }
        if (!acceptsJsonAndEventStream(http.getHeader(HttpHeaders.ACCEPT))) {
            return error(HttpStatus.NOT_ACCEPTABLE, null, JSONRPC_INVALID_REQUEST,
                    "Accept must include application/json and text/event-stream", null);
        }

        byte[] bytes = http.getInputStream().readNBytes(properties.effectiveMaxBodyBytes() + 1);
        if (bytes.length > properties.effectiveMaxBodyBytes()) {
            return error(HttpStatus.PAYLOAD_TOO_LARGE, null, JSONRPC_PAYLOAD_TOO_LARGE,
                    McpToolError.PAYLOAD_TOO_LARGE.name(), null);
        }
        String body = decodeUtf8(bytes);
        JsonNode request = body == null ? null : McpJsonRpc.parse(body);
        if (request == null) {
            return error(HttpStatus.BAD_REQUEST, null, JSONRPC_PARSE_ERROR,
                    McpToolError.INVALID_REQUEST.name(), null);
        }

        McpProtocolRequestValidator.Validation validation =
                McpProtocolRequestValidator.validate(http, request);
        if (!validation.valid()) {
            Object data = validation.errorCode() == McpProtocolRequestValidator.UNSUPPORTED_VERSION
                    ? Map.of("supported", McpProtocolVersions.SUPPORTED,
                            "requested", validation.requestedVersion())
                    : null;
            return error(HttpStatus.BAD_REQUEST, McpJsonRpc.id(request), validation.errorCode(),
                    validation.errorMessage(), data);
        }
        if (validation.notification()) {
            return ResponseEntity.accepted().build();
        }
        return dispatch(request, validation.era());
    }

    @GetMapping
    public ResponseEntity<String> getUnsupported() {
        return methodNotAllowed();
    }

    @DeleteMapping
    public ResponseEntity<String> deleteUnsupported() {
        return methodNotAllowed();
    }

    private ResponseEntity<String> dispatch(JsonNode request, McpProtocolEra era) {
        String method = McpJsonRpc.method(request);
        return switch (method) {
            case "initialize" -> ok(request, legacyInitialize());
            case "ping" -> ok(request, eraPayload(era, Map.of()));
            case "server/discover" -> era == McpProtocolEra.MODERN
                    ? ok(request, modernDiscover()) : methodNotFound(request, era);
            case "tools/list" -> ok(request, toolsList(era));
            case "tools/call" -> handleToolCall(request, era);
            default -> methodNotFound(request, era);
        };
    }

    private ResponseEntity<String> methodNotFound(JsonNode request, McpProtocolEra era) {
        HttpStatus status = era == McpProtocolEra.MODERN ? HttpStatus.NOT_FOUND : HttpStatus.OK;
        return error(status, McpJsonRpc.id(request), JSONRPC_METHOD_NOT_FOUND,
                "unsupported mcp method", null);
    }

    private static Map<String, Object> legacyInitialize() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("protocolVersion", McpProtocolVersions.LEGACY);
        info.put("capabilities", McpProtocolVersions.capabilities());
        info.put("serverInfo", McpProtocolVersions.serverInfo());
        return info;
    }

    private static Map<String, Object> modernDiscover() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resultType", "complete");
        result.put("supportedVersions", McpProtocolVersions.SUPPORTED);
        result.put("capabilities", McpProtocolVersions.capabilities());
        result.put("instructions", "Local read-only knowledge tools; canonical writes are unsupported.");
        addCacheContract(result);
        result.put("_meta", responseMeta());
        return result;
    }

    private static Map<String, Object> toolsList(McpProtocolEra era) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools", McpCapabilityManifest.specTools());
        if (era == McpProtocolEra.MODERN) {
            addCacheContract(result);
        }
        return eraPayload(era, result);
    }

    private static void addCacheContract(Map<String, Object> result) {
        // Capability discovery is authorization-context scoped and intentionally not cached.
        result.put("ttlMs", 0);
        result.put("cacheScope", "private");
    }

    private ResponseEntity<String> handleToolCall(JsonNode request, McpProtocolEra era) {
        JsonNode params = McpJsonRpc.params(request);
        JsonNode name = params.get("name");
        String toolName = name != null && name.isTextual() ? name.textValue() : null;
        if (toolName == null || toolName.isBlank()) {
            return error(HttpStatus.BAD_REQUEST, McpJsonRpc.id(request), JSONRPC_INVALID_REQUEST,
                    McpToolError.INVALID_REQUEST.name(), null);
        }
        if (!McpCapabilityManifest.isKnown(toolName)) {
            return ok(request, toolResult(era, true, null, null,
                    McpToolError.UNSUPPORTED_TOOL,
                    "unsupported tool; read-only tools only: "
                            + McpCapabilityManifest.tools().keySet()));
        }
        McpToolResult result = executor.execute(toolName, params.get("arguments"));
        if (result.isError()) {
            return ok(request, toolResult(era, true, null, null, result.errorCode(),
                    result.message() == null ? result.errorCode().name() : result.message()));
        }
        return ok(request, toolResult(era, false,
                result.payload() == null ? Map.of() : result.payload(),
                result.providerEgress(), null, null));
    }

    private static Map<String, Object> toolResult(
            McpProtocolEra era, boolean isError, Object payload, Object providerEgress,
            McpToolError errorCode, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("isError", isError);
        if (isError) {
            Map<String, Object> error = Map.of("code", errorCode.name(), "message", message);
            result.put("error", error);
            if (era == McpProtocolEra.MODERN) {
                result.put("content", List.of(Map.of("type", "text", "text", message)));
                result.put("structuredContent", Map.of("error", error));
            }
        } else {
            result.put("result", payload);
            result.put("providerEgress", providerEgress);
            if (era == McpProtocolEra.MODERN) {
                result.put("content", List.of(Map.of("type", "text", "text",
                        McpJsonRpc.renderValue(payload))));
                result.put("structuredContent", Map.of(
                        "result", payload, "providerEgress", providerEgress));
            }
        }
        return eraPayload(era, result);
    }

    private static Map<String, Object> eraPayload(McpProtocolEra era, Map<String, Object> payload) {
        if (era == McpProtocolEra.LEGACY) {
            return payload;
        }
        Map<String, Object> modern = new LinkedHashMap<>(payload);
        modern.put("resultType", "complete");
        modern.put("_meta", responseMeta());
        return modern;
    }

    private static Map<String, Object> responseMeta() {
        return Map.of("io.modelcontextprotocol/serverInfo", McpProtocolVersions.serverInfo());
    }

    private boolean tokenMatches(HttpServletRequest http) {
        var values = http.getHeaders(HttpHeaders.AUTHORIZATION);
        List<String> authorizations = values == null ? List.of() : Collections.list(values);
        if (authorizations.size() != 1 || !authorizations.getFirst().startsWith("Bearer ")) {
            return false;
        }
        String authorization = authorizations.getFirst();
        String candidate = authorization.substring("Bearer ".length()).strip();
        return java.security.MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.UTF_8),
                properties.authToken().getBytes(StandardCharsets.UTF_8));
    }

    private static boolean acceptsJsonRequest(String value) {
        try {
            return value != null && MediaType.APPLICATION_JSON.isCompatibleWith(
                    MediaType.parseMediaType(value));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean acceptsJsonAndEventStream(String value) {
        if (value == null) {
            return false;
        }
        try {
            List<MediaType> accepted = MediaType.parseMediaTypes(value);
            return accepted.stream().anyMatch(type -> sameType(type, MediaType.APPLICATION_JSON))
                    && accepted.stream().anyMatch(
                            type -> sameType(type, MediaType.TEXT_EVENT_STREAM));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean sameType(MediaType actual, MediaType expected) {
        return actual.getType().equalsIgnoreCase(expected.getType())
                && actual.getSubtype().equalsIgnoreCase(expected.getSubtype());
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            return null;
        }
    }

    private static ResponseEntity<String> ok(JsonNode request, Object payload) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(McpJsonRpc.result(McpJsonRpc.id(request), payload));
    }

    private static ResponseEntity<String> error(
            HttpStatus status, JsonNode id, int code, String message, Object data) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(McpJsonRpc.error(id, code, message, data));
    }

    private static ResponseEntity<String> methodNotAllowed() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .header(HttpHeaders.ALLOW, "POST")
                .contentType(MediaType.APPLICATION_JSON)
                .body(McpJsonRpc.error(null, JSONRPC_INVALID_REQUEST,
                        "only POST is supported"));
    }
}
