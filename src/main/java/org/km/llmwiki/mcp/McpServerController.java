package org.km.llmwiki.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Loopback-only MCP server boundary (MCP Streamable HTTP subset: single JSON objects,
 * JSON-RPC 2.0). Security contract:
 *
 * <ul>
 *   <li>the whole HTTP server already binds 127.0.0.1 only (application.yml); there is no
 *       remote-bind configuration path for this adapter;</li>
 *   <li>backend-only bearer authentication — the token lives in the backend configuration
 *       only and is compared with a constant-time comparison; a disabled or unconfigured
 *       adapter answers {@code MCP_DISABLED} deterministically;</li>
 *   <li>hard request-body bound (256 KiB default) enforced on the decoded body, not just
 *       Content-Length, so chunked bodies cannot bypass it;</li>
 *   <li>read-only tool surface only; write capabilities answer typed unsupported.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/mcp")
public class McpServerController {

    private static final int JSONRPC_PARSE_ERROR = -32700;
    private static final int JSONRPC_INVALID_REQUEST = -32600;
    private static final int JSONRPC_METHOD_NOT_FOUND = -32601;
    /** Application-owned code: the envelope parsed, but the body exceeded the hard bound. */
    private static final int JSONRPC_PAYLOAD_TOO_LARGE = -32001;

    private final McpProperties properties;
    private final McpToolExecutor executor;

    @Autowired
    public McpServerController(McpProperties properties,
                               @Lazy McpToolExecutor executor) {
        this.properties = properties;
        this.executor = executor;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> handle(
            @org.springframework.web.bind.annotation.RequestHeader(
                    value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) String body) {
        // Fail-closed authentication: the adapter requires an explicitly configured
        // backend-only token (enabled=true + non-blank token). The token never appears in
        // any response, log, or capability resource.
        if (!properties.enabled() || !properties.authConfigured()) {
            return respond(null, McpToolError.MCP_DISABLED);
        }
        if (!tokenMatches(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(McpJsonRpc.error(null, JSONRPC_INVALID_REQUEST,
                            McpToolError.AUTHENTICATION_FAILED.name()));
        }
        String bodyOrEmpty = body == null ? "" : body;
        byte[] bytes = bodyOrEmpty.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > properties.maxBodyBytes()) {
            return respond(null, JSONRPC_PAYLOAD_TOO_LARGE, McpToolError.PAYLOAD_TOO_LARGE);
        }
        JsonNode request = McpJsonRpc.parse(bodyOrEmpty);
        if (request == null || !request.isObject()) {
            return respond(null, JSONRPC_PARSE_ERROR, McpToolError.INVALID_REQUEST);
        }
        String method = McpJsonRpc.method(request);
        if (method == null) {
            return respond(McpJsonRpc.id(request), JSONRPC_INVALID_REQUEST,
                    McpToolError.INVALID_REQUEST);
        }
        return switch (method) {
            case "initialize" -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .body(McpJsonRpc.result(McpJsonRpc.id(request), initializeInfo()));
            case "ping" -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .body(McpJsonRpc.result(McpJsonRpc.id(request), Map.of()));
            case "tools/list" -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .body(McpJsonRpc.result(McpJsonRpc.id(request),
                            Map.of("tools", McpCapabilityManifest.specTools())));
            case "tools/call" -> handleToolCall(request);
            default -> ResponseEntity.status(HttpStatus.OK)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(McpJsonRpc.error(McpJsonRpc.id(request), JSONRPC_METHOD_NOT_FOUND,
                            "unsupported mcp method"));
        };
    }

    private static Map<String, Object> initializeInfo() {
        Map<String, Object> serverInfo = new java.util.LinkedHashMap<>();
        serverInfo.put("name", "llm-wiki-km");
        serverInfo.put("version", "read-only-1");
        Map<String, Object> capabilities = new java.util.LinkedHashMap<>();
        capabilities.put("tools", Map.of("listChanged", false));
        Map<String, Object> info = new java.util.LinkedHashMap<>();
        info.put("protocolVersion", "2025-06-18");
        info.put("capabilities", capabilities);
        info.put("serverInfo", serverInfo);
        return info;
    }

    private ResponseEntity<String> handleToolCall(JsonNode request) {
        JsonNode params = McpJsonRpc.params(request);
        String toolName = params.hasNonNull("name") ? params.get("name").asText() : null;
        if (toolName == null || toolName.isBlank()) {
            return respond(McpJsonRpc.id(request), JSONRPC_INVALID_REQUEST,
                    McpToolError.INVALID_REQUEST);
        }
        if (!McpCapabilityManifest.isKnown(toolName)) {
            // Deterministic typed unsupported: JSON-RPC result envelope with isError so MCP
            // clients surface it as a tool-level failure, never a transport crash.
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .body(McpJsonRpc.result(McpJsonRpc.id(request), Map.of(
                            "isError", true,
                            "error", Map.of("code", McpToolError.UNSUPPORTED_TOOL.name(),
                                    "message", "unsupported tool; read-only tools only: "
                                            + McpCapabilityManifest.tools().keySet()))));
        }
        McpToolResult result = executor.execute(toolName, params.get("arguments"));
        if (result.isError()) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .body(McpJsonRpc.result(McpJsonRpc.id(request), Map.of(
                            "isError", true,
                            "error", Map.of("code", result.errorCode().name(),
                                    "message", result.message() == null
                                            ? result.errorCode().name() : result.message()))));
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(McpJsonRpc.result(McpJsonRpc.id(request), Map.of(
                        "isError", false,
                        "result", result.payload() == null ? Map.of() : result.payload(),
                        "providerEgress", result.providerEgress())));
    }

    private boolean tokenMatches(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return false;
        }
        String candidate = authorization.substring("Bearer ".length()).strip();
        return java.security.MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.UTF_8),
                properties.authToken().getBytes(StandardCharsets.UTF_8));
    }

    private ResponseEntity<String> respond(JsonNode id, McpToolError error) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(McpJsonRpc.error(id, JSONRPC_INVALID_REQUEST,
                        error == McpToolError.PAYLOAD_TOO_LARGE
                                ? "request body exceeds the hard bound" : error.name()));
    }

    private ResponseEntity<String> respond(JsonNode id, int jsonRpcCode, McpToolError error) {
        HttpStatus status = jsonRpcCode == JSONRPC_PARSE_ERROR
                || jsonRpcCode == JSONRPC_PAYLOAD_TOO_LARGE
                || error == McpToolError.PAYLOAD_TOO_LARGE
                || error == McpToolError.INVALID_REQUEST || error == McpToolError.MCP_DISABLED
                || error == McpToolError.UNSUPPORTED_TOOL
                ? HttpStatus.BAD_REQUEST : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(McpJsonRpc.error(id, jsonRpcCode, error.name()));
    }
}
