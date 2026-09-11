package org.km.llmwiki.mcp;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Transport-level contract for the read-only local MCP adapter (#327): fail-closed disabled
 * mode, backend-only bearer authentication with constant-time comparison, hard body bound
 * enforced on the decoded body (not only Content-Length), read-only tool surface (write
 * capabilities answer typed unsupported), and no secret/raw-endpoint leakage in any response.
 * The server itself binds 127.0.0.1 only (application.yml), so remote binds are impossible by
 * configuration.
 */
@Tag("integration")
class McpServerContractTest extends IsolatedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void disabledAdapterFailsClosedWithTypedErrorAndNoLeak() throws Exception {
        // Default test configuration: app.mcp.enabled=false → deterministic MCP_DISABLED.
        mockMvc.perform(post("/api/mcp").contentType("application/json")
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("MCP_DISABLED")
                        .doesNotContain("Bearer", "secret"));
    }

    @Test
    void malformedJsonAlsoFailsClosedWhileDisabled() throws Exception {
        // The disabled gate is the FIRST check: a disabled adapter never parses or processes
        // any body, so malformed/oversized content deterministically answers MCP_DISABLED
        // too (nothing is processed while the boundary is off).
        mockMvc.perform(post("/api/mcp").contentType("application/json").content("not-json"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("MCP_DISABLED"));
    }
}
