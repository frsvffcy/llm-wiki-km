package org.km.llmwiki.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.rag.RetrievalInspectionResponse;
import org.km.llmwiki.search.FtsSearchIndexRepository;
import org.km.llmwiki.search.KnowledgeSearchDocument;
import org.km.llmwiki.search.SearchResult;
import org.km.llmwiki.search.SourceChunkIndexingService;
import org.km.llmwiki.search.SourceIndexSyncStatus;
import org.km.llmwiki.source.ChunkCurrentness;
import org.km.llmwiki.source.SourceLocator;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.km.llmwiki.web.PageResponse;
import org.km.llmwiki.wiki.WikiContentHash;
import org.km.llmwiki.workspace.CreateWorkspaceRequest;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #583 的 TOOL_CONTRACT plane：以真實 Spring/SQLite/FTS/MCP executor 證明 synthetic fixture
 * 可以產生 task corpus 所依賴的 observable。這不是模型 discoverability 證據。
 */
@Tag("integration")
class McpTaskEvaluationFixtureIntegrationTest extends IsolatedIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @Autowired
    WorkspaceService workspaces;

    @Autowired
    FtsSearchIndexRepository ftsRepository;

    @Autowired
    SourceChunkIndexingService sourceIndexingService;

    @Autowired
    McpToolExecutor executor;

    @Test
    void fixtureExposesSearchLocatorGraphNoEvidenceAndProviderDisabledObservables()
            throws Exception {
        Fixture fixture = fixture("mcp-task-eval");
        writeWiki(fixture, "mcp-alpha", "MCP Alpha",
                "alpha-marker canonical fact Aurora code 42");
        SourceFixture source = writeSource(fixture,
                "beta-marker source-locator-marker Beta color blue");

        assertThat(sourceIndexingService.reindexDocument(
                fixture.workspaceId(), source.documentId()).status())
                .isEqualTo(SourceIndexSyncStatus.SYNCED);

        McpToolResult status = executor.execute(
                McpCapabilityManifest.TOOL_STATUS, JSON.createObjectNode());
        assertThat(status.isError()).isFalse();

        McpToolResult wikiSearch = executor.execute(
                McpCapabilityManifest.TOOL_SEARCH,
                JSON.createObjectNode()
                        .put("query", "alpha-marker")
                        .put("corpus", "WIKI"));
        assertThat(wikiSearch.isError()).isFalse();
        assertThat(searchResults(wikiSearch))
                .extracting(SearchResult::knowledgeId)
                .containsExactly("mcp-alpha");

        McpToolResult sourceSearch = executor.execute(
                McpCapabilityManifest.TOOL_SEARCH,
                JSON.createObjectNode()
                        .put("query", "source-locator-marker")
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
                    assertThat(locator.preview()).contains("source-locator-marker");
                    assertThat(locator.documentId()).isEqualTo(source.documentId());
                });

        McpToolResult graphInspect = executor.execute(
                McpCapabilityManifest.TOOL_RETRIEVAL_INSPECT,
                JSON.createObjectNode()
                        .put("question", "alpha-marker")
                        .put("mode", "HYBRID_GRAPH"));
        assertThat(graphInspect.isError()).isFalse();
        RetrievalInspectionResponse graph = (RetrievalInspectionResponse) graphInspect.payload();
        assertThat(graph.modalityDiagnostics().graph())
                .isIn("DISABLED", "UNAVAILABLE", "NOT_READY");
        assertThat(graph.finalEvidence()).isNotEmpty();

        McpToolResult noEvidence = executor.execute(
                McpCapabilityManifest.TOOL_RETRIEVAL_INSPECT,
                JSON.createObjectNode()
                        .put("question", "omega-absent-marker")
                        .put("mode", "WIKI_ONLY"));
        assertThat(noEvidence.isError()).isFalse();
        assertThat(((RetrievalInspectionResponse) noEvidence.payload()).finalEvidence())
                .isEmpty();

        McpToolResult ask = executor.execute(
                McpCapabilityManifest.TOOL_ASK,
                JSON.createObjectNode()
                        .put("question", "What is the Aurora code?")
                        .put("retrievalMode", "WIKI_ONLY"));
        assertThat(ask.isError()).isTrue();
        assertThat(ask.errorCode())
                .isEqualTo(McpToolError.PROVIDER_CONFIGURATION_UNAVAILABLE);
    }

    @Test
    void removingTargetEvidenceMakesTheFormerSearchGoldFailClosed() throws Exception {
        Fixture fixture = fixture("mcp-task-eval-remove");
        SourceFixture source = writeSource(fixture,
                "beta-marker source-locator-marker Beta color blue");
        assertThat(sourceIndexingService.reindexDocument(
                fixture.workspaceId(), source.documentId()).status())
                .isEqualTo(SourceIndexSyncStatus.SYNCED);

        assertThat(searchResults(executor.execute(
                McpCapabilityManifest.TOOL_SEARCH,
                JSON.createObjectNode()
                        .put("query", "source-locator-marker")
                        .put("corpus", "SOURCE"))))
                .isNotEmpty();

        db().sql("DELETE FROM source_chunk WHERE id = :id")
                .param("id", source.chunkId())
                .update();

        McpToolResult afterRemoval = executor.execute(
                McpCapabilityManifest.TOOL_SEARCH,
                JSON.createObjectNode()
                        .put("query", "source-locator-marker")
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

    private void writeWiki(Fixture fixture, String knowledgeId, String title, String body)
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
        String logicalPath = "vault/concepts/" + title.toLowerCase().replace(' ', '-') + ".md";
        Path target = fixture.root().resolve(logicalPath);
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
        ftsRepository.upsertKnowledge(new KnowledgeSearchDocument(
                fixture.workspaceId(), knowledgeId, title, title.toLowerCase(),
                body, logicalPath, "CONCEPT", "PUBLISHED", hash));
        db().sql("""
                        INSERT INTO knowledge_search_index_sync
                            (workspace_id, knowledge_page_id, knowledge_id, status, content_hash,
                             indexed_content_hash, indexed_revision, failure_detail, updated_at)
                        VALUES (:workspace, :pageId, :knowledgeId, 'SYNCED', :hash, :hash, 1,
                                NULL, '2026-09-22T00:00:00Z')
                        """)
                .param("workspace", fixture.workspaceId())
                .param("pageId", pageKey.getKey().longValue())
                .param("knowledgeId", knowledgeId)
                .param("hash", hash)
                .update();
    }

    private SourceFixture writeSource(Fixture fixture, String normalized) throws Exception {
        KeyHolder documentKey = new GeneratedKeyHolder();
        db().sql("""
                        INSERT INTO document (workspace_id, file_name, source_path, sha256, status,
                            parse_status, created_at, updated_at)
                        VALUES (:workspace, 'mcp-eval-source.md', 'archive/mcp-eval-source.md',
                            :documentHash, 'PENDING', 'PROCESSED',
                            '2026-09-22T00:00:00Z', '2026-09-22T00:00:00Z')
                        """)
                .param("workspace", fixture.workspaceId())
                .param("documentHash", sha256("mcp-eval-source.md"))
                .update(documentKey);
        long documentId = documentKey.getKey().longValue();

        KeyHolder chunkKey = new GeneratedKeyHolder();
        db().sql("""
                        INSERT INTO source_chunk (document_id, chunk_no, page_no, section, heading_path,
                            content, normalized_content, content_hash, created_at, updated_at)
                        VALUES (:document, 1, 1, 'MCP evaluation', 'Fixture > MCP evaluation',
                            :content, :content, :hash,
                            '2026-09-22T00:00:00Z', '2026-09-22T00:00:00Z')
                        """)
                .param("document", documentId)
                .param("content", normalized)
                .param("hash", sha256(normalized))
                .update(chunkKey);
        return new SourceFixture(documentId, chunkKey.getKey().longValue());
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
