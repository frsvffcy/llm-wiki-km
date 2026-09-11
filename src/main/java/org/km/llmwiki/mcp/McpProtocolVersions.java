package org.km.llmwiki.mcp;

import java.util.List;
import java.util.Map;

/** Single authority for the MCP revisions intentionally served by the local adapter. */
public final class McpProtocolVersions {

    public static final String CURRENT = "2026-07-28";
    public static final String LEGACY = "2025-06-18";

    /**
     * Modern-era revisions advertised by {@code server/discover}. Modern discovery never
     * carries legacy revisions; legacy versions keep flowing through {@code initialize}
     * negotiation instead.
     */
    public static final List<String> MODERN_SUPPORTED = List.of(CURRENT);

    /**
     * Bounded legacy revisions the server speaks. The list stays explicit so that adding a
     * future revision forces a deliberate per-era decision instead of silently exposing it
     * on every wire surface.
     */
    public static final List<String> LEGACY_SUPPORTED = List.of(LEGACY);

    /**
     * Every served revision, for internal error diagnostics only. It must never drive an
     * era contract: discovery reads {@link #MODERN_SUPPORTED} and initialize negotiation
     * reads {@link #LEGACY_SUPPORTED}.
     */
    public static final List<String> ALL_SUPPORTED = List.of(CURRENT, LEGACY);

    private McpProtocolVersions() {
    }

    /**
     * Legacy {@code initialize} negotiation (MCP 2025-11-25 lifecycle): echo the requested
     * revision when the server speaks it; otherwise counter-offer the newest
     * server-supported legacy revision and let the client accept or disconnect. A missing or
     * blank value cannot be negotiated and returns {@code null} so the caller fails closed
     * with a typed rejection.
     */
    public static String negotiateLegacy(String requested) {
        if (requested == null || requested.isBlank()) {
            return null;
        }
        if (LEGACY_SUPPORTED.contains(requested)) {
            return requested;
        }
        return LEGACY_SUPPORTED.getLast();
    }

    public static Map<String, Object> serverInfo() {
        return Map.of("name", "llm-wiki-km", "version", "read-only-2");
    }

    public static Map<String, Object> capabilities() {
        return Map.of("tools", Map.of("listChanged", false));
    }
}
