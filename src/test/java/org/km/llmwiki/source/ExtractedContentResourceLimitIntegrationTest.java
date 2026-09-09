package org.km.llmwiki.source;

import org.junit.jupiter.api.Test;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = {
        "app.extraction.resource.max-input-bytes=8",
        "app.extraction.resource.max-output-characters=12",
        "app.extraction.resource.max-metadata-characters=32"
})
class ExtractedContentResourceLimitIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void acceptsInputExactlyAtTheConfiguredBound() throws Exception {
        createWorkspace();
        long documentId = upload("exact-input.bounded", "12345678");

        mockMvc.perform(post("/api/v1/documents/{documentId}/extract", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parseStatus").value("PROCESSED"));

        assertThat(db().sql("SELECT COUNT(*) FROM document WHERE id = :id AND error_code IS NULL")
                .param("id", documentId).query(Integer.class).single()).isEqualTo(1);
        assertThat(db().sql("SELECT COUNT(*) FROM source_chunk WHERE document_id = :id")
                .param("id", documentId).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void rejectsInputOverTheBoundBeforeParserPersistence() throws Exception {
        createWorkspace();
        long documentId = upload("over-input.bounded", "123456789");

        mockMvc.perform(post("/api/v1/documents/{documentId}/extract", documentId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("EXTRACTION_RESOURCE_LIMIT"));

        assertFailedWithoutDerivedContent(documentId);
    }

    @Test
    void rejectsParserExpansionEvenWhenTheSourceFileIsSmall() throws Exception {
        createWorkspace();
        long documentId = upload("expansion.bounded", "x");

        mockMvc.perform(post("/api/v1/documents/{documentId}/extract", documentId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("EXTRACTION_RESOURCE_LIMIT"));

        assertFailedWithoutDerivedContent(documentId);
    }

    @Test
    void rejectsMetadataExpansionAndDoesNotPersistPartialChunks() throws Exception {
        createWorkspace();
        long documentId = upload("metadata.bounded", "x");

        mockMvc.perform(post("/api/v1/documents/{documentId}/extract", documentId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("EXTRACTION_RESOURCE_LIMIT"));

        assertFailedWithoutDerivedContent(documentId);
    }

    private void assertFailedWithoutDerivedContent(long documentId) {
        assertThat(db().sql("SELECT parse_status FROM document WHERE id = :id")
                .param("id", documentId).query(String.class).single()).isEqualTo("FAILED");
        assertThat(db().sql("SELECT error_code FROM document WHERE id = :id")
                .param("id", documentId).query(String.class).single()).isEqualTo("EXTRACTION_RESOURCE_LIMIT");
        assertThat(db().sql("SELECT COUNT(*) FROM document_extracted_content WHERE document_id = :id")
                .param("id", documentId).query(Integer.class).single()).isZero();
        assertThat(db().sql("SELECT COUNT(*) FROM source_chunk WHERE document_id = :id")
                .param("id", documentId).query(Integer.class).single()).isZero();
    }

    private long upload(String fileName, String content) throws Exception {
        String response = mockMvc.perform(multipart("/api/v1/inbox/files")
                        .file(new MockMultipartFile("file", fileName, "application/x-test-bounded",
                                content.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(response.replaceAll(".*\\\"documentId\\\":(\\d+).*", "$1"));
    }

    private void createWorkspace() throws Exception {
        Path root = Path.of("target/test-data/extraction-limit-root-" + UUID.randomUUID()).toAbsolutePath();
        mockMvc.perform(post("/api/v1/workspaces")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "Extraction Limit Test", "rootPath": "%s"}
                                """.formatted(root)))
                .andExpect(status().isCreated());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class BoundedParserConfiguration {

        @Bean
        DocumentParser boundedParser() {
            return new DocumentParser() {
                @Override
                public boolean supportsMimeType(String mimeType) {
                    return "application/x-test-bounded".equals(mimeType);
                }

                @Override
                public boolean supportsExtension(String extension) {
                    return "bounded".equals(extension);
                }

                @Override
                public ParsedDocument parse(Path source) throws IOException {
                    String fileName = source.getFileName().toString();
                    if (fileName.startsWith("expansion")) {
                        return new ParsedDocument("x".repeat(13), Map.of());
                    }
                    if (fileName.startsWith("metadata")) {
                        return new ParsedDocument("safe", Map.of("title", "x".repeat(32)));
                    }
                    return new ParsedDocument("bounded", Map.of());
                }
            };
        }
    }
}
