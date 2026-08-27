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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.persistence.sqlite.path=target/test-data/extraction-${random.uuid}/knowledge.db"
})
@AutoConfigureMockMvc
class ExtractedContentIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void extractsTextAndReturnsPagedChunksForPreview() throws Exception {
        createWorkspace();
        long documentId = upload("long-note.txt", "a".repeat(2_100));

        mockMvc.perform(post("/api/v1/documents/{documentId}/extract", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documentId").value(documentId))
                .andExpect(jsonPath("$.data.parseStatus").value("PROCESSED"))
                .andExpect(jsonPath("$.data.chunkCount").value(2));

        mockMvc.perform(get("/api/v1/documents/{documentId}/extracted-content", documentId)
                        .param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documentId").value(documentId))
                .andExpect(jsonPath("$.data.parseStatus").value("PROCESSED"))
                .andExpect(jsonPath("$.data.chunkCount").value(2))
                .andExpect(jsonPath("$.data.chunks[0].chunkIndex").value(1))
                .andExpect(jsonPath("$.data.chunks[0].content").isNotEmpty())
                .andExpect(jsonPath("$.data.page.number").value(1))
                .andExpect(jsonPath("$.data.page.totalElements").value(2));

        assertThat(db().sql("SELECT extracted_text_hash FROM document WHERE id = :id")
                .param("id", documentId).query(String.class).single()).isNotBlank();
    }

    @Test
    void persistsNormalizedContentAndItsHashForLaterComparison() throws Exception {
        createWorkspace();
        long documentId = upload("normalized-note.txt", "Cafe\u0301\r\n\r\n\r\nNext line");

        mockMvc.perform(post("/api/v1/documents/{documentId}/extract", documentId))
                .andExpect(status().isOk());

        assertThat(db().sql("SELECT content FROM document_extracted_content WHERE document_id = :id")
                .param("id", documentId).query(String.class).single()).isEqualTo("Caf\u00e9\n\nNext line\n");
        assertThat(db().sql("SELECT extracted_text_hash FROM document WHERE id = :id")
                .param("id", documentId).query(String.class).single())
                .isEqualTo("e05f27724cb3a4bd6a67654f93ceab6a1ee5557310e2ca2549e92a82287af0d1");
    }

    @Test
    void reportsUnderstandableErrorForUnsupportedDocumentType() throws Exception {
        createWorkspace();
        long documentId = upload("archive.bin", "application/octet-stream", "not a supported document");

        mockMvc.perform(post("/api/v1/documents/{documentId}/extract", documentId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("EXTRACTION_UNSUPPORTED_TYPE"));

        assertThat(db().sql("SELECT parse_status FROM document WHERE id = :id")
                .param("id", documentId).query(String.class).single()).isEqualTo("UNSUPPORTED");
    }

    private long upload(String fileName, String content) throws Exception {
        return upload(fileName, "text/plain", content);
    }

    private long upload(String fileName, String contentType, String content) throws Exception {
        String response = mockMvc.perform(multipart("/api/v1/inbox/files")
                        .file(new MockMultipartFile("file", fileName, contentType,
                                content.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(response.replaceAll(".*\\\"documentId\\\":(\\d+).*", "$1"));
    }

    private void createWorkspace() throws Exception {
        Path root = Path.of("target/test-data/extraction-root-" + UUID.randomUUID()).toAbsolutePath();
        mockMvc.perform(post("/api/v1/workspaces")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "Extraction Test", "rootPath": "%s"}
                                """.formatted(root)))
                .andExpect(status().isCreated());
    }
}
