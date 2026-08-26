package org.km.llmwiki.system;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SystemStatusController.class)
class SystemStatusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SystemStatusService systemService;

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
}
