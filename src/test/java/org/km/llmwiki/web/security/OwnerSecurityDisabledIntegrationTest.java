package org.km.llmwiki.web.security;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Local-only default evidence (#417): with {@code app.owner.auth-enabled=false}
 * every {@code /api/v1} endpoint stays open exactly as the #393 baseline
 * evaluated. Enabling protection is opt-in and never changes
 * {@code server.address}.
 */
@Tag("integration")
class OwnerSecurityDisabledIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void localOnlyDefaultLeavesApiOpenWithoutSession() throws Exception {
        mvc.perform(get("/api/v1/system/status")
                        .header("Host", "localhost:8765"))
                .andExpect(status().isOk());
    }

    @Test
    void loginEndpointIsClosedWhileAuthIsDisabled() throws Exception {
        mvc.perform(post("/api/v1/owner/session")
                        .header("Host", "localhost:8765")
                        .header("Origin", "http://localhost:8765")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"anything\"}"))
                .andExpect(status().isUnauthorized());
    }
}
