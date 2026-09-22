package org.km.llmwiki.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.web.RetrievalInspectionResponse;
import org.km.llmwiki.search.FtsSearchIndexRepository;
import org.km.llmwiki.search.PublishedWikiIndexingService;
import org.km.llmwiki.search.SearchResult;
import org.km.llmwiki.search.SearchServingConsistencyGate;
import org.km.llmwiki.search.SourceChunkIndexingService;
import org.km.llmwiki.search.SourceIndexSyncStatus;
import org.km.llmwiki.search.WikiIndexSyncStatus;
import org.km.llmwiki.source.ChunkCurrentness;
import org.km.llmwiki.source.SourceLocator;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.km.llmwiki.web.PageResponse;
import org.km.llmwiki.wiki.WikiContentHash;
import org.km.llmwiki.wiki.WikiPageType;
import org.km.llmwiki.wiki.WikiPathContract;
import org.km.llmwiki.workspace.CreateWorkspaceRequest;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #583 的 TOOL_CONTRACT plane：使用真實 upload/extract/index 與 MCP executor 證明
 * synthetic fixture 可以產生 task corpus 所依賴的 observable。這不是模型 discoverability 證據。
 */
@Tag("integration")
class McpTaskEvaluationFixtureIntegrationTest extends IsolatedIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @Autowired
    WorkspaceService workspaces;

    @Autowired
    PublishedWikiIndexingService publishedWikiIndexingService;

    @Autowired
    FtsSearchIndexRepository ftsRepository;

    @Autowired
    SearchServingConsistencyGate searchServingConsistencyGate;

    @Autowired
    WikiPathContract wikiPathContract;

    @Autowired
    SourceChunkIndexingService sourceChunkIndexingService;

    @Autowired
    McpToolExecutor executor;

    @Autowired
    MockMvc mockMvc;

    @Test
    void fixtureExposesSearchLocatorGraphNoEvidenceAndProviderDisabledObservables()
            throws Exception {
        Fixture fixture = fixture("mcp-task-eval");
        long wikiPageId = writePublishedWiki(fixture, "mcp-alpha", "MCP Alpha",
                "alphamarker canonical fact Aurora code 42");
        assertThat(publishedWikiIndexingService.reindex(
                fixture.workspaceId(), wikiPageId).status())
                .isEqualTo(WikiIndexSyncStatus.SYNCED);

        SourceFixture source = uploadAndExtract(
                "mcp-eval-source.md",
                "betamarker sourcelocatormarker Beta color blue");

        McpToolResult status = executor.execute(
                McpCapabilityManifest.TOOL_STATUS, JSON.createObjectNode());
        assertThat(status.isError()).isFalse();

        McpToolResult wikiSearch = executor.execute(
                McpCapabilityManifest.TOOL_SEARCH,
                JSON.createObjectNode()
                        .put("query", "alphamarker")
                        .put("corpus", "WIKI"));
        assertThat(wikiSearch.isError()).isFalse();
        assertThat(searchResults(wikiSearch))
                .extracting(SearchResult::knowledgeId)
                .containsExactly("mcp-alpha");

        McpToolResult sourceSearch = executor.execute(
                McpCapabilityManifest.TOOL_SEARCH,
                JSON.createObjectNode()
                        .put("query", "sourcelocatormarker")
                        .put("corpus", "SOURCE"));
        assertThat(sourceSearch.isError()).isFalse();
        assertThat(searchResults(sourceSearch))
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.sourceChunkId()).isEqualTo(source.chunkId());
                    assertThat(result.documentId()).isEqualTo(source.documentId());
                });

        McpToolResult located = executor.execute(
                McpCapabilityManifest.TOOL_SOURCE_LOCATOR,
                JSON.createObjectNode().put("chunkId", source.chunkId()));
        assertThat(located.isError()).isFalse();
        assertThat((SourceLocator) located.payload())
                .satisfies(locator -> {
                    assertThat(locator.currentness()).isEqualTo(ChunkCurrentness.CURRENT);
                    assertThat(locator.preview()).contains("sourcelocatormarker");
                    assertThat(locator.documentId()).isEqualTo(source.documentId());
                });

        McpToolResult graphInspect = executor.execute(
                McpCapabilityManifest.TOOL_RETRIEVAL_INSPECT,
                JSON.createObjectNode()
                        .put("question", "alphamarker")
                        .put("mode", "HYBRID_GRAPH"));
        assertThat(graphInspect.isError()).isFalse();
        RetrievalInspectionResponse graph = (RetrievalInspectionResponse) graphInspect.payload();
        assertThat(graph.modalityDiagnostics().graph())
                .isIn("DISABLED", "UNAVAILABLE", "NOT_READY");
        assertThat(graph.finalEvidence()).isNotEmpty();

        McpToolResult noEvidence = executor.execute(
                McpCapabilityManifest.TOOL_RETRIEVAL_INSPECT,
                JSON.createObjectNode()
                        .put("question", "omegaabsentmarker")
                        .put("mode", "WIKI_ONLY"));
        assertThat(noEvidence.isError()).isFalse();
        assertThat(((RetrievalInspectionResponse) noEvidence.payload()).finalEvidence())
                .isEmpty();

        McpToolResult ask = executor.execute(
                McpCapabilityManifest.TOOL_ASK,
                JSON.createObjectNode()
                        .put("question", "Aurora code")
                        .put("retrievalMode", "WIKI_ONLY"));
        assertThat(ask.isError()).isTrue();
        assertThat(ask.errorCode())
                .isEqualTo(McpToolError.PROVIDER_CONFIGURATION_UNAVAILABLE);
    }

    @Test
    void removingTargetEvidenceMakesTheFormerSearchGoldFailClosed() throws Exception {
        fixture("mcp-task-eval-remove");
        SourceFixture source = uploadAndExtract(
                "mcp-eval-source-remove.md",
                "betamarker sourcelocatormarker Beta color blue");

        assertThat(searchResults(executor.execute(
                McpCapabilityManifest.TOOL_SEARCH,
                JSON.createObjectNode()
                        .put("query", "sourcelocatormarker")
                        .put("corpus", "SOURCE"))))
                .isNotEmpty();

        db().sql("DELETE FROM source_chunk WHERE id = :id")
                .param("id", source.chunkId())
                .update();

        McpToolResult afterRemoval = executor.execute(
                McpCapabilityManifest.TOOL_SEARCH,
                JSON.createObjectNode()
                        .put("query", "sourcelocatormarker")
                        .put("corpus", "SOURCE"));
        assertThat(afterRemoval.isError()).isFalse();
        assertThat(searchResults(afterRemoval)).isEmpty();

        McpToolResult locator = executor.execute(
                McpCapabilityManifest.TOOL_SOURCE_LOCATOR,
                JSON.createObjectNode().put("chunkId", source.chunkId()));
        assertThat(locator.isError()).isTrue();
        assertThat(locator.errorCode()).isEqualTo(McpToolError.NOT_FOUND);
    }

    @SuppressWarnings("unchecked")
    private List<SearchResult> searchResults(McpToolResult result) {
        PageResponse<List<SearchResult>> page =
                (PageResponse<List<SearchResult>>) result.payload();
        return page.data();
    }

    private record Fixture(long workspaceId, Path root) {
    }

    private record SourceFixture(long documentId, long chunkId) {
    }

    private Fixture fixture(String name) {
        Path root = temp.resolve(name);
        long workspaceId = workspaces.create(
                new CreateWorkspaceRequest(name, root.toString())).id();
        return new Fixture(workspaceId, root);
    }

    private long writePublishedWiki(Fixture fixture, String knowledgeId, String title, String body)
            throws Exception {
        String markdown = """
                ---
                id: "%s"
                title: "%s"
                type: "CONCEPT"
                status: "PUBLISHED"
                ---

                # %s

                %s
                """.formatted(knowledgeId, title, title, body);
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
        String hash = WikiContentHash.sha256(bytes);
        String logicalPath = wikiPathContract.resolveLogicalPath(WikiPageType.CONCEPT, title);
        Path target = fixture.root().resolve("vault").resolve(logicalPath);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);

        KeyHolder pageKey = new GeneratedKeyHolder();
        db().sql("""
                        INSERT INTO knowledge_page (workspace_id, knowledge_id, title, normalized_title,
                            type, markdown_path, status, content_hash, revision, created_at, updated_at,
                            published_at)
                        VALUES (:workspace, :knowledgeId, :title, :normalizedTitle, 'CONCEPT', :path,
                            'PUBLISHED', :hash, 1, '2026-09-22T00:00:00Z', '2026-09-22T00:00:00Z',
                            '2026-09-22T00:00:00Z')
                        """)
                .param("workspace", fixture.workspaceId())
                .param("knowledgeId", knowledgeId)
                .param("title", title)
                .param("normalizedTitle", title.toLowerCase())
                .param("path", logicalPath)
                .param("hash", hash)
                .update(pageKey);
        return pageKey.getKey().longValue();
    }

    private SourceFixture uploadAndExtract(String fileName, String content) throws Exception {
        String response = mockMvc.perform(multipart("/api/v1/inbox/files")
                        .file(new MockMultipartFile("file", fileName, "text/markdown",
                                content.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long documentId = Long.parseLong(
                response.replaceAll(".*\"documentId\":(\\d+).*", "$1"));

        mockMvc.perform(post("/api/v1/documents/{documentId}/extract", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parseStatus").value("PROCESSED"));

        Long chunkId = db().sql("""
                        SELECT id
                          FROM source_chunk
                         WHERE document_id = :document
                         ORDER BY chunk_no, id
                         LIMIT 1
                        """)
                .param("document", documentId)
                .query(Long.class)
                .single();
        long workspaceId = workspaces.findActiveWithoutValidation().orElseThrow().id();
        assertThat(sourceChunkIndexingService.reindexDocument(workspaceId, documentId).status())
                .isEqualTo(SourceIndexSyncStatus.SYNCED);

        String projected = db().sql("""
                        SELECT projected_content
                          FROM source_fts
                         WHERE document_id = :document
                        """)
                .param("document", documentId)
                .query(String.class)
                .single();
        assertThat(projected).contains("sourcelocatormarker");
        assertThat(ftsRepository.findMatchedSourceDocumentIds(
                workspaceId, "sourcelocatormarker", null))
                .containsExactly(documentId);
        assertThat(searchServingConsistencyGate.isDocumentFresh(workspaceId, documentId))
                .isTrue();

        return new SourceFixture(documentId, chunkId);
    }
}
