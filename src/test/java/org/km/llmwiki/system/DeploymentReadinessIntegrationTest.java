package org.km.llmwiki.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Default deployment profile evidence (#418 §A/B/I).
 *
 * <p>Runs on the untouched default configuration and proves the shipped
 * baseline still reports local-only supported on the loopback backend with an
 * operator-safe projection.
 */
@Tag("integration")
class DeploymentReadinessIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private MockMvc mvc;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void defaultProfileReportsLocalOnlySupported() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/system/deployment"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = mapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(data.get("mode").asText()).isEqualTo("LOCAL_ONLY");
        assertThat(data.get("supportState").asText()).isEqualTo("SUPPORTED");
        assertThat(data.get("backendBind").asText()).isEqualTo("127.0.0.1:8765");
        assertThat(data.get("singleInstance").asBoolean()).isTrue();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("sk-", "Bearer", "password", "RID", "jdbc:");
    }
}
