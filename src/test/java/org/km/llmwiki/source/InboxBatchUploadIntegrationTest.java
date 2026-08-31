package org.km.llmwiki.source;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("integration")
@SpringBootTest(properties = {
        "app.persistence.sqlite.path=target/test-data/batch-${random.uuid}/knowledge.db"
})
@AutoConfigureMockMvc
class InboxBatchUploadIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsBatchUploadWhenNoWorkspaceRegistered() throws Exception {
        mockMvc.perform(batch("a.txt", "b.txt"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NO_ACTIVE_WORKSPACE"));
    }

    @Test
    void rejectsEmptyBatch() throws Exception {
        mockMvc.perform(multipart("/api/v1/inbox/files/batch"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void uploadsMultipleFilesAndCreatesDocumentForEach() throws Exception {
        Path root = createWorkspace();

        mockMvc.perform(batch("alpha.txt", "beta.md", "gamma.pdf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.accepted").value(3))
                .andExpect(jsonPath("$.data.failed").value(0))
                .andExpect(jsonPath("$.data.duplicate").value(0))
                .andExpect(jsonPath("$.data.documents.length()").value(3))
                .andExpect(jsonPath("$.data.documents[0].fileName").value("alpha.txt"))
                .andExpect(jsonPath("$.data.documents[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data.documents[0].documentId").isNumber());

        assertThat(root.resolve("inbox").resolve("alpha.txt")).isRegularFile();
        assertThat(root.resolve("inbox").resolve("beta.md")).isRegularFile();
        assertThat(root.resolve("inbox").resolve("gamma.pdf")).isRegularFile();

        Integer documentRows = db().sql(
                        "SELECT COUNT(*) FROM document WHERE status = 'PENDING'")
                .query(Integer.class)
                .single();
        assertThat(documentRows).isEqualTo(3);

        Integer documentsWithHash = db().sql(
                        "SELECT COUNT(*) FROM document WHERE LENGTH(sha256) = 64")
                .query(Integer.class)
                .single();
        assertThat(documentsWithHash).isEqualTo(3);
    }

    @Test
    void isolatesIndividualFailuresWithoutBlockingOthers() throws Exception {
        Path root = createWorkspace();
        long documentsBefore = documentCount();

        mockMvc.perform(batchWithFiles(
                        file("good.txt"),
                        file(".."),
                        file("also-good.txt")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.accepted").value(2))
                .andExpect(jsonPath("$.data.failed").value(1))
                .andExpect(jsonPath("$.data.failures.length()").value(1))
                .andExpect(jsonPath("$.data.failures[0].fileName").value(".."));

        assertThat(root.resolve("inbox").resolve("good.txt")).isRegularFile();
        assertThat(root.resolve("inbox").resolve("also-good.txt")).isRegularFile();
        assertThat(documentCount()).isEqualTo(documentsBefore + 2);
    }

    private long documentCount() {
        return db().sql("SELECT COUNT(*) FROM document").query(Long.class).single();
    }

    private MockMultipartHttpServletRequestBuilder batch(String... filenames) {
        MockMultipartHttpServletRequestBuilder builder = multipart("/api/v1/inbox/files/batch");
        for (String filename : filenames) {
            builder.file(file(filename));
        }
        return builder;
    }

    private MockMultipartHttpServletRequestBuilder batchWithFiles(MockMultipartFile... files) {
        MockMultipartHttpServletRequestBuilder builder = multipart("/api/v1/inbox/files/batch");
        for (MockMultipartFile file : files) {
            builder.file(file);
        }
        return builder;
    }

    private static MockMultipartFile file(String filename) {
        return new MockMultipartFile("files", filename, "text/plain",
                ("content of " + filename).getBytes(StandardCharsets.UTF_8));
    }

    private Path createWorkspace() throws Exception {
        Path root = tempRoot();
        mockMvc.perform(post("/api/v1/workspaces")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "Batch Test", "rootPath": "%s"}
                                """.formatted(root)))
                .andExpect(status().isCreated());
        return root;
    }

    private Path activeRoot() {
        return Path.of(db().sql(
                        "SELECT root_path FROM workspace WHERE status = 'ACTIVE' ORDER BY id DESC LIMIT 1")
                .query(String.class)
                .single());
    }

    private static Path tempRoot() {
        return Path.of("target/test-data/batch-" + UUID.randomUUID()).toAbsolutePath();
    }
}
