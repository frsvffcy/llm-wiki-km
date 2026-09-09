package org.km.llmwiki.rag;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.ai.embedding.EmbeddingInput;
import org.km.llmwiki.ai.embedding.EmbeddingVector;
import org.km.llmwiki.graph.GraphCanonicalCurrentness;
import org.km.llmwiki.graph.GraphProjectionInput;
import org.km.llmwiki.graph.GraphProjectionInputAssembler;
import org.km.llmwiki.graph.GraphProjectionLifecycleRepository;
import org.km.llmwiki.graph.GraphProjectionLifecycleService;
import org.km.llmwiki.graph.GraphProjectionStatusResponse;
import org.km.llmwiki.graph.GraphProjectionVersion;
import org.km.llmwiki.graph.GraphWorkspaceScope;
import org.km.llmwiki.persistence.graph.arcadedb.ArcadeDbGraphProjectionBackendFactory;
import org.km.llmwiki.processing.ProcessingJobRepository;
import org.km.llmwiki.processing.ProcessingJobType;
import org.km.llmwiki.rag.GraphRetrievalGoldenCorpus.GoldenPage;
import org.km.llmwiki.search.FtsSearchIndexRepository;
import org.km.llmwiki.search.KnowledgeSearchDocument;
import org.km.llmwiki.search.SearchService;
import org.km.llmwiki.search.SourceSearchAuthorityRepository;
import org.km.llmwiki.search.embedding.EmbeddingEvidenceKind;
import org.km.llmwiki.search.embedding.EmbeddingProjectionIdentity;
import org.km.llmwiki.search.embedding.EmbeddingProjectionReadinessRepository;
import org.km.llmwiki.search.embedding.EmbeddingProjectionRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression gate for graph-grounded retrieval quality over the golden corpus. The benchmark
 * runs the production-equivalent application contracts (real FTS, real SQLite KNN with a
 * deterministic local embedding fixture, real ArcadeDB projection with traversal/admission/
 * fusion) and gates on identity-level metrics only — no raw cross-scale score comparison, no
 * live provider, no network, no sleep.
 */
@Tag("integration")
class GraphRetrievalQualityGateTest extends IsolatedIntegrationTest {

    private static final GraphRetrievalGoldenCorpus CORPUS = new GraphRetrievalGoldenCorpus();
    private static final DeterministicConceptEmbeddingClient EMBEDDER =
            new DeterministicConceptEmbeddingClient();
    private static final int K = 8;
    private static final String NOW = "2026-09-09T00:00:00Z";

    // Metric floors sit a modest headroom below the measured golden-corpus baseline recorded in
    // docs/development/testing.md (measured: HYBRID_FTS 0.444/0.667, HYBRID_VECTOR 0.889/1.000,
    // HYBRID_GRAPH 1.000/0.833). They are deliberately floors, not exact pins, so a reasonable
    // ranking change is not misread as a regression; a DROP below the floor is.
    private static final double MIN_RECALL_HYBRID_FTS = 0.40d;
    private static final double MIN_RECALL_HYBRID_VECTOR = 0.80d;
    private static final double MIN_RECALL_HYBRID_GRAPH = 0.95d;
    private static final double MIN_MRR_HYBRID_FTS = 0.60d;
    private static final double MIN_MRR_HYBRID_VECTOR = 0.90d;
    private static final double MIN_MRR_HYBRID_GRAPH = 0.75d;

    @TempDir
    Path temp;

    @Autowired WorkspaceService workspaces;
    @Autowired SearchService searchService;
    @Autowired PublishedWikiRepository publishedWikiRepository;
    @Autowired PublishedWikiContentReader publishedWikiContentReader;
    @Autowired SourceSearchAuthorityRepository sourceAuthorityRepository;
    @Autowired FtsSearchIndexRepository ftsRepository;
    @Autowired EmbeddingProjectionRepository embeddingRepository;
    @Autowired EmbeddingProjectionReadinessRepository embeddingReadiness;
    @Autowired ProcessingJobRepository jobs;
    @Autowired GraphProjectionInputAssembler assembler;
    @Autowired GraphProjectionLifecycleRepository lifecycleRepository;
    @Autowired GraphCanonicalCurrentness currentness;
    @Autowired WikiPathContract paths;

    @Test
    void graphGroundedRetrievalQualityMeetsTheGoldenCorpusBaseline() throws Exception {
        GraphWorkspaceScope active = workspace("quality-corpus");
        GraphWorkspaceScope foreign = workspace("quality-foreign");
        workspaces.open(active.id());

        List<GoldenPage> pages = CORPUS.pages();
        Map<String, String> pageHashes = new java.util.HashMap<>();
        for (GoldenPage page : pages) {
            pageHashes.put(page.knowledgeId(), wiki(active, page));
        }
        for (GoldenPage page : CORPUS.foreignWorkspacePages()) {
            wiki(foreign, page);
        }
        indexEmbeddings(active.id(), pageHashes);
        // Safety fixture: an external editor republishes the page (vault file and DB hash move
        // together), so the FTS and embedding rows hold the old hash and authority revalidation
        // must reject the page in every mode.
        GoldenPage stalePage = CORPUS.page("wiki-db-stale");
        String mutatedContent = new StringBuilder("---\n")
                .append("id: \"").append(stalePage.knowledgeId()).append("\"\n")
                .append("title: \"").append(stalePage.title()).append("\"\n")
                .append("type: \"CONCEPT\"\nstatus: \"PUBLISHED\"\n")
                .append("aliases: []\n")
                .append(renderList("tags", stalePage.tags()))
                .append("sources: []\n")
                .append("created_at: \"").append(NOW).append("\"\n")
                .append("updated_at: \"").append(NOW).append("\"\n")
                .append("---\n\n# ").append(stalePage.title()).append('\n')
                .append(stalePage.body()).append("\n外部編輯後的修訂內容。").toString();
        Path stalePath = Path.of(workspaces.get(active.id()).vaultPath())
                .resolve(paths.resolveLogicalPath(WikiPageType.CONCEPT, stalePage.title())
                        .substring("vault/".length()));
        Files.writeString(stalePath, mutatedContent);
        db().sql("UPDATE knowledge_page SET content_hash=? WHERE workspace_id=? AND knowledge_id=?")
                .params(WikiContentHash.sha256(mutatedContent.getBytes(StandardCharsets.UTF_8)),
                        active.id(), stalePage.knowledgeId()).update();

        try (var lifecycleFactory = lifecycle(temp.resolve("quality-graph"))) {
            FusedRetrievalOrchestrator orchestrator = orchestrator(
                    lifecycleFactory.lifecycle(), lifecycleFactory.factory());
            RetrievalService retrievalService = retrievalService(orchestrator);

            // Degradation observation happens before the projection exists: HYBRID_GRAPH must
            // degrade to the lexical + vector baseline instead of failing or inventing results.
            GraphRetrievalQualityBenchmark.QueryOutcome degraded = fusedOutcome(orchestrator,
                    degradedQuery(), "degraded");
            assertThat(degraded.graphUnavailable()).isTrue();
            assertThat(degraded.graphDegraded()).isFalse();

            GraphProjectionInput input = assembler.assemble(active);
            lifecycleFactory.lifecycle().rebuild(input);
            GraphProjectionStatusResponse projection = GraphProjectionStatusResponse.from(
                    lifecycleFactory.lifecycle().readiness(active));
            assertThat(projection.status()).isEqualTo("READY");

            Map<String, Function<String, org.km.llmwiki.rag.EvidenceBundle>> runners = Map.of(
                    "HYBRID_FTS", text -> retrievalService.retrieve(
                            org.km.llmwiki.rag.RetrievalRequest.defaults(text,
                                    org.km.llmwiki.rag.RetrievalMode.HYBRID_FTS)),
                    "HYBRID_VECTOR", text -> retrievalService.retrieve(
                            org.km.llmwiki.rag.RetrievalRequest.defaults(text,
                                    org.km.llmwiki.rag.RetrievalMode.HYBRID_VECTOR)),
                    "HYBRID_GRAPH", text -> orchestrator.retrieveFused(
                            org.km.llmwiki.rag.RetrievalRequest.defaults(text,
                                    org.km.llmwiki.rag.RetrievalMode.HYBRID_GRAPH)));

            GraphRetrievalQualityBenchmark.Evaluation evaluation = GraphRetrievalQualityBenchmark
                    .evaluate(CORPUS.VERSION, CORPUS.queries(), runners, K,
                            projection.projectionVersion(), projection.appliedGeneration(),
                            List.of(CORPUS.staleIdentity(), CORPUS.foreignIdentity(),
                                    CORPUS.mentionNegativeIdentity()),
                            degraded,
                            Set.copyOf(outcome(runners, degradedQuery().text()).retrieved()));

            writeReportsAndAssertGates(evaluation);
        }
    }

    private void writeReportsAndAssertGates(GraphRetrievalQualityBenchmark.Evaluation evaluation) {
        Path reports = Path.of("target", "quality-reports");
        GraphRetrievalQualityBenchmark.writeReports(evaluation, reports);
        assertThat(reports.resolve("graph-retrieval-quality.json")).exists();
        assertThat(reports.resolve("graph-retrieval-quality.md")).exists();

        // Authority correctness, workspace isolation, and production MENTIONS NO-GO are hard
        // gates: no mode may ever retrieve a stale, foreign, or mention-only identity.
        assertThat(evaluation.safetyViolations()).isEmpty();

        // Graph-added discovery is the benchmark's core claim: HYBRID_GRAPH must find the
        // graph-only relevant target, and the baseline modes must not (otherwise the graph
        // gain would be a fixture artifact, not a graph contribution).
        GraphRetrievalQualityBenchmark.ModeAggregate graph =
                evaluation.aggregates().get("HYBRID_GRAPH");
        assertThat(graph.graphAddedFound()).isEqualTo(graph.graphAddedExpected());
        assertThat(graph.graphAddedExpected()).isEqualTo(1);
        assertThat(evaluation.aggregates().get("HYBRID_FTS").graphAddedFound()).isZero();
        assertThat(evaluation.aggregates().get("HYBRID_VECTOR").graphAddedFound()).isZero();

        // Degraded graph must retain the lexical + vector baseline for the same query.
        assertThat(evaluation.degradedBaselineRetained()).isTrue();

        assertThat(graph.recallAtK()).isGreaterThanOrEqualTo(MIN_RECALL_HYBRID_GRAPH);
        assertThat(graph.mrr()).isGreaterThanOrEqualTo(MIN_MRR_HYBRID_GRAPH);
        assertThat(evaluation.aggregates().get("HYBRID_VECTOR").recallAtK())
                .isGreaterThanOrEqualTo(MIN_RECALL_HYBRID_VECTOR);
        assertThat(evaluation.aggregates().get("HYBRID_VECTOR").mrr())
                .isGreaterThanOrEqualTo(MIN_MRR_HYBRID_VECTOR);
        assertThat(evaluation.aggregates().get("HYBRID_FTS").recallAtK())
                .isGreaterThanOrEqualTo(MIN_RECALL_HYBRID_FTS);
        assertThat(evaluation.aggregates().get("HYBRID_FTS").mrr())
                .isGreaterThanOrEqualTo(MIN_MRR_HYBRID_FTS);
    }

    private GraphRetrievalGoldenCorpus.GoldenQuery degradedQuery() {
        return CORPUS.queries().stream()
                .filter(query -> query.id().equals("graph-added-discovery")).findFirst()
                .orElseThrow();
    }

    private GraphRetrievalQualityBenchmark.QueryOutcome fusedOutcome(
            FusedRetrievalOrchestrator orchestrator,
            GraphRetrievalGoldenCorpus.GoldenQuery query, String queryId) {
        org.km.llmwiki.rag.EvidenceBundle bundle = orchestrator.retrieveFused(
                org.km.llmwiki.rag.RetrievalRequest.defaults(query.text(),
                        org.km.llmwiki.rag.RetrievalMode.HYBRID_GRAPH));
        return new GraphRetrievalQualityBenchmark.QueryOutcome(queryId, query.queryClass(),
                "HYBRID_GRAPH", bundle.items().stream()
                .map(org.km.llmwiki.rag.EvidenceItem::stableIdentity).toList(),
                bundle.rejectedCandidateCount(),
                bundle.diagnostics().graphUnavailable(), bundle.diagnostics().graphDegraded());
    }

    private GraphRetrievalQualityBenchmark.QueryOutcome outcome(
            Map<String, Function<String, org.km.llmwiki.rag.EvidenceBundle>> runners,
            String text) {
        org.km.llmwiki.rag.EvidenceBundle bundle = runners.get("HYBRID_VECTOR").apply(text);
        return new GraphRetrievalQualityBenchmark.QueryOutcome("baseline", "BASELINE",
                "HYBRID_VECTOR", bundle.items().stream()
                .map(org.km.llmwiki.rag.EvidenceItem::stableIdentity).toList(),
                bundle.rejectedCandidateCount(), bundle.diagnostics().graphUnavailable(),
                bundle.diagnostics().graphDegraded());
    }

    private FusedRetrievalOrchestrator orchestrator(
            GraphProjectionLifecycleService lifecycle, ArcadeDbGraphProjectionBackendFactory factory) {
        FusedEvidenceService fusion = new FusedEvidenceService(workspaces, searchService,
                vectorService(), publishedWikiRepository, publishedWikiContentReader,
                sourceAuthorityRepository, lifecycle,
                new org.km.llmwiki.graph.GraphTraversalService(lifecycle, factory),
                new GraphEvidenceAdmissionService(lifecycle, publishedWikiRepository,
                        publishedWikiContentReader, sourceAuthorityRepository));
        return new FusedRetrievalOrchestrator(fusion, publishedWikiRepository,
                publishedWikiContentReader, sourceAuthorityRepository, lifecycle);
    }

    private RetrievalService retrievalService(FusedRetrievalOrchestrator orchestrator) {
        return new RetrievalService(workspaces, searchService, publishedWikiRepository,
                publishedWikiContentReader, sourceAuthorityRepository, vectorService(),
                new ReciprocalRankFusion(), orchestrator);
    }

    private VectorCandidateSearchService vectorService() {
        return new VectorCandidateSearchService(EMBEDDER, publishedWikiRepository,
                sourceAuthorityRepository, new DeterministicVectorSimilaritySearch(db()),
                embeddingReadiness);
    }

    private record LifecycleBundle(GraphProjectionLifecycleService lifecycle,
                                   ArcadeDbGraphProjectionBackendFactory factory)
            implements AutoCloseable {
        @Override
        public void close() {
            lifecycle.close();
        }
    }

    private LifecycleBundle lifecycle(Path path) {
        ArcadeDbGraphProjectionBackendFactory factory =
                new ArcadeDbGraphProjectionBackendFactory(path, GraphProjectionVersion.current());
        return new LifecycleBundle(new GraphProjectionLifecycleService(true, "arcadedb",
                GraphProjectionVersion.current(), lifecycleRepository, factory, currentness),
                factory);
    }

    private GraphWorkspaceScope workspace(String name) {
        return new GraphWorkspaceScope(workspaces.create(new CreateWorkspaceRequest(name,
                temp.resolve(name).toString())).id());
    }

    private String wiki(GraphWorkspaceScope scope, GoldenPage page) throws Exception {
        String content = new StringBuilder("---\n")
                .append("id: \"").append(page.knowledgeId()).append("\"\n")
                .append("title: \"").append(page.title()).append("\"\n")
                .append("type: \"CONCEPT\"\nstatus: \"PUBLISHED\"\n")
                .append("aliases: []\n")
                .append(renderList("tags", page.tags()))
                .append("sources: []\n")
                .append("created_at: \"").append(NOW).append("\"\n")
                .append("updated_at: \"").append(NOW).append("\"\n")
                .append("---\n\n# ").append(page.title()).append('\n').append(page.body())
                .toString();
        String logicalPath = paths.resolveLogicalPath(WikiPageType.CONCEPT, page.title());
        Path target = Path.of(workspaces.get(scope.id()).vaultPath())
                .resolve(logicalPath.substring("vault/".length()));
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
        String hash = WikiContentHash.sha256(content.getBytes(StandardCharsets.UTF_8));
        KeyHolder pageKey = new GeneratedKeyHolder();
        db().sql("""
                INSERT INTO knowledge_page(workspace_id, knowledge_id, title, normalized_title,
                    type, markdown_path, status, content_hash, revision, created_at, updated_at)
                VALUES(?, ?, ?, ?, 'CONCEPT', ?, 'PUBLISHED', ?, 1, ?, ?)
                """).params(scope.id(), page.knowledgeId(), page.title(),
                org.km.llmwiki.wiki.WikiTargetReference.normalizeTitle(page.title()), logicalPath,
                hash, NOW, NOW).update(pageKey);
        ftsRepository.upsertKnowledge(new KnowledgeSearchDocument(scope.id(), page.knowledgeId(),
                page.title(), org.km.llmwiki.wiki.WikiTargetReference.normalizeTitle(page.title()),
                page.body(), logicalPath, "CONCEPT", "PUBLISHED", hash));
        db().sql("""
                INSERT INTO knowledge_search_index_sync
                    (workspace_id, knowledge_page_id, knowledge_id, status, content_hash,
                     indexed_content_hash, indexed_revision, failure_detail, updated_at)
                VALUES (?, ?, ?, 'SYNCED', ?, ?, 1, NULL, ?)
                """).params(scope.id(), pageKey.getKey().longValue(), page.knowledgeId(), hash,
                hash, NOW).update();
        return hash;
    }

    private void indexEmbeddings(long workspaceId, Map<String, String> pageHashes)
            throws Exception {
        int expected = 0;
        for (GoldenPage page : CORPUS.pages()) {
            if (!page.embedded()) {
                continue;
            }
            expected++;
            List<Double> vector = EMBEDDER.embedText(page.title() + " " + page.body());
            byte[] blob = org.km.llmwiki.search.embedding.EmbeddingVectorCodec.encode(
                    new EmbeddingVector(EmbeddingInput.identityFor(page.body()), vector));
            embeddingRepository.upsertFresh(new EmbeddingProjectionIdentity(workspaceId,
                    EmbeddingEvidenceKind.WIKI, page.knowledgeId(),
                    pageHashes.get(page.knowledgeId()),
                    DeterministicConceptEmbeddingClient.PROVIDER,
                    DeterministicConceptEmbeddingClient.MODEL,
                    DeterministicConceptEmbeddingClient.DIMENSION,
                    DeterministicConceptEmbeddingClient.PROJECTION_VERSION), blob, NOW);
        }
        markProjectionReady(workspaceId, "wiki", EmbeddingEvidenceKind.WIKI, expected);
        markProjectionReady(workspaceId, "sources", EmbeddingEvidenceKind.SOURCE_CHUNK, 0);
    }

    private void markProjectionReady(long workspaceId, String jobName,
                                     EmbeddingEvidenceKind corpus, int expected) {
        // An incremental operation can only establish READY on top of a completed FULL
        // baseline; the fixture therefore replays the production full-then-incremental flow.
        long fullJob = jobs.create(workspaceId, "quality-full-" + jobName,
                ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long fullGeneration = embeddingReadiness.markQueued(workspaceId, fullJob, corpus, 0);
        embeddingReadiness.markRunning(workspaceId, fullJob, corpus);
        embeddingReadiness.markCompletedForGeneration(workspaceId, fullJob, corpus, fullGeneration,
                expected, expected, 0, DeterministicConceptEmbeddingClient.PROVIDER,
                DeterministicConceptEmbeddingClient.MODEL,
                DeterministicConceptEmbeddingClient.DIMENSION, true, "fixture-snapshot-proof");
        if (expected == 0) {
            return;
        }
        long jobId = jobs.create(workspaceId, "quality-" + jobName,
                ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long generation = embeddingReadiness.markQueued(workspaceId, jobId, corpus, expected);
        embeddingReadiness.markRunning(workspaceId, jobId, corpus);
        embeddingReadiness.markCompletedForGeneration(workspaceId, jobId, corpus, generation,
                expected, expected, 0, DeterministicConceptEmbeddingClient.PROVIDER,
                DeterministicConceptEmbeddingClient.MODEL,
                DeterministicConceptEmbeddingClient.DIMENSION, true, "fixture-snapshot-proof");
    }

    private static String renderList(String field, List<String> values) {
        if (values.isEmpty()) return field + ": []\n";
        StringBuilder result = new StringBuilder(field).append(":\n");
        values.forEach(value -> result.append("  - \"").append(value).append("\"\n"));
        return result.toString();
    }
}
