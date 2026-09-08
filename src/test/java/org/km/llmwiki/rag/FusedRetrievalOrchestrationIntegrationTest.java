package org.km.llmwiki.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.graph.GraphCanonicalCurrentness;
import org.km.llmwiki.graph.GraphProjectionBackendFactory;
import org.km.llmwiki.graph.GraphProjectionInput;
import org.km.llmwiki.graph.GraphProjectionInputAssembler;
import org.km.llmwiki.graph.GraphProjectionLifecycleRepository;
import org.km.llmwiki.graph.GraphProjectionLifecycleService;
import org.km.llmwiki.graph.GraphProjectionReadinessReader;
import org.km.llmwiki.graph.GraphProjectionSnapshot;
import org.km.llmwiki.graph.GraphProjectionVersion;
import org.km.llmwiki.graph.GraphTraversalService;
import org.km.llmwiki.graph.GraphWorkspaceScope;
import org.km.llmwiki.persistence.graph.arcadedb.ArcadeDbGraphProjectionBackendFactory;
import org.km.llmwiki.search.FtsSearchIndexRepository;
import org.km.llmwiki.search.KnowledgeSearchDocument;
import org.km.llmwiki.search.SearchService;
import org.km.llmwiki.search.SourceChunkIndexingService;
import org.km.llmwiki.search.SourceSearchAuthorityRepository;
import org.km.llmwiki.search.vector.VectorCandidateSearchService;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.km.llmwiki.wiki.PublishedWikiContentReader;
import org.km.llmwiki.wiki.PublishedWikiRepository;
import org.km.llmwiki.wiki.WikiContentHash;
import org.km.llmwiki.wiki.WikiPageType;
import org.km.llmwiki.wiki.WikiPathContract;
import org.km.llmwiki.workspace.CreateWorkspaceRequest;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Production evidence for the graph-grounded Ask retrieval orchestration: real FTS serving,
 * real ArcadeDB projection with STORY-807 admission, deterministic fusion, and the last-mile
 * Ask handoff currentness guard. Drift between fusion publication and the Ask handoff is
 * reproduced with call-count barriers around the production wiki repository and the projection
 * lifecycle reader — no sleep, no timing luck.
 */
class FusedRetrievalOrchestrationIntegrationTest extends IsolatedIntegrationTest {

    private static final GraphProjectionVersion VERSION = GraphProjectionVersion.initial();

    @TempDir
    Path temp;

    @Autowired GraphProjectionInputAssembler assembler;
    @Autowired GraphCanonicalCurrentness currentness;
    @Autowired GraphProjectionLifecycleRepository repository;
    @Autowired WorkspaceService workspaces;
    @Autowired WikiPathContract paths;
    @Autowired PublishedWikiRepository publishedWikiRepository;
    @Autowired PublishedWikiContentReader publishedWikiContentReader;
    @Autowired SourceSearchAuthorityRepository sourceAuthorityRepository;
    @Autowired SearchService searchService;
    @Autowired VectorCandidateSearchService vectorCandidateSearchService;
    @Autowired FtsSearchIndexRepository ftsRepository;
    @Autowired SourceChunkIndexingService sourceChunkIndexingService;
    @Autowired FusedRetrievalOrchestrator disabledContextOrchestrator;

    @Test
    void graphGroundedAskRetrievalProducesAnAuthoritativeBundleFromTheProductionProjection()
            throws Exception {
        GraphWorkspaceScope workspace = workspace("orchestration");
        // The linked target deliberately does not match the lexical query: only the graph
        // channel can discover it, and the bundle must carry it with canonical identity.
        wiki(workspace, "wiki-alpha-seed", "Alpha Seed", "alpha seed authority [[Linked Goal|目標]]");
        wiki(workspace, "wiki-linked-goal", "Linked Goal", "beta unrelated");

        try (var lifecycle = lifecycle(factory(temp.resolve("orchestration")))) {
            lifecycle.rebuild(assembler.assemble(workspace));
            FusedEvidenceService fusion = fusion(lifecycle,
                    factory(temp.resolve("orchestration")));
            FusedRetrievalOrchestrator orchestrator = new FusedRetrievalOrchestrator(fusion,
                    publishedWikiRepository, publishedWikiContentReader, sourceAuthorityRepository,
                    lifecycle);

            EvidenceBundle bundle = orchestrator.retrieveFused(RetrievalRequest.defaults(
                    "alpha", RetrievalMode.HYBRID_GRAPH));
            EvidenceBundle repeated = orchestrator.retrieveFused(RetrievalRequest.defaults(
                    "alpha", RetrievalMode.HYBRID_GRAPH));

            assertThat(bundle.mode()).isEqualTo(RetrievalMode.HYBRID_GRAPH);
            assertThat(bundle.workspace().id()).isEqualTo(workspace.id());
            assertThat(bundle.items()).extracting(EvidenceItem::stableIdentity)
                    .containsExactlyInAnyOrder("WIKI:wiki-alpha-seed", "WIKI:wiki-linked-goal");
            assertThat(bundle.insufficientEvidence()).isFalse();
            assertThat(bundle.diagnostics().strategy()).isEqualTo(RetrievalStrategy.FUSED);
            assertThat(bundle.diagnostics().graphSignalUsed()).isTrue();
            assertThat(bundle.diagnostics().graphDegraded()).isFalse();
            assertThat(bundle.diagnostics().graphUnavailable()).isFalse();
            assertThat(repeated).isEqualTo(bundle);
        }
    }

    @Test
    void canonicalDriftBetweenFusionAndAskHandoffDropsStaleEvidenceAtTheLastMile()
            throws Exception {
        GraphWorkspaceScope workspace = workspace("handoff-drift");
        wiki(workspace, "wiki-handoff", "Handoff Page", "handoff authority");
        var lifecycle = lifecycle(factory(temp.resolve("handoff-drift")));
        lifecycle.rebuild(assembler.assemble(workspace));
        FusedEvidenceService fusion = fusion(lifecycle,
                factory(temp.resolve("handoff-drift")));

        // Deterministic barrier: probe one retrieval to count the production wiki reads, then
        // re-run with the canonical mutation armed exactly on the final read, which is the Ask
        // handoff revalidation. Fusion channel and terminal guards still see the current state.
        AtomicInteger probeReads = new AtomicInteger();
        PublishedWikiRepository counting = mock(PublishedWikiRepository.class);
        when(counting.findPublishedByKnowledgeId(anyLong(), anyString()))
                .thenAnswer(invocation -> {
                    probeReads.incrementAndGet();
                    return publishedWikiRepository.findPublishedByKnowledgeId(
                            invocation.getArgument(0), invocation.getArgument(1));
                });
        new FusedRetrievalOrchestrator(fusion, counting, publishedWikiContentReader,
                sourceAuthorityRepository, lifecycle).retrieveFused(RetrievalRequest.defaults(
                "handoff", RetrievalMode.HYBRID_GRAPH));
        int handoffRead = probeReads.get();

        AtomicInteger reads = new AtomicInteger();
        PublishedWikiRepository mutating = mock(PublishedWikiRepository.class);
        when(mutating.findPublishedByKnowledgeId(anyLong(), anyString()))
                .thenAnswer(invocation -> {
                    if (reads.incrementAndGet() == handoffRead) {
                        mutateWikiHash(workspace, "wiki-handoff",
                                WikiContentHash.sha256("mutated".getBytes(StandardCharsets.UTF_8)));
                    }
                    return publishedWikiRepository.findPublishedByKnowledgeId(
                            invocation.getArgument(0), invocation.getArgument(1));
                });
        EvidenceBundle bundle = new FusedRetrievalOrchestrator(fusion, mutating,
                publishedWikiContentReader, sourceAuthorityRepository, lifecycle)
                .retrieveFused(RetrievalRequest.defaults("handoff", RetrievalMode.HYBRID_GRAPH));

        assertThat(bundle.items()).isEmpty();
        assertThat(bundle.insufficientEvidence()).isTrue();
        // The handoff rejection is counted; nothing was silently promoted to fill the gap.
        assertThat(bundle.rejectedCandidateCount()).isEqualTo(1);
    }

    @Test
    void projectionDriftBetweenFusionAndAskHandoffIsRejectedAtTheLastMile() throws Exception {
        GraphWorkspaceScope workspace = workspace("handoff-projection");
        wiki(workspace, "wiki-handoff-seed", "Handoff Seed",
                "handoff seed authority [[Alpha Goal|目標]]");
        wiki(workspace, "wiki-alpha-goal", "Alpha Goal", "goal authority");
        wiki(workspace, "wiki-drift-fuel", "Drift Fuel", "unrelated fuel");

        try (var lifecycle = lifecycle(factory(temp.resolve("handoff-projection")))) {
            lifecycle.rebuild(assembler.assemble(workspace));
            GraphProjectionSnapshot snapshotA = lifecycle.readiness(workspace)
                    .controlPlane().appliedSnapshot();
            FusedEvidenceService fusion = fusion(lifecycle,
                    factory(temp.resolve("handoff-projection")));

            // Probe: count lifecycle readiness reads, then arm the projection rebuild (with a
            // canonical mutation to advance the generation) exactly on the final read, which is
            // the Ask handoff projection check.
            AtomicInteger probeReads = new AtomicInteger();
            GraphProjectionReadinessReader counting = scope -> {
                probeReads.incrementAndGet();
                return lifecycle.readiness(scope);
            };
            new FusedRetrievalOrchestrator(fusion, publishedWikiRepository,
                    publishedWikiContentReader, sourceAuthorityRepository, counting)
                    .retrieveFused(RetrievalRequest.defaults("handoff seed",
                            RetrievalMode.HYBRID_GRAPH));
            int handoffRead = probeReads.get();

            AtomicInteger readinessReads = new AtomicInteger();
            GraphProjectionReadinessReader driftingReadiness = scope -> {
                if (readinessReads.incrementAndGet() >= handoffRead) {
                    try {
                        mutateWiki(workspace, "wiki-drift-fuel", "Drift Fuel", List.of(), List.of(),
                                "unrelated fuel mutated");
                    } catch (Exception mutationFailure) {
                        throw new IllegalStateException(mutationFailure);
                    }
                    lifecycle.rebuild(assembler.assemble(workspace));
                }
                return lifecycle.readiness(scope);
            };
            EvidenceBundle bundle = new FusedRetrievalOrchestrator(fusion, publishedWikiRepository,
                    publishedWikiContentReader, sourceAuthorityRepository, driftingReadiness)
                    .retrieveFused(RetrievalRequest.defaults("handoff seed",
                            RetrievalMode.HYBRID_GRAPH));

            GraphProjectionSnapshot snapshotB = lifecycle.readiness(workspace)
                    .controlPlane().appliedSnapshot();
            assertThat(snapshotB.generation()).isGreaterThan(snapshotA.generation());
            // Graph-only evidence lost its only validity chain; the lexical baseline survives.
            assertThat(bundle.items()).extracting(EvidenceItem::stableIdentity)
                    .containsExactly("WIKI:wiki-handoff-seed");
            assertThat(bundle.insufficientEvidence()).isFalse();
            assertThat(bundle.diagnostics().graphDegraded()).isTrue();
            assertThat(bundle.diagnostics().graphDetail()).contains("handoff");
        }
    }

    @Test
    void disabledGraphProjectionStillServesTheLexicalBaselineThroughTheSharedOrchestrator()
            throws Exception {
        GraphWorkspaceScope workspace = workspace("disabled-shared");
        wiki(workspace, "wiki-plain", "Plain Page", "plain authority");

        EvidenceBundle bundle = disabledContextOrchestrator.retrieveFused(
                RetrievalRequest.defaults("plain", RetrievalMode.HYBRID_GRAPH));

        assertThat(bundle.items()).extracting(EvidenceItem::stableId).contains("wiki-plain");
        assertThat(bundle.insufficientEvidence()).isFalse();
        assertThat(bundle.diagnostics().strategy()).isEqualTo(RetrievalStrategy.FUSED);
        assertThat(bundle.diagnostics().graphSignalUsed()).isFalse();
        assertThat(bundle.diagnostics().graphDegraded()).isFalse();
    }

    private FusedEvidenceService fusion(GraphProjectionLifecycleService lifecycle,
                                        ArcadeDbGraphProjectionBackendFactory backendFactory) {
        GraphTraversalService traversal = new GraphTraversalService(lifecycle, backendFactory);
        GraphEvidenceAdmissionService admission = new GraphEvidenceAdmissionService(lifecycle,
                publishedWikiRepository, publishedWikiContentReader, sourceAuthorityRepository);
        return new FusedEvidenceService(workspaces, searchService, vectorCandidateSearchService,
                publishedWikiRepository, publishedWikiContentReader, sourceAuthorityRepository,
                lifecycle, traversal, admission);
    }

    private GraphWorkspaceScope workspace(String name) {
        return new GraphWorkspaceScope(workspaces.create(new CreateWorkspaceRequest(name,
                temp.resolve(name).toString())).id());
    }

    private void wiki(GraphWorkspaceScope workspace, String knowledgeId, String title,
                      String body) throws Exception {
        wiki(workspace, knowledgeId, title, List.of(), List.of(), body);
    }

    private void wiki(GraphWorkspaceScope workspace, String knowledgeId, String title,
                      List<String> tags, List<Long> sources, String body) throws Exception {
        String content = new StringBuilder("---\n")
                .append("id: \"").append(knowledgeId).append("\"\n")
                .append("title: \"").append(title).append("\"\n")
                .append("type: \"CONCEPT\"\nstatus: \"PUBLISHED\"\n")
                .append(renderList("aliases", List.of()))
                .append(renderList("tags", tags))
                .append(renderList("sources", sources.stream()
                        .map(id -> "document:" + id).toList()))
                .append("created_at: \"2026-09-08T00:00:00Z\"\n")
                .append("updated_at: \"2026-09-08T00:00:00Z\"\n")
                .append("---\n\n# ").append(title).append('\n').append(body).toString();
        String logicalPath = paths.resolveLogicalPath(WikiPageType.CONCEPT, title);
        Path target = Path.of(workspaces.get(workspace.id()).vaultPath())
                .resolve(logicalPath.substring("vault/".length()));
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
        String hash = WikiContentHash.sha256(content.getBytes(StandardCharsets.UTF_8));
        KeyHolder pageKey = new GeneratedKeyHolder();
        db().sql("""
                INSERT INTO knowledge_page(workspace_id, knowledge_id, title, normalized_title,
                    type, markdown_path, status, content_hash, revision, created_at, updated_at)
                VALUES(?, ?, ?, ?, 'CONCEPT', ?, 'PUBLISHED', ?, 1,
                    '2026-09-08T00:00:00Z', '2026-09-08T00:00:00Z')
                """).params(workspace.id(), knowledgeId, title,
                org.km.llmwiki.wiki.WikiTargetReference.normalizeTitle(title), logicalPath,
                hash).update(pageKey);
        ftsRepository.upsertKnowledge(new KnowledgeSearchDocument(workspace.id(), knowledgeId,
                title, org.km.llmwiki.wiki.WikiTargetReference.normalizeTitle(title), body,
                logicalPath, "CONCEPT", "PUBLISHED", hash));
        db().sql("""
                INSERT INTO knowledge_search_index_sync
                    (workspace_id, knowledge_page_id, knowledge_id, status, content_hash,
                     indexed_content_hash, indexed_revision, failure_detail, updated_at)
                VALUES (?, ?, ?, 'SYNCED', ?, ?, 1, NULL, '2026-09-08T00:00:00Z')
                """).params(workspace.id(), pageKey.getKey().longValue(), knowledgeId, hash,
                hash).update();
    }

    /** Rewrites the canonical vault file and its DB hash; the FTS row intentionally stays stale. */
    private void mutateWiki(GraphWorkspaceScope workspace, String knowledgeId, String title,
                            List<String> tags, List<Long> sources, String body) throws Exception {
        String content = new StringBuilder("---\n")
                .append("id: \"").append(knowledgeId).append("\"\n")
                .append("title: \"").append(title).append("\"\n")
                .append("type: \"CONCEPT\"\nstatus: \"PUBLISHED\"\n")
                .append(renderList("aliases", List.of()))
                .append(renderList("tags", tags))
                .append(renderList("sources", sources.stream()
                        .map(id -> "document:" + id).toList()))
                .append("created_at: \"2026-09-08T00:00:00Z\"\n")
                .append("updated_at: \"2026-09-08T00:00:00Z\"\n")
                .append("---\n\n# ").append(title).append('\n').append(body).toString();
        String logicalPath = paths.resolveLogicalPath(WikiPageType.CONCEPT, title);
        Path target = Path.of(workspaces.get(workspace.id()).vaultPath())
                .resolve(logicalPath.substring("vault/".length()));
        Files.writeString(target, content);
        String hash = WikiContentHash.sha256(content.getBytes(StandardCharsets.UTF_8));
        db().sql("""
                UPDATE knowledge_page SET content_hash = ? WHERE workspace_id = ?
                    AND knowledge_id = ?
                """).params(hash, workspace.id(), knowledgeId).update();
    }

    private void mutateWikiHash(GraphWorkspaceScope workspace, String knowledgeId,
                                String newHash) {
        db().sql("""
                UPDATE knowledge_page SET content_hash = ? WHERE workspace_id = ?
                    AND knowledge_id = ?
                """).params(newHash, workspace.id(), knowledgeId).update();
    }

    private static String renderList(String field, List<String> values) {
        if (values.isEmpty()) return field + ": []\n";
        StringBuilder result = new StringBuilder(field).append(":\n");
        values.forEach(value -> result.append("  - \"")
                .append(value.replace("\\", "\\\\").replace("\"", "\\\"")
                        .replace("\n", "\\n"))
                .append("\"\n"));
        return result.toString();
    }

    private ArcadeDbGraphProjectionBackendFactory factory(Path path) {
        return new ArcadeDbGraphProjectionBackendFactory(path, VERSION);
    }

    private GraphProjectionLifecycleService lifecycle(GraphProjectionBackendFactory factory) {
        return new GraphProjectionLifecycleService(true,
                ArcadeDbGraphProjectionBackendFactory.PROVIDER, VERSION, repository, factory,
                currentness);
    }
}
