package org.km.llmwiki.source;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
        assertThat(db().sql("SELECT COUNT(*) FROM source_fts WHERE document_id = :id")
                .param("id", documentId).query(Integer.class).single()).isEqualTo(1);
        assertThat(db().sql("SELECT status FROM source_search_index_sync WHERE document_id = :id")
                .param("id", documentId).query(String.class).single()).isEqualTo("SYNCED");
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
        Path root = createWorkspace();
        long documentId = upload("archive.bin", "application/octet-stream", "not a supported document");

        mockMvc.perform(post("/api/v1/documents/{documentId}/extract", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documentId").value(documentId))
                .andExpect(jsonPath("$.data.parseStatus").value("UNSUPPORTED"))
                .andExpect(jsonPath("$.data.chunkCount").value(0))
                .andExpect(jsonPath("$.data.errorCode").value("EXTRACTION_UNSUPPORTED_TYPE"))
                .andExpect(jsonPath("$.data.errorMessage").value("不支援此文件類型的文字抽取"));

        assertThat(db().sql("SELECT parse_status FROM document WHERE id = :id")
                .param("id", documentId).query(String.class).single()).isEqualTo("UNSUPPORTED");
        assertThat(db().sql("SELECT status FROM source_search_index_sync WHERE document_id = :id")
                .param("id", documentId).query(String.class).single()).isEqualTo("INELIGIBLE");
        assertThat(Files.readString(root.resolve("inbox/archive.bin"))).isEqualTo("not a supported document");

        mockMvc.perform(get("/api/v1/inbox"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].parseStatus").value("UNSUPPORTED"))
                .andExpect(jsonPath("$.data[0].errorCode").value("EXTRACTION_UNSUPPORTED_TYPE"))
                .andExpect(jsonPath("$.data[0].errorMessage").value("不支援此文件類型的文字抽取"));

        long supportedDocumentId = upload("note.txt", "text/plain", "仍可處理其他文件");
        mockMvc.perform(post("/api/v1/documents/{documentId}/extract", supportedDocumentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parseStatus").value("PROCESSED"));

        db().sql("UPDATE document SET mime_type = 'text/plain' WHERE id = :id")
                .param("id", documentId).update();
        mockMvc.perform(post("/api/v1/documents/{documentId}/extract", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parseStatus").value("PROCESSED"))
                .andExpect(jsonPath("$.data.errorCode").doesNotExist())
                .andExpect(jsonPath("$.data.errorMessage").doesNotExist());

        assertThat(db().sql("SELECT COUNT(*) FROM document WHERE id = :id AND error_code IS NULL")
                .param("id", documentId).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void marksTextlessPdfAsNeedOcrWithoutPersistingContentOrChunks() throws Exception {
        createWorkspace();
        long documentId = upload("scanned.pdf", "application/pdf", blankPdf());

        mockMvc.perform(post("/api/v1/documents/{documentId}/extract", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documentId").value(documentId))
                .andExpect(jsonPath("$.data.parseStatus").value("NEED_OCR"))
                .andExpect(jsonPath("$.data.chunkCount").value(0));

        mockMvc.perform(get("/api/v1/inbox"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].parseStatus").value("NEED_OCR"));

        assertThat(db().sql("SELECT parse_status FROM document WHERE id = :id")
                .param("id", documentId).query(String.class).single()).isEqualTo("NEED_OCR");
        assertThat(db().sql("SELECT error_code FROM document WHERE id = :id")
                .param("id", documentId).query(String.class).single()).isEqualTo("OCR_REQUIRED");
        assertThat(db().sql("SELECT COUNT(*) FROM document_extracted_content WHERE document_id = :id")
                .param("id", documentId).query(Integer.class).single()).isZero();
        assertThat(db().sql("SELECT COUNT(*) FROM source_chunk WHERE document_id = :id")
                .param("id", documentId).query(Integer.class).single()).isZero();
    }

    @Test
    void processesPdfWhenTextMeetsTheConfiguredQualityThreshold() throws Exception {
        createWorkspace();
        long documentId = upload("digital.pdf", "application/pdf", pdfWithText("x".repeat(50)));

        mockMvc.perform(post("/api/v1/documents/{documentId}/extract", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parseStatus").value("PROCESSED"))
                .andExpect(jsonPath("$.data.chunkCount").value(1));

        assertThat(db().sql("SELECT COUNT(*) FROM document_extracted_content WHERE document_id = :id")
                .param("id", documentId).query(Integer.class).single()).isEqualTo(1);
    }

    private long upload(String fileName, String content) throws Exception {
        return upload(fileName, "text/plain", content);
    }

    private long upload(String fileName, String contentType, String content) throws Exception {
        return upload(fileName, contentType, content.getBytes(StandardCharsets.UTF_8));
    }

    private long upload(String fileName, String contentType, byte[] content) throws Exception {
        String response = mockMvc.perform(multipart("/api/v1/inbox/files")
                        .file(new MockMultipartFile("file", fileName, contentType,
                                content)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(response.replaceAll(".*\\\"documentId\\\":(\\d+).*", "$1"));
    }

    private Path createWorkspace() throws Exception {
        Path root = Path.of("target/test-data/extraction-root-" + UUID.randomUUID()).toAbsolutePath();
        mockMvc.perform(post("/api/v1/workspaces")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "Extraction Test", "rootPath": "%s"}
                                """.formatted(root)))
                .andExpect(status().isCreated());
        return root;
    }

    private static byte[] blankPdf() throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] pdfWithText(String text) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(72, 720);
                stream.showText(text);
                stream.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
