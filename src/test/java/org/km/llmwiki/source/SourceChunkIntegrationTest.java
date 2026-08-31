package org.km.llmwiki.source;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("integration")
@SpringBootTest(properties = {
        "app.persistence.sqlite.path=target/test-data/source-chunk-${random.uuid}/knowledge.db"
})
@AutoConfigureMockMvc
@Import(SourceChunkIntegrationTest.MultiPageParserConfiguration.class)
class SourceChunkIntegrationTest extends IsolatedIntegrationTest {

    private static final String MULTI_PAGE_CONTENT = """
            文件標頭

            # 總覽

            Café 第一頁內容。

            文件頁尾文件標頭

            第二頁內容。

            文件頁尾
            """;

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

    @Test
    void keepsRepeatedPageEdgesOnlyInOriginalChunkEvidence() throws Exception {
        createWorkspace();
        long documentId = upload("repeated-edges.pages", "application/x-test-multipage", "placeholder");

        mockMvc.perform(post("/api/v1/documents/{documentId}/extract", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parseStatus").value("PROCESSED"));

        String documentContent = db().sql("SELECT content FROM document_extracted_content WHERE document_id = :id")
                .param("id", documentId).query(String.class).single();
        List<String> originalContents = db().sql("""
                        SELECT content FROM source_chunk
                        WHERE document_id = :id
                        ORDER BY chunk_no
                        """)
                .param("id", documentId).query(String.class).list();
        List<String> normalizedContents = db().sql("""
                        SELECT normalized_content FROM source_chunk
                        WHERE document_id = :id
                        ORDER BY chunk_no
                        """)
                .param("id", documentId).query(String.class).list();
        List<String> contentHashes = db().sql("""
                        SELECT content_hash FROM source_chunk
                        WHERE document_id = :id
                        ORDER BY chunk_no
                        """)
                .param("id", documentId).query(String.class).list();

        assertThat(documentContent).contains("Café 第一頁內容。", "第二頁內容。");
        assertThat(documentContent).doesNotContain("文件標頭", "文件頁尾");
        assertThat(originalContents).allSatisfy(content ->
                assertThat(content).isNotBlank());
        assertThat(String.join("\n", originalContents)).contains("文件標頭", "文件頁尾");
        assertThat(normalizedContents).allSatisfy(content ->
                assertThat(content).doesNotContain("文件標頭", "文件頁尾"));
        assertThat(normalizedContents.stream().map(String::strip).toList())
                .containsExactly("# 總覽\n\nCafé 第一頁內容。", "第二頁內容。");
        assertThat(List.of(documentContent.split("\\f", -1)).stream().map(String::strip).toList())
                .isEqualTo(normalizedContents.stream().map(String::strip).toList());
        assertThat(contentHashes).zipSatisfy(normalizedContents,
                (hash, content) -> assertThat(hash).isEqualTo(sha256(content)));

        assertThat(db().sql("""
                        SELECT group_concat(chunk_no || ':' || page_no || ':' || section || ':' || heading_path, ',')
                        FROM (
                            SELECT chunk_no, page_no, section, heading_path FROM source_chunk
                            WHERE document_id = :id ORDER BY chunk_no
                        )
                        """)
                .param("id", documentId).query(String.class).single())
                .isEqualTo("1:1:總覽:總覽,2:2:總覽:總覽");
    }

    private long upload(String fileName, String content) throws Exception {
        return upload(fileName, "text/markdown", content);
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
        Path root = Path.of("target/test-data/source-chunk-root-" + UUID.randomUUID()).toAbsolutePath();
        mockMvc.perform(post("/api/v1/workspaces")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "Source Chunk Test", "rootPath": "%s"}
                                """.formatted(root)))
                .andExpect(status().isCreated());
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MultiPageParserConfiguration {

        @Bean
        DocumentParser multiPageDocumentParser() {
            return new DocumentParser() {
                @Override
                public boolean supportsMimeType(String mimeType) {
                    return "application/x-test-multipage".equals(mimeType);
                }

                @Override
                public boolean supportsExtension(String extension) {
                    return "pages".equals(extension);
                }

                @Override
                public ParsedDocument parse(Path source) {
                    return new ParsedDocument(MULTI_PAGE_CONTENT, Map.of());
                }
            };
        }
    }
}
