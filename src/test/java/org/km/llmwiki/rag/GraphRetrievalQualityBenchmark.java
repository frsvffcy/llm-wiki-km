package org.km.llmwiki.rag;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Deterministic evaluation engine for the graph-grounded retrieval quality benchmark.
 *
 * <p>Every mode runs through its production-equivalent application contract and is compared on
 * the final canonical evidence order only: retrieved identities are the bundle's
 * {@code EvidenceItem#stableIdentity()} values (never raw FTS/vector/graph scores). Metrics are
 * identity-level; duplicates, cross-workspace leaks, and stale/ineligible retrievals are safety
 * violations, not scoring credit.
 */
final class GraphRetrievalQualityBenchmark {

    private GraphRetrievalQualityBenchmark() {
    }

    record QueryOutcome(String queryId, String queryClass, String mode, List<String> retrieved,
                        int rejectedCandidates, boolean graphUnavailable, boolean graphDegraded) {
    }

    record QueryMetrics(String queryId, String queryClass, String mode, double recallAtK,
                        double mrr, List<String> retrieved, List<String> relevantMissed,
                        List<String> noise, List<String> graphOnlyFound) {
    }

    record ModeAggregate(String mode, double recallAtK, double mrr, int noiseTotal,
                         int graphAddedFound, int graphAddedExpected) {
    }

    record Evaluation(String corpusVersion, String rankingPolicyVersion, int k,
                      String graphProjectionVersion, long graphAppliedGeneration,
                      List<QueryMetrics> metrics, Map<String, ModeAggregate> aggregates,
                      List<String> safetyViolations, boolean degradedBaselineRetained) {

        String toJson() {
            try {
                return new ObjectMapper().writeValueAsString(this) + "\n";
            } catch (IOException failure) {
                throw new IllegalStateException("Benchmark report serialization failed", failure);
            }
        }

        String toMarkdown() {
            StringBuilder report = new StringBuilder();
            report.append("# Graph-grounded retrieval quality report\n\n");
            report.append("- corpus: `").append(corpusVersion).append("`\n");
            report.append("- ranking policy: `").append(rankingPolicyVersion).append("`\n");
            report.append("- k: ").append(k).append('\n');
            report.append("- graph projection: `").append(graphProjectionVersion).append("` generation ")
                    .append(graphAppliedGeneration).append('\n');
            report.append("- degraded baseline retained: ").append(degradedBaselineRetained)
                    .append("\n\n");
            report.append("| mode | recall@k | mrr | noise | graph-added found/expected |\n");
            report.append("| --- | --- | --- | --- | --- |\n");
            for (Map.Entry<String, ModeAggregate> entry : aggregates.entrySet()) {
                ModeAggregate aggregate = entry.getValue();
                report.append("| ").append(entry.getKey()).append(" | ")
                        .append(String.format("%.4f", aggregate.recallAtK())).append(" | ")
                        .append(String.format("%.4f", aggregate.mrr())).append(" | ")
                        .append(aggregate.noiseTotal()).append(" | ")
                        .append(aggregate.graphAddedFound()).append('/')
                        .append(aggregate.graphAddedExpected()).append(" |\n");
            }
            report.append("\n## Per-query detail\n\n");
            for (QueryMetrics metric : metrics) {
                report.append("### ").append(metric.queryId()).append(" / ").append(metric.mode())
                        .append(" (").append(metric.queryClass()).append(")\n\n");
                report.append("- recall@k: ").append(String.format("%.4f", metric.recallAtK()))
                        .append(", mrr: ").append(String.format("%.4f", metric.mrr())).append('\n');
                report.append("- retrieved: ").append(metric.retrieved()).append('\n');
                report.append("- missed relevant: ").append(metric.relevantMissed()).append('\n');
                report.append("- noise (retrieved, not relevant): ").append(metric.noise())
                        .append('\n');
                report.append("- graph-added relevant found: ").append(metric.graphOnlyFound())
                        .append("\n\n");
            }
            report.append("## Safety violations\n\n").append(safetyViolations.isEmpty()
                    ? "none\n" : safetyViolations.toString() + "\n");
            report.append("\n## Limitations\n\n");
            report.append("This report describes the golden corpus scenarios only; it is not a\n")
                    .append("general product-quality guarantee. Graph-added discovery is\n")
                    .append("demonstrated for LINKS_TO over wiki pages; source-chunk evidence and\n")
                    .append("CONTAINS/DERIVED_FROM relations are covered by the correctness suites.\n");
            return report.toString();
        }
    }

    /**
     * Runs every query through every mode runner and computes identity-level metrics. Runners
     * must be production-equivalent application contracts; this engine never re-ranks or
     * re-validates anything by itself.
     */
    static Evaluation evaluate(String corpusVersion, String rankingPolicyVersion,
                               List<GraphRetrievalGoldenCorpus.GoldenQuery> queries,
                               Map<String, Function<String, EvidenceBundle>> modeRunners, int k,
                               String graphProjectionVersion, long graphAppliedGeneration,
                               List<String> forbiddenIdentities,
                               QueryOutcome degradedGraphOutcome,
                               Set<String> degradedBaselineExpectation) {
        List<QueryMetrics> metrics = new ArrayList<>();
        Map<String, ModeAggregate> aggregates = new LinkedHashMap<>();
        Map<String, List<Double>> recalls = new LinkedHashMap<>();
        Map<String, List<Double>> mrrs = new LinkedHashMap<>();
        Map<String, Integer> noise = new LinkedHashMap<>();
        Map<String, Integer> graphAddedFound = new LinkedHashMap<>();
        Map<String, Integer> graphAddedExpected = new LinkedHashMap<>();
        List<String> safetyViolations = new ArrayList<>();

        for (Map.Entry<String, Function<String, EvidenceBundle>> mode : modeRunners.entrySet()) {
            recalls.put(mode.getKey(), new ArrayList<>());
            mrrs.put(mode.getKey(), new ArrayList<>());
            noise.put(mode.getKey(), 0);
            graphAddedFound.put(mode.getKey(), 0);
            graphAddedExpected.put(mode.getKey(), 0);
        }

        for (GraphRetrievalGoldenCorpus.GoldenQuery query : queries) {
            for (Map.Entry<String, Function<String, EvidenceBundle>> mode : modeRunners.entrySet()) {
                EvidenceBundle bundle = mode.getValue().apply(query.text());
                List<String> retrieved = bundle.items().stream()
                        .map(org.km.llmwiki.rag.EvidenceItem::stableIdentity).toList();
                Set<String> unique = new java.util.LinkedHashSet<>(retrieved);
                if (unique.size() != retrieved.size()) {
                    safetyViolations.add(mode.getKey() + "/" + query.id()
                            + " duplicate canonical identities");
                }
                for (String identity : retrieved) {
                    if (forbiddenIdentities.contains(identity)) {
                        safetyViolations.add(mode.getKey() + "/" + query.id()
                                + " retrieved forbidden identity " + identity);
                    }
                }
                long hits = retrieved.stream().filter(query.relevant()::contains).count();
                double recall = query.relevant().isEmpty() ? 1.0d : (double) hits / query.relevant().size();
                double mrr = reciprocalRank(retrieved, query.relevant());
                List<String> missed = query.relevant().stream()
                        .filter(identity -> !retrieved.contains(identity)).sorted().toList();
                List<String> queryNoise = retrieved.stream()
                        .filter(identity -> !query.relevant().contains(identity)).toList();
                List<String> graphOnlyFound = query.graphOnlyRelevant().stream()
                        .filter(retrieved::contains).sorted().toList();
                metrics.add(new QueryMetrics(query.id(), query.queryClass(), mode.getKey(),
                        recall, mrr, retrieved, missed, queryNoise, graphOnlyFound));
                recalls.get(mode.getKey()).add(recall);
                mrrs.get(mode.getKey()).add(mrr);
                noise.merge(mode.getKey(), queryNoise.size(), Integer::sum);
                graphAddedFound.merge(mode.getKey(), graphOnlyFound.size(), Integer::sum);
                graphAddedExpected.merge(mode.getKey(), query.graphOnlyRelevant().size(),
                        Integer::sum);
            }
        }

        for (Map.Entry<String, Function<String, EvidenceBundle>> mode : modeRunners.entrySet()) {
            aggregates.put(mode.getKey(), new ModeAggregate(mode.getKey(),
                    mean(recalls.get(mode.getKey())), mean(mrrs.get(mode.getKey())),
                    noise.get(mode.getKey()), graphAddedFound.get(mode.getKey()),
                    graphAddedExpected.get(mode.getKey())));
        }

        boolean degradedBaselineRetained = degradedGraphOutcome != null
                && new java.util.LinkedHashSet<>(degradedGraphOutcome.retrieved())
                .equals(degradedBaselineExpectation);

        return new Evaluation(corpusVersion, rankingPolicyVersion, k, graphProjectionVersion,
                graphAppliedGeneration, List.copyOf(metrics), Map.copyOf(aggregates),
                List.copyOf(safetyViolations), degradedBaselineRetained);
    }

    static void writeReports(Evaluation evaluation, Path directory) {
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("graph-retrieval-quality.json"),
                    evaluation.toJson());
            Files.writeString(directory.resolve("graph-retrieval-quality.md"),
                    evaluation.toMarkdown());
        } catch (IOException failure) {
            throw new IllegalStateException("Benchmark report could not be written", failure);
        }
    }

    private static double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0d);
    }

    private static double reciprocalRank(List<String> retrieved, Set<String> relevant) {
        for (int index = 0; index < retrieved.size(); index++) {
            if (relevant.contains(retrieved.get(index))) {
                return 1.0d / (index + 1);
            }
        }
        return 0.0d;
    }
}
