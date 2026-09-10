package org.km.llmwiki.web;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.source.ChunkCurrentness;
import org.km.llmwiki.source.SourceChunkNotFoundException;
import org.km.llmwiki.source.SourceChunkService;
import org.km.llmwiki.source.SourceLocator;
import org.km.llmwiki.source.SourceChunkLocatorService;
import org.km.llmwiki.testsupport.SpringIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("integration")
@WebMvcTest(org.km.llmwiki.source.SourceChunkController.class)
@Import(GlobalExceptionHandler.class)
class SourceLocatorApiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SourceChunkService sourceChunkService;

    @MockitoBean
    private SourceChunkLocatorService locatorService;

    @Test
    void projectsOnlySafeLocatorFieldsForCurrentChunks() throws Exception {
        when(locatorService.locate(42L)).thenReturn(new SourceLocator(42L, 900L, "design.pdf",
                3, 17, "Graph lifecycle", "Projection > Generation ownership",
                ChunkCurrentness.CURRENT, null, "authoritative preview", false));

        mockMvc.perform(get("/api/v1/source-chunks/{chunkId}/locator", 42L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceChunkId").value(42))
                .andExpect(jsonPath("$.data.documentId").value(900))
                .andExpect(jsonPath("$.data.documentName").value("design.pdf"))
                .andExpect(jsonPath("$.data.chunkNo").value(3))
                .andExpect(jsonPath("$.data.pageNo").value(17))
                .andExpect(jsonPath("$.data.section").value("Graph lifecycle"))
                .andExpect(jsonPath("$.data.headingPath").value("Projection > Generation ownership"))
                .andExpect(jsonPath("$.data.currentness").value("CURRENT"))
                .andExpect(jsonPath("$.data.notCurrentReason").doesNotExist())
                .andExpect(jsonPath("$.data.preview").value("authoritative preview"))
                .andExpect(jsonPath("$.data.previewTruncated").value(false));
    }

    @Test
    void notCurrentLocatorsExposeTypedReasonWithoutContent() throws Exception {
        when(locatorService.locate(42L)).thenReturn(new SourceLocator(42L, 900L, null,
                3, 17, "Graph lifecycle", null, ChunkCurrentness.NOT_CURRENT,
                "INELIGIBLE", null, false));

        mockMvc.perform(get("/api/v1/source-chunks/{chunkId}/locator", 42L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentness").value("NOT_CURRENT"))
                .andExpect(jsonPath("$.data.notCurrentReason").value("INELIGIBLE"))
                .andExpect(jsonPath("$.data.preview").doesNotExist())
                .andExpect(jsonPath("$.data.documentName").doesNotExist());
    }

    @Test
    void unknownChunksMapToTheSharedSafeNotFoundCode() throws Exception {
        when(locatorService.locate(42L)).thenThrow(new SourceChunkNotFoundException(42L));

        mockMvc.perform(get("/api/v1/source-chunks/{chunkId}/locator", 42L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SOURCE_CHUNK_NOT_FOUND"));
    }

    @Test
    void locatorProjectionNeverCarriesRawInternals() throws Exception {
        when(locatorService.locate(42L)).thenReturn(new SourceLocator(42L, 900L, "design.pdf",
                3, 17, "Graph lifecycle", "Projection > Generation ownership",
                ChunkCurrentness.CURRENT, null, "preview with <script>alert(1)</script> text",
                false));

        String body = mockMvc.perform(get("/api/v1/source-chunks/{chunkId}/locator", 42L))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("archive/", "file://", "/var/folders", "RuntimeException")
                .doesNotContainPattern("#\\d+:\\d+")
                .contains("preview with <script>alert(1)</script> text");
    }
}
