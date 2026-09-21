package org.km.llmwiki.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.search.FtsSearchIndexRepository;
import org.km.llmwiki.search.KnowledgeSearchDocument;
import org.km.llmwiki.search.SearchService;
import org.km.llmwiki.search.SourceSearchAuthorityRepository;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.km.llmwiki.wiki.PublishedWikiContentReader;
import org.km.llmwiki.wiki.PublishedWikiRepository;
import org.km.llmwiki.wiki.WikiContentHash;
import org.km.llmwiki.workspace.CreateWorkspaceRequest;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #579 regression: budget stop must not hide authority failures.
 *
 * <p>Before the fix, {@code RetrievalService#recordRemainingBudgetExclusions} marked every
 * remaining candidate as {@code BUDGET_EXCLUDED} without authority revalidation, so a stale or
 * ineligible candidate past the item/character window was misattributed as
 * {@code RANKING_WINDOW} instead of {@code AUTHORITY_REJECT}.
 */
class RetrievalBudgetAuthorityAttributionIntegrationTest extends IsolatedIntegrationTest {

    @TempDir Path temp;
    @Autowired WorkspaceService workspaces;
    @Autowired SearchService searchService;
    @Autowired FtsSearchIndexRepository ftsRepository;
    @Autowired SourceSearchAuthorityRepository sourceAuthority;
    @Autowired PublishedWikiRepository publishedWikiRepository;
    @Autowired PublishedWikiContentReader publishedWikiContentReader;
    @Autowired FusionRankingPolicyProvider fusionPolicy;

    @Test
    void itemBudgetStopMarksAuthorityInvalidAsRejected() throws Exception {
        WorkspaceFixture ws = workspace("budget-authority-reject");
        RetrievalInspectorService inspector = inspector();
        wiki(ws, "budget-a-keep", "Budget A Keep", "budgetneedle shared evidence");
        wiki(ws, "budget-z-stale", "Budget Z Stale", "budgetneedle shared evidence");
        mutateVault(ws, "budget-z-stale");

        RetrievalRequest request =
                new RetrievalRequest("budgetneedle", RetrievalMode.HYBRID_FTS, 1, 20_000);
        RetrievalInspectionReport report = inspector.inspect(request);

        RetrievalInspectionTrace.SelectionTrace stale = report.selection().stream()
                .filter(item -> item.identity().equals("WIKI:budget-z-stale"))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("missing stale selection trace"));
        assertThat(stale.disposition())
                .isEqualTo(RetrievalInspectionTrace.Disposition.REJECTED);
        assertThat(stale.reasonCode()).isEqualTo("INELIGIBLE");

        RetrievalInspectionTrace.SelectionTrace keep = report.selection().stream()
                .filter(item -> item.identity().equals("WIKI:budget-a-keep"))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("missing keep selection trace"));
        assertThat(keep.disposition())
                .isEqualTo(RetrievalInspectionTrace.Disposition.SELECTED);
    }

    @Test
    void itemBudgetStopKeepsAuthorityValidAsBudgetExcluded() throws Exception {
        WorkspaceFixture ws = workspace("budget-window-valid");
        RetrievalInspectorService inspector = inspector();
        wiki(ws, "window-a", "Window A", "validwindowneedle shared evidence");
        wiki(ws, "window-z", "Window Z", "validwindowneedle shared evidence");

        RetrievalRequest request =
                new RetrievalRequest("validwindowneedle", RetrievalMode.HYBRID_FTS, 1, 20_000);
        RetrievalInspectionReport report = inspector.inspect(request);

        RetrievalInspectionTrace.SelectionTrace second = report.selection().stream()
                .filter(item -> item.identity().equals("WIKI:window-z"))
                .reduce((first, secondTrace) -> secondTrace)
                .orElseThrow(() -> new AssertionError("missing window-z selection trace"));
        assertThat(second.disposition())
                .isEqualTo(RetrievalInspectionTrace.Disposition.BUDGET_EXCLUDED);
    }

    @Test
    void characterBudgetStopMarksAuthorityInvalidAsRejected() throws Exception {
        WorkspaceFixture ws = workspace("char-budget-authority-reject");
        RetrievalInspectorService inspector = inspector();
        wiki(ws, "char-a-keep", "Char A Keep", "charbudgetneedle shared evidence content");
        wiki(ws, "char-z-stale", "Char Z Stale", "charbudgetneedle shared evidence content");
        mutateVault(ws, "char-z-stale");

        // Tiny character window: first valid evidence truncates, second must still be
        // authority-checked on the same contract as the item-budget stop.
        RetrievalRequest request =
                new RetrievalRequest("charbudgetneedle", RetrievalMode.HYBRID_FTS, 8, 10);
        RetrievalInspectionReport report = inspector.inspect(request);

        RetrievalInspectionTrace.SelectionTrace stale = report.selection().stream()
                .filter(item -> item.identity().equals("WIKI:char-z-stale"))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("missing char stale selection trace"));
        assertThat(stale.disposition())
                .isEqualTo(RetrievalInspectionTrace.Disposition.REJECTED);
        assertThat(stale.reasonCode()).isEqualTo("INELIGIBLE");
    }

    @Test
    void characterBudgetStopKeepsAuthorityValidAsBudgetExcluded() throws Exception {
        WorkspaceFixture ws = workspace("char-budget-window-valid");
        RetrievalInspectorService inspector = inspector();
        wiki(ws, "char-win-a", "Char Win A", "charvalidneedle shared evidence content");
        wiki(ws, "char-win-z", "Char Win Z", "charvalidneedle shared evidence content");

        RetrievalRequest request =
                new RetrievalRequest("charvalidneedle", RetrievalMode.HYBRID_FTS, 8, 10);
        RetrievalInspectionReport report = inspector.inspect(request);

        RetrievalInspectionTrace.SelectionTrace second = report.selection().stream()
                .filter(item -> item.identity().equals("WIKI:char-win-z"))
                .reduce((first, secondTrace) -> secondTrace)
                .orElseThrow(() -> new AssertionError("missing char-win-z selection trace"));
        assertThat(second.disposition())
                .isEqualTo(RetrievalInspectionTrace.Disposition.BUDGET_EXCLUDED);
    }

    @Test
    void inspectorAttributionDoesNotChangeProductionSelection() throws Exception {
        WorkspaceFixture ws = workspace("budget-production-parity");
        wiki(ws, "parity-a-keep", "Parity A Keep", "parityneedle shared evidence");
        wiki(ws, "parity-z-stale", "Parity Z Stale", "parityneedle shared evidence");
        mutateVault(ws, "parity-z-stale");

        RetrievalService retrieval = new RetrievalService(workspaces, searchService,
                publishedWikiRepository, publishedWikiContentReader, sourceAuthority,
                null, new ReciprocalRankFusion(), null);
        RetrievalRequest request =
                new RetrievalRequest("parityneedle", RetrievalMode.HYBRID_FTS, 1, 20_000);

        EvidenceBundle withoutCollector = retrieval.retrieve(request);
        RetrievalInspectionCollector collector = new RetrievalInspectionCollector();
        EvidenceBundle withCollector = retrieval.retrieve(request, collector);

        // Production selection/order/budget identical with and without the Inspector collector.
        assertThat(withCollector.items().stream().map(EvidenceItem::stableIdentity).toList())
                .containsExactlyElementsOf(withoutCollector.items().stream()
                        .map(EvidenceItem::stableIdentity).toList());
        assertThat(withCollector.items()).hasSize(1);
        assertThat(withCollector.items().get(0).stableIdentity())
                .isEqualTo("WIKI:parity-a-keep");
        assertThat(withCollector.budget().truncated()).isTrue();
        assertThat(withCollector.budget()).isEqualTo(withoutCollector.budget());
        assertThat(withCollector.rejectedCandidateCount())
                .isEqualTo(withoutCollector.rejectedCandidateCount());

        // Inspector trace still carries the corrected authority attribution.
        List<RetrievalInspectionTrace.SelectionTrace> selection = collector.toTrace().selection();
        RetrievalInspectionTrace.SelectionTrace stale = selection.stream()
                .filter(item -> item.identity().equals("WIKI:parity-z-stale"))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("missing parity stale trace"));
        assertThat(stale.disposition())
                .isEqualTo(RetrievalInspectionTrace.Disposition.REJECTED);
        assertThat(stale.reasonCode()).isEqualTo("INELIGIBLE");
        // Silence unused-workspace warning: workspace id is the authority scope for both bundles.
        assertThat(withCollector.workspace().id()).isEqualTo(ws.id());
    }

    private RetrievalInspectorService inspector() {
        RetrievalService retrieval = new RetrievalService(workspaces, searchService,
                publishedWikiRepository, publishedWikiContentReader, sourceAuthority,
                null, new ReciprocalRankFusion(), null);
        return new RetrievalInspectorService(retrieval, fusionPolicy);
    }

    private WorkspaceFixture workspace(String name) {
        Path root = temp.resolve(name);
        return new WorkspaceFixture(workspaces.create(
                new CreateWorkspaceRequest(name, root.toString())).id(), root);
    }

    private void wiki(WorkspaceFixture ws, String knowledgeId, String title, String body)
            throws Exception {
        String markdown = "---\nid: \"%s\"\ntitle: \"%s\"\ntype: \"CONCEPT\"\nstatus: \"PUBLISHED\"\n---\n\n# %s\n\n%s\n"
                .formatted(knowledgeId, title, title, body);
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
        String hash = WikiContentHash.sha256(bytes);
        String logicalPath = "vault/concepts/" + knowledgeId + ".md";
        Path target = ws.root().resolve(logicalPath);
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
                        """).param("workspace", ws.id()).param("knowledgeId", knowledgeId)
                .param("title", title).param("normalizedTitle", title.toLowerCase())
                .param("path", logicalPath).param("hash", hash).update(pageKey);
        ftsRepository.upsertKnowledge(new KnowledgeSearchDocument(ws.id(), knowledgeId, title,
                title.toLowerCase(), body, logicalPath, "CONCEPT", "PUBLISHED", hash));
        db().sql("""
                        INSERT INTO knowledge_search_index_sync
                            (workspace_id, knowledge_page_id, knowledge_id, status, content_hash,
                             indexed_content_hash, indexed_revision, failure_detail, updated_at)
                        VALUES (:workspace, :pageId, :knowledgeId, 'SYNCED', :hash, :hash, 3,
                                NULL, '2026-09-01T00:00:00Z')
                        """).param("workspace", ws.id())
                .param("pageId", pageKey.getKey().longValue()).param("knowledgeId", knowledgeId)
                .param("hash", hash).update();
    }

    private void mutateVault(WorkspaceFixture ws, String knowledgeId) throws Exception {
        String path = db().sql("SELECT markdown_path FROM knowledge_page "
                        + "WHERE workspace_id = :workspace AND knowledge_id = :knowledgeId")
                .param("workspace", ws.id()).param("knowledgeId", knowledgeId)
                .query(String.class).single();
        Files.writeString(ws.root().resolve(path), "manual vault drift", StandardCharsets.UTF_8);
    }

    record WorkspaceFixture(long id, Path root) { }
}
