package org.km.llmwiki.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/** Stateless protocol-era detector and header/body agreement validator. */
final class McpProtocolRequestValidator {

    static final int HEADER_MISMATCH = -32020;
    static final int UNSUPPORTED_VERSION = -32022;

    private static final Pattern SAFE_HEADER = Pattern.compile("[\\x21-\\x7E]+");
    private static final String META_VERSION = "io.modelcontextprotocol/protocolVersion";
    private static final String META_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";
    private static final String META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo";

    private McpProtocolRequestValidator() {
    }

    static Validation validate(HttpServletRequest http, JsonNode request) {
        if (!request.isObject() || !"2.0".equals(request.path("jsonrpc").asText(null))) {
            return Validation.invalid(-32600, "INVALID_REQUEST", null);
        }
        String method = McpJsonRpc.method(request);
        if (method == null || method.isBlank()) {
            return Validation.invalid(-32600, "INVALID_REQUEST", null);
        }
        if (request.has("params") && !request.path("params").isObject()) {
            return Validation.invalid(-32600, "INVALID_REQUEST", null);
        }
        List<String> versions = headers(http, "MCP-Protocol-Version");
        if (versions.isEmpty()) {
            if (!"initialize".equals(method)) {
                return Validation.invalid(HEADER_MISMATCH,
                        "MCP-Protocol-Version header missing", null);
            }
            return validateLegacyInitialize(http, request, method);
        }
        if (versions.size() != 1 || !plainHeader(versions.getFirst())) {
            return Validation.invalid(HEADER_MISMATCH,
                    "MCP-Protocol-Version header malformed", null);
        }
        String version = versions.getFirst();
        if (McpProtocolVersions.CURRENT.equals(version)) {
            return validateModern(http, request, method);
        }
        if (McpProtocolVersions.LEGACY.equals(version)) {
            return validateLegacyRequest(http, request, method);
        }
        return Validation.invalid(UNSUPPORTED_VERSION, "unsupported protocol version", version);
    }

    private static Validation validateLegacyInitialize(
            HttpServletRequest http, JsonNode request, String method) {
        if (!headers(http, "Mcp-Method").isEmpty() || !headers(http, "Mcp-Name").isEmpty()) {
            return Validation.invalid(HEADER_MISMATCH, "protocol-era header mismatch", null);
        }
        if (!"initialize".equals(method) || !validRequestId(request)) {
            return Validation.invalid(UNSUPPORTED_VERSION, "missing protocol version", "missing");
        }
        String requested = McpJsonRpc.params(request).path("protocolVersion").asText(null);
        String negotiated = McpProtocolVersions.negotiateLegacy(requested);
        if (negotiated == null) {
            return Validation.invalid(UNSUPPORTED_VERSION, "missing protocol version", "missing");
        }
        return Validation.validLegacyInitialize(negotiated);
    }

    private static Validation validateLegacyRequest(
            HttpServletRequest http, JsonNode request, String method) {
        if (!headers(http, "Mcp-Method").isEmpty() || !headers(http, "Mcp-Name").isEmpty()
                || "initialize".equals(method)) {
            return Validation.invalid(HEADER_MISMATCH, "protocol-era header mismatch", null);
        }
        boolean notification = "notifications/initialized".equals(method)
                && McpJsonRpc.classifyRequestId(request).type()
                        == McpJsonRpc.RequestIdType.ABSENT;
        if (!notification && !validRequestId(request)) {
            return Validation.invalid(-32600, "INVALID_REQUEST", null);
        }
        return Validation.valid(McpProtocolEra.LEGACY, notification);
    }

    private static Validation validateModern(
            HttpServletRequest http, JsonNode request, String method) {
        if ("initialize".equals(method) || !validRequestId(request)) {
            return Validation.invalid(HEADER_MISMATCH, "modern requests are stateless", null);
        }
        List<String> methods = headers(http, "Mcp-Method");
        if (methods.size() != 1 || !method.equals(decodePlainHeader(methods.getFirst()))) {
            return Validation.invalid(HEADER_MISMATCH, "Mcp-Method header mismatch", null);
        }
        JsonNode params = request.get("params");
        JsonNode meta = params == null ? null : params.get("_meta");
        if (params == null || !params.isObject() || meta == null || !meta.isObject()
                || !McpProtocolVersions.CURRENT.equals(meta.path(META_VERSION).asText(null))
                || !meta.path(META_CAPABILITIES).isObject()
                || !validOptionalImplementation(meta.get(META_CLIENT_INFO))) {
            return Validation.invalid(HEADER_MISMATCH, "modern request metadata mismatch", null);
        }
        List<String> names = headers(http, "Mcp-Name");
        if ("tools/call".equals(method)) {
            JsonNode name = params.get("name");
            String bodyName = name != null && name.isTextual() ? name.textValue() : null;
            if (bodyName == null || bodyName.isBlank() || names.size() != 1
                    || !bodyName.equals(decodeNameHeader(names.getFirst()))) {
                return Validation.invalid(HEADER_MISMATCH, "Mcp-Name header mismatch", null);
            }
        } else if (!names.isEmpty()) {
            return Validation.invalid(HEADER_MISMATCH, "unexpected Mcp-Name header", null);
        }
        return Validation.valid(McpProtocolEra.MODERN, false);
    }

    private static boolean validOptionalImplementation(JsonNode implementation) {
        if (implementation == null) {
            return true;
        }
        return implementation.isObject()
                && implementation.path("name").isTextual()
                && !implementation.path("name").asText().isBlank()
                && implementation.path("version").isTextual()
                && !implementation.path("version").asText().isBlank();
    }

    /**
     * Request-id legality delegates to the shared {@link McpJsonRpc#classifyRequestId}
     * grammar (#350/#358): the supported MCP era schemas define {@code RequestId =
     * string | number}, so every JSON number (integral or fractional, parsed exactly)
     * is valid — boolean, object, array, and explicit-null ids are invalid requests,
     * and the same classifier keeps the transport error-echo path from ever reflecting
     * them.
     */
    private static boolean validRequestId(JsonNode request) {
        return McpJsonRpc.classifyRequestId(request).type() == McpJsonRpc.RequestIdType.VALID;
    }

    private static String decodePlainHeader(String value) {
        return plainHeader(value) ? value : null;
    }

    private static String decodeNameHeader(String value) {
        if (!plainHeader(value)) {
            return null;
        }
        if (value.startsWith("=?base64?") && value.endsWith("?=")) {
            try {
                byte[] decoded = Base64.getDecoder().decode(value.substring(9, value.length() - 2));
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(decoded)).toString();
            } catch (IllegalArgumentException | CharacterCodingException invalid) {
                return null;
            }
        }
        return value;
    }

    private static boolean plainHeader(String value) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && SAFE_HEADER.matcher(value).matches();
    }

    private static List<String> headers(HttpServletRequest request, String name) {
        var values = request.getHeaders(name);
        return values == null ? List.of() : Collections.list(values);
    }

    record Validation(boolean valid, McpProtocolEra era, boolean notification,
                      int errorCode, String errorMessage, String requestedVersion,
                      String negotiatedVersion) {
        static Validation valid(McpProtocolEra era, boolean notification) {
            return new Validation(true, era, notification, 0, null, null, null);
        }

        static Validation validLegacyInitialize(String negotiatedVersion) {
            return new Validation(true, McpProtocolEra.LEGACY, false, 0, null, null,
                    negotiatedVersion);
        }

        static Validation invalid(int errorCode, String errorMessage, String requestedVersion) {
            return new Validation(false, null, false, errorCode, errorMessage, requestedVersion,
                    null);
        }
    }
}
