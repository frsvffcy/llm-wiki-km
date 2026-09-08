package org.km.llmwiki.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.graph.GraphCanonicalCurrentness;
import org.km.llmwiki.graph.GraphProjectionBackendFactory;
import org.km.llmwiki.graph.GraphProjectionInput;
import org.km.llmwiki.graph.GraphProjectionInputAssembler;
import org.km.llmwiki.graph.GraphProjectionLifecycleRepository;
import org.km.llmwiki.graph.GraphProjectionLifecycleService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Production evidence for three-modality fusion: real FTS serving, real ArcadeDB projection with
 * STORY-807 admission, deterministic fusion, and the terminal publication guard. Canonical
 * mutation between channel revalidation and terminal publication is reproduced with a call-count
 * barrier around the production wiki repository — no sleep, no timing luck.
 */
class FusedEvidenceIntegrationTest extends IsolatedIntegrationTest {

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
    @Autowired FusedEvidenceService disabledContextFusion;

    @Test
    void fusedRetrievalCombinesLexicalAndGraphEvidenceFromProductionProjection() throws Exception {
        GraphWorkspaceScope workspace = workspace("fusion");
        // The linked target deliberately does not match the lexical query: only the graph
        // channel can discover it, which proves both channels contribute distinct canonical
        // evidence to one fused result.
        wiki(workspace, "wiki-alpha-seed", "Alpha Seed", "alpha seed authority [[Linked Goal|目標]]");
        wiki(workspace, "wiki-linked-goal", "Linked Goal", "beta unrelated");
        GraphProjectionInput input = assembler.assemble(workspace);

        try (var lifecycle = lifecycle(factory(temp.resolve("fusion")))) {
            lifecycle.rebuild(input);
            FusedEvidenceService fusion = fusion(lifecycle,
                    factory(temp.resolve("fusion")), publishedWikiRepository);

            FusedEvidenceResult result = fusion.fuse(FusedEvidenceRequest.of("alpha"));

            assertThat(result.diagnostics().lexical()).isEqualTo(ModalityOutcome.CONTRIBUTED);
            assertThat(result.diagnostics().graph()).isEqualTo(ModalityOutcome.CONTRIBUTED);
            assertThat(result.items())
                    .extracting(EvidenceItem::stableIdentity)
                    .containsExactlyInAnyOrder("WIKI:wiki-alpha-seed", "WIKI:wiki-linked-goal");
            assertThat(result.insufficientEvidence()).isFalse();
            assertThat(result.diagnostics().terminalRejectedCount()).isZero();
            assertThat(fusion.fuse(FusedEvidenceRequest.of("alpha"))).isEqualTo(result);
        }
    }

    @Test
    void canonicalMutationBetweenChannelRevalidationAndTerminalPublicationDropsStaleEvidence()
            throws Exception {
        GraphWorkspaceScope workspace = workspace("terminal-drift");
        wiki(workspace, "wiki-drift", "Drift Page", "drift authority");
        // Deterministic barrier: the first production read is the channel revalidation (current
        // state), then the canonical mutation happens, and the second read is the terminal
        // publication guard, which must reject the stale evidence.
        AtomicInteger reads = new AtomicInteger();
        PublishedWikiRepository mutatingRepository = org.mockito.Mockito
                .mock(PublishedWikiRepository.class);
        when(mutatingRepository.findPublishedByKnowledgeId(anyLong(), anyString()))
                .thenAnswer(invocation -> {
                    if (reads.incrementAndGet() >= 2) {
                        mutateWikiHash(workspace, "wiki-drift",
                                WikiContentHash.sha256("mutated".getBytes(StandardCharsets.UTF_8)));
                    }
                    return publishedWikiRepository.findPublishedByKnowledgeId(
                            invocation.getArgument(0), invocation.getArgument(1));
                });
        FusedEvidenceService fusion = new FusedEvidenceService(workspaces, searchService,
                vectorCandidateSearchService, mutatingRepository, publishedWikiContentReader,
                sourceAuthorityRepository, graphLifecycleStub(), traversalStub(),
                admissionStub());

        FusedEvidenceResult result = fusion.fuse(FusedEvidenceRequest.of("drift", null, null,
                false));

        assertThat(result.items()).isEmpty();
        assertThat(result.insufficientEvidence()).isTrue();
        assertThat(result.diagnostics().terminalRejectedCount()).isEqualTo(1);
        assertThat(result.diagnostics().lexical()).isEqualTo(ModalityOutcome.CONTRIBUTED);
    }

    @Test
    void fusionAfterProjectionRebuildServesCurrentGenerationB() throws Exception {
        GraphWorkspaceScope workspace = workspace("projection-race");
        wiki(workspace, "wiki-race-source", "Race Source", List.of(), List.of(),
                "racing content [[Alpha Goal|目標]]");
        wiki(workspace, "wiki-alpha-goal", "Alpha Goal", "goal authority");
        wiki(workspace, "wiki-stable-seed", "Stable Seed", "racing stable anchor [[Alpha Goal|目標]]");
        GraphProjectionInput input = assembler.assemble(workspace);

        try (var lifecycle = lifecycle(factory(temp.resolve("projection-race")))) {
            GraphProjectionSnapshot snapshotA = lifecycle.rebuild(input)
                    .controlPlane().appliedSnapshot();
            FusedEvidenceService fusion = fusion(lifecycle,
                    factory(temp.resolve("projection-race")), publishedWikiRepository);
            FusedEvidenceResult beforeMutation = fusion.fuse(FusedEvidenceRequest.of("racing"));
            assertThat(beforeMutation.diagnostics().graph())
                    .isEqualTo(ModalityOutcome.CONTRIBUTED);

            // Generation B wins: the mutated wiki body invalidates the stale FTS projection
            // entry for the source page, and only generation B may serve graph evidence.
            mutateWiki(workspace, "wiki-race-source", "Race Source", List.of(), List.of(),
                    "racing content mutated [[Alpha Goal|目標]]");
            lifecycle.rebuild(assembler.assemble(workspace));
            GraphProjectionSnapshot snapshotB = lifecycle.readiness(workspace)
                    .controlPlane().appliedSnapshot();
            assertThat(snapshotB.generation()).isGreaterThan(snapshotA.generation());

            FusedEvidenceResult afterMutation = fusion.fuse(FusedEvidenceRequest.of("racing"));

            // Lexical revalidation rejects the stale-indexed source page; the stable seed page
            // still revalidates and seeds the graph traversal from generation B only.
            assertThat(afterMutation.items())
                    .extracting(EvidenceItem::stableIdentity)
                    .contains("WIKI:wiki-alpha-goal", "WIKI:wiki-stable-seed");
            assertThat(afterMutation.items())
                    .noneMatch(item -> item.stableIdentity().equals("WIKI:wiki-race-source"));
            assertThat(afterMutation.diagnostics().graph()).isEqualTo(ModalityOutcome.CONTRIBUTED);
        }
    }

    @Test
    void excludedGraphChannelStillServesTheLexicalBaseline() throws Exception {
        GraphWorkspaceScope workspace = workspace("lexical-only");
        wiki(workspace, "wiki-plain", "Plain Page", "plain authority");

        FusedEvidenceResult result = disabledContextFusion.fuse(
                FusedEvidenceRequest.of("plain", null, null, false));

        assertThat(result.diagnostics().graph()).isEqualTo(ModalityOutcome.DISABLED);
        assertThat(result.items()).extracting(EvidenceItem::stableId).contains("wiki-plain");
    }

    @Test
    void disabledGraphProjectionIsTypedWhileLexicalServingContinues() throws Exception {
        GraphWorkspaceScope workspace = workspace("disabled-typed");
        wiki(workspace, "wiki-unused", "Unused Page", "unused");

        FusedEvidenceResult result = disabledContextFusion.fuse(FusedEvidenceRequest.of("unused"));

        assertThat(result.diagnostics().graph()).isEqualTo(ModalityOutcome.DISABLED);
        assertThat(result.diagnostics().lexical()).isEqualTo(ModalityOutcome.CONTRIBUTED);
        assertThat(result.items()).extracting(EvidenceItem::stableId).contains("wiki-unused");
    }

    private FusedEvidenceService fusion(GraphProjectionLifecycleService lifecycle,
                                        ArcadeDbGraphProjectionBackendFactory backendFactory,
                                        PublishedWikiRepository wikiRepository) {
        GraphTraversalService traversal = new GraphTraversalService(lifecycle, backendFactory);
        GraphEvidenceAdmissionService admission = new GraphEvidenceAdmissionService(lifecycle,
                wikiRepository, publishedWikiContentReader, sourceAuthorityRepository);
        return new FusedEvidenceService(workspaces, searchService, vectorCandidateSearchService,
                wikiRepository, publishedWikiContentReader, sourceAuthorityRepository,
                lifecycle, traversal, admission);
    }

    private GraphProjectionLifecycleService graphLifecycleStub() {
        return lifecycle(factory(temp.resolve("terminal-drift-unused")));
    }

    private GraphTraversalService traversalStub() {
        return new GraphTraversalService(graphLifecycleStub(),
                factory(temp.resolve("terminal-drift-unused")));
    }

    private GraphEvidenceAdmissionService admissionStub() {
        return new GraphEvidenceAdmissionService(graphLifecycleStub(), publishedWikiRepository,
                publishedWikiContentReader, sourceAuthorityRepository);
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
