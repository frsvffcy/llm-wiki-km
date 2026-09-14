package org.km.llmwiki.source;

import org.junit.jupiter.api.Test;
import org.km.llmwiki.search.CjkBigramProjector;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
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

/**
 * Production regression for #412: the versioned selected-Cf policy through the
 * real extraction → chunk → FTS → locator path (not string-transform only).
 */
class NormalizationPolicyExtractionIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SourceChunkRepository chunkRepository;

    @Autowired
    private NormalizationPolicyRegistry normalizationPolicies;

    @Test
    void newExtractionStampsV2StripsArtifactsAndRestoresCleanQueryMatching() throws Exception {
        createWorkspace();
        long documentId = upload("artifacts.md",
                "self­attention and key​word\n\n知​識管理 with a⁠glue\n");

        mockMvc.perform(post("/api/v1/documents/{documentId}/extract", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parseStatus").value("PROCESSED"))
                .andExpect(jsonPath("$.data.normalizationPolicyVersion")
                        .value("normalization-policy-v2-selected-cf-strip"));

        String extracted = db().sql(
                        "SELECT content FROM document_extracted_content WHERE document_id = :id")
                .param("id", documentId).query(String.class).single();
        assertThat(extracted).doesNotContain("­", "​", "⁠");
        assertThat(extracted).contains("selfattention", "keyword", "知識管理");
        assertThat(db().sql("SELECT normalization_policy_version FROM document_extracted_content"
                        + " WHERE document_id = :id")
                .param("id", documentId).query(String.class).single())
                .isEqualTo("normalization-policy-v2-selected-cf-strip");

        var chunks = chunkRepository.findByDocumentId(documentId);
        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.normalizationPolicyVersion())
                    .isEqualTo("normalization-policy-v2-selected-cf-strip");
            assertThat(chunk.normalizedContent()).doesNotContain("­", "​", "⁠");
        });
        String chunkContent = chunks.get(0).normalizedContent();
        assertThat(CjkBigramProjector.transform(chunkContent))
                .isEqualTo(CjkBigramProjector.transform(
                        chunkContent.replace("­", "").replace("​", "").replace("⁠", "")));

        // Production retrieval path: the clean query finds the stripped document.
        mockMvc.perform(get("/api/v1/search").param("query", "selfattention"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isNotEmpty());
        mockMvc.perform(get("/api/v1/search").param("query", "知識管理"))
                .andExpect(status().isOk());

        // Derived FTS projection follows the existing authority (synced, no fake health).
        assertThat(db().sql("SELECT status FROM source_search_index_sync WHERE document_id = :id")
                .param("id", documentId).query(String.class).single()).isEqualTo("SYNCED");
        mockMvc.perform(get("/api/v1/search/index/health").param("corpus", "SOURCE"))
                .andExpect(status().isOk());
    }

    @Test
    void historicalV1RowsAreStaleUntilExplicitReExtractionUpgradesThem() throws Exception {
        String workspaceResponse = createWorkspaceWithResponse();
        long workspaceId = Long.parseLong(workspaceResponse.replaceAll(".*\"id\":(\\d+).*", "$1"));
        long documentId = upload("legacy.md", "legacy self­attention body");

        mockMvc.perform(post("/api/v1/documents/{documentId}/extract", documentId))
                .andExpect(status().isOk());

        // Simulate a historical baseline row: bytes + version as written by V1.
        db().sql("UPDATE source_chunk SET normalization_policy_version = :version,"
                        + " normalized_content = :content, content_hash = :hash"
                        + " WHERE document_id = :id")
                .param("version", "normalization-policy-v1-current")
                .param("content", "legacy self­attention body")
                .param("hash", sha256("legacy self­attention body"))
                .param("id", documentId).update();
        db().sql("UPDATE document_extracted_content SET normalization_policy_version = :version,"
                        + " content = :content WHERE document_id = :id")
                .param("version", "normalization-policy-v1-current")
                .param("content", "legacy self­attention body")
                .param("id", documentId).update();

        // Mixed-policy fake-current is impossible: the stale hook fires on version alone,
        // even though hashes are self-consistent.
        assertThat(chunkRepository.findDocumentIdsWithStaleNormalizationPolicy(workspaceId,
                normalizationPolicies.activeVersion())).contains(documentId);

        String staleChunkId = db().sql(
                        "SELECT id FROM source_chunk WHERE document_id = :id ORDER BY chunk_no LIMIT 1")
                .param("id", documentId).query(String.class).single();

        // Explicit re-extraction is the only upgrade path; it rewrites bytes + version
        // atomically and retires the old chunk identity.
        mockMvc.perform(post("/api/v1/documents/{documentId}/extract", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.normalizationPolicyVersion")
                        .value(normalizationPolicies.activeVersion()));

        assertThat(chunkRepository.findDocumentIdsWithStaleNormalizationPolicy(workspaceId,
                normalizationPolicies.activeVersion())).isEmpty();
        assertThat(db().sql("SELECT content FROM document_extracted_content WHERE document_id = :id")
                .param("id", documentId).query(String.class).single())
                .doesNotContain("­");
        mockMvc.perform(get("/api/v1/source-chunks/{chunkId}", Long.parseLong(staleChunkId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SOURCE_CHUNK_NOT_FOUND"));
    }

    @Test
    void keepsSemanticCharactersAndExposesCurrentVersionThroughChunkContract() throws Exception {
        createWorkspace();
        String zwjEmoji = "👨‍💻";
        long documentId = upload("semantic.md",
                zwjEmoji + " keeps shaping می‌شود and bidi abc‮def");

        mockMvc.perform(post("/api/v1/documents/{documentId}/extract", documentId))
                .andExpect(status().isOk());

        var chunks = chunkRepository.findByDocumentId(documentId);
        assertThat(chunks).isNotEmpty();
        String joined = chunks.stream().map(SourceChunk::normalizedContent)
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(joined).contains(zwjEmoji, "می‌شود", "‮");

        mockMvc.perform(get("/api/v1/documents/{documentId}/chunks", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].normalizationPolicyVersion")
                        .value("normalization-policy-v2-selected-cf-strip"));

        long chunkId = chunks.get(0).id();
        mockMvc.perform(get("/api/v1/source-chunks/{chunkId}/locator", chunkId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentness").value("CURRENT"));
    }

    @Test
    void failedExtractionLeavesNoVersionContentMismatch() throws Exception {
        createWorkspace();
        long documentId = upload("unsupported.bin", "application/octet-stream", "binary");

        mockMvc.perform(post("/api/v1/documents/{documentId}/extract", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parseStatus").value("UNSUPPORTED"))
                .andExpect(jsonPath("$.data.normalizationPolicyVersion").isNotEmpty());

        assertThat(db().sql("SELECT COUNT(*) FROM source_chunk WHERE document_id = :id")
                .param("id", documentId).query(Integer.class).single()).isZero();
        assertThat(db().sql("SELECT COUNT(*) FROM document_extracted_content WHERE document_id = :id")
                .param("id", documentId).query(Integer.class).single()).isZero();
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
        return Long.parseLong(response.replaceAll(".*\"documentId\":(\\d+).*", "$1"));
    }

    private void createWorkspace() throws Exception {
        createWorkspaceWithResponse();
    }

    private String createWorkspaceWithResponse() throws Exception {
        Path root = Path.of("target/test-data/normalization-root-" + UUID.randomUUID()).toAbsolutePath();
        return mockMvc.perform(post("/api/v1/workspaces")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "Normalization Test", "rootPath": "%s"}
                                """.formatted(root)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private static String sha256(String content) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
