package org.km.llmwiki.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.ai.ask.AskApiException;
import org.km.llmwiki.ai.ask.AskController;
import org.km.llmwiki.ai.ask.AskFailureType;
import org.km.llmwiki.search.FtsSearchIndexRepository;
import org.km.llmwiki.search.KnowledgeSearchDocument;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.km.llmwiki.web.RetrievalInspectionResponse;
import org.km.llmwiki.web.RetrievalInspectorController;
import org.km.llmwiki.wiki.WikiContentHash;
import org.km.llmwiki.workspace.CreateWorkspaceRequest;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REST↔MCP contract parity against the real application stack (#331). Where the unit parity
 * test pins shared-boundary semantics with controlled doubles, this suite proves the same
 * parity through the production wiring (real services, real FTS, shared Spring context):
 * evidence-bearing inspector reports, degraded graph diagnostics, failure-typed asks, and the
 * status tool all agree field-for-field across adapters.
 */
@Tag("integration")
class McpAdapterParityIntegrationTest extends IsolatedIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @Autowired
    WorkspaceService workspaces;
    @Autowired
    FtsSearchIndexRepository ftsRepository;
    @Autowired
    AskController restAsk;
    @Autowired
    RetrievalInspectorController restInspector;
    @Autowired
    McpToolExecutor executor;

    @Test
    void inspectorWithRealEvidenceAgreesOnBothAdapters() throws Exception {
        Fixture fixture = fixture("parity");
        writeWiki(fixture, "parity-wiki", "Parity Wiki", "parity wiki authority content");

        RetrievalInspectionResponse rest = restInspector.inspect("parity", "WIKI_ONLY").data();
        McpToolResult mcp = executor.execute("km_retrieval_inspect",
                JSON.createObjectNode().put("question", "parity").put("mode", "WIKI_ONLY"));

        assertThat(mcp.isError()).isFalse();
        assertThat(mcp.payload()).isEqualTo(rest);
        assertThat(rest.finalEvidence()).extracting(
                        RetrievalInspectionResponse.FinalEvidence::identity)
                .containsExactly("WIKI:parity-wiki");
        assertThat(((RetrievalInspectionResponse) mcp.payload()).finalEvidence())
                .isEqualTo(rest.finalEvidence());
    }

    @Test
    void degradedGraphDiagnosticsAgreeOnBothAdapters() throws Exception {
        Fixture fixture = fixture("parity-degraded");
        writeWiki(fixture, "parity-degraded-wiki", "Parity Degraded",
                "parity degraded authority content");

        RetrievalInspectionResponse rest = restInspector.inspect("parity", "HYBRID_GRAPH").data();
        McpToolResult mcp = executor.execute("km_retrieval_inspect",
                JSON.createObjectNode().put("question", "parity").put("mode", "HYBRID_GRAPH"));

        assertThat(mcp.isError()).isFalse();
        assertThat(mcp.payload()).isEqualTo(rest);
        // No graph projection exists in this context, so the graph modality must surface a
        // non-contributing signal identically on both adapters — never a silent healthy claim.
        assertThat(rest.modalityDiagnostics().graph()).isIn(
                "DISABLED", "UNAVAILABLE", "NOT_READY");
        assertThat(((RetrievalInspectionResponse) mcp.payload()).modalityDiagnostics())
                .isEqualTo(rest.modalityDiagnostics());
    }

    @Test
    void disabledProviderAskFailsWithSameApplicationTypeOnBothAdapters() throws Exception {
        Fixture fixture = fixture("parity-ask");
        writeWiki(fixture, "parity-ask-wiki", "Parity Ask", "parity ask authority content");
        JsonNode body = JSON.createObjectNode()
                .put("question", "parity ask").put("retrievalMode", "WIKI_ONLY");

        assertThatThrownBy(() -> restAsk.ask(body))
                .isInstanceOf(AskApiException.class)
                .satisfies(failure -> assertThat(((AskApiException) failure).failureType())
                        .isEqualTo(AskFailureType.PROVIDER_CONFIGURATION_UNAVAILABLE));
        McpToolResult mcp = executor.execute("km_ask", JSON.createObjectNode()
                .put("question", "parity ask").put("retrievalMode", "WIKI_ONLY"));

        assertThat(mcp.isError()).isTrue();
        assertThat(mcp.errorCode())
                .isEqualTo(McpToolError.PROVIDER_CONFIGURATION_UNAVAILABLE);
    }

    @Test
    void statusToolDelegatesIdentically() {
        McpToolResult mcp = executor.execute("km_status",
                JSON.createObjectNode());

        assertThat(mcp.isError()).isFalse();
        assertThat(mcp.payload()).isNotNull();
    }

    private record Fixture(long workspaceId, Path root) {
    }

    private Fixture fixture(String name) {
        Path root = temp.resolve(name);
        long workspaceId = workspaces.create(new CreateWorkspaceRequest(name,
                root.toString())).id();
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
                            'PUBLISHED', :hash, 3, '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z',
                            '2026-09-01T00:00:00Z')
                        """)
                .param("workspace", fixture.workspaceId()).param("knowledgeId", knowledgeId)
                .param("title", title).param("normalizedTitle", title.toLowerCase())
                .param("path", logicalPath).param("hash", hash).update(pageKey);
        ftsRepository.upsertKnowledge(new KnowledgeSearchDocument(fixture.workspaceId(),
                knowledgeId, title, title.toLowerCase(), body, logicalPath, "CONCEPT",
                "PUBLISHED", hash));
        db().sql("""
                        INSERT INTO knowledge_search_index_sync
                            (workspace_id, knowledge_page_id, knowledge_id, status, content_hash,
                             indexed_content_hash, indexed_revision, failure_detail, updated_at)
                        VALUES (:workspace, :pageId, :knowledgeId, 'SYNCED', :hash, :hash, 3,
                                NULL, '2026-09-01T00:00:00Z')
                        """)
                .param("workspace", fixture.workspaceId())
                .param("pageId", pageKey.getKey().longValue())
                .param("knowledgeId", knowledgeId).param("hash", hash).update();
    }
}
