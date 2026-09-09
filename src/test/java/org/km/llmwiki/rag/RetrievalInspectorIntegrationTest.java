package org.km.llmwiki.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.ai.embedding.EmbeddingInput;
import org.km.llmwiki.ai.embedding.EmbeddingVector;
import org.km.llmwiki.graph.GraphCanonicalCurrentness;
import org.km.llmwiki.graph.GraphProjectionInputAssembler;
import org.km.llmwiki.graph.GraphProjectionLifecycleRepository;
import org.km.llmwiki.graph.GraphProjectionLifecycleService;
import org.km.llmwiki.graph.GraphProjectionVersion;
import org.km.llmwiki.graph.GraphWorkspaceScope;
import org.km.llmwiki.persistence.graph.arcadedb.ArcadeDbGraphProjectionBackendFactory;
import org.km.llmwiki.processing.ProcessingJobRepository;
import org.km.llmwiki.processing.ProcessingJobType;
import org.km.llmwiki.search.FtsSearchIndexRepository;
import org.km.llmwiki.search.SearchService;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.km.llmwiki.search.KnowledgeSearchDocument;
import org.km.llmwiki.search.SourceChunkIndexingService;
import org.km.llmwiki.search.SourceSearchAuthorityRepository;
import org.km.llmwiki.search.embedding.EmbeddingEvidenceKind;
import org.km.llmwiki.search.embedding.EmbeddingProjectionIdentity;
import org.km.llmwiki.search.embedding.EmbeddingProjectionReadinessRepository;
import org.km.llmwiki.search.embedding.EmbeddingProjectionRepository;
import org.km.llmwiki.search.vector.VectorCandidateSearchService;
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
 * Production evidence for the read-only Retrieval Inspector across representative public
 * modes: the inspector observes the same production retrieval path (real FTS, real embedding
 * projection readiness, real ArcadeDB projection lifecycle) and its final evidence must equal
 * the production handoff for the same input and state, without mutating canonical state.
 */
class RetrievalInspectorIntegrationTest extends IsolatedIntegrationTest {

    @TempDir
    Path temp;

    @Autowired WorkspaceService workspaces;
    @Autowired SearchService searchService;
    @Autowired PublishedWikiRepository publishedWikiRepository;
    @Autowired PublishedWikiContentReader publishedWikiContentReader;
    @Autowired SourceSearchAuthorityRepository sourceAuthorityRepository;
    @Autowired FtsSearchIndexRepository ftsRepository;
    @Autowired SourceChunkIndexingService sourceChunkIndexingService;
    @Autowired EmbeddingProjectionRepository embeddingRepository;
    @Autowired EmbeddingProjectionReadinessRepository embeddingReadiness;
    @Autowired ProcessingJobRepository jobs;
    @Autowired GraphProjectionInputAssembler assembler;
    @Autowired GraphCanonicalCurrentness currentness;
    @Autowired GraphProjectionLifecycleRepository lifecycleRepository;

    private final DeterministicConceptEmbeddingClient embedder =
            new DeterministicConceptEmbeddingClient();

    @Test
    void wikiOnlyInspectionShowsLexicalCandidatesStaleRejectionAndFinalEvidence() throws Exception {
        RetrievalInspectorService inspector = inspector((FusedRetrievalOrchestrator) null);
        WorkspaceFixture ws = workspace("wiki-only");
        wiki(ws, "inspector-wiki-keep", "Inspector Keep", "inspector wiki keep authority");
        wiki(ws, "inspector-wiki-stale", "Inspector Stale", "inspector wiki stale authority");
        mutateVault(ws, "inspector-wiki-stale");

        RetrievalInspectionReport report = inspector.inspect(
                RetrievalRequest.defaults("inspector", RetrievalMode.WIKI_ONLY));

        assertThat(report.mode()).isEqualTo(RetrievalMode.WIKI_ONLY);
        assertThat(report.strategy()).isEqualTo(RetrievalStrategy.LEXICAL);
        assertThat(report.fusionPolicyVersion()).isNull();
        assertThat(report.modalities()).singleElement().satisfies(section -> {
            assertThat(section.modality()).isEqualTo(CandidateSignal.LEXICAL);
            assertThat(section.outcome()).isEqualTo(ModalityOutcome.CONTRIBUTED);
            assertThat(section.candidates()).extracting(
                            RetrievalInspectionTrace.CandidateTrace::identity,
                            RetrievalInspectionTrace.CandidateTrace::ordinal)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("WIKI:inspector-wiki-keep", 1),
                            org.assertj.core.groups.Tuple.tuple("WIKI:inspector-wiki-stale", 2));
            assertThat(section.rejected()).isEmpty();
        });
        assertThat(report.selection()).extracting(
                        RetrievalInspectionTrace.SelectionTrace::identity,
                        RetrievalInspectionTrace.SelectionTrace::disposition)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("WIKI:inspector-wiki-keep",
                                RetrievalInspectionTrace.Disposition.SELECTED),
                        org.assertj.core.groups.Tuple.tuple("WIKI:inspector-wiki-stale",
                                RetrievalInspectionTrace.Disposition.REJECTED));
        assertThat(report.selection().get(1).reasonCode()).isEqualTo("INELIGIBLE");
        assertThat(report.finalEvidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.ordinal()).isEqualTo(1);
            assertThat(evidence.identity()).isEqualTo("WIKI:inspector-wiki-keep");
        });
        assertThat(report.modalityDiagnostics().vector()).isEqualTo(ModalityOutcome.DISABLED);
        assertThat(report.modalityDiagnostics().graph()).isEqualTo(ModalityOutcome.DISABLED);
        assertThat(report.insufficientEvidence()).isFalse();
    }

    @Test
    void hybridFtsInspectionShowsBothCorporaWithVectorAndGraphDisabled() throws Exception {
        RetrievalInspectorService inspector = inspector((FusedRetrievalOrchestrator) null);
        WorkspaceFixture ws = workspace("hybrid-fts");
        wiki(ws, "inspector-hybrid-wiki", "Inspector Hybrid", "hybrid fts wiki authority");
        long sourceChunkId = source(ws, "inspector-hybrid.pdf", "hybrid fts source authority");

        RetrievalInspectionReport report = inspector.inspect(
                RetrievalRequest.defaults("hybrid", RetrievalMode.HYBRID_FTS));

        assertThat(report.modalities()).singleElement().satisfies(section -> {
            assertThat(section.modality()).isEqualTo(CandidateSignal.LEXICAL);
            assertThat(section.candidates()).extracting(
                            RetrievalInspectionTrace.CandidateTrace::identity)
                    .containsExactlyInAnyOrder("WIKI:inspector-hybrid-wiki",
                            "SOURCE_CHUNK:" + sourceChunkId);
        });
        assertThat(report.finalEvidence()).extracting(
                        RetrievalInspectionReport.FinalEvidence::identity)
                .containsExactlyInAnyOrder("WIKI:inspector-hybrid-wiki",
                        "SOURCE_CHUNK:" + sourceChunkId);
        assertThat(report.modalityDiagnostics().lexical()).isEqualTo(ModalityOutcome.CONTRIBUTED);
        assertThat(report.modalityDiagnostics().vector()).isEqualTo(ModalityOutcome.DISABLED);
        assertThat(report.modalityDiagnostics().graph()).isEqualTo(ModalityOutcome.DISABLED);
    }

    @Test
    void sourceOnlyInspectionShowsSourceCandidatesOnly() throws Exception {
        RetrievalInspectorService inspector = inspector((FusedRetrievalOrchestrator) null);
        WorkspaceFixture ws = workspace("source-only");
        wiki(ws, "inspector-source-wiki", "Inspector Source", "source wiki authority");
        long sourceChunkId = source(ws, "inspector-source.pdf", "source chunk authority");

        RetrievalInspectionReport report = inspector.inspect(
                RetrievalRequest.defaults("source", RetrievalMode.SOURCE_ONLY));

        assertThat(report.modalities()).singleElement().satisfies(section -> {
            assertThat(section.modality()).isEqualTo(CandidateSignal.LEXICAL);
            assertThat(section.candidates()).extracting(
                            RetrievalInspectionTrace.CandidateTrace::identity)
                    .containsExactly("SOURCE_CHUNK:" + sourceChunkId);
        });
        assertThat(report.finalEvidence()).singleElement().satisfies(evidence ->
                assertThat(evidence.identity()).isEqualTo("SOURCE_CHUNK:" + sourceChunkId));
        assertThat(report.modalityDiagnostics().vector()).isEqualTo(ModalityOutcome.DISABLED);
        assertThat(report.modalityDiagnostics().graph()).isEqualTo(ModalityOutcome.DISABLED);
    }

    @Test
    void semanticSourceInspectionShowsVectorChannelWithSourceChunkEmbedding() throws Exception {
        WorkspaceFixture ws = workspace("semantic-source");
        wiki(ws, "inspector-semantic-source", "Semantic Source Page",
                "unrelated wiki filler");
        long sourceChunkId = source(ws, "inspector-semantic.pdf",
                "source chunk authority about the search index design");
        String chunkHash = db().sql("SELECT content_hash FROM source_chunk WHERE id = :id")
                .param("id", sourceChunkId).query(String.class).single();
        List<Double> vector = embedder.embedText("source chunk authority about the search index design");
        byte[] blob = org.km.llmwiki.search.embedding.EmbeddingVectorCodec.encode(
                new EmbeddingVector(EmbeddingInput.identityFor(
                        "source chunk authority about the search index design"), vector));
        embeddingRepository.upsertFresh(new EmbeddingProjectionIdentity(ws.id(),
                EmbeddingEvidenceKind.SOURCE_CHUNK, Long.toString(sourceChunkId), chunkHash,
                DeterministicConceptEmbeddingClient.PROVIDER,
                DeterministicConceptEmbeddingClient.MODEL,
                DeterministicConceptEmbeddingClient.DIMENSION,
                DeterministicConceptEmbeddingClient.PROJECTION_VERSION), blob,
                "2026-09-01T00:00:00Z");
        markProjectionReady(ws.id(), EmbeddingEvidenceKind.WIKI, 0);
        markProjectionReady(ws.id(), EmbeddingEvidenceKind.SOURCE_CHUNK, 1);
        RetrievalInspectorService inspector = inspector(withVector());

        RetrievalInspectionReport report = inspector.inspect(
                RetrievalRequest.defaults("search", RetrievalMode.SEMANTIC_SOURCE));

        assertThat(report.modalities()).singleElement().satisfies(section -> {
            assertThat(section.modality()).isEqualTo(CandidateSignal.VECTOR);
            assertThat(section.outcome()).isEqualTo(ModalityOutcome.CONTRIBUTED);
            assertThat(section.candidates()).extracting(
                            RetrievalInspectionTrace.CandidateTrace::identity)
                    .containsExactly("SOURCE_CHUNK:" + sourceChunkId);
        });
        assertThat(report.finalEvidence()).singleElement().satisfies(evidence ->
                assertThat(evidence.identity()).isEqualTo("SOURCE_CHUNK:" + sourceChunkId));
    }

    @Test
    void semanticWikiInspectionShowsVectorChannelWithEmbeddingBackedCandidates() throws Exception {
        WorkspaceFixture ws = workspace("semantic-wiki");
        WikiFixture wiki = wiki(ws, "inspector-semantic-wiki", "Inspector Semantic",
                "semantic wiki authority about the search index design");
        indexEmbeddings(ws, List.of(wiki));
        RetrievalInspectorService inspector = inspector(withVector());

        RetrievalInspectionReport report = inspector.inspect(
                RetrievalRequest.defaults("search", RetrievalMode.SEMANTIC_WIKI));

        assertThat(report.modalities()).singleElement().satisfies(section -> {
            assertThat(section.modality()).isEqualTo(CandidateSignal.VECTOR);
            assertThat(section.outcome()).isEqualTo(ModalityOutcome.CONTRIBUTED);
            assertThat(section.candidates()).extracting(
                            RetrievalInspectionTrace.CandidateTrace::identity)
                    .containsExactly("WIKI:inspector-semantic-wiki");
        });
        assertThat(report.finalEvidence()).singleElement().satisfies(evidence ->
                assertThat(evidence.identity()).isEqualTo("WIKI:inspector-semantic-wiki"));
        assertThat(report.modalityDiagnostics().lexical()).isEqualTo(ModalityOutcome.DISABLED);
        assertThat(report.modalityDiagnostics().vector()).isEqualTo(ModalityOutcome.CONTRIBUTED);
        assertThat(report.modalityDiagnostics().graph()).isEqualTo(ModalityOutcome.DISABLED);
    }

    @Test
    void hybridVectorInspectionShowsBothChannelsAndMergedSelection() throws Exception {
        WorkspaceFixture ws = workspace("hybrid-vector");
        WikiFixture wiki = wiki(ws, "inspector-vector-wiki", "Inspector Vector",
                "hybrid vector wiki authority about the search index design");
        source(ws, "inspector-vector.pdf", "hybrid vector source authority");
        indexEmbeddings(ws, List.of(wiki));
        RetrievalInspectorService inspector = inspector(withVector());

        RetrievalInspectionReport report = inspector.inspect(
                RetrievalRequest.defaults("search", RetrievalMode.HYBRID_VECTOR));

        assertThat(report.modalities()).extracting(
                        RetrievalInspectionReport.ModalitySection::modality)
                .containsExactly(CandidateSignal.LEXICAL, CandidateSignal.VECTOR);
        assertThat(report.selection()).extracting(
                        RetrievalInspectionTrace.SelectionTrace::disposition)
                .containsOnly(RetrievalInspectionTrace.Disposition.SELECTED);
        assertThat(report.finalEvidence()).isNotEmpty();
        assertThat(report.modalityDiagnostics().graph()).isEqualTo(ModalityOutcome.DISABLED);
    }

    @Test
    void hybridGraphInspectionShowsGraphChannelFusedOrderAndPolicyVersion() throws Exception {
        WorkspaceFixture ws = workspace("hybrid-graph");
        wiki(ws, "inspector-graph-seed", "Inspector Seed",
                "graph seed authority [[Inspector Goal|目標]]");
        wiki(ws, "inspector-graph-goal", "Inspector Goal", "unrelated body");
        try (var lifecycle = lifecycle(temp.resolve("hybrid-graph"), true)) {
            lifecycle.rebuild(assembler.assemble(new GraphWorkspaceScope(ws.id())));
            RetrievalInspectorService inspector =
                    inspector(withGraph(lifecycle, temp.resolve("hybrid-graph")));

            RetrievalInspectionReport report = inspector.inspect(
                    RetrievalRequest.defaults("graph", RetrievalMode.HYBRID_GRAPH));

            assertThat(report.fusionPolicyVersion()).isEqualTo("fusion-rrf-v2-graph-damped");
            assertThat(report.fusedOrder()).isNotEmpty();
            assertThat(report.modalities()).extracting(
                            RetrievalInspectionReport.ModalitySection::modality)
                    .contains(CandidateSignal.GRAPH);
            assertThat(report.modalities().stream()
                    .filter(section -> section.modality() == CandidateSignal.GRAPH)
                    .findFirst().orElseThrow().outcome()).isEqualTo(ModalityOutcome.CONTRIBUTED);
            assertThat(report.finalEvidence()).isNotEmpty();
            assertThat(report.modalityDiagnostics().graph()).isEqualTo(ModalityOutcome.CONTRIBUTED);
        }
    }

    @Test
    void inspectionMatchesProductionRetrievalForTheSameState() throws Exception {
        WorkspaceFixture ws = workspace("consistency");
        wiki(ws, "inspector-consistency-wiki", "Inspector Consistency",
                "consistency wiki authority");
        try (var lifecycle = lifecycle(temp.resolve("consistency"), true)) {
            lifecycle.rebuild(assembler.assemble(new GraphWorkspaceScope(ws.id())));
            FusedRetrievalOrchestrator orchestrator =
                    withGraph(lifecycle, temp.resolve("consistency"));
            RetrievalInspectorService inspector = inspector(orchestrator);
            RetrievalRequest request = RetrievalRequest.defaults("consistency",
                    RetrievalMode.HYBRID_GRAPH);

            RetrievalInspectionReport report = inspector.inspect(request);
            EvidenceBundle production = new RetrievalService(workspaces, searchService,
                    publishedWikiRepository, publishedWikiContentReader, sourceAuthorityRepository,
                    vectorService(), new ReciprocalRankFusion(), orchestrator).retrieve(request);

            assertThat(report.finalEvidence()).extracting(
                            RetrievalInspectionReport.FinalEvidence::identity)
                    .containsExactlyElementsOf(production.items().stream()
                            .map(EvidenceItem::stableIdentity).toList());
        }
    }

    @Test
    void terminalCurrentnessDropIsObservableThroughInspectionInsteadOfFailingIt() throws Exception {
        WorkspaceFixture ws = workspace("terminal-drop");
        wiki(ws, "inspector-terminal-wiki", "Inspector Terminal", "terminal wiki authority");
        try (var lifecycle = lifecycle(temp.resolve("terminal-drop"), true)) {
            lifecycle.rebuild(assembler.assemble(new GraphWorkspaceScope(ws.id())));
            RetrievalRequest request = RetrievalRequest.defaults("terminal",
                    RetrievalMode.HYBRID_GRAPH);

            // Probe one inspection to count production wiki reads, then arm the canonical
            // mutation exactly on the final read (the Ask handoff guard in this path); a
            // mutation at either currentness guard yields the same observable drop.
            java.util.concurrent.atomic.AtomicInteger probeReads =
                    new java.util.concurrent.atomic.AtomicInteger();
            PublishedWikiRepository counting = org.mockito.Mockito.mock(PublishedWikiRepository.class);
            org.mockito.Mockito.when(counting.findPublishedByKnowledgeId(
                    org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString()))
                    .thenAnswer(invocation -> {
                        probeReads.incrementAndGet();
                        return publishedWikiRepository.findPublishedByKnowledgeId(
                                invocation.getArgument(0), invocation.getArgument(1));
                    });
            inspector(orchestratorWithRepository(counting, lifecycle,
                    temp.resolve("terminal-drop"))).inspect(request);
            int finalRead = probeReads.get();

            java.util.concurrent.atomic.AtomicInteger reads =
                    new java.util.concurrent.atomic.AtomicInteger();
            PublishedWikiRepository mutating = org.mockito.Mockito.mock(PublishedWikiRepository.class);
            org.mockito.Mockito.when(mutating.findPublishedByKnowledgeId(
                    org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString()))
                    .thenAnswer(invocation -> {
                        if (reads.incrementAndGet() == finalRead) {
                            db().sql("""
                                            UPDATE knowledge_page SET content_hash = :hash
                                            WHERE workspace_id = :workspace
                                              AND knowledge_id = :knowledgeId
                                            """)
                                    .param("hash", WikiContentHash.sha256(
                                            "mutated".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                                    .param("workspace", ws.id())
                                    .param("knowledgeId", "inspector-terminal-wiki").update();
                        }
                        return publishedWikiRepository.findPublishedByKnowledgeId(
                                invocation.getArgument(0), invocation.getArgument(1));
                    });

            RetrievalInspectionReport report = inspector(orchestratorWithRepository(mutating,
                    lifecycle, temp.resolve("terminal-drop"))).inspect(request);

            // The drop is observable: fusion selected the item, the terminal guard rejected it.
            assertThat(report.finalEvidence()).isEmpty();
            assertThat(report.selection()).extracting(
                            RetrievalInspectionTrace.SelectionTrace::identity,
                            RetrievalInspectionTrace.SelectionTrace::disposition)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("WIKI:inspector-terminal-wiki",
                                    RetrievalInspectionTrace.Disposition.SELECTED),
                            org.assertj.core.groups.Tuple.tuple("WIKI:inspector-terminal-wiki",
                                    RetrievalInspectionTrace.Disposition.REJECTED));
            assertThat(report.selection().get(1).reasonCode()).isEqualTo("STALE_REVISION");
            assertThat(report.insufficientEvidence()).isTrue();
        }
    }

    private FusedRetrievalOrchestrator orchestratorWithRepository(
            PublishedWikiRepository wikiRepository, GraphProjectionLifecycleService lifecycle,
            Path path) {
        FusedEvidenceService fusion = new FusedEvidenceService(workspaces, searchService,
                vectorService(), wikiRepository, publishedWikiContentReader,
                sourceAuthorityRepository, lifecycle,
                new org.km.llmwiki.graph.GraphTraversalService(lifecycle,
                        new ArcadeDbGraphProjectionBackendFactory(path,
                                GraphProjectionVersion.current())),
                new GraphEvidenceAdmissionService(lifecycle, wikiRepository,
                        publishedWikiContentReader, sourceAuthorityRepository),
                FusionRankingPolicy.production());
        return new FusedRetrievalOrchestrator(fusion, wikiRepository, publishedWikiContentReader,
                sourceAuthorityRepository, lifecycle);
    }

    @Test
    void hybridGraphInspectionDegradesTypedWhenBackendUnavailableAndKeepsBaseline()
            throws Exception {
        WorkspaceFixture ws = workspace("graph-disabled");
        wiki(ws, "inspector-disabled-wiki", "Inspector Disabled", "disabled baseline authority");
        try (var lifecycle = lifecycle(temp.resolve("graph-disabled"), false)) {
            RetrievalInspectorService inspector =
                    inspector(withGraph(lifecycle, temp.resolve("graph-disabled")));

            RetrievalInspectionReport report = inspector.inspect(
                    RetrievalRequest.defaults("disabled", RetrievalMode.HYBRID_GRAPH));

            assertThat(report.modalities()).extracting(
                            RetrievalInspectionReport.ModalitySection::modality)
                    .contains(CandidateSignal.LEXICAL);
            assertThat(report.finalEvidence()).singleElement().satisfies(evidence ->
                    assertThat(evidence.identity()).isEqualTo("WIKI:inspector-disabled-wiki"));
            assertThat(report.modalityDiagnostics().lexical())
                    .isEqualTo(ModalityOutcome.CONTRIBUTED);
            assertThat(report.modalityDiagnostics().graph())
                    .isIn(ModalityOutcome.DISABLED, ModalityOutcome.UNAVAILABLE,
                            ModalityOutcome.NOT_READY);
            assertThat(report.insufficientEvidence()).isFalse();
        }
    }

    @Test
    void repeatedInspectionIsDeterministicAndLeavesCanonicalStateUntouched() throws Exception {
        RetrievalInspectorService inspector = inspector((FusedRetrievalOrchestrator) null);
        WorkspaceFixture ws = workspace("determinism");
        wiki(ws, "inspector-determinism-wiki", "Inspector Determinism",
                "determinism wiki authority");

        RetrievalRequest request = RetrievalRequest.defaults("determinism",
                RetrievalMode.WIKI_ONLY);
        long pagesBefore = canonicalRowCount("knowledge_page");
        long chunksBefore = canonicalRowCount("source_chunk");
        long ftsBefore = canonicalRowCount("knowledge_fts");

        RetrievalInspectionReport first = inspector.inspect(request);
        RetrievalInspectionReport second = inspector.inspect(request);

        assertThat(first).isEqualTo(second);
        assertThat(canonicalRowCount("knowledge_page")).isEqualTo(pagesBefore);
        assertThat(canonicalRowCount("source_chunk")).isEqualTo(chunksBefore);
        assertThat(canonicalRowCount("knowledge_fts")).isEqualTo(ftsBefore);
    }

    private long canonicalRowCount(String table) {
        return db().sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    private RetrievalInspectorService inspector(FusedRetrievalOrchestrator orchestrator) {
        return inspector(orchestrator, null);
    }

    private RetrievalInspectorService inspector(VectorCandidateSearchService vectorService) {
        return inspector(null, vectorService);
    }

    private RetrievalInspectorService inspector(FusedRetrievalOrchestrator orchestrator,
                                                VectorCandidateSearchService vectorService) {
        RetrievalService retrievalService = new RetrievalService(workspaces, searchService,
                publishedWikiRepository, publishedWikiContentReader, sourceAuthorityRepository,
                vectorService, new ReciprocalRankFusion(), orchestrator);
        return new RetrievalInspectorService(retrievalService,
                new FusionRankingPolicyProvider("fusion-rrf-v2-graph-damped"));
    }

    private VectorCandidateSearchService withVector() {
        return vectorService();
    }

    private FusedRetrievalOrchestrator withGraph(GraphProjectionLifecycleService lifecycle,
                                                 Path path) {
        FusedEvidenceService fusion = new FusedEvidenceService(workspaces, searchService,
                vectorService(), publishedWikiRepository, publishedWikiContentReader,
                sourceAuthorityRepository, lifecycle,
                new org.km.llmwiki.graph.GraphTraversalService(lifecycle,
                        new ArcadeDbGraphProjectionBackendFactory(path,
                                GraphProjectionVersion.current())),
                new GraphEvidenceAdmissionService(lifecycle, publishedWikiRepository,
                        publishedWikiContentReader, sourceAuthorityRepository),
                FusionRankingPolicy.production());
        return new FusedRetrievalOrchestrator(fusion, publishedWikiRepository,
                publishedWikiContentReader, sourceAuthorityRepository, lifecycle);
    }

    private VectorCandidateSearchService vectorService() {
        return new VectorCandidateSearchService(embedder, publishedWikiRepository,
                sourceAuthorityRepository,
                new DeterministicVectorSimilaritySearch(db()), embeddingReadiness);
    }

    private GraphProjectionLifecycleService lifecycle(Path path, boolean enabled) {
        ArcadeDbGraphProjectionBackendFactory factory =
                new ArcadeDbGraphProjectionBackendFactory(path, GraphProjectionVersion.current());
        return new GraphProjectionLifecycleService(enabled, "arcadedb",
                GraphProjectionVersion.current(), lifecycleRepository, factory, currentness);
    }

    private WorkspaceFixture workspace(String name) {
        return new WorkspaceFixture(workspaces.create(new CreateWorkspaceRequest(name,
                temp.resolve(name).toString())).id(), temp.resolve(name));
    }

    private WikiFixture wiki(WorkspaceFixture ws, String knowledgeId, String title, String body)
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
                        """)
                .param("workspace", ws.id()).param("knowledgeId", knowledgeId)
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
                        """)
                .param("workspace", ws.id()).param("pageId", pageKey.getKey().longValue())
                .param("knowledgeId", knowledgeId).param("hash", hash).update();
        return new WikiFixture(knowledgeId, title, body, hash);
    }

    /** Vault-file drift passes FTS serving freshness but fails authority content validation. */
    private void mutateVault(WorkspaceFixture ws, String knowledgeId) throws Exception {
        String path = db().sql("""
                        SELECT markdown_path FROM knowledge_page
                        WHERE workspace_id = :workspace AND knowledge_id = :knowledgeId
                        """)
                .param("workspace", ws.id()).param("knowledgeId", knowledgeId)
                .query(String.class).single();
        Files.writeString(ws.root().resolve(path),
                "manual vault drift", StandardCharsets.UTF_8);
    }

    private long source(WorkspaceFixture ws, String documentName, String content) {
        KeyHolder documentKey = new GeneratedKeyHolder();
        db().sql("""
                        INSERT INTO document (workspace_id, file_name, original_file_name, source_path,
                            sha256, status, parse_status, created_at, updated_at)
                        VALUES (:workspace, :name, :name, :path, :hash, 'PENDING', 'PROCESSED',
                                '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z')
                        """)
                .param("workspace", ws.id()).param("name", documentName)
                .param("path", "archive/" + documentName).param("hash", "d".repeat(64))
                .update(documentKey);
        long documentId = documentKey.getKey().longValue();
        String hash = WikiContentHash.sha256(content.getBytes(StandardCharsets.UTF_8));
        db().sql("""
                        INSERT INTO source_chunk (document_id, chunk_no, content, normalized_content,
                            content_hash, created_at, updated_at)
                        VALUES (:document, 1, :content, :content, :hash,
                                '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z')
                        """)
                .param("document", documentId).param("content", content).param("hash", hash)
                .update();
        sourceChunkIndexingService.reindexDocument(ws.id(), documentId);
        return db().sql("SELECT id FROM source_chunk WHERE document_id = :documentId AND chunk_no = 1")
                .param("documentId", documentId).query(Long.class).single();
    }

    private void indexEmbeddings(WorkspaceFixture ws, List<WikiFixture> pages) {
        for (WikiFixture page : pages) {
            List<Double> vector = embedder.embedText(page.knowledgeId() + " " + page.body());
            byte[] blob = org.km.llmwiki.search.embedding.EmbeddingVectorCodec.encode(
                    new EmbeddingVector(EmbeddingInput.identityFor(page.body()), vector));
            embeddingRepository.upsertFresh(new EmbeddingProjectionIdentity(ws.id(),
                    EmbeddingEvidenceKind.WIKI, page.knowledgeId(), page.contentHash(),
                    DeterministicConceptEmbeddingClient.PROVIDER,
                    DeterministicConceptEmbeddingClient.MODEL,
                    DeterministicConceptEmbeddingClient.DIMENSION,
                    DeterministicConceptEmbeddingClient.PROJECTION_VERSION), blob,
                    "2026-09-01T00:00:00Z");
        }
        markProjectionReady(ws.id(), EmbeddingEvidenceKind.WIKI, pages.size());
        markProjectionReady(ws.id(), EmbeddingEvidenceKind.SOURCE_CHUNK, 0);
    }

    private void markProjectionReady(long workspaceId, EmbeddingEvidenceKind corpus,
                                     int expected) {
        long fullJob = jobs.create(workspaceId,
                "inspector-full-" + workspaceId + "-" + corpus,
                ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long fullGeneration = embeddingReadiness.markQueued(workspaceId, fullJob, corpus, 0);
        embeddingReadiness.markRunning(workspaceId, fullJob, corpus);
        embeddingReadiness.markCompletedForGeneration(workspaceId, fullJob, corpus,
                fullGeneration, expected, expected, 0,
                DeterministicConceptEmbeddingClient.PROVIDER,
                DeterministicConceptEmbeddingClient.MODEL,
                DeterministicConceptEmbeddingClient.DIMENSION, true,
                "fixture-snapshot-proof");
        if (expected == 0) {
            return;
        }
        long jobId = jobs.create(workspaceId,
                "inspector-incremental-" + workspaceId + "-" + corpus,
                ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long generation = embeddingReadiness.markQueued(workspaceId, jobId, corpus, expected);
        embeddingReadiness.markRunning(workspaceId, jobId, corpus);
        embeddingReadiness.markCompletedForGeneration(workspaceId, jobId, corpus, generation,
                expected, expected, 0, DeterministicConceptEmbeddingClient.PROVIDER,
                DeterministicConceptEmbeddingClient.MODEL,
                DeterministicConceptEmbeddingClient.DIMENSION, true,
                "fixture-snapshot-proof");
    }

    private record WorkspaceFixture(long id, Path root) {
    }

    private record WikiFixture(String knowledgeId, String title, String body, String contentHash) {
    }
}
