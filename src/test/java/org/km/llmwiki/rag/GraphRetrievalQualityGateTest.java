package org.km.llmwiki.rag;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.graph.GraphProjectionInput;
import org.km.llmwiki.graph.GraphProjectionInputAssembler;
import org.km.llmwiki.graph.GraphProjectionStatusResponse;
import org.km.llmwiki.graph.GraphWorkspaceScope;
import org.km.llmwiki.rag.GraphRetrievalGoldenCorpus.GoldenQuery;
import org.km.llmwiki.search.embedding.EmbeddingProjectionReadinessRepository;
import org.km.llmwiki.search.embedding.EmbeddingProjectionRepository;
import org.km.llmwiki.search.FtsSearchIndexRepository;
import org.km.llmwiki.search.SearchService;
import org.km.llmwiki.search.SourceSearchAuthorityRepository;
import org.km.llmwiki.graph.GraphCanonicalCurrentness;
import org.km.llmwiki.graph.GraphProjectionLifecycleRepository;
import org.km.llmwiki.processing.ProcessingJobRepository;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.km.llmwiki.wiki.PublishedWikiContentReader;
import org.km.llmwiki.wiki.PublishedWikiRepository;
import org.km.llmwiki.wiki.WikiPathContract;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression gate for graph-grounded retrieval quality over the golden corpus. The benchmark
 * runs the production-equivalent application contracts (real FTS, deterministic vector channel
 * fixtures over the real candidate service, real ArcadeDB projection with traversal/admission/
 * fusion) and gates on identity-level metrics only — no raw cross-scale score comparison, no
 * live provider, no network, no sleep.
 */
@Tag("integration")
class GraphRetrievalQualityGateTest extends IsolatedIntegrationTest {

    private static final GraphRetrievalGoldenCorpus CORPUS = new GraphRetrievalGoldenCorpus();
    private static final int K = 8;

    // Metric floors sit a modest headroom below the measured golden-corpus baseline recorded in
    // docs/development/testing.md (measured under the production policy fusion-rrf-v2-graph-
    // damped: HYBRID_FTS 0.444/0.667, HYBRID_VECTOR 0.889/1.000, HYBRID_GRAPH 1.000/1.000).
    // They are deliberately floors, not exact pins, so a reasonable ranking change is not
    // misread as a regression; a DROP below the floor is. A policy swap that measures lower
    // than the floor must update this gate through a documented calibration, not a silent edit.
    private static final double MIN_RECALL_HYBRID_FTS = 0.40d;
    private static final double MIN_RECALL_HYBRID_VECTOR = 0.80d;
    private static final double MIN_RECALL_HYBRID_GRAPH = 0.95d;
    private static final double MIN_MRR_HYBRID_FTS = 0.60d;
    private static final double MIN_MRR_HYBRID_VECTOR = 0.90d;
    private static final double MIN_MRR_HYBRID_GRAPH = 0.90d;

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
        GraphRetrievalQualityFixture fixture = new GraphRetrievalQualityFixture(db(), workspaces,
                searchService, publishedWikiRepository, publishedWikiContentReader,
                sourceAuthorityRepository, ftsRepository, embeddingRepository, embeddingReadiness,
                jobs, assembler, lifecycleRepository, currentness, paths);
        GraphRetrievalQualityFixture.CorpusMaterialization materialization =
                fixture.materializeCorpus(temp, "quality-corpus", CORPUS.pages());
        GraphWorkspaceScope active = materialization.active();
        fixture.mutateStaleHash(active, CORPUS.page("wiki-db-stale"));

        try (GraphRetrievalQualityFixture.LifecycleBundle lifecycleFactory =
                     fixture.lifecycle(temp.resolve("quality-graph"))) {
            // The gate runs the production ranking policy through the production constructor
            // chain, so a future calibrated policy is gated, not a hand-pinned test policy.
            FusedRetrievalOrchestrator orchestrator = fixture.orchestrator(
                    lifecycleFactory.lifecycle(), lifecycleFactory.factory(),
                    FusionRankingPolicy.production());
            RetrievalService retrievalService = fixture.retrievalService(orchestrator);

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

            Map<String, Function<String, EvidenceBundle>> runners = Map.of(
                    "HYBRID_FTS", text -> retrievalService.retrieve(
                            RetrievalRequest.defaults(text, RetrievalMode.HYBRID_FTS)),
                    "HYBRID_VECTOR", text -> retrievalService.retrieve(
                            RetrievalRequest.defaults(text, RetrievalMode.HYBRID_VECTOR)),
                    "HYBRID_GRAPH", text -> orchestrator.retrieveFused(
                            RetrievalRequest.defaults(text, RetrievalMode.HYBRID_GRAPH)));

            GraphRetrievalQualityBenchmark.Evaluation evaluation = GraphRetrievalQualityBenchmark
                    .evaluate(CORPUS.VERSION, FusionRankingPolicy.production().version(),
                            CORPUS.queries(), runners, K,
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

    private GoldenQuery degradedQuery() {
        return CORPUS.queries().stream()
                .filter(query -> query.id().equals("graph-added-discovery")).findFirst()
                .orElseThrow();
    }

    private GraphRetrievalQualityBenchmark.QueryOutcome fusedOutcome(
            FusedRetrievalOrchestrator orchestrator, GoldenQuery query, String queryId) {
        EvidenceBundle bundle = orchestrator.retrieveFused(
                RetrievalRequest.defaults(query.text(), RetrievalMode.HYBRID_GRAPH));
        return new GraphRetrievalQualityBenchmark.QueryOutcome(queryId, query.queryClass(),
                "HYBRID_GRAPH", bundle.items().stream()
                .map(EvidenceItem::stableIdentity).toList(),
                bundle.rejectedCandidateCount(),
                bundle.diagnostics().graphUnavailable(), bundle.diagnostics().graphDegraded());
    }

    private GraphRetrievalQualityBenchmark.QueryOutcome outcome(
            Map<String, Function<String, EvidenceBundle>> runners, String text) {
        EvidenceBundle bundle = runners.get("HYBRID_VECTOR").apply(text);
        return new GraphRetrievalQualityBenchmark.QueryOutcome("baseline", "BASELINE",
                "HYBRID_VECTOR", bundle.items().stream()
                .map(EvidenceItem::stableIdentity).toList(),
                bundle.rejectedCandidateCount(), bundle.diagnostics().graphUnavailable(),
                bundle.diagnostics().graphDegraded());
    }
}
