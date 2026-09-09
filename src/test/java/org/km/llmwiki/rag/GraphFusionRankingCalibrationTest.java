package org.km.llmwiki.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.graph.GraphCanonicalCurrentness;
import org.km.llmwiki.graph.GraphProjectionInput;
import org.km.llmwiki.graph.GraphProjectionInputAssembler;
import org.km.llmwiki.graph.GraphProjectionLifecycleRepository;
import org.km.llmwiki.graph.GraphProjectionStatusResponse;
import org.km.llmwiki.graph.GraphWorkspaceScope;
import org.km.llmwiki.processing.ProcessingJobRepository;
import org.km.llmwiki.rag.GraphRetrievalGoldenCorpus.GoldenQuery;
import org.km.llmwiki.search.FtsSearchIndexRepository;
import org.km.llmwiki.search.SearchService;
import org.km.llmwiki.search.SourceSearchAuthorityRepository;
import org.km.llmwiki.search.embedding.EmbeddingProjectionReadinessRepository;
import org.km.llmwiki.search.embedding.EmbeddingProjectionRepository;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.km.llmwiki.wiki.PublishedWikiContentReader;
import org.km.llmwiki.wiki.PublishedWikiRepository;
import org.km.llmwiki.wiki.WikiPathContract;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Offline fusion ranking calibration (STORY-813). Compares the recorded baseline policy against
 * bounded deterministic candidate policies over the golden corpus plus an isolated holdout
 * workspace, gates the decision with leave-one-query-out and sensitivity evidence, and writes a
 * versioned calibration report. The test re-verifies the recorded selection on every run: a
 * production policy change without the evidence recorded in docs/development/testing.md fails
 * here.
 */
@Tag("integration")
class GraphFusionRankingCalibrationTest extends IsolatedIntegrationTest {

    private static final GraphRetrievalGoldenCorpus GOLDEN = new GraphRetrievalGoldenCorpus();
    private static final GraphRetrievalHoldoutCorpus HOLDOUT = new GraphRetrievalHoldoutCorpus();
    private static final int K = 8;

    /**
     * The policy selected by this calibration and promoted to production: uniform k=60 RRF with
     * the graph channel damped to a bounded 0.75 contribution. The damping is an application
     * modality prior measured against graph-channel noise, not a per-query or identity hardcode;
     * the selection evidence (golden + holdout + leave-one-out + sensitivity) is recorded in
     * docs/development/testing.md and re-verified below.
     */
    private static final FusionRankingPolicy SELECTED = new FusionRankingPolicy(
            "fusion-rrf-v2-graph-damped", 60, Map.of(
            CandidateSignal.LEXICAL, 1.0d,
            CandidateSignal.VECTOR, 1.0d,
            CandidateSignal.GRAPH, 0.75d));

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
    void fusionRankingPolicySelectionIsEvidenceDrivenAndAntiOverfit() throws Exception {
        GraphRetrievalQualityFixture fixture = new GraphRetrievalQualityFixture(db(), workspaces,
                searchService, publishedWikiRepository, publishedWikiContentReader,
                sourceAuthorityRepository, ftsRepository, embeddingRepository, embeddingReadiness,
                jobs, assembler, lifecycleRepository, currentness, paths);
        GraphRetrievalQualityFixture.CorpusMaterialization goldenEnv =
                fixture.materializeCorpus(temp, "calibration-golden", GOLDEN.pages());
        GraphRetrievalQualityFixture.CorpusMaterialization holdoutEnv =
                fixture.materializeCorpus(temp, "calibration-holdout", HOLDOUT.pages());
        fixture.mutateStaleHash(goldenEnv.active(), GOLDEN.page("wiki-db-stale"));
        fixture.mutateStaleHash(holdoutEnv.active(), HOLDOUT.stalePage());

        List<FusionRankingPolicy> candidates = List.of(
                FusionRankingPolicy.baseline(), SELECTED,
                new FusionRankingPolicy("cand-k20-uniform", 20, Map.of(
                        CandidateSignal.LEXICAL, 1.0d, CandidateSignal.VECTOR, 1.0d,
                        CandidateSignal.GRAPH, 1.0d)),
                new FusionRankingPolicy("cand-k20-graph-damped", 20, Map.of(
                        CandidateSignal.LEXICAL, 1.0d, CandidateSignal.VECTOR, 1.0d,
                        CandidateSignal.GRAPH, 0.8d)),
                new FusionRankingPolicy("cand-k60-vector-boost", 60, Map.of(
                        CandidateSignal.LEXICAL, 1.0d, CandidateSignal.VECTOR, 1.1d,
                        CandidateSignal.GRAPH, 1.0d)));

        List<String> forbidden = List.of(GOLDEN.staleIdentity(), GOLDEN.foreignIdentity(),
                GOLDEN.mentionNegativeIdentity());
        // The holdout carries its own stale negative inside the holdout workspace; the golden
        // foreign-page identity also applies because every environment materializes a foreign
        // workspace with that same page.
        List<String> holdoutForbidden = HOLDOUT.forbiddenIdentities();
        List<String> safetyViolations = new ArrayList<>();
        Map<String, EvaluationPair> results = new LinkedHashMap<>();
        Map<String, List<Double>> sensitivity = new LinkedHashMap<>();

        try (GraphRetrievalQualityFixture.LifecycleBundle lifecycleFactory =
                     fixture.lifecycle(temp.resolve("calibration-graph"))) {

            // Degradation semantics are policy-independent; observed once for the selected
            // policy before any projection exists.
            workspaces.open(goldenEnv.active().id());
            FusedRetrievalOrchestrator selectedOrchestrator = fixture.orchestrator(
                    lifecycleFactory.lifecycle(), lifecycleFactory.factory(), SELECTED);
            EvidenceBundle degradedBundle = selectedOrchestrator.retrieveFused(
                    RetrievalRequest.defaults("資料庫鎖", RetrievalMode.HYBRID_GRAPH));
            assertThat(degradedBundle.diagnostics().graphUnavailable()).isTrue();
            assertThat(degradedBundle.diagnostics().graphDegraded()).isFalse();

            for (GraphWorkspaceScope scope : List.of(goldenEnv.active(), holdoutEnv.active())) {
                workspaces.open(scope.id());
                GraphProjectionInput input = assembler.assemble(scope);
                lifecycleFactory.lifecycle().rebuild(input);
                GraphProjectionStatusResponse projection = GraphProjectionStatusResponse.from(
                        lifecycleFactory.lifecycle().readiness(scope));
                assertThat(projection.status()).isEqualTo("READY");
            }

            for (FusionRankingPolicy policy : candidates) {
                FusedRetrievalOrchestrator orchestrator = fixture.orchestrator(
                        lifecycleFactory.lifecycle(), lifecycleFactory.factory(), policy);
                RetrievalService retrievalService = fixture.retrievalService(orchestrator);

                workspaces.open(goldenEnv.active().id());
                GraphRetrievalQualityBenchmark.Evaluation golden = GraphRetrievalQualityBenchmark
                        .evaluate(GOLDEN.VERSION, policy.version(), GOLDEN.queries(),
                                goldenRunners(retrievalService, orchestrator), K,
                                "graph-projection-v2", 1L, forbidden, null, Set.of());
                safetyViolations.addAll(golden.safetyViolations());

                workspaces.open(holdoutEnv.active().id());
                GraphRetrievalQualityBenchmark.Evaluation holdout =
                        GraphRetrievalQualityBenchmark.evaluate(HOLDOUT.VERSION,
                                policy.version(), HOLDOUT.queries(),
                                goldenRunners(retrievalService, orchestrator), K,
                                "graph-projection-v2", 1L, holdoutForbidden, null, Set.of());
                safetyViolations.addAll(holdout.safetyViolations());
                results.put(policy.version(), new EvaluationPair(golden, holdout, policy));
            }

            // Sensitivity neighborhood of the selected point: nearby (k, weight) points must
            // keep the improvement, so the decision is not a single lucky parameter value.
            for (int deltaK : List.of(-20, 0, 20)) {
                for (double deltaWeight : List.of(-0.1d, 0.0d, 0.1d)) {
                    int k = SELECTED.k() + deltaK;
                    double weight = SELECTED.weight(CandidateSignal.GRAPH) + deltaWeight;
                    if (k < FusionRankingPolicy.MIN_K || k > FusionRankingPolicy.MAX_K
                            || weight < FusionRankingPolicy.MIN_WEIGHT
                            || weight > FusionRankingPolicy.MAX_WEIGHT) {
                        continue;
                    }
                    FusionRankingPolicy neighbor = new FusionRankingPolicy("sensitivity", k,
                            Map.of(CandidateSignal.LEXICAL, 1.0d, CandidateSignal.VECTOR, 1.0d,
                                    CandidateSignal.GRAPH, weight));
                    FusedRetrievalOrchestrator orchestrator = fixture.orchestrator(
                            lifecycleFactory.lifecycle(), lifecycleFactory.factory(), neighbor);

                    workspaces.open(goldenEnv.active().id());
                    GraphRetrievalQualityBenchmark.Evaluation golden = GraphRetrievalQualityBenchmark
                            .evaluate(GOLDEN.VERSION, "sensitivity", GOLDEN.queries(),
                                    Map.of("HYBRID_GRAPH", text -> orchestrator.retrieveFused(
                                            RetrievalRequest.defaults(text,
                                                    RetrievalMode.HYBRID_GRAPH))), K,
                                    "graph-projection-v2", 1L, forbidden, null, Set.of());
                    workspaces.open(holdoutEnv.active().id());
                    GraphRetrievalQualityBenchmark.Evaluation holdout =
                            GraphRetrievalQualityBenchmark.evaluate(HOLDOUT.VERSION,
                                    "sensitivity", HOLDOUT.queries(),
                                    Map.of("HYBRID_GRAPH", text -> orchestrator.retrieveFused(
                                            RetrievalRequest.defaults(text,
                                                    RetrievalMode.HYBRID_GRAPH))), K,
                                    "graph-projection-v2", 1L, holdoutForbidden, null, Set.of());
                    safetyViolations.addAll(golden.safetyViolations());
                    safetyViolations.addAll(holdout.safetyViolations());
                    sensitivity.put(neighbor.k() + "/" + weight, List.of(
                            (double) (golden.aggregates().get("HYBRID_GRAPH").graphAddedFound()
                                    + holdout.aggregates().get("HYBRID_GRAPH").graphAddedFound()),
                            (golden.aggregates().get("HYBRID_GRAPH").recallAtK()
                                    + holdout.aggregates().get("HYBRID_GRAPH").recallAtK()) / 2.0d,
                            (golden.aggregates().get("HYBRID_GRAPH").mrr()
                                    + holdout.aggregates().get("HYBRID_GRAPH").mrr()) / 2.0d));
                }
            }
        }

        // Safety is a precondition for every candidate and sensitivity point, never a metric.
        assertThat(safetyViolations).isEmpty();

        // The calibration always re-verifies the production wiring: the promoted registry
        // policy and this test's selected policy must stay the same object.
        assertThat(FusionRankingPolicy.production()).isEqualTo(SELECTED);
        assertThat(FusionRankingPolicy.byVersion(SELECTED.version())).isEqualTo(SELECTED);

        EvaluationPair baseline = results.get(FusionRankingPolicy.baseline().version());
        EvaluationPair selected = results.get(SELECTED.version());

        // AC A: the baseline reproduction must match the STORY-812 recorded numbers.
        assertThat(baseline.golden().aggregates().get("HYBRID_FTS").recallAtK())
                .isCloseTo(0.4444d, within(0.001d));
        assertThat(baseline.golden().aggregates().get("HYBRID_FTS").mrr())
                .isCloseTo(0.6667d, within(0.001d));
        assertThat(baseline.golden().aggregates().get("HYBRID_VECTOR").recallAtK())
                .isCloseTo(0.8889d, within(0.001d));
        assertThat(baseline.golden().aggregates().get("HYBRID_VECTOR").mrr())
                .isCloseTo(1.0d, within(0.001d));
        assertThat(baseline.golden().aggregates().get("HYBRID_GRAPH").recallAtK())
                .isCloseTo(1.0d, within(0.001d));
        assertThat(baseline.golden().aggregates().get("HYBRID_GRAPH").mrr())
                .isCloseTo(0.8333d, within(0.001d));

        // The ranking policy may only reorder the fused boundary: FTS/VECTOR aggregates are
        // identical across every candidate.
        for (EvaluationPair candidate : results.values()) {
            assertThat(candidate.golden().aggregates().get("HYBRID_FTS"))
                    .isEqualTo(baseline.golden().aggregates().get("HYBRID_FTS"));
            assertThat(candidate.golden().aggregates().get("HYBRID_VECTOR"))
                    .isEqualTo(baseline.golden().aggregates().get("HYBRID_VECTOR"));
        }

        // AC C: the selected policy must retain graph-added discovery and strictly improve the
        // fused ordering on the combined corpus without losing recall.
        assertThat(selected.combinedGraphAddedFound()).isEqualTo(2);
        assertThat(baseline.combinedGraphAddedFound()).isEqualTo(2);
        assertThat(selected.combinedGraphRecall())
                .isGreaterThanOrEqualTo(baseline.combinedGraphRecall());
        assertThat(selected.combinedGraphMrr()).isGreaterThan(baseline.combinedGraphMrr());

        // Rejected candidates are recorded with their measured numbers; none beats the
        // selected policy on the combined corpus.
        for (EvaluationPair candidate : results.values()) {
            if (candidate.policy().version().equals(SELECTED.version())) {
                continue;
            }
            assertThat(candidate.combinedGraphMrr())
                    .as("candidate %s", candidate.policy().version())
                    .isLessThanOrEqualTo(selected.combinedGraphMrr());
        }

        // AC D: leave-one-query-out — the improvement must not depend on a single scenario.
        List<String> allQueryIds = new ArrayList<>(queryIds(baseline.golden()));
        allQueryIds.addAll(queryIds(baseline.holdout()));
        for (String held : allQueryIds) {
            double baselineFold = baseline.graphMrrWithout(allQueryIds, held);
            double selectedFold = selected.graphMrrWithout(allQueryIds, held);
            assertThat(selectedFold)
                    .as("LOO fold without %s must not regress against the same fold", held)
                    .isGreaterThanOrEqualTo(baselineFold);
        }

        // AC D: sensitivity — every neighborhood point keeps graph-added discovery, recall,
        // and at least the baseline ordering quality.
        double baselineMrr = baseline.combinedGraphMrr();
        double baselineRecall = baseline.combinedGraphRecall();
        for (Map.Entry<String, List<Double>> point : sensitivity.entrySet()) {
            assertThat(point.getValue().get(0))
                    .as("sensitivity point %s must keep both graph-added targets",
                            point.getKey())
                    .isEqualTo(2.0d);
            assertThat(point.getValue().get(1))
                    .as("sensitivity point %s must keep recall", point.getKey())
                    .isGreaterThanOrEqualTo(baselineRecall);
            assertThat(point.getValue().get(2))
                    .as("sensitivity point %s must stay at or above the baseline ordering",
                            point.getKey())
                    .isGreaterThanOrEqualTo(baselineMrr);
        }

        writeCalibrationReport(results, sensitivity, allQueryIds, baseline, selected);
    }

    private Map<String, Function<String, EvidenceBundle>> goldenRunners(
            RetrievalService retrievalService, FusedRetrievalOrchestrator orchestrator) {
        return Map.of(
                "HYBRID_FTS", text -> retrievalService.retrieve(
                        RetrievalRequest.defaults(text, RetrievalMode.HYBRID_FTS)),
                "HYBRID_VECTOR", text -> retrievalService.retrieve(
                        RetrievalRequest.defaults(text, RetrievalMode.HYBRID_VECTOR)),
                "HYBRID_GRAPH", text -> orchestrator.retrieveFused(
                        RetrievalRequest.defaults(text, RetrievalMode.HYBRID_GRAPH)));
    }

    private List<String> queryIds(GraphRetrievalQualityBenchmark.Evaluation evaluation) {
        return evaluation.metrics().stream()
                .map(GraphRetrievalQualityBenchmark.QueryMetrics::queryId).distinct().toList();
    }

    private void writeCalibrationReport(Map<String, EvaluationPair> results,
                                        Map<String, List<Double>> sensitivity,
                                        List<String> allQueryIds, EvaluationPair baseline,
                                        EvaluationPair selected) {
        ObjectNode root = new ObjectMapper().createObjectNode();
        root.put("goldenCorpus", GOLDEN.VERSION);
        root.put("holdoutCorpus", HOLDOUT.VERSION);
        root.put("baselinePolicy", baseline.policy().version());
        root.put("selectedPolicy", selected.policy().version());
        ArrayNode policies = root.putArray("policies");
        for (EvaluationPair pair : results.values()) {
            ObjectNode node = policies.addObject();
            node.put("version", pair.policy().version());
            node.put("k", pair.policy().k());
            node.put("graphWeight", pair.policy().weight(CandidateSignal.GRAPH));
            node.put("goldenGraphMrr", pair.golden().aggregates().get("HYBRID_GRAPH").mrr());
            node.put("goldenGraphRecall",
                    pair.golden().aggregates().get("HYBRID_GRAPH").recallAtK());
            node.put("holdoutGraphMrr", pair.holdout().aggregates().get("HYBRID_GRAPH").mrr());
            node.put("combinedGraphMrr", pair.combinedGraphMrr());
            node.put("combinedGraphRecall", pair.combinedGraphRecall());
            node.put("graphAddedFound", pair.combinedGraphAddedFound());
        }
        ArrayNode folds = root.putArray("leaveOneQueryOut");
        for (String held : allQueryIds) {
            ObjectNode node = folds.addObject();
            node.put("heldOut", held);
            node.put("baselineMrr", baseline.graphMrrWithout(allQueryIds, held));
            node.put("selectedMrr", selected.graphMrrWithout(allQueryIds, held));
        }
        ObjectNode sensitivityNode = root.putObject("sensitivity");
        sensitivity.forEach((key, values) -> {
            ObjectNode node = sensitivityNode.putObject(key);
            node.put("graphAddedFound", values.get(0));
            node.put("combinedGraphRecall", values.get(1));
            node.put("combinedGraphMrr", values.get(2));
        });
        try {
            Path directory = Path.of("target", "quality-reports");
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("fusion-ranking-calibration.json"),
                    new ObjectMapper().writeValueAsString(root) + "\n");
            Files.writeString(directory.resolve("fusion-ranking-calibration.md"),
                    renderMarkdown(results, sensitivity, baseline, selected));
        } catch (IOException failure) {
            throw new IllegalStateException("Calibration report could not be written", failure);
        }
    }

    private String renderMarkdown(Map<String, EvaluationPair> results,
                                  Map<String, List<Double>> sensitivity,
                                  EvaluationPair baseline, EvaluationPair selected) {
        StringBuilder report = new StringBuilder();
        report.append("# Fusion ranking calibration report\n\n");
        report.append("- baseline policy: `").append(baseline.policy().version())
                .append("`\n- selected policy: `").append(selected.policy().version())
                .append("`\n\n");
        report.append("| policy | k | graph weight | golden mrr | holdout mrr | combined mrr | combined recall | graph-added |\n");
        report.append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (EvaluationPair pair : results.values()) {
            report.append("| `").append(pair.policy().version()).append("` | ")
                    .append(pair.policy().k()).append(" | ")
                    .append(pair.policy().weight(CandidateSignal.GRAPH)).append(" | ")
                    .append(fmt(pair.golden().aggregates().get("HYBRID_GRAPH").mrr()))
                    .append(" | ")
                    .append(fmt(pair.holdout().aggregates().get("HYBRID_GRAPH").mrr()))
                    .append(" | ").append(fmt(pair.combinedGraphMrr())).append(" | ")
                    .append(fmt(pair.combinedGraphRecall())).append(" | ")
                    .append(pair.combinedGraphAddedFound()).append("/2 |\n");
        }
        report.append("\n## Sensitivity neighborhood (combined golden+holdout)\n\n");
        report.append("| k/graph weight | graph-added | recall | mrr |\n| --- | --- | --- | --- |\n");
        for (Map.Entry<String, List<Double>> entry : sensitivity.entrySet()) {
            report.append("| ").append(entry.getKey()).append(" | ")
                    .append(entry.getValue().get(0).intValue()).append(" | ")
                    .append(fmt(entry.getValue().get(1))).append(" | ")
                    .append(fmt(entry.getValue().get(2))).append(" |\n");
        }
        report.append("\nBaseline combined mrr: ").append(fmt(baseline.combinedGraphMrr()))
                .append("; selected combined mrr: ").append(fmt(selected.combinedGraphMrr()))
                .append('\n');
        return report.toString();
    }

    private static String fmt(double value) {
        return String.format("%.4f", value);
    }

    /** Golden and holdout evaluations for one candidate policy. */
    private record EvaluationPair(GraphRetrievalQualityBenchmark.Evaluation golden,
                                  GraphRetrievalQualityBenchmark.Evaluation holdout,
                                  FusionRankingPolicy policy) {

        double combinedGraphMrr() {
            return (golden().aggregates().get("HYBRID_GRAPH").mrr()
                    + holdout().aggregates().get("HYBRID_GRAPH").mrr()) / 2.0d;
        }

        double combinedGraphRecall() {
            return (golden().aggregates().get("HYBRID_GRAPH").recallAtK()
                    + holdout().aggregates().get("HYBRID_GRAPH").recallAtK()) / 2.0d;
        }

        int combinedGraphAddedFound() {
            return golden().aggregates().get("HYBRID_GRAPH").graphAddedFound()
                    + holdout().aggregates().get("HYBRID_GRAPH").graphAddedFound();
        }

        double graphMrrWithout(List<String> allQueryIds, String held) {
            List<Double> values = new ArrayList<>();
            for (GraphRetrievalQualityBenchmark.QueryMetrics metric : golden().metrics()) {
                if (metric.mode().equals("HYBRID_GRAPH") && !metric.queryId().equals(held)) {
                    values.add(metric.mrr());
                }
            }
            for (GraphRetrievalQualityBenchmark.QueryMetrics metric : holdout().metrics()) {
                if (metric.mode().equals("HYBRID_GRAPH") && !metric.queryId().equals(held)) {
                    values.add(metric.mrr());
                }
            }
            return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0d);
        }
    }
}
