package org.km.llmwiki.source;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("integration")
@SpringBootTest(properties = {
        "app.persistence.sqlite.path=target/test-data/boundary-${random.uuid}/knowledge.db"
})
@AutoConfigureMockMvc
class InboxBoundaryConcurrencyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InboxFileService inboxFileService;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void rescanIgnoresSymlinksPointingOutsideInbox() throws Exception {
        Path root = createWorkspace();
        Path inbox = root.resolve("inbox");
        Files.writeString(inbox.resolve("real.txt"), "real content");

        Path outsideFile = Files.createTempFile(Path.of("target/test-data"), "outside-", ".txt");
        Files.writeString(outsideFile, "secret outside workspace");
        try {
            Path linkFile = inbox.resolve("link.txt");
            Files.createSymbolicLink(linkFile, outsideFile);
            assumeTrue(Files.isSymbolicLink(linkFile));

            Path externalDir = Files.createTempDirectory(Path.of("target/test-data"), "outside-dir-");
            Files.writeString(externalDir.resolve("nested.txt"), "nested secret");
            Path linkDir = inbox.resolve("linked-dir");
            Files.createSymbolicLink(linkDir, externalDir);
            assumeTrue(Files.isSymbolicLink(linkDir));

            mockMvc.perform(post("/api/v1/inbox/rescan"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.newDocuments").value(1));

            Integer linkRows = jdbcClient.sql(
                            "SELECT COUNT(*) FROM document WHERE file_name IN ('link.txt', 'nested.txt')")
                    .query(Integer.class)
                    .single();
            assertThat(linkRows).isZero();

            Integer secretContentRows = jdbcClient.sql(
                            "SELECT COUNT(*) FROM document WHERE sha256 = :sha256")
                    .param("sha256", sha256Of(outsideFile))
                    .query(Integer.class)
                    .single();
            assertThat(secretContentRows).isZero();
        } finally {
            Files.deleteIfExists(outsideFile);
        }
    }

    @Test
    void concurrentSameNameUploadsAllLandWithDistinctIntactFiles() throws Exception {
        createWorkspace();
        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<UploadedFileResponse>> futures = new ArrayList<>();

        for (int index = 0; index < threads; index++) {
            final String content = "payload-" + index;
            final MockMultipartFile file = new MockMultipartFile("file", "race.txt", "text/plain",
                    content.getBytes(StandardCharsets.UTF_8));
            futures.add(executor.submit(() -> inboxFileService.upload(file)));
        }

        Set<String> storedNames = new HashSet<>();
        for (int index = 0; index < threads; index++) {
            UploadedFileResponse response = futures.get(index).get();
            String expectedContent = "payload-" + index;

            assertThat(response.status()).isEqualTo("PENDING");

            String sourcePath = jdbcClient.sql("SELECT source_path FROM document WHERE id = :id")
                    .param("id", response.documentId())
                    .query(String.class)
                    .single();
            assertThat(sourcePath).startsWith("inbox/");

            String actualContent = Files.readString(activeRoot().resolve(sourcePath));
            assertThat(actualContent).isEqualTo(expectedContent);

            assertThat(storedNames.add(response.fileName()))
                    .as("stored names must be unique, got duplicate %s", response.fileName())
                    .isTrue();
        }

        long inboxFiles;
        try (var files = Files.list(activeRoot().resolve("inbox"))) {
            inboxFiles = files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().matches("race(\\.txt|-\\d+\\.txt)"))
                    .count();
        }
        assertThat(inboxFiles).isEqualTo(threads);

        executor.shutdownNow();
    }

    private String sha256Of(Path path) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private Path activeRoot() {
        return Path.of(jdbcClient.sql(
                        "SELECT root_path FROM workspace WHERE status = 'ACTIVE' ORDER BY id DESC LIMIT 1")
                .query(String.class)
                .single());
    }

    private Path createWorkspace() throws Exception {
        Path root = Path.of("target/test-data/boundary-root-" + UUID.randomUUID()).toAbsolutePath();
        mockMvc.perform(post("/api/v1/workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Boundary Test", "rootPath": "%s"}
                                """.formatted(root)))
                .andExpect(status().isCreated());
        return root;
    }
}
