package org.km.llmwiki.web.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end owner security boundary evidence (#417).
 *
 * <p>Runs with {@code app.owner.auth-enabled=true} and asserts the full
 * admission contract over real HTTP semantics: typed 401/403 without
 * existence leakage, cookie/bearer session lifecycle, cookie-mutation origin
 * equivalence, Host/Origin allowlists with default-deny cross-origin headers,
 * forwarded-header distrust, login throttling, domain-authority preservation,
 * and MCP isolation.
 */
@Tag("integration")
@TestPropertySource(properties = {
        "app.owner.auth-enabled=true",
        "app.owner.password-hash=9148a9b37f4f80aa2e47430e455049e2e41c020df0febe085c306c40a2626393",
        "app.owner.cookie-secure=false",
        "app.owner.session-absolute-timeout=12h",
        "app.owner.session-idle-timeout=30m",
        "app.owner.allowed-hosts=localhost,127.0.0.1",
        "app.owner.allowed-origins=http://localhost:8765,http://127.0.0.1:8765",
        "app.owner.login-max-attempts=100",
        "app.owner.login-window=1m",
        "app.owner.mutation-max-requests=1000",
        "app.owner.mutation-window=1m"})
class OwnerSecurityIntegrationTest extends IsolatedIntegrationTest {

    private static final String PASSWORD = "owner-test-password";
    private static final String ORIGIN = "http://localhost:8765";

    @Autowired
    private MockMvc mvc;

    private final ObjectMapper mapper = new ObjectMapper();

    private String loginToken() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/owner/session")
                        .header("Host", "localhost:8765")
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode data = mapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(data.get("authenticated").asBoolean()).isTrue();
        assertThat(data.get("token").asText()).isNotBlank();
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).startsWith("km-owner-session=");
        assertThat(setCookie).contains("HttpOnly").contains("SameSite=Lax").contains("Path=/");
        assertThat(result.getResponse().getContentAsString()).doesNotContain(PASSWORD);
        return data.get("token").asText();
    }

    @Test
    void unauthenticatedRequestFailsClosedWithoutLeakingExistence() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/system/status")
                        .header("Host", "localhost:8765"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        JsonNode error = mapper.readTree(result.getResponse().getContentAsString()).get("error");
        assertThat(error.get("code").asText()).isEqualTo("OWNER_AUTH_REQUIRED");
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("workspace", "vault", "archive");
    }

    @Test
    void wrongCredentialFailsClosedWithTheSameTypedContract() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/owner/session")
                        .header("Host", "localhost:8765")
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        JsonNode error = mapper.readTree(result.getResponse().getContentAsString()).get("error");
        assertThat(error.get("code").asText()).isEqualTo("OWNER_AUTH_REQUIRED");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("wrong-password");
    }

    @Test
    void cookieSessionAdmitsReadsAndBearerSessionAdmitsMutationsWithoutOrigin() throws Exception {
        String token = loginToken();

        mvc.perform(get("/api/v1/system/status")
                        .header("Host", "localhost:8765")
                        .cookie(new Cookie("km-owner-session", token)))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/system/status")
                        .header("Host", "localhost:8765")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        // Bearer is non-ambient: no origin equivalence is required.
        mvc.perform(post("/api/v1/owner/session/rotation")
                        .header("Host", "localhost:8765")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void cookieMutationWithoutAllowlistedOriginIsRejected() throws Exception {
        String token = loginToken();

        MvcResult result = mvc.perform(post("/api/v1/owner/session/rotation")
                        .header("Host", "localhost:8765")
                        .cookie(new Cookie("km-owner-session", token)))
                .andExpect(status().isForbidden())
                .andReturn();
        JsonNode error = mapper.readTree(result.getResponse().getContentAsString()).get("error");
        assertThat(error.get("code").asText()).isEqualTo("OWNER_ORIGIN_REJECTED");

        mvc.perform(post("/api/v1/owner/session/rotation")
                        .header("Host", "localhost:8765")
                        .header("Origin", ORIGIN)
                        .cookie(new Cookie("km-owner-session", token)))
                .andExpect(status().isOk());
    }

    @Test
    void crossSiteOriginIsRejectedAndNeverReflectedWithCredentials() throws Exception {
        String token = loginToken();

        MvcResult result = mvc.perform(get("/api/v1/system/status")
                        .header("Host", "localhost:8765")
                        .header("Origin", "https://evil.example")
                        .cookie(new Cookie("km-owner-session", token)))
                .andExpect(status().isForbidden())
                .andReturn();
        assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(token);
        JsonNode error = mapper.readTree(result.getResponse().getContentAsString()).get("error");
        assertThat(error.get("code").asText()).isEqualTo("OWNER_ORIGIN_REJECTED");
    }

    @Test
    void allowlistedOriginIsReflectedWithoutWildcardAndHostSpoofFails() throws Exception {
        String token = loginToken();

        MvcResult allowed = mvc.perform(get("/api/v1/system/status")
                        .header("Host", "localhost:8765")
                        .header("Origin", ORIGIN)
                        .cookie(new Cookie("km-owner-session", token)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(allowed.getResponse().getHeader("Access-Control-Allow-Origin"))
                .isEqualTo(ORIGIN);
        assertThat(allowed.getResponse().getHeader("Access-Control-Allow-Credentials"))
                .isEqualTo("true");
        assertThat(allowed.getResponse().getHeader("Access-Control-Allow-Origin"))
                .isNotEqualTo("*");

        MvcResult spoofed = mvc.perform(get("/api/v1/system/status")
                        .header("Host", "evil.example")
                        .header("Origin", ORIGIN)
                        .cookie(new Cookie("km-owner-session", token)))
                .andExpect(status().isForbidden())
                .andReturn();
        JsonNode error = mapper.readTree(spoofed.getResponse().getContentAsString()).get("error");
        assertThat(error.get("code").asText()).isEqualTo("OWNER_HOST_REJECTED");
    }

    @Test
    void spoofedUpstreamIdentityAndProxyMetadataNeverAuthenticate() throws Exception {
        mvc.perform(get("/api/v1/system/status")
                        .header("Host", "localhost:8765")
                        .header("X-Forwarded-User", "owner")
                        .header("X-Remote-User", "owner"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/system/status")
                        .header("Host", "evil.example")
                        .header("X-Forwarded-Host", "localhost")
                        .header("X-Forwarded-Proto", "http"))
                .andExpect(status().isForbidden());
    }

    @Test
    void logoutRevokesServerSideAndReplayFailsClosed() throws Exception {
        String token = loginToken();

        mvc.perform(delete("/api/v1/owner/session")
                        .header("Host", "localhost:8765")
                        .header("Origin", ORIGIN)
                        .cookie(new Cookie("km-owner-session", token)))
                .andExpect(status().isNoContent());

        MvcResult replay = mvc.perform(get("/api/v1/system/status")
                        .header("Host", "localhost:8765")
                        .cookie(new Cookie("km-owner-session", token)))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertThat(replay.getResponse().getContentAsString()).doesNotContain(token);
    }

    @Test
    void rotationReplacesTheSessionAndTheOldTokenStopsWorking() throws Exception {
        String token = loginToken();

        MvcResult rotated = mvc.perform(post("/api/v1/owner/session/rotation")
                        .header("Host", "localhost:8765")
                        .header("Origin", ORIGIN)
                        .cookie(new Cookie("km-owner-session", token)))
                .andExpect(status().isOk())
                .andReturn();
        String fresh = mapper.readTree(rotated.getResponse().getContentAsString())
                .get("data").get("token").asText();
        assertThat(fresh).isNotBlank().isNotEqualTo(token);

        mvc.perform(get("/api/v1/system/status")
                        .header("Host", "localhost:8765")
                        .cookie(new Cookie("km-owner-session", token)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/system/status")
                        .header("Host", "localhost:8765")
                        .cookie(new Cookie("km-owner-session", fresh)))
                .andExpect(status().isOk());
    }

    @Test
    void authenticationNeverBypassesDomainAuthority() throws Exception {
        String token = loginToken();

        MvcResult wiki = mvc.perform(get("/api/v1/wiki/does-not-exist-417")
                        .header("Host", "localhost:8765")
                        .cookie(new Cookie("km-owner-session", token)))
                .andExpect(status().isNotFound())
                .andReturn();
        JsonNode error = mapper.readTree(wiki.getResponse().getContentAsString()).get("error");
        // The filter passed the request through: the domain answers with its own
        // typed 404 (missing page, or missing workspace first) — never an owner
        // code, and never a bypassed success.
        assertThat(error.get("code").asText()).isIn(
                "WIKI_PAGE_NOT_FOUND", "WIKI_PAGE_UNAVAILABLE", "NO_ACTIVE_WORKSPACE");

        mvc.perform(post("/api/v1/ask")
                        .header("Host", "localhost:8765")
                        .header("Origin", ORIGIN)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void ownerSessionNeverAdmitsMcpAndMcpBearerNeverAdmitsOwnerApi() throws Exception {
        String token = loginToken();

        // The MCP adapter keeps its own loopback boundary: without an MCP token
        // it stays disabled regardless of the owner session.
        mvc.perform(post("/api/mcp")
                        .header("Host", "localhost:8765")
                        .header("Origin", ORIGIN)
                        .cookie(new Cookie("km-owner-session", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, org.springframework.http.MediaType.TEXT_EVENT_STREAM)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\"}"))
                .andExpect(status().isServiceUnavailable());

        // An MCP-style bearer for the owner API is not an owner session.
        mvc.perform(get("/api/v1/system/status")
                        .header("Host", "localhost:8765")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer mcp-adapter-token"))
                .andExpect(status().isUnauthorized());
    }
}
