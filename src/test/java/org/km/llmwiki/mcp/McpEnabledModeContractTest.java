package org.km.llmwiki.mcp;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Enabled-mode MCP contract tests (#327): with an explicitly configured backend-only token the
 * adapter answers initialize/ping/tools/list/tools/call; the token is compared constant-time
 * and never leaks; oversized bodies are rejected on the decoded body; malformed JSON is a
 * typed parse error; write tools answer typed unsupported.
 */
@Tag("integration")
class McpEnabledModeContractTest extends IsolatedIntegrationTest {

    private static final String TOKEN = "test-mcp-bearer-token-327";

    @DynamicPropertySource
    static void mcpProperties(DynamicPropertyRegistry registry) {
        registry.add("app.mcp.enabled", () -> "true");
        registry.add("app.mcp.auth-token", () -> TOKEN);
    }

    @Autowired
    private MockMvc mockMvc;

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder mcpPost(
            String body, String bearerToken) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/mcp").contentType("application/json").content(body)
                .header("Authorization", "Bearer " + bearerToken);
    }

    @Test
    void initializeAnswersProtocolVersionWithoutSecrets() throws Exception {
        mockMvc.perform(mcpPost("""
                {"jsonrpc":"2.0","id":1,"method":"initialize"}""", TOKEN))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assertThat(body).contains("2025-06-18").contains("llm-wiki-km")
                            .contains("tools");
                    assertThat(body).doesNotContain(TOKEN);
                });
    }

    @Test
    void wrongTokenIsUnauthorizedWithoutEchoingTheToken() throws Exception {
        mockMvc.perform(mcpPost("""
                {"jsonrpc":"2.0","id":1,"method":"ping"}""", "wrong-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("wrong-token", TOKEN));
    }

    @Test
    void toolsListExposesReadOnlyManifestOnly() throws Exception {
        mockMvc.perform(mcpPost("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}""", TOKEN))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assertThat(body).contains("km_status").contains("km_search")
                            .contains("km_ask").contains("km_retrieval_inspect")
                            .contains("km_source_locator");
                    // No write capability exists anywhere in the manifest.
                    assertThat(body).doesNotContain("publish", "rebuild", "repair",
                            "upload", "proposal", "backup");
                    assertThat(body).doesNotContain(TOKEN);
                });
    }

    @Test
    void oversizedBodyIsRejectedOnTheDecodedBody() throws Exception {
        String oversized = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"padding\":\""
                + "x".repeat(300_000) + "\"}";
        mockMvc.perform(mcpPost(oversized, TOKEN)).andExpect(status().isBadRequest());
    }

    @Test
    void malformedJsonIsADeterministicParseError() throws Exception {
        mockMvc.perform(mcpPost("not-json", TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("INVALID_REQUEST"));
    }

    @Test
    void toolResultsStayInsideTheJsonRpcEnvelopeWithoutLeaking() throws Exception {
        // Tool calls run through the existing application contracts: status answers bounded
        // safe fields (NOT_INITIALIZED + database READY) inside the JSON-RPC result envelope
        // (never a transport crash, never a REST error envelope, never an internal
        // exception or a filesystem path). Ask with no active workspace fails with a typed
        // INVALID_REQUEST and carries no egress claim at all.
        mockMvc.perform(mcpPost("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call",
                "params":{"name":"km_status","arguments":{}}}""", TOKEN))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assertThat(body).contains("NOT_INITIALIZED").contains("isError");
                    assertThat(body).doesNotContain("/Users/", "RID:", TOKEN,
                            "IllegalStateException", "Exception");
                });
        mockMvc.perform(mcpPost("""
                {"jsonrpc":"2.0","id":5,"method":"tools/call",
                "params":{"name":"km_ask","arguments":{"question":"unknown topic test",
                "retrievalMode":"WIKI_ONLY"}}}""", TOKEN))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    // The ask without an active workspace fails typed inside the envelope
                    // and carries no egress claim at all (no CONFIGURATION or EXECUTION
                    // lines — those are for successful asks).
                    assertThat(body).contains("INVALID_REQUEST");
                    assertThat(body).doesNotContain("https://", "apiKey", "Bearer",
                            "CONFIGURATION", "EXECUTION");
                });
    }

    @Test
    void unknownToolIsATypedUnsupportedError() throws Exception {
        mockMvc.perform(mcpPost("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"km_publish",
                "arguments":{}}}""", TOKEN))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("UNSUPPORTED_TOOL"));
    }
}
