package org.km.llmwiki.mcp;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Versioned modern/legacy wire, transport-security, and read-only regressions for #330. */
@Tag("integration")
class McpEnabledModeContractTest extends IsolatedIntegrationTest {

    private static final String TOKEN = "test-mcp-bearer-token-330";
    private static final String ACCEPT = "application/json, text/event-stream";
    private static final String META = """
            "_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28",
            "io.modelcontextprotocol/clientCapabilities":{}}""";

    @DynamicPropertySource
    static void mcpProperties(DynamicPropertyRegistry registry) {
        registry.add("app.mcp.enabled", () -> "true");
        registry.add("app.mcp.auth-token", () -> TOKEN);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void modernToolsListIsStatelessAndExposesExactlyFiveReadOnlyTools() throws Exception {
        mockMvc.perform(modern("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{%s}}"""
                .formatted(META), "tools/list", null))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assertThat(body).contains("resultType", "complete", "\"ttlMs\":0",
                                    "\"cacheScope\":\"private\"",
                                    "io.modelcontextprotocol/serverInfo",
                                    "km_status", "km_search", "km_retrieval_inspect",
                                    "km_source_locator", "km_ask")
                            .doesNotContain("publish", "rebuild", "repair", "upload",
                                    "proposal", "backup", TOKEN);
                    assertThat(occurrences(body, "\"name\":\"km_")).isEqualTo(5);
                });

        // The same request succeeds again without initialize or any hidden session state.
        mockMvc.perform(modern("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{%s}}"""
                .formatted(META), "tools/list", null))
                .andExpect(status().isOk());
    }

    @Test
    void modernToolCallRequiresMatchingMethodNameAndMetadata() throws Exception {
        mockMvc.perform(modern("""
                {"jsonrpc":"2.0","id":"call-1","method":"tools/call",
                "params":{"name":"km_status","arguments":{},%s}}"""
                .formatted(META), "tools/call", "km_status"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assertThat(body).contains("NOT_INITIALIZED", "structuredContent",
                                    "content", "resultType")
                            .doesNotContain("/Users/", "RID:", TOKEN,
                                    "IllegalStateException", "Exception");
                });
    }

    @Test
    void modernMissingOrUnsupportedVersionFailsBeforeDispatch() throws Exception {
        String body = """
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{%s}}"""
                .formatted(META);
        mockMvc.perform(base(body).header("Mcp-Method", "tools/list"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("-32020", "MCP-Protocol-Version header missing")
                        .doesNotContain("km_status"));
        mockMvc.perform(base(body).header("MCP-Protocol-Version", "2099-01-01")
                        .header("Mcp-Method", "tools/list"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("-32022", "2099-01-01")
                        .doesNotContain("km_status"));
        mockMvc.perform(base(body)
                        .header("MCP-Protocol-Version", "2026-07-28", "2025-06-18")
                        .header("Mcp-Method", "tools/list"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("-32020", "MCP-Protocol-Version header malformed")
                        .doesNotContain("km_status"));
    }

    @Test
    void modernMethodHeaderIsRequiredAndMustMatchBody() throws Exception {
        String body = """
                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                "params":{"name":"km_status","arguments":{},%s}}""".formatted(META);
        mockMvc.perform(base(body).header("MCP-Protocol-Version", "2026-07-28")
                        .header("Mcp-Name", "km_status"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(modern(body, "tools/list", "km_status"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("-32020").doesNotContain("NOT_INITIALIZED"));
    }

    @Test
    void modernNameHeaderIsRequiredAndMustMatchBody() throws Exception {
        String body = """
                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                "params":{"name":"km_ask","arguments":{"question":"x"},%s}}"""
                .formatted(META);
        mockMvc.perform(modern(body, "tools/call", null))
                .andExpect(status().isBadRequest());
        mockMvc.perform(modern(body, "tools/call", "km_search"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("-32020").doesNotContain("CONFIGURATION", "EXECUTION"));
    }

    @Test
    void modernBase64NameHeaderIsDecodedBeforeAgreementCheck() throws Exception {
        String encoded = "=?base64?" + Base64.getEncoder().encodeToString(
                "km_status".getBytes(StandardCharsets.UTF_8)) + "?=";
        mockMvc.perform(modern("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                "params":{"name":"km_status","arguments":{},%s}}"""
                .formatted(META), "tools/call", encoded))
                .andExpect(status().isOk());

        String unicodeName = "知識查詢";
        String unicodeEncoded = "=?base64?" + Base64.getEncoder().encodeToString(
                unicodeName.getBytes(StandardCharsets.UTF_8)) + "?=";
        mockMvc.perform(modern("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call",
                "params":{"name":"%s","arguments":{},%s}}"""
                .formatted(unicodeName, META), "tools/call", unicodeEncoded))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("UNSUPPORTED_TOOL").doesNotContain("-32020"));

        String encodedMethod = "=?base64?" + Base64.getEncoder().encodeToString(
                "tools/list".getBytes(StandardCharsets.UTF_8)) + "?=";
        mockMvc.perform(modern("""
                {"jsonrpc":"2.0","id":3,"method":"tools/list","params":{%s}}"""
                .formatted(META), encodedMethod, null))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("-32020"));
    }

    @Test
    void modernMetadataMustBeCompleteAndAgreeWithHeader() throws Exception {
        mockMvc.perform(modern("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{}}}""",
                "tools/list", null)).andExpect(status().isBadRequest());
        mockMvc.perform(modern("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{
                "io.modelcontextprotocol/protocolVersion":"2025-06-18",
                "io.modelcontextprotocol/clientCapabilities":{}}}}""", "tools/list", null))
                .andExpect(status().isBadRequest());
        mockMvc.perform(modern("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{
                "io.modelcontextprotocol/protocolVersion":"2026-07-28",
                "io.modelcontextprotocol/clientCapabilities":{},
                "io.modelcontextprotocol/clientInfo":{}}}}""", "tools/list", null))
                .andExpect(status().isBadRequest());
    }

    @Test
    void jsonRpcMethodNameAndParamsKeepTheirDeclaredTypes() throws Exception {
        mockMvc.perform(modern("""
                {"jsonrpc":"2.0","id":1,"method":123,"params":{%s}}"""
                .formatted(META), "123", null))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("-32600"));
        mockMvc.perform(modern("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call",
                "params":{"name":123,"arguments":{},%s}}"""
                .formatted(META), "tools/call", "123"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("-32020"));
        mockMvc.perform(legacy("""
                {"jsonrpc":"2.0","id":3,"method":"ping","params":"invalid"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("-32600"));
    }

    @Test
    void modernDiscoveryAndUnknownMethodHaveCurrentSemantics() throws Exception {
        mockMvc.perform(modern("""
                {"jsonrpc":"2.0","id":1,"method":"server/discover","params":{%s}}"""
                .formatted(META), "server/discover", null))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("supportedVersions", "2026-07-28",
                                "io.modelcontextprotocol/serverInfo", "\"ttlMs\":0",
                                "\"cacheScope\":\"private\"")
                        .doesNotContain("2025-06-18"));
        mockMvc.perform(modern("""
                {"jsonrpc":"2.0","id":2,"method":"unknown/read","params":{%s}}"""
                .formatted(META), "unknown/read", null))
                .andExpect(status().isNotFound())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("-32601"));
    }

    @Test
    void legacyInitializeNegotiatesOnlyTheBoundedSupportedRevision() throws Exception {
        mockMvc.perform(base("""
                {"jsonrpc":"2.0","id":1,"method":"initialize",
                "params":{"protocolVersion":"2025-06-18","capabilities":{},
                "clientInfo":{"name":"test","version":"1"}}}"""))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("2025-06-18", "serverInfo").doesNotContain(TOKEN));
    }

    @Test
    void legacyInitializeCounterOffersNewerLegacyRevisions() throws Exception {
        // MCP 2025-11-25 lifecycle: the server answers an unsupported-but-wellformed legacy
        // revision with a version it speaks instead of erroring; the client decides.
        for (String offered : new String[]{"2025-11-25", "2024-01-01"}) {
            mockMvc.perform(base("""
                    {"jsonrpc":"2.0","id":2,"method":"initialize",
                    "params":{"protocolVersion":"%s"}}""".formatted(offered)))
                    .andExpect(status().isOk())
                    .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                            .contains("\"protocolVersion\":\"2025-06-18\"")
                            .doesNotContain("-32022", TOKEN));
        }
    }

    @Test
    void legacyInitializeWithoutAVersionFailsClosed() throws Exception {
        mockMvc.perform(base("""
                {"jsonrpc":"2.0","id":3,"method":"initialize","params":{}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("-32022", "missing"));
        mockMvc.perform(base("""
                {"jsonrpc":"2.0","id":4,"method":"initialize",
                "params":{"protocolVersion":""}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("-32022"));
    }

    @Test
    void perEraMethodAvailabilityIsExplicit() throws Exception {
        // Legacy ping keeps its existing legal semantics.
        mockMvc.perform(legacy("""
                {"jsonrpc":"2.0","id":10,"method":"ping","params":{}}"""))
                .andExpect(status().isOk());
        // Modern ping is undefined in the 2026 era and must be rejected, not silently run.
        mockMvc.perform(modern("""
                {"jsonrpc":"2.0","id":11,"method":"ping","params":{%s}}"""
                .formatted(META), "ping", null))
                .andExpect(status().isNotFound())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("-32601"));
        // Modern initialize is rejected (modern era has no initialize handshake).
        mockMvc.perform(modern("""
                {"jsonrpc":"2.0","id":12,"method":"initialize","params":{%s}}"""
                .formatted(META), "initialize", null))
                .andExpect(status().isBadRequest());
        // Legacy server/discover is rejected (discovery belongs to the modern era).
        mockMvc.perform(legacy("""
                {"jsonrpc":"2.0","id":13,"method":"server/discover","params":{}}"""))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("-32601"));
    }

    @Test
    void legacyFollowupAndNotificationStayInTheirOwnEra() throws Exception {
        mockMvc.perform(legacy("""
                {"jsonrpc":"2.0","id":3,"method":"tools/list","params":{}}"""))
                .andExpect(status().isOk());
        mockMvc.perform(legacy("""
                {"jsonrpc":"2.0","method":"notifications/initialized","params":{}}"""))
                .andExpect(status().isAccepted())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEmpty());
        mockMvc.perform(legacy("""
                {"jsonrpc":"2.0","id":4,"method":"initialize",
                "params":{"protocolVersion":"2025-06-18"}}"""))
                .andExpect(status().isBadRequest());
        mockMvc.perform(base("""
                {"jsonrpc":"2.0","id":5,"method":"initialize",
                "params":{"protocolVersion":"2025-06-18"}}""")
                        .header("Mcp-Method", "initialize"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void modernNotificationAndInitializeAreRejected() throws Exception {
        mockMvc.perform(modern("""
                {"jsonrpc":"2.0","method":"tools/list","params":{%s}}"""
                .formatted(META), "tools/list", null)).andExpect(status().isBadRequest());
        mockMvc.perform(modern("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{%s}}"""
                .formatted(META), "initialize", null)).andExpect(status().isBadRequest());
    }

    @Test
    void hostAndOriginUseExactStructuredLoopbackAllowlist() throws Exception {
        String body = """
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{%s}}"""
                .formatted(META);
        mockMvc.perform(modern(body, "tools/list", null).header("Origin", "https://localhost:8765"))
                .andExpect(status().isOk());
        mockMvc.perform(modern(body, "tools/list", null).header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
        mockMvc.perform(modern(body, "tools/list", null)
                        .header("Origin", "https://localhost.evil.example"))
                .andExpect(status().isForbidden());
        mockMvc.perform(modern(body, "tools/list", null).header("Host", "localhost.evil.example"))
                .andExpect(status().isForbidden());
        mockMvc.perform(modern(body, "tools/list", null).header("Host", "127.0.0.1.evil.example"))
                .andExpect(status().isForbidden());
        mockMvc.perform(modern(body, "tools/list", null).header("Host", "localhost:8765,evil"))
                .andExpect(status().isForbidden());
        mockMvc.perform(modern(body, "tools/list", null).header("Origin", "null"))
                .andExpect(status().isForbidden());
        mockMvc.perform(modern(body, "tools/list", null)
                        .header("Origin", "https://user@localhost:8765"))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidOriginWinsBeforeAuthenticationAndJsonParsing() throws Exception {
        mockMvc.perform(base("not-json").header("Origin", "https://evil.example")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer wrong-token"))
                .andExpect(status().isForbidden())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("transport origin or host rejected")
                        .doesNotContain("wrong-token", "INVALID_REQUEST"));
    }

    @Test
    void wrongTokenNeverEchoesEitherCredential() throws Exception {
        mockMvc.perform(post("/api/mcp").header("Host", "localhost")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer wrong-token")
                        .header(HttpHeaders.ACCEPT, ACCEPT)
                        .contentType("application/json").content("not-json"))
                .andExpect(status().isUnauthorized())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("wrong-token", TOKEN));
    }

    @Test
    void oversizedDecodedBodyIsRejectedBeforeProtocolDispatch() throws Exception {
        String oversized = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"padding\":\""
                + "x".repeat(300_000) + "\"}";
        mockMvc.perform(base(oversized)).andExpect(status().isPayloadTooLarge())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("PAYLOAD_TOO_LARGE").doesNotContain("km_status"));
    }

    @Test
    void bodyMustBeOneStrictUtf8JsonObject() throws Exception {
        String valid = """
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{%s}}"""
                .formatted(META);
        mockMvc.perform(modern(valid + valid, "tools/list", null))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("-32700").doesNotContain("km_status"));

        byte[] invalidUtf8 = valid.replace("tools/list", "tools/lÿst")
                .getBytes(StandardCharsets.ISO_8859_1);
        mockMvc.perform(base(invalidUtf8)
                        .header("MCP-Protocol-Version", "2026-07-28")
                        .header("Mcp-Method", "tools/lÿst"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("-32700").doesNotContain("km_status"));
    }

    @Test
    void mediaTypesAndUnsupportedHttpMethodsAreExplicit() throws Exception {
        mockMvc.perform(post("/api/mcp").header("Host", "localhost")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .header(HttpHeaders.ACCEPT, ACCEPT).contentType("text/plain").content("{}"))
                .andExpect(status().isUnsupportedMediaType());
        mockMvc.perform(post("/api/mcp").header("Host", "localhost")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .header(HttpHeaders.ACCEPT, "application/json")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isNotAcceptable());
        mockMvc.perform(post("/api/mcp").header("Host", "localhost")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .header(HttpHeaders.ACCEPT, "*/*")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isNotAcceptable());
        mockMvc.perform(get("/api/mcp")).andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, "POST"));
        mockMvc.perform(delete("/api/mcp")).andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, "POST"));
    }

    @Test
    void toolErrorsAndEgressProjectionDoNotBleedAcrossRequests() throws Exception {
        mockMvc.perform(modern("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                "params":{"name":"km_ask","arguments":{"question":"unknown topic",
                "retrievalMode":"WIKI_ONLY"},%s}}""".formatted(META), "tools/call", "km_ask"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("INVALID_REQUEST")
                        .doesNotContain("https://", "apiKey", "Bearer",
                                "CONFIGURATION", "EXECUTION"));
        mockMvc.perform(modern("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{%s}}"""
                .formatted(META), "tools/list", null))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("CONFIGURATION", "EXECUTION", "INVALID_REQUEST"));
    }

    @Test
    void unknownWriteLikeToolRemainsTypedUnsupported() throws Exception {
        mockMvc.perform(modern("""
                {"jsonrpc":"2.0","id":7,"method":"tools/call",
                "params":{"name":"km_publish","arguments":{},%s}}"""
                .formatted(META), "tools/call", "km_publish"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("UNSUPPORTED_TOOL", "isError", "content")
                        .doesNotContain("PUBLISHED", TOKEN));
    }

    private static MockHttpServletRequestBuilder modern(
            String body, String method, String name) {
        MockHttpServletRequestBuilder request = base(body)
                .header("MCP-Protocol-Version", "2026-07-28")
                .header("Mcp-Method", method);
        return name == null ? request : request.header("Mcp-Name", name);
    }

    private static MockHttpServletRequestBuilder legacy(String body) {
        return base(body).header("MCP-Protocol-Version", "2025-06-18");
    }

    private static MockHttpServletRequestBuilder base(String body) {
        return base(body.getBytes(StandardCharsets.UTF_8));
    }

    private static MockHttpServletRequestBuilder base(byte[] body) {
        return post("/api/mcp").header("Host", "localhost:8765")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .header(HttpHeaders.ACCEPT, ACCEPT)
                .contentType("application/json").content(body);
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        for (int index = 0; (index = value.indexOf(needle, index)) >= 0; index += needle.length()) {
            count++;
        }
        return count;
    }
}
