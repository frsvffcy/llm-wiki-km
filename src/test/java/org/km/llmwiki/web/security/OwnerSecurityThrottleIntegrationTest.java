package org.km.llmwiki.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bounded abuse-control evidence (#417 §F): login attempts and authenticated
 * mutations each carry an independent sliding-window bound. Request-size
 * limits (multipart, bounded extraction) cap a single request; these bounds
 * cap how often high-risk operations may be retried.
 */
@Tag("integration")
@TestPropertySource(properties = {
        "app.owner.auth-enabled=true",
        "app.owner.password-verifier=pbkdf2-sha256$v1$iter=600000$MDEyMzQ1Njc4OWFiY2RlZg$OExLF-y530K2YCU_905346sLUKmgzToFNdzTASr1_1Q",
        "app.owner.cookie-secure=false",
        "app.owner.allowed-hosts=localhost,127.0.0.1",
        "app.owner.allowed-origins=http://localhost:8765,http://127.0.0.1:8765",
        "app.owner.login-max-attempts=3",
        "app.owner.login-window=1m",
        "app.owner.mutation-max-requests=3",
        "app.owner.mutation-window=1m"})
class OwnerSecurityThrottleIntegrationTest extends IsolatedIntegrationTest {

    private static final String ORIGIN = "http://localhost:8765";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OwnerRateLimiter rateLimiter;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void clearRateLimiter() {
        rateLimiter.clear();
    }

    @Test
    void loginAttemptsBeyondTheBoundAreRejectedWith429() throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            mvc.perform(post("/api/v1/owner/session")
                            .header("Host", "localhost:8765")
                            .header("Origin", ORIGIN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"password\":\"wrong\"}"))
                    .andExpect(status().isUnauthorized());
        }
        MvcResult throttled = mvc.perform(post("/api/v1/owner/session")
                        .header("Host", "localhost:8765")
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"wrong\"}"))
                .andExpect(status().isTooManyRequests())
                .andReturn();
        assertThat(mapper.readTree(throttled.getResponse().getContentAsString())
                .get("error").get("code").asText()).isEqualTo("OWNER_RATE_LIMITED");
    }

    @Test
    void authenticatedMutationsBeyondTheBoundAreRejectedWith429() throws Exception {
        MvcResult login = mvc.perform(post("/api/v1/owner/session")
                        .header("Host", "localhost:8765")
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"owner-test-password\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String token = mapper.readTree(login.getResponse().getContentAsString())
                .get("data").get("token").asText();

        for (int attempt = 0; attempt < 3; attempt++) {
            MvcResult rotated = mvc.perform(post("/api/v1/owner/session/rotation")
                            .header("Host", "localhost:8765")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn();
            token = mapper.readTree(rotated.getResponse().getContentAsString())
                    .get("data").get("token").asText();
        }
        mvc.perform(post("/api/v1/owner/session/rotation")
                        .header("Host", "localhost:8765")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isTooManyRequests());
    }
}
