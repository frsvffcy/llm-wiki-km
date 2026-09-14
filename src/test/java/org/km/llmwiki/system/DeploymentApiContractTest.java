package org.km.llmwiki.system;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("contract")
@WebMvcTest(DeploymentReadinessController.class)
class DeploymentApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DeploymentReadinessService readinessService;

    @Test
    void deploymentReadinessUsesDataEnvelopeWithSafeProjection() throws Exception {
        when(readinessService.current()).thenReturn(new DeploymentReadiness(
                "LOCAL_ONLY", DeploymentReadiness.SUPPORTED, "127.0.0.1:8765",
                true, true, "Local-only deployment on the loopback backend."));

        mockMvc.perform(get("/api/v1/system/deployment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("LOCAL_ONLY"))
                .andExpect(jsonPath("$.data.supportState").value("SUPPORTED"))
                .andExpect(jsonPath("$.data.backendBind").value("127.0.0.1:8765"))
                .andExpect(jsonPath("$.data.forwarderBounded").value(true))
                .andExpect(jsonPath("$.data.singleInstance").value(true))
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    org.assertj.core.api.Assertions.assertThat(body)
                            .doesNotContain("sk-", "Bearer", "password", "token",
                                    "RID", "jdbc:", "/vault", "/archive");
                });
    }

    @Test
    void candidateStateKeepsTheSameEnvelope() throws Exception {
        when(readinessService.current()).thenReturn(new DeploymentReadiness(
                "REVERSE_PROXY_CANDIDATE", DeploymentReadiness.CANDIDATE, "127.0.0.1:8765",
                true, true, "Reverse-proxy TLS deployment is a candidate and is not supported"
                        + " by the current operations contract."));

        mockMvc.perform(get("/api/v1/system/deployment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.supportState").value("CANDIDATE"));
    }
}
