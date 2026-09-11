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

@Tag("integration")
@WebMvcTest(SystemStatusController.class)
class SystemStatusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SystemStatusService systemService;

    @MockitoBean
    private org.km.llmwiki.ai.provider.ProviderEgressService providerEgressService;

    @Test
    void returnsReadyStatusAndVersion() throws Exception {
        when(systemService.getStatus()).thenReturn(
                new SystemStatusResponse("READY", "0.1.0", 1L, "Personal Knowledge", "READY"));

        mockMvc.perform(get("/api/v1/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.version").value("0.1.0"));
    }

    @Test
    void returnsErrorStatusWhenDatabaseUnavailable() throws Exception {
        when(systemService.getStatus()).thenReturn(
                new SystemStatusResponse("ERROR", "0.1.0", null, null, "ERROR"));

        mockMvc.perform(get("/api/v1/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ERROR"))
                .andExpect(jsonPath("$.data.database").value("ERROR"));
    }

    @Test
    void exposesProviderEgressDescriptorsAsSafeDataOnly() throws Exception {
        when(providerEgressService.descriptors()).thenReturn(java.util.List.of(
                new org.km.llmwiki.ai.provider.ProviderEgressDescriptor(
                        org.km.llmwiki.ai.provider.ProviderEgressDescriptor.ProviderPurpose.ANSWER,
                        org.km.llmwiki.ai.provider.ProviderDestination.REMOTE_SECURE,
                        "openai-compatible", "offline-model",
                        java.util.List.of(
                                org.km.llmwiki.ai.provider.ProviderEgressCategory.QUESTION_TEXT,
                                org.km.llmwiki.ai.provider.ProviderEgressCategory
                                        .EVIDENCE_CONTEXT_REPRESENTATION)),
                new org.km.llmwiki.ai.provider.ProviderEgressDescriptor(
                        org.km.llmwiki.ai.provider.ProviderEgressDescriptor.ProviderPurpose.EMBEDDING,
                        org.km.llmwiki.ai.provider.ProviderDestination.DISABLED,
                        null, null, java.util.List.of())));

        mockMvc.perform(get("/api/v1/system/ai-provider-egress"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].purpose").value("ANSWER"))
                .andExpect(jsonPath("$.data[0].destinationClass").value("REMOTE_SECURE"))
                .andExpect(jsonPath("$.data[0].providerType").value("openai-compatible"))
                .andExpect(jsonPath("$.data[0].modelDisplayName").value("offline-model"))
                .andExpect(jsonPath("$.data[0].egressCategories.length()").value(2))
                .andExpect(jsonPath("$.data[1].destinationClass").value("DISABLED"))
                .andExpect(jsonPath("$.data[1].providerType").doesNotExist())
                .andExpect(jsonPath("$.data[1].modelDisplayName").doesNotExist())
                .andExpect(jsonPath("$.data[1].egressCategories.length()").value(0))
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    org.assertj.core.api.Assertions.assertThat(body)
                            .doesNotContain("http", "key", "token", "path", "baseUrl");
                });
    }

    @Test
    void providerEgressServiceFailureSurfacesTypedErrorWithoutProviderDetails() throws Exception {
        when(providerEgressService.descriptors()).thenThrow(
                new IllegalStateException("internal provider state"));

        mockMvc.perform(get("/api/v1/system/ai-provider-egress"))
                .andExpect(status().isInternalServerError())
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    org.assertj.core.api.Assertions.assertThat(body).doesNotContain(
                            "IllegalStateException", "provider state");
                    org.assertj.core.api.Assertions.assertThat(body).contains("error");
                });
    }
}
