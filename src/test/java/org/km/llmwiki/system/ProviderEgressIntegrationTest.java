package org.km.llmwiki.system;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-context integration for the provider egress transparency contract (#323): the real
 * {@code ProviderEgressService} derives destination classes from the current configuration and
 * the #281 transport policy. With the default test configuration both provider boundaries are
 * disabled, so the disclosure must say DISABLED without claiming any data category and without
 * exposing endpoints, credentials, or transport details.
 */
@Tag("integration")
class ProviderEgressIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exposesApplicationOwnedProviderEgressDescriptorsWithoutProviderDetails() throws Exception {
        mockMvc.perform(get("/api/v1/system/ai-provider-egress"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].purpose").value("ANSWER"))
                .andExpect(jsonPath("$.data[0].destinationClass").value("DISABLED"))
                .andExpect(jsonPath("$.data[0].providerType").doesNotExist())
                .andExpect(jsonPath("$.data[0].modelDisplayName").doesNotExist())
                .andExpect(jsonPath("$.data[0].egressCategories.length()").value(0))
                .andExpect(jsonPath("$.data[1].purpose").value("EMBEDDING"))
                .andExpect(jsonPath("$.data[1].destinationClass").value("DISABLED"))
                .andExpect(content().string(not(containsString("https://"))))
                .andExpect(content().string(not(containsString("apiKey"))))
                .andExpect(content().string(not(containsString("bearer"))))
                .andExpect(content().string(not(containsString("/v1"))));
    }
}
