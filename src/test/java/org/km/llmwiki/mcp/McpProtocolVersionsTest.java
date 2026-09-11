package org.km.llmwiki.mcp;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit contract for the MCP version authority partition (#334): modern discovery and legacy
 * initialize negotiation read disjoint, explicit revision sets, so adding a future revision
 * forces a deliberate per-era decision instead of silently exposing it on every wire surface.
 */
@Tag("unit")
class McpProtocolVersionsTest {

    @Test
    void eraAuthoritiesAreDisjointAndCoverExactlyTheServedRevisions() {
        assertThat(McpProtocolVersions.MODERN_SUPPORTED).containsExactly("2026-07-28");
        assertThat(McpProtocolVersions.LEGACY_SUPPORTED).containsExactly("2025-06-18");
        assertThat(McpProtocolVersions.ALL_SUPPORTED)
                .containsExactly("2026-07-28", "2025-06-18");
        assertThat(McpProtocolVersions.MODERN_SUPPORTED)
                .doesNotContainAnyElementsOf(McpProtocolVersions.LEGACY_SUPPORTED);
    }

    @Test
    void legacyNegotiationEchoesSupportedRevisionsAndCounterOffersTheNewestSupported() {
        assertThat(McpProtocolVersions.negotiateLegacy("2025-06-18")).isEqualTo("2025-06-18");
        assertThat(McpProtocolVersions.negotiateLegacy("2025-11-25")).isEqualTo("2025-06-18");
        assertThat(McpProtocolVersions.negotiateLegacy("2024-01-01")).isEqualTo("2025-06-18");
    }

    @Test
    void legacyNegotiationFailsClosedWithoutAVersion() {
        assertThat(McpProtocolVersions.negotiateLegacy(null)).isNull();
        assertThat(McpProtocolVersions.negotiateLegacy("")).isNull();
        assertThat(McpProtocolVersions.negotiateLegacy("   ")).isNull();
    }

    @Test
    void perEraMethodAvailabilityIsExplicit() {
        assertThat(McpServerController.methodSupported(McpProtocolEra.LEGACY, "initialize"))
                .isTrue();
        assertThat(McpServerController.methodSupported(McpProtocolEra.LEGACY, "ping")).isTrue();
        assertThat(McpServerController.methodSupported(McpProtocolEra.LEGACY, "tools/list"))
                .isTrue();
        assertThat(McpServerController.methodSupported(McpProtocolEra.LEGACY, "tools/call"))
                .isTrue();
        assertThat(McpServerController.methodSupported(McpProtocolEra.LEGACY, "server/discover"))
                .isFalse();
        assertThat(McpServerController.methodSupported(McpProtocolEra.MODERN, "server/discover"))
                .isTrue();
        assertThat(McpServerController.methodSupported(McpProtocolEra.MODERN, "tools/list"))
                .isTrue();
        assertThat(McpServerController.methodSupported(McpProtocolEra.MODERN, "tools/call"))
                .isTrue();
        assertThat(McpServerController.methodSupported(McpProtocolEra.MODERN, "ping")).isFalse();
        assertThat(McpServerController.methodSupported(McpProtocolEra.MODERN, "initialize"))
                .isFalse();
    }
}
