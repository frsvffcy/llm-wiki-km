package org.km.llmwiki.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Backend-only configuration for the local MCP adapter. The auth token is backend/runtime-owned:
 * it is never persisted in SQLite/vault, never sent to the Browser, never logged, and never
 * appears in any public diagnostic or capability resource.
 */
@ConfigurationProperties("app.mcp")
public record McpProperties(boolean enabled, String authToken, int maxBodyBytes) {

    /** Default hard bound for a single MCP request body (256 KiB). */
    public static final int DEFAULT_MAX_BODY_BYTES = 262_144;

    public McpProperties {
        if (maxBodyBytes <= 0) {
            maxBodyBytes = DEFAULT_MAX_BODY_BYTES;
        }
    }

    /** Effective bound actually enforced by the adapter (fail-fast on negative values). */
    public int effectiveMaxBodyBytes() {
        return maxBodyBytes;
    }

    public boolean authConfigured() {
        return authToken != null && !authToken.isBlank();
    }
}
