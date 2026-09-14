package org.km.llmwiki.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Mode 1 private-ingress evidence (#418 §C).
 *
 * <p>Runs with an explicit bounded forwarder plus the application-owned owner
 * boundary and proves the topology contract end to end: the deployment surface
 * reports supported, the owner session still guards it (unauthenticated reads
 * fail closed without existence leakage), and the domain authority underneath
 * is unchanged.
 */
@Tag("integration")
@TestPropertySource(properties = {
        "app.deployment.mode=PRIVATE_INGRESS",
        "app.deployment.forwarder-binds=100.64.0.5:8766",
        "app.deployment.forwarder-target=127.0.0.1:8765",
        "app.owner.auth-enabled=true",
        "app.owner.password-hash=9148a9b37f4f80aa2e47430e455049e2e41c020df0febe085c306c40a2626393",
        "app.owner.cookie-secure=false",
        "app.owner.login-max-attempts=100",
        "app.owner.login-window=1m",
        "app.owner.mutation-max-requests=1000",
        "app.owner.mutation-window=1m"})
class DeploymentPrivateIngressIntegrationTest extends IsolatedIntegrationTest {

    private static final String PASSWORD = "owner-test-password";
    private static final String ORIGIN = "http://localhost:8765";

    @Autowired
    private MockMvc mvc;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void privateIngressReportsSupportedBehindOwnerSession() throws Exception {
        mvc.perform(get("/api/v1/system/deployment")
                        .header("Host", "localhost:8765"))
                .andExpect(status().isUnauthorized());

        String token = loginToken();

        MvcResult result = mvc.perform(get("/api/v1/system/deployment")
                        .header("Host", "localhost:8765")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = mapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(data.get("mode").asText()).isEqualTo("PRIVATE_INGRESS");
        assertThat(data.get("supportState").asText()).isEqualTo("SUPPORTED");
        assertThat(data.get("backendBind").asText()).isEqualTo("127.0.0.1:8765");
        assertThat(data.get("forwarderBounded").asBoolean()).isTrue();
        assertThat(data.get("singleInstance").asBoolean()).isTrue();
    }

    private String loginToken() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/owner/session")
                        .header("Host", "localhost:8765")
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("token").asText();
    }
}
