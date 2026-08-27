package org.km.llmwiki.source;

import org.junit.jupiter.api.Test;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.persistence.sqlite.path=target/test-data/source-chunk-${random.uuid}/knowledge.db"
})
@AutoConfigureMockMvc
class SourceChunkIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void persistsTraceableChunksAndExposesBothChunkEndpoints() throws Exception {
        createWorkspace();
        long documentId = upload("guide.md", """
                # Overview

                First paragraph.

                ## Details

                First-page detail.Second-page detail.
                """);

        mockMvc.perform(post("/api/v1/documents/{documentId}/extract", documentId))
                .andExpect(status().isOk());

        String chunksResponse = mockMvc.perform(get("/api/v1/documents/{documentId}/chunks", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].documentId").value(documentId))
                .andExpect(jsonPath("$.data[0].chunkNo").value(1))
                .andExpect(jsonPath("$.data[1].pageNo").value(1))
                .andExpect(jsonPath("$.data[1].section").value("Details"))
                .andExpect(jsonPath("$.data[1].headingPath").value("Overview > Details"))
                .andExpect(jsonPath("$.data[1].content").value(org.hamcrest.Matchers.containsString("First-page detail.")))
                .andExpect(jsonPath("$.data[1].normalizedContent").value(org.hamcrest.Matchers.containsString("First-page detail.")))
                .andExpect(jsonPath("$.data[1].contentHash").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        long chunkId = Long.parseLong(chunksResponse.replaceAll(".*\\\"id\\\":(\\d+).*", "$1"));

        mockMvc.perform(get("/api/v1/source-chunks/{chunkId}", chunkId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(chunkId))
                .andExpect(jsonPath("$.data.documentId").value(documentId));

        mockMvc.perform(post("/api/v1/documents/{documentId}/extract", documentId))
                .andExpect(status().isOk());

        assertThat(db().sql("SELECT group_concat(chunk_no, ',') FROM source_chunk WHERE document_id = :id ORDER BY chunk_no")
                .param("id", documentId).query(String.class).single()).isEqualTo("1,2");
        assertThat(db().sql("SELECT content_hash FROM source_chunk WHERE document_id = :id AND chunk_no = 2")
                .param("id", documentId).query(String.class).single()).hasSize(64);
    }

    @Test
    void returnsNotFoundForAChunkOutsideTheActiveWorkspace() throws Exception {
        createWorkspace();

        mockMvc.perform(get("/api/v1/source-chunks/{chunkId}", 9_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SOURCE_CHUNK_NOT_FOUND"));
    }

    private long upload(String fileName, String content) throws Exception {
        String response = mockMvc.perform(multipart("/api/v1/inbox/files")
                        .file(new MockMultipartFile("file", fileName, "text/markdown",
                                content.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(response.replaceAll(".*\\\"documentId\\\":(\\d+).*", "$1"));
    }

    private void createWorkspace() throws Exception {
        Path root = Path.of("target/test-data/source-chunk-root-" + UUID.randomUUID()).toAbsolutePath();
        mockMvc.perform(post("/api/v1/workspaces")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "Source Chunk Test", "rootPath": "%s"}
                                """.formatted(root)))
                .andExpect(status().isCreated());
    }
}
