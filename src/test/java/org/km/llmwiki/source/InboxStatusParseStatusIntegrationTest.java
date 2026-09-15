package org.km.llmwiki.source;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Inbox dual-state contract (#451): {@code document.status} is the document lifecycle
 * while {@code document.parse_status} is the independent extraction lifecycle.
 * Extraction success must keep {@code status=PENDING} and set {@code parseStatus=PROCESSED};
 * {@code status=} filters only the lifecycle column and {@code parseStatus=} only the
 * extraction column.
 */
class InboxStatusParseStatusIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void extractionSuccessKeepsLifecycleAndExposesParseStatus() throws Exception {
        createWorkspace();
        long documentId = upload("test-note.md", "# hello\n\nworld");

        mockMvc.perform(get("/api/v1/inbox"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data[0].parseStatus").isEmpty());

        mockMvc.perform(post("/api/v1/documents/{documentId}/extract", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parseStatus").value("PROCESSED"));

        mockMvc.perform(get("/api/v1/inbox"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data[0].parseStatus").value("PROCESSED"));
    }

    @Test
    void lifecycleFilterAndExtractionFilterApplyToTheirOwnColumns() throws Exception {
        createWorkspace();
        long extractedId = upload("extracted.txt", "extract me");
        long plainId = upload("plain.txt", "leave me alone");

        mockMvc.perform(post("/api/v1/documents/{documentId}/extract", extractedId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parseStatus").value("PROCESSED"));

        // Lifecycle filter still sees both rows as PENDING.
        mockMvc.perform(get("/api/v1/inbox").param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        // PROCESSED was never written to document.status: lifecycle filter finds nothing.
        mockMvc.perform(get("/api/v1/inbox").param("status", "PROCESSED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.page.totalElements").value(0));

        // Extraction filter hits only the extracted row.
        mockMvc.perform(get("/api/v1/inbox").param("parseStatus", "PROCESSED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].documentId").value((int) extractedId));

        // Combined filters intersect on their own columns.
        mockMvc.perform(get("/api/v1/inbox").param("status", "PENDING").param("parseStatus", "PROCESSED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].documentId").value((int) extractedId));

        mockMvc.perform(get("/api/v1/inbox").param("status", "DUPLICATE").param("parseStatus", "PROCESSED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        if (plainId <= 0) {
            throw new AssertionError("expected positive document id, got " + plainId);
        }
    }

    @Test
    void parseFailureVariantsStayVisibleThroughExtractionFilter() throws Exception {
        createWorkspace();
        long unsupportedId = upload("archive.bin", "application/octet-stream", "not a supported document");

        mockMvc.perform(post("/api/v1/documents/{documentId}/extract", unsupportedId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parseStatus").value("UNSUPPORTED"));

        // The lifecycle status is untouched by the extraction outcome.
        mockMvc.perform(get("/api/v1/inbox"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data[0].parseStatus").value("UNSUPPORTED"));

        mockMvc.perform(get("/api/v1/inbox").param("parseStatus", "UNSUPPORTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        mockMvc.perform(get("/api/v1/inbox").param("parseStatus", "PROCESSED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void rejectsUnknownParseStatusFilter() throws Exception {
        createWorkspace();
        upload("note.txt", "hello");

        mockMvc.perform(get("/api/v1/inbox").param("parseStatus", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void extractedRowRemainsSoftDeletableUnderLifecycleContract() throws Exception {
        createWorkspace();
        long documentId = upload("deletable.txt", "extract then remove");

        mockMvc.perform(post("/api/v1/documents/{documentId}/extract", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parseStatus").value("PROCESSED"));

        // Extraction success keeps status=PENDING, which the delete authority allows.
        mockMvc.perform(delete("/api/v1/inbox/files/{id}", documentId))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/inbox"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    private long upload(String fileName, String content) throws Exception {
        return upload(fileName, "text/plain", content.getBytes(StandardCharsets.UTF_8));
    }

    private long upload(String fileName, String contentType, String content) throws Exception {
        return upload(fileName, contentType, content.getBytes(StandardCharsets.UTF_8));
    }

    private long upload(String fileName, String contentType, byte[] content) throws Exception {
        String response = mockMvc.perform(multipart("/api/v1/inbox/files")
                        .file(new MockMultipartFile("file", fileName, contentType, content)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(response.replaceAll(".*\"documentId\":(\\d+).*", "$1"));
    }

    private Path createWorkspace() throws Exception {
        Path root = Path.of("target/test-data/inbox-dual-state-" + UUID.randomUUID()).toAbsolutePath();
        mockMvc.perform(post("/api/v1/workspaces")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "Dual State Test", "rootPath": "%s"}
                                """.formatted(root)))
                .andExpect(status().isCreated());
        return root;
    }
}
