package org.km.llmwiki.mcp;

import java.util.List;
import java.util.Map;

/** Single authority for the MCP revisions intentionally served by the local adapter. */
public final class McpProtocolVersions {

    public static final String CURRENT = "2026-07-28";
    public static final String LEGACY = "2025-06-18";
    public static final List<String> SUPPORTED = List.of(CURRENT, LEGACY);

    private McpProtocolVersions() {
    }

    public static Map<String, Object> serverInfo() {
        return Map.of("name", "llm-wiki-km", "version", "read-only-2");
    }

    public static Map<String, Object> capabilities() {
        return Map.of("tools", Map.of("listChanged", false));
    }
}
