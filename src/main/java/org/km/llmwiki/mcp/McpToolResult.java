package org.km.llmwiki.mcp;

import java.util.List;

/**
 * One safe, application-owned MCP tool result. Payloads are the existing application DTOs
 * (already safe projections); errors carry a typed code with an operator-safe message and
 * never contain credentials, raw endpoints, paths, RIDs, provider bodies, or raw exceptions.
 */
public record McpToolResult(
        boolean isError,
        McpToolError errorCode,
        String message,
        Object payload,
        List<ProviderEgressLine> providerEgress
) {
    public McpToolResult {
        providerEgress = List.copyOf(providerEgress == null ? List.of() : providerEgress);
    }

    /** One configuration-vs-execution egress line (labels only, no provider details). */
    public record ProviderEgressLine(
            String purpose,
            String destinationClass,
            String level
    ) {
    }

    public static McpToolResult success(Object payload,
                                        List<ProviderEgressLine> providerEgress) {
        return new McpToolResult(false, null, null, payload, providerEgress);
    }

    public static McpToolResult failure(McpToolError error, String safeMessage) {
        String bounded = safeMessage == null || safeMessage.isBlank() ? error.name()
                : (safeMessage.length() <= 256 ? safeMessage : safeMessage.substring(0, 256));
        return new McpToolResult(true, error, bounded, null, List.of());
    }
}
