package org.km.llmwiki.mcp;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Transport-level contract for the read-only local MCP adapter (#330): fail-closed disabled
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
        // The disabled gate still decides first (nothing is dispatched); the error
        // envelope best-effort echoes the request id when the body happens to parse (#345),
        // through the shared classifier — illegal id types collapse to null (#350).
        mockMvc.perform(post("/api/mcp").header("Host", "localhost")
                        .header("Accept", "application/json, text/event-stream")
                        .contentType("application/json")
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("MCP_DISABLED", "\"id\":1")
                        .doesNotContain("Bearer", "secret"));
        mockMvc.perform(post("/api/mcp").header("Host", "localhost")
                        .header("Accept", "application/json, text/event-stream")
                        .contentType("application/json")
                        .content("{\"jsonrpc\":\"2.0\",\"id\":{\"secret\":\"x\"},"
                                + "\"method\":\"tools/list\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("MCP_DISABLED", "\"id\":null")
                        .doesNotContain("secret"));
    }

    @Test
    void malformedJsonAlsoFailsClosedWhileDisabled() throws Exception {
        // The disabled gate is the FIRST check: a disabled adapter never dispatches or
        // processes any body, so malformed/oversized content deterministically answers
        // MCP_DISABLED too. The envelope still tries a bounded id echo; a body that
        // cannot parse keeps id:null (#345).
        mockMvc.perform(post("/api/mcp").header("Host", "127.0.0.1:8765")
                        .header("Accept", "application/json, text/event-stream")
                        .contentType("application/json").content("not-json"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("MCP_DISABLED", "\"id\":null"));
    }

    @Test
    void invalidOriginIsRejectedBeforeTheDisabledGate() throws Exception {
        mockMvc.perform(post("/api/mcp").header("Host", "localhost")
                        .header("Origin", "https://evil.example")
                        .contentType("application/json").content("not-json"))
                .andExpect(status().isForbidden())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("\"id\":null")
                        .doesNotContain("MCP_DISABLED", "INVALID_REQUEST"));
    }
}
