package org.km.llmwiki.rag;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.graph.GraphCanonicalCurrentness;
import org.km.llmwiki.graph.GraphProjectionInput;
import org.km.llmwiki.graph.GraphProjectionInputAssembler;
import org.km.llmwiki.graph.GraphProjectionLifecycleRepository;
import org.km.llmwiki.graph.GraphProjectionStatusResponse;
import org.km.llmwiki.graph.GraphWorkspaceScope;
import org.km.llmwiki.persistence.graph.arcadedb.ArcadeDbGraphProjectionBackendFactory;
import org.km.llmwiki.processing.ProcessingJobRepository;
import org.km.llmwiki.rag.GraphRetrievalGoldenCorpus.GoldenQuery;
import org.km.llmwiki.rag.GraphRetrievalQualityBenchmark.Evaluation;
import org.km.llmwiki.rag.GraphRetrievalQualityBenchmark.QueryMetrics;
import org.km.llmwiki.rag.GraphRetrievalQualityBenchmark.QueryOutcome;
import org.km.llmwiki.search.FtsSearchIndexRepository;
import org.km.llmwiki.search.SearchService;
import org.km.llmwiki.search.SourceSearchAuthorityRepository;
import org.km.llmwiki.search.embedding.EmbeddingProjectionReadinessRepository;
import org.km.llmwiki.search.embedding.EmbeddingProjectionRepository;
import org.km.llmwiki.source.DocumentRepository;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.km.llmwiki.wiki.PublishedWikiContentReader;
import org.km.llmwiki.wiki.PublishedWikiRepository;
import org.km.llmwiki.wiki.WikiContentHash;
import org.km.llmwiki.wiki.WikiPathContract;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Graph ranking generalization gate over the diversified evaluation corpus
 * ({@code graph-retrieval-evaluation-v2}). The production-equivalent application pipeline (real
 * FTS, deterministic vector fixtures over the real candidate service, real ArcadeDB projection
 * with traversal/admission/fusion) runs every corpus query through all three fused modes under
 * both the calibrated baseline policy and the production policy. Gates are identity-level:
 * authority safety, graph-added discovery per admitted relation scenario, no-regression of the
 * production policy against the calibrated baseline, and lexical/vector invariance across
 * policies. The report is written to {@code target/quality-reports/} and never enters Git.
 */
@Tag("integration")
class GraphRetrievalGeneralizationEvaluationTest extends IsolatedIntegrationTest {

    private static final GraphRetrievalEvaluationCorpusV2 CORPUS =
            new GraphRetrievalEvaluationCorpusV2();
    private static final int K = 8;
    private static final String SELECTED_POLICY_VERSION = "fusion-rrf-v2-graph-damped";

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
    @Autowired DocumentRepository documents;

    @Test
    void fusedGraphRankingGeneralizesAcrossAdmittedRelationScenarios() throws Exception {
        GraphRetrievalQualityFixture fixture = new GraphRetrievalQualityFixture(db(), workspaces,
                searchService, publishedWikiRepository, publishedWikiContentReader,
                sourceAuthorityRepository, ftsRepository, embeddingRepository, embeddingReadiness,
                jobs, assembler, lifecycleRepository, currentness, paths);
        GraphWorkspaceScope active = fixture.workspace(temp, "generalization-eval-v2");
        GraphWorkspaceScope foreign = fixture.workspace(temp, "generalization-eval-v2-foreign");
        fixture.openWorkspace(active);
        fixture.writeForeignGoldenPages(foreign);

        // Dependency fixtures: one processed, extraction-complete document whose single chunk is
        // the DERIVED_FROM→CONTAINS citation target, and one deleted legacy document whose
        // chunk identity must never be admitted.
        long restoreDoc = documents.insert(active.id(), "restore-runbook.txt", "restore-runbook.txt",
                "txt", "inbox/restore-runbook.txt",
                WikiContentHash.sha256("restore-runbook.txt".getBytes(StandardCharsets.UTF_8)),
                100L, "text/plain", GraphRetrievalQualityFixture.NOW, "PROCESSED", null, null);
        documents.markExtractionSucceeded(restoreDoc,
                WikiContentHash.sha256("restore-runbook-extracted".getBytes(StandardCharsets.UTF_8)));
        fixture.insertChunk(restoreDoc, 1, "還原演練的執行節奏與檢核紀錄。");
        String restoreChunkIdentity = "SOURCE_CHUNK:" + fixture.sourceChunkId(restoreDoc, 1);

        long legacyDoc = documents.insert(active.id(), "legacy-archive.txt", "legacy-archive.txt",
                "txt", "inbox/legacy-archive.txt",
                WikiContentHash.sha256("legacy-archive.txt".getBytes(StandardCharsets.UTF_8)),
                100L, "text/plain", GraphRetrievalQualityFixture.NOW, "DELETED", null, null);
        fixture.insertChunk(legacyDoc, 1, "封存排程的歷史文件片段。");
        String legacyChunkIdentity = "SOURCE_CHUNK:" + fixture.sourceChunkId(legacyDoc, 1);

        fixture.materializeInto(active, foreign, CORPUS.pages(restoreDoc));
        fixture.mutateStaleHash(active, stalePage(restoreDoc));

        List<GoldenQuery> queries = CORPUS.queries(restoreChunkIdentity);
        GoldenQuery degradedQuery = queries.stream()
                .filter(query -> query.id().equals("wiki-2hop")).findFirst().orElseThrow();
        List<String> forbiddenIdentities = new ArrayList<>(
                CORPUS.safetyIdentities(legacyChunkIdentity));
        forbiddenIdentities.add(new GraphRetrievalGoldenCorpus().foreignIdentity());

        try (GraphRetrievalQualityFixture.LifecycleBundle lifecycleFactory =
                     fixture.lifecycle(temp.resolve("generalization-graph"))) {
            FusedRetrievalOrchestrator baselineOrchestrator = fixture.orchestrator(
                    lifecycleFactory.lifecycle(), lifecycleFactory.factory(),
                    FusionRankingPolicy.baseline());
            RetrievalService baselineRetrieval = fixture.retrievalService(baselineOrchestrator);

            // Degradation observation happens before the projection exists: HYBRID_GRAPH must
            // degrade to the lexical + vector baseline instead of failing or inventing results.
            QueryOutcome degraded = fusedOutcome(baselineOrchestrator, degradedQuery, "degraded");
            assertThat(degraded.graphUnavailable()).isTrue();
            assertThat(degraded.graphDegraded()).isFalse();

            GraphProjectionInput input = assembler.assemble(active);
            lifecycleFactory.lifecycle().rebuild(input);
            GraphProjectionStatusResponse projection = GraphProjectionStatusResponse.from(
                    lifecycleFactory.lifecycle().readiness(active));
            assertThat(projection.status()).isEqualTo("READY");

            FusedRetrievalOrchestrator selectedOrchestrator = fixture.orchestrator(
                    lifecycleFactory.lifecycle(), lifecycleFactory.factory(),
                    FusionRankingPolicy.production());
            RetrievalService selectedRetrieval = fixture.retrievalService(selectedOrchestrator);

            Map<String, Function<String, EvidenceBundle>> baselineRunners =
                    runners(baselineRetrieval, baselineOrchestrator);
            Map<String, Function<String, EvidenceBundle>> selectedRunners =
                    runners(selectedRetrieval, selectedOrchestrator);

            QueryOutcome baselineExpectation = outcome(baselineRunners, degradedQuery.text());
            Evaluation baseline = GraphRetrievalQualityBenchmark.evaluate(CORPUS.VERSION,
                    FusionRankingPolicy.baseline().version(), queries, baselineRunners, K,
                    projection.projectionVersion(), projection.appliedGeneration(),
                    forbiddenIdentities, degraded,
                    Set.copyOf(baselineExpectation.retrieved()));
            Evaluation selected = GraphRetrievalQualityBenchmark.evaluate(CORPUS.VERSION,
                    FusionRankingPolicy.production().version(), queries, selectedRunners, K,
                    projection.projectionVersion(), projection.appliedGeneration(),
                    forbiddenIdentities, degraded,
                    Set.copyOf(baselineExpectation.retrieved()));

            writeReportAndAssertGates(baseline, selected, queries);
        }
    }

    private GraphRetrievalGoldenCorpus.GoldenPage stalePage(long restoreDocumentId) {
        return CORPUS.pages(restoreDocumentId).stream()
                .filter(page -> page.knowledgeId()
                        .equals(GraphRetrievalEvaluationCorpusV2.STALE_PAGE))
                .findFirst().orElseThrow();
    }

    private Map<String, Function<String, EvidenceBundle>> runners(
            RetrievalService retrievalService, FusedRetrievalOrchestrator orchestrator) {
        return Map.of(
                "HYBRID_FTS", text -> retrievalService.retrieve(
                        RetrievalRequest.defaults(text, RetrievalMode.HYBRID_FTS)),
                "HYBRID_VECTOR", text -> retrievalService.retrieve(
                        RetrievalRequest.defaults(text, RetrievalMode.HYBRID_VECTOR)),
                "HYBRID_GRAPH", text -> orchestrator.retrieveFused(
                        RetrievalRequest.defaults(text, RetrievalMode.HYBRID_GRAPH)));
    }

    private QueryOutcome fusedOutcome(FusedRetrievalOrchestrator orchestrator,
                                      GoldenQuery query, String queryId) {
        EvidenceBundle bundle = orchestrator.retrieveFused(
                RetrievalRequest.defaults(query.text(), RetrievalMode.HYBRID_GRAPH));
        return new QueryOutcome(queryId, query.queryClass(), "HYBRID_GRAPH",
                bundle.items().stream().map(EvidenceItem::stableIdentity).toList(),
                bundle.rejectedCandidateCount(), bundle.diagnostics().graphUnavailable(),
                bundle.diagnostics().graphDegraded());
    }

    private QueryOutcome outcome(Map<String, Function<String, EvidenceBundle>> runners,
                                 String text) {
        EvidenceBundle bundle = runners.get("HYBRID_VECTOR").apply(text);
        return new QueryOutcome("baseline", "BASELINE", "HYBRID_VECTOR",
                bundle.items().stream().map(EvidenceItem::stableIdentity).toList(),
                bundle.rejectedCandidateCount(), bundle.diagnostics().graphUnavailable(),
                bundle.diagnostics().graphDegraded());
    }

    private void writeReportAndAssertGates(Evaluation baseline, Evaluation selected,
                                           List<GoldenQuery> queries) throws IOException {
        List<String> gateFailures = new ArrayList<>();
        if (!baseline.safetyViolations().isEmpty()) {
            gateFailures.add("baseline safety violations: " + baseline.safetyViolations());
        }
        if (!selected.safetyViolations().isEmpty()) {
            gateFailures.add("selected safety violations: " + selected.safetyViolations());
        }
        if (!SELECTED_POLICY_VERSION.equals(selected.rankingPolicyVersion())) {
            gateFailures.add("production policy mutated: " + selected.rankingPolicyVersion());
        }
        for (String mode : List.of("HYBRID_FTS", "HYBRID_VECTOR")) {
            if (baseline.aggregates().get(mode).graphAddedFound() != 0
                    || selected.aggregates().get(mode).graphAddedFound() != 0) {
                gateFailures.add(mode + " retrieved a graph-only identity");
            }
        }
        // Degraded graph must retain the lexical + vector baseline for the same query in both
        // policy runs; an unavailable signal may never masquerade as a normal empty result.
        if (!baseline.degradedBaselineRetained() || !selected.degradedBaselineRetained()) {
            gateFailures.add("degraded HYBRID_GRAPH did not retain the lexical + vector baseline");
        }
        Map<String, QueryMetrics> baselineMetrics = index(baseline.metrics());
        for (GoldenQuery query : queries) {
            QueryMetrics graphMetric = selectedMetrics(selected, query.id(), "HYBRID_GRAPH");
            QueryMetrics baselineGraphMetric = baselineMetrics.get(query.id() + "/HYBRID_GRAPH");
            // Graph-added discovery is the scenario's core claim: under the selected production
            // policy every graph-only relevant target must be retrieved, and the baseline modes
            // must never retrieve one (otherwise the graph gain would be a fixture artifact).
            if (graphMetric != null
                    && !new java.util.LinkedHashSet<>(graphMetric.graphOnlyFound())
                    .equals(query.graphOnlyRelevant())) {
                gateFailures.add("HYBRID_GRAPH missed graph-only targets at " + query.id()
                        + ": expected " + query.graphOnlyRelevant() + " found "
                        + graphMetric.graphOnlyFound());
            }
            if (baselineGraphMetric != null && graphMetric != null
                    && (baselineGraphMetric.mrr() > graphMetric.mrr()
                    || baselineGraphMetric.recallAtK() > graphMetric.recallAtK())) {
                gateFailures.add("graph regression under selected policy at " + query.id());
            }
        }
        assertPerQueryGates(baseline, selected, gateFailures);

        String decision = gateFailures.isEmpty() ? "GO" : "NO-GO";
        writeReports(baseline, selected, decision, gateFailures, queries);
        assertThat(gateFailures).as("generalization decision " + decision).isEmpty();
    }

    private QueryMetrics selectedMetrics(Evaluation evaluation, String queryId, String mode) {
        return evaluation.metrics().stream()
                .filter(metric -> metric.queryId().equals(queryId) && metric.mode().equals(mode))
                .findFirst().orElse(null);
    }

    private void assertPerQueryGates(Evaluation baseline, Evaluation selected,
                                     List<String> gateFailures) {
        Map<String, QueryMetrics> baselineMetrics = index(baseline.metrics());
        Map<String, QueryMetrics> selectedMetrics = index(selected.metrics());
        for (QueryMetrics metric : selected.metrics()) {
            QueryMetrics baselineMetric = baselineMetrics.get(key(metric));
            if (baselineMetric == null) {
                gateFailures.add("missing baseline metric for " + key(metric));
                continue;
            }
            if ("HYBRID_GRAPH".equals(metric.mode())) {
                if (metric.mrr() < baselineMetric.mrr()
                        || metric.recallAtK() < baselineMetric.recallAtK()) {
                    gateFailures.add("graph regression under selected policy at " + key(metric));
                }
            } else {
                // The lexical and vector channels do not consume the fused ranking policy, so
                // their retrieved order must be invariant across policy evaluations.
                if (!baselineMetric.retrieved().equals(metric.retrieved())
                        || baselineMetric.recallAtK() != metric.recallAtK()
                        || baselineMetric.mrr() != metric.mrr()) {
                    gateFailures.add(metric.mode() + " drift across policies at " + key(metric));
                }
            }
            if (!metric.graphOnlyFound().equals(baselineMetric.graphOnlyFound())) {
                gateFailures.add("graph-only discovery drift at " + key(metric));
            }
        }
    }

    private String key(QueryMetrics metric) {
        return metric.queryId() + "/" + metric.mode();
    }

    private Map<String, QueryMetrics> index(List<QueryMetrics> metrics) {
        return metrics.stream().collect(Collectors.toMap(this::key, metric -> metric,
                (first, second) -> first, LinkedHashMap::new));
    }

    private void writeReports(Evaluation baseline, Evaluation selected, String decision,
                              List<String> gateFailures, List<GoldenQuery> queries)
            throws IOException {
        Path reports = Path.of("target", "quality-reports");
        Files.createDirectories(reports);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("corpus", CORPUS.VERSION);
        json.put("decision", decision);
        json.put("baselinePolicy", baseline);
        json.put("selectedPolicy", selected);
        json.put("gateFailures", gateFailures);
        Files.writeString(reports.resolve("graph-retrieval-generalization-v2.json"),
                new ObjectMapper().writeValueAsString(json) + "\n");
        Files.writeString(reports.resolve("graph-retrieval-generalization-v2.md"),
                markdown(baseline, selected, decision, gateFailures, queries));
    }

    private String markdown(Evaluation baseline, Evaluation selected, String decision,
                            List<String> gateFailures, List<GoldenQuery> queries) {
        StringBuilder report = new StringBuilder();
        report.append("# Graph ranking generalization report (v2)\n\n");
        report.append("- corpus: `").append(CORPUS.VERSION).append("`\n");
        report.append("- baseline policy: `").append(baseline.rankingPolicyVersion())
                .append("`, selected policy: `").append(selected.rankingPolicyVersion())
                .append("`\n");
        report.append("- k: ").append(selected.k()).append(", graph projection: `")
                .append(selected.graphProjectionVersion()).append("` generation ")
                .append(selected.graphAppliedGeneration()).append('\n');
        report.append("- decision: **").append(decision).append("**\n\n");
        report.append("## Decision gates\n\n")
                .append(gateFailures.isEmpty() ? "all gates passed\n"
                        : gateFailures.toString() + "\n").append('\n');
        report.append("## Mode aggregates\n\n");
        report.append("| policy | mode | recall@k | mrr | noise | graph-added found/expected |\n");
        report.append("| --- | --- | --- | --- | --- | --- |\n");
        for (Map.Entry<String, Evaluation> policy : Map.of("baseline", baseline,
                "selected", selected).entrySet()) {
            for (Map.Entry<String, GraphRetrievalQualityBenchmark.ModeAggregate> entry
                    : policy.getValue().aggregates().entrySet()) {
                GraphRetrievalQualityBenchmark.ModeAggregate aggregate = entry.getValue();
                report.append("| ").append(policy.getKey()).append(" | ").append(entry.getKey())
                        .append(" | ").append(String.format("%.4f", aggregate.recallAtK()))
                        .append(" | ").append(String.format("%.4f", aggregate.mrr()))
                        .append(" | ").append(aggregate.noiseTotal()).append(" | ")
                        .append(aggregate.graphAddedFound()).append('/')
                        .append(aggregate.graphAddedExpected()).append(" |\n");
            }
        }
        report.append("\n## Per-class diagnostics (selected policy)\n\n");
        report.append("| queryClass | mode | recall@k | mrr | graph-added found/expected |\n");
        report.append("| --- | --- | --- | --- | --- |\n");
        for (Map.Entry<String, List<ClassAggregate>> entry : perClass(selected, queries)
                .entrySet()) {
            for (ClassAggregate aggregate : entry.getValue()) {
                report.append("| ").append(entry.getKey()).append(" | ").append(aggregate.mode())
                        .append(" | ").append(String.format("%.4f", aggregate.recall()))
                        .append(" | ").append(String.format("%.4f", aggregate.mrr()))
                        .append(" | ").append(aggregate.graphFound()).append('/')
                        .append(aggregate.graphExpected()).append(" |\n");
            }
        }
        report.append("\n## Per-query detail\n\n");
        for (QueryMetrics metric : selected.metrics()) {
            QueryMetrics baselineMetric = index(baseline.metrics()).get(key(metric));
            report.append("### ").append(metric.queryId()).append(" / ").append(metric.mode())
                    .append(" (").append(metric.queryClass()).append(")\n\n");
            report.append("- baseline mrr: ")
                    .append(baselineMetric == null ? "n/a"
                            : String.format("%.4f", baselineMetric.mrr()))
                    .append(", selected mrr: ").append(String.format("%.4f", metric.mrr()))
                    .append('\n');
            report.append("- retrieved: ").append(metric.retrieved()).append('\n');
            report.append("- missed relevant: ").append(metric.relevantMissed()).append('\n');
            report.append("- noise (retrieved, not relevant): ").append(metric.noise())
                    .append('\n');
            report.append("- graph-added relevant found: ").append(metric.graphOnlyFound())
                    .append("\n\n");
        }
        report.append("## Relation coverage notes\n\n");
        report.append("- LINKS_TO: direct 1-hop, 2-hop via an intermediate hub, and multi-target\n")
                .append("  scenarios are all measured above.\n");
        report.append("- DERIVED_FROM→CONTAINS: the source-chunk citation target is reachable\n")
                .append("  only through the graph channel; the intermediate SOURCE_DOCUMENT node\n")
                .append("  is non-citation authority and must be rejected by admission.\n");
        report.append("- TAGGED_WITH: edges exist in the projection, but TAG entities have no\n")
                .append("  outgoing edges, so a tag can never contribute a traversal path; a\n")
                .append("  false tag contribution would surface as a safety violation.\n");
        report.append("- MENTIONS: production NO-GO; the plain-text mention scenario must never\n")
                .append("  surface the mentioned page (forbidden identity in every mode).\n");
        report.append("## Limitations\n\n");
        report.append("This corpus measures generalization (relation coverage, budget fit, and\n")
                .append("no-regression across policies), not ranking discrimination; the targeted\n")
                .append("tie-break failure mechanism is owned by the versioned holdout corpus.\n");
        return report.toString();
    }

    private record ClassAggregate(String mode, double recall, double mrr, int graphFound,
                                  int graphExpected) {
    }

    private Map<String, List<ClassAggregate>> perClass(Evaluation evaluation,
                                                       List<GoldenQuery> queries) {
        Map<String, Map<String, Integer>> expectedByClass = new LinkedHashMap<>();
        for (GoldenQuery query : queries) {
            expectedByClass.computeIfAbsent(query.queryClass(), key -> new LinkedHashMap<>())
                    .merge("expected", query.graphOnlyRelevant().size(), Integer::sum);
        }
        Map<String, List<ClassAggregate>> result = new LinkedHashMap<>();
        Map<String, Map<String, List<QueryMetrics>>> byClass = evaluation.metrics().stream()
                .collect(Collectors.groupingBy(QueryMetrics::queryClass, LinkedHashMap::new,
                        Collectors.groupingBy(QueryMetrics::mode, LinkedHashMap::new,
                                Collectors.toList())));
        for (Map.Entry<String, Map<String, List<QueryMetrics>>> classEntry : byClass.entrySet()) {
            List<ClassAggregate> aggregates = new ArrayList<>();
            for (Map.Entry<String, List<QueryMetrics>> modeEntry : classEntry.getValue()
                    .entrySet()) {
                List<QueryMetrics> metrics = modeEntry.getValue();
                double recall = metrics.stream().mapToDouble(QueryMetrics::recallAtK).average()
                        .orElse(0.0d);
                double mrr = metrics.stream().mapToDouble(QueryMetrics::mrr).average().orElse(0.0d);
                int found = metrics.stream().mapToInt(metric -> metric.graphOnlyFound().size())
                        .sum();
                int expected = expectedByClass
                        .getOrDefault(classEntry.getKey(), Map.of())
                        .getOrDefault("expected", 0);
                aggregates.add(new ClassAggregate(modeEntry.getKey(), recall, mrr, found,
                        expected));
            }
            result.put(classEntry.getKey(), aggregates);
        }
        return result;
    }
}
