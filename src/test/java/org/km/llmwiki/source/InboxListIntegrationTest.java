package org.km.llmwiki.source;

import org.junit.jupiter.api.Test;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.persistence.sqlite.path=target/test-data/list-${random.uuid}/knowledge.db"
})
@AutoConfigureMockMvc
class InboxListIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsEmptyCollectionWhenInboxHasNoDocuments() throws Exception {
        createWorkspace();

        mockMvc.perform(get("/api/v1/inbox"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$.page.totalPages").value(0));
    }

    @Test
    void rejectsListWhenNoWorkspaceRegistered() throws Exception {
        mockMvc.perform(get("/api/v1/inbox"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NO_ACTIVE_WORKSPACE"));
    }

    @Test
    void listsDocumentsWithNormalizedExtensionMetadata() throws Exception {
        createWorkspace();
        upload("Report.PDF");
        upload("notes.txt");

        mockMvc.perform(get("/api/v1/inbox"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.data[?(@.fileName == 'Report.PDF')].extension").value("pdf"))
                .andExpect(jsonPath("$.data[?(@.fileName == 'Report.PDF')].status").value("PENDING"))
                .andExpect(jsonPath("$.data[?(@.fileName == 'notes.txt')].extension").value("txt"));
    }

    @Test
    void preservesOriginalFileNameAfterCollisionRename() throws Exception {
        createWorkspace();
        upload("dup.txt");
        upload("dup.txt");

        mockMvc.perform(get("/api/v1/inbox").param("sort", "fileName,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].fileName").value("dup-1.txt"))
                .andExpect(jsonPath("$.data[0].originalFileName").value("dup.txt"))
                .andExpect(jsonPath("$.data[1].fileName").value("dup.txt"))
                .andExpect(jsonPath("$.data[1].originalFileName").value("dup.txt"))
                .andExpect(jsonPath("$.data[1].extension").value("txt"));
    }

    @Test
    void paginatesResults() throws Exception {
        createWorkspace();
        upload("f1.txt");
        upload("f2.txt");
        upload("f3.txt");

        mockMvc.perform(get("/api/v1/inbox")
                        .param("page", "0").param("size", "2").param("sort", "fileName,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].fileName").value("f1.txt"))
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.page.totalPages").value(2));

        mockMvc.perform(get("/api/v1/inbox")
                        .param("page", "1").param("size", "2").param("sort", "fileName,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].fileName").value("f3.txt"));
    }

    @Test
    void filtersByStatus() throws Exception {
        Path root = createWorkspace();
        Files.writeString(root.resolve("inbox").resolve("base.txt"), "shared");
        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(status().isOk());
        Files.writeString(root.resolve("inbox").resolve("copy.txt"), "shared");
        mockMvc.perform(post("/api/v1/inbox/rescan"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/inbox").param("status", "DUPLICATE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].fileName").value("copy.txt"))
                .andExpect(jsonPath("$.data[0].status").value("DUPLICATE"));

        mockMvc.perform(get("/api/v1/inbox").param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].fileName").value("base.txt"));

        Integer total = db().sql("SELECT COUNT(*) FROM document WHERE status <> 'DELETED'")
                .query(Integer.class)
                .single();
        assertThat(total).isEqualTo(2);
    }

    @Test
    void filtersByExtensionIgnoringDotAndCase() throws Exception {
        createWorkspace();
        upload("doc.pdf");
        upload("sheet.TXT");
        upload("image.png");

        mockMvc.perform(get("/api/v1/inbox").param("extension", ".PDF"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].fileName").value("doc.pdf"));

        mockMvc.perform(get("/api/v1/inbox").param("extension", "txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].fileName").value("sheet.TXT"));

        mockMvc.perform(get("/api/v1/inbox").param("extension", "md"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    void sortsByRequestedField() throws Exception {
        createWorkspace();
        upload("small.txt");
        upload("larger-content-file.txt");

        mockMvc.perform(get("/api/v1/inbox").param("sort", "fileSize,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].fileName").value("larger-content-file.txt"));

        mockMvc.perform(get("/api/v1/inbox").param("sort", "fileName,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].fileName").value("larger-content-file.txt"));
    }

    @Test
    void rejectsInvalidFiltersAndPagination() throws Exception {
        createWorkspace();

        mockMvc.perform(get("/api/v1/inbox").param("status", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        mockMvc.perform(get("/api/v1/inbox").param("sort", "sha256,asc"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/inbox").param("page", "-1"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/inbox").param("size", "500"))
                .andExpect(status().isBadRequest());
    }

    private MockHttpServletRequestBuilder get(String url) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url);
    }

    private void upload(String filename) throws Exception {
        mockMvc.perform(multipart("/api/v1/inbox/files")
                        .file(new MockMultipartFile("file", filename, "text/plain",
                                ("content of " + filename).getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isCreated());
    }

    private Path createWorkspace() throws Exception {
        Path root = Path.of("target/test-data/list-root-" + UUID.randomUUID()).toAbsolutePath();
        mockMvc.perform(post("/api/v1/workspaces")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "List Test", "rootPath": "%s"}
                                """.formatted(root)))
                .andExpect(status().isCreated());
        return root;
    }
}
