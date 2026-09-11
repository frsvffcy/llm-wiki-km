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
import org.km.llmwiki.rag.SecondStageRerankPolicies.SecondStageRerankPolicy;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Second-stage reranking evaluation (#316, corpus {@code rerank-evaluation-corpus-v1}).
 *
 * <p>Baseline is the current production deterministic ranking (real FTS with cjk-bigram-v1,
 * deterministic concept embeddings over the real vector candidate service, real ArcadeDB
 * projection with traversal/admission, canonical fusion). Rerank candidates are evaluation-only
 * deterministic policies that reorder the already-qualified bundle items. Blocking correctness
 * gates: a reranker can never resurrect or re-identify evidence (identity multiset must be
 * preserved), exact technical-token queries must not lose their relevant evidence from the top
 * window, the already-good baseline must not regress, graph-added discovery must survive, and
 * every policy must be exactly reproducible. Quality regressions are recorded per query in the
 * report; the recorded decision (GO / CONDITIONAL GO / NO-GO) drives any future adoption issue,
 * and this test never changes the production default.
 */
@Tag("integration")
class RerankEvaluationIntegrationTest extends IsolatedIntegrationTest {

    private static final int K = 8;
    private static final String CROSS_ENCODER_DECISION = "NO-GO (feasibility: no local/offline "
            + "Java-runnable model artifact or runtime dependency may be introduced for this "
            + "evaluation; CI must stay deterministic and provider-free; re-evaluate only with a "
            + "dedicated adoption issue that first proves dependency, license, memory and "
            + "reproducibility constraints)";

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
    void evaluatesSecondStageRerankingAgainstTheDeterministicBaseline() throws Exception {
        GraphRetrievalQualityFixture fixture = new GraphRetrievalQualityFixture(db(), workspaces,
                searchService, publishedWikiRepository, publishedWikiContentReader,
                sourceAuthorityRepository, ftsRepository, embeddingRepository, embeddingReadiness,
                jobs, assembler, lifecycleRepository, currentness, paths);
        GraphWorkspaceScope active = fixture.workspace(temp, "rerank-eval-v1");
        GraphWorkspaceScope foreign = fixture.workspace(temp, "rerank-eval-v1-foreign");
        fixture.openWorkspace(active);
        fixture.writeForeignGoldenPages(foreign);

        long restoreDoc = documents.insert(active.id(), "restore-runbook.txt", "restore-runbook.txt",
                "txt", "inbox/restore-runbook.txt",
                WikiContentHash.sha256("restore-runbook.txt".getBytes(StandardCharsets.UTF_8)),
                100L, "text/plain", GraphRetrievalQualityFixture.NOW, "PROCESSED", null, null);
        documents.markExtractionSucceeded(restoreDoc,
                WikiContentHash.sha256("restore-extracted".getBytes(StandardCharsets.UTF_8)));
        fixture.insertChunk(restoreDoc, 1, "還原演練的執行節奏：每季一次，先在影子環境完整還原。");
        String restoreChunkIdentity = "SOURCE_CHUNK:" + fixture.sourceChunkId(restoreDoc, 1);

        long legacyDoc = documents.insert(active.id(), "legacy-archive.txt", "legacy-archive.txt",
                "txt", "inbox/legacy-archive.txt",
                WikiContentHash.sha256("legacy-archive.txt".getBytes(StandardCharsets.UTF_8)),
                100L, "text/plain", GraphRetrievalQualityFixture.NOW, "DELETED", null, null);
        fixture.insertChunk(legacyDoc, 1, "封存的歷史片段。");
        String legacyChunkIdentity = "SOURCE_CHUNK:" + fixture.sourceChunkId(legacyDoc, 1);

        fixture.materializeInto(active, foreign, RerankEvaluationCorpusV1.pages(restoreDoc));
        GraphRetrievalGoldenCorpus.GoldenPage stalePage =
                RerankEvaluationCorpusV1.pages(restoreDoc).stream()
                        .filter(page -> page.knowledgeId().equals(RerankEvaluationCorpusV1.STALE_PAGE))
                        .findFirst().orElseThrow();
        fixture.mutateStaleHash(active, stalePage);

        String foreignIdentity = new GraphRetrievalGoldenCorpus().foreignIdentity();
        List<GoldenQuery> queries = RerankEvaluationCorpusV1.queries(restoreChunkIdentity,
                "WIKI:" + RerankEvaluationCorpusV1.CACHE_INVALIDATION_TARGET);
        List<String> forbidden = RerankEvaluationCorpusV1.safetyIdentities(legacyChunkIdentity,
                foreignIdentity);

        try (GraphRetrievalQualityFixture.LifecycleBundle lifecycleFactory =
                     fixture.lifecycle(temp.resolve("rerank-eval-graph"))) {
            FusedRetrievalOrchestrator orchestrator = fixture.orchestrator(
                    lifecycleFactory.lifecycle(), lifecycleFactory.factory(),
                    FusionRankingPolicy.production());
            RetrievalService retrievalService = fixture.retrievalService(orchestrator);
            GraphProjectionInput input = assembler.assemble(active);
            lifecycleFactory.lifecycle().rebuild(input);
            GraphProjectionStatusResponse projection = GraphProjectionStatusResponse.from(
                    lifecycleFactory.lifecycle().readiness(active));
            assertThat(projection.status()).isEqualTo("READY");

            List<SecondStageRerankPolicy> candidates = List.of(
                    SecondStageRerankPolicies.noRerank(),
                    SecondStageRerankPolicies.exactAnchor(),
                    SecondStageRerankPolicies.coverageBlend());
            List<ModeRun> runs = new ArrayList<>();
            List<String> violations = new ArrayList<>();

            List<String> corpusObservationList = new ArrayList<>();
            for (SecondStageRerankPolicy candidate : candidates) {
                runs.add(run(candidate, orchestrator, retrievalService, queries, forbidden,
                        violations, corpusObservationList));
            }
            ModeRun evaluationWinnerRun = runs.stream()
                    .filter(run -> run.policy().equals("rerank-v1-exact-anchor"))
                    .findFirst().orElseThrow();
            // A second identical pass must be exactly reproducible for every deterministic
            // policy: the reranked order (not the baseline order) is what must repeat.
            for (SecondStageRerankPolicy candidate : candidates.stream().skip(1).toList()) {
                ModeRun repeat = run(candidate, orchestrator, retrievalService, queries, forbidden,
                        violations, corpusObservationList);
                ModeRun first = runs.stream().filter(run -> run.policy().equals(candidate.name()))
                        .findFirst().orElseThrow();
                for (int index = 0; index < first.queries().size(); index++) {
                    if (!first.queries().get(index).reranked()
                            .equals(repeat.queries().get(index).reranked())) {
                        violations.add("non-reproducible rerank order at "
                                + candidate.name() + "/" + queries.get(index).id());
                    }
                }
            }
            boolean exactReproducible = violations.stream()
                    .noneMatch(violation -> violation.startsWith("non-reproducible"));

            // Production adoption regression (#326): the production boundary (registry +
            // executor) must reproduce the #316 winner's behavior on the same corpus and must
            // satisfy the same blocking gates as the evaluation candidates.
            org.km.llmwiki.rag.SecondStageRerankService productionRerank =
                    new org.km.llmwiki.rag.SecondStageRerankService(
                            new org.km.llmwiki.rag.SecondStageRerankPolicyRegistry(
                                    java.util.List.of(
                                            new org.km.llmwiki.rag
                                                    .SecondStageRerankPolicy.NoOp(),
                                            new org.km.llmwiki.rag.ExactAnchorRerankPolicyV1()),
                                    ExactAnchorRerankPolicyV1.VERSION));
            ModeRun productionRun = runProductionPolicy(productionRerank, orchestrator,
                    retrievalService, queries, forbidden, violations);
            ModeRun productionRepeat = runProductionPolicy(productionRerank, orchestrator,
                    retrievalService, queries, forbidden, violations);
            for (int index = 0; index < productionRun.queries().size(); index++) {
                if (!productionRun.queries().get(index).reranked()
                        .equals(productionRepeat.queries().get(index).reranked())) {
                    violations.add("non-reproducible production rerank order at "
                            + queries.get(index).id());
                }
                // Parity with the evaluation winner: the production policy's ordering must be
                // identical to the evaluation-only exact-anchor candidate.
                if (!productionRun.queries().get(index).reranked()
                        .equals(evaluationWinnerRun.queries().get(index).reranked())) {
                    violations.add("production rerank policy diverged from the #316 winner at "
                            + queries.get(index).id());
                }
            }
            exactReproducible = exactReproducible && violations.stream()
                    .noneMatch(violation -> violation.startsWith("non-reproducible"));
            runs.add(productionRun);
            runs.add(productionRepeat);

            Decision decision = decide(runs, violations, exactReproducible);
            writeReports(runs, decision, violations, projection, corpusObservationList,
                    exactReproducible);
            assertThat(violations)
                    .as("rerank evaluation correctness gates (see "
                            + "target/quality-reports/rerank-evaluation-v1.md)")
                    .isEmpty();
        }
    }

    private record ModeRun(String policy, List<QueryRun> queries, List<List<String>> orders,
                           long rerankOverheadNanos, long retrievalNanos) {
    }

    /**
     * Runs the production rerank boundary (registry + executor with the exact-anchor policy)
     * over the production retrieval output for every corpus query. The production executor's
     * blocking invariants and the corpus-level quality gates therefore cover the adopted
     * production policy, not only the evaluation candidates.
     */
    private ModeRun runProductionPolicy(
            org.km.llmwiki.rag.SecondStageRerankService rerankService,
            FusedRetrievalOrchestrator orchestrator, RetrievalService retrievalService,
            List<GoldenQuery> queries, List<String> forbidden, List<String> violations) {
        List<QueryRun> queryRuns = new ArrayList<>();
        List<List<String>> orders = new ArrayList<>();
        long rerankOverheadTotal = 0L;
        long retrievalStartedAt = System.nanoTime();
        Map<String, EvidenceBundle> baselineBundles = new LinkedHashMap<>();
        for (GoldenQuery query : queries) {
            baselineBundles.put(query.id(), orchestrator.retrieveFused(
                    RetrievalRequest.defaults(query.text(), RetrievalMode.HYBRID_GRAPH)));
        }
        long retrievalNanos = System.nanoTime() - retrievalStartedAt;
        for (GoldenQuery query : queries) {
            EvidenceBundle baseline = baselineBundles.get(query.id());
            List<String> baselineOrder = baseline.items().stream()
                    .map(EvidenceItem::stableIdentity).toList();
            orders.add(baselineOrder);
            long rerankStartedAt = System.nanoTime();
            org.km.llmwiki.rag.RerankResult rerank =
                    rerankService.apply(baseline);
            long rerankOverhead = System.nanoTime() - rerankStartedAt;
            rerankOverheadTotal += rerankOverhead;
            List<String> rerankedOrder = rerank.orderedItems().stream()
                    .map(EvidenceItem::stableIdentity).toList();
            boolean graphOnlyRetained = query.graphOnlyRelevant().stream()
                    .allMatch(identity -> rerankedOrder.indexOf(identity)
                            <= baselineOrder.indexOf(identity))
                    || query.graphOnlyRelevant().isEmpty();
            if (!graphOnlyRetained) {
                violations.add(query.id() + "/" + rerank.policyVersion()
                        + ": production rerank degraded graph-added relevant ranking");
            }
            for (String identity : rerankedOrder) {
                if (forbidden.contains(identity)) {
                    violations.add(query.id() + "/" + rerank.policyVersion()
                            + ": production rerank surfaced forbidden identity " + identity);
                }
            }
            queryRuns.add(new QueryRun(query.id(), query.queryClass(),
                    List.copyOf(query.relevant()), baselineOrder, rerankedOrder,
                    mrr(baselineOrder, query.relevant()), mrr(rerankedOrder, query.relevant()),
                    recallAtK(baselineOrder, query.relevant()),
                    recallAtK(rerankedOrder, query.relevant()),
                    precisionAtK(rerankedOrder, query.relevant()),
                    query.graphOnlyRelevant().stream().filter(baselineOrder::contains).toList(),
                    graphOnlyRetained));
        }
        return new ModeRun("rerank-policy-v1-exact-anchor [production]", queryRuns, orders,
                rerankOverheadTotal, retrievalNanos);
    }

    private record QueryRun(String queryId, String queryClass, List<String> relevant,
                            List<String> baseline, List<String> reranked, double baselineMrr,
                            double rerankedMrr, double baselineRecallAtK,
                            double rerankedRecallAtK, double rerankedPrecisionAtK,
                            List<String> graphOnlyInBaseline, boolean graphAddedRankRetained) {
    }

    private record Decision(String verdict, List<String> reasons) {
    }

    private ModeRun run(SecondStageRerankPolicy policy, FusedRetrievalOrchestrator orchestrator,
                        RetrievalService retrievalService, List<GoldenQuery> queries,
                        List<String> forbidden, List<String> violations,
                        List<String> corpusObservations) {
        List<QueryRun> queryRuns = new ArrayList<>();
        List<List<String>> orders = new ArrayList<>();
        long rerankOverheadTotal = 0L;
        long retrievalStartedAt = System.nanoTime();
        Map<String, List<EvidenceItem>> baselineBundles = new LinkedHashMap<>();
        for (GoldenQuery query : queries) {
            EvidenceBundle bundle = orchestrator.retrieveFused(
                    RetrievalRequest.defaults(query.text(), RetrievalMode.HYBRID_GRAPH));
            baselineBundles.put(query.id(), bundle.items());
        }
        long retrievalNanos = System.nanoTime() - retrievalStartedAt;
        for (GoldenQuery query : queries) {
            List<EvidenceItem> baselineItems = baselineBundles.get(query.id());
            List<String> baselineOrder = baselineItems.stream()
                    .map(EvidenceItem::stableIdentity).toList();
            orders.add(baselineOrder);
            for (String identity : baselineOrder) {
                if (forbidden.contains(identity)) {
                    violations.add(query.id() + ": baseline retrieved forbidden identity "
                            + identity);
                }
            }
            if (query.relevant().isEmpty() && !corpusObservations.stream()
                    .anyMatch(observation -> observation.startsWith(query.id()))) {
                corpusObservations.add(query.id()
                        + ": safety-negative query contributes 1.0 to the means by convention "
                        + "(same as the #272/#280 benchmark contract)");
            }
            long rerankStartedAt = System.nanoTime();
            List<EvidenceItem> rerankedItems = policy.rerank(query.text(), baselineItems);
            long rerankOverhead = System.nanoTime() - rerankStartedAt;
            rerankOverheadTotal += rerankOverhead;
            List<String> rerankedOrder = rerankedItems.stream()
                    .map(EvidenceItem::stableIdentity).toList();
            // Identity set must be preserved: reorder only, never resurrect or drop evidence.
            if (!new java.util.LinkedHashSet<>(rerankedOrder)
                    .equals(new java.util.LinkedHashSet<>(baselineOrder))) {
                violations.add(query.id() + "/" + candidate(query, policy)
                        + ": rerank changed the qualified evidence identity set");
            }
            for (String identity : rerankedOrder) {
                if (forbidden.contains(identity)) {
                    violations.add(query.id() + "/" + candidate(query, policy)
                            + ": rerank surfaced forbidden identity " + identity);
                }
            }
            // Graph-added discovery retention is a rank gate: whenever the baseline window
            // contains a graph-only relevant identity, reranking must keep it and must not
            // push it behind its baseline position. A graph-only identity missing from the
            // baseline window is a candidate-generation signal recorded as a corpus
            // observation (the baseline reachability of graph-added discovery is owned by
            // the #280 generalization corpus), never silently treated as a rerank pass.
            List<String> graphOnlyInBaseline = query.graphOnlyRelevant().stream()
                    .filter(baselineOrder::contains).toList();
            boolean graphAddedRankRetained = graphOnlyInBaseline.stream().allMatch(identity ->
                    rerankedOrder.indexOf(identity) <= baselineOrder.indexOf(identity));
            for (String identity : query.relevant()) {
                if (!baselineOrder.contains(identity)) {
                    String outsideWindow = query.id() + ": relevant identity " + identity
                            + " is outside the baseline fused window (candidate generation "
                            + "signal; reranking can only reorder in-window evidence)";
                    if (!corpusObservations.contains(outsideWindow)) {
                        corpusObservations.add(outsideWindow);
                    }
                }
            }
            for (String identity : query.graphOnlyRelevant()) {
                if (!baselineOrder.contains(identity)) {
                    String observation = query.id() + ": graph-only relevant identity "
                            + identity + " is outside the baseline fused window (candidate "
                            + "generation signal; discovery ownership stays with #280)";
                    if (!corpusObservations.contains(observation)) {
                        corpusObservations.add(observation);
                    }
                }
            }
            queryRuns.add(new QueryRun(query.id(), query.queryClass(),
                    List.copyOf(query.relevant()), baselineOrder, rerankedOrder,
                    mrr(baselineOrder, query.relevant()), mrr(rerankedOrder, query.relevant()),
                    recallAtK(baselineOrder, query.relevant()),
                    recallAtK(rerankedOrder, query.relevant()),
                    precisionAtK(rerankedOrder, query.relevant()),
                    graphOnlyInBaseline, graphAddedRankRetained));
            if (!graphAddedRankRetained) {
                violations.add(query.id() + "/" + policy.name()
                        + ": rerank degraded graph-added relevant ranking");
            }
        }
        return new ModeRun(policy.name(), queryRuns, orders, rerankOverheadTotal, retrievalNanos);
    }

    private static String candidate(GoldenQuery query, SecondStageRerankPolicy policy) {
        return policy.name();
    }

    private static double recallAtK(List<String> order, java.util.Set<String> relevant) {
        if (relevant.isEmpty()) {
            return 1.0d;
        }
        long hits = order.stream().limit(K).filter(relevant::contains).count();
        return (double) hits / relevant.size();
    }

    private static double precisionAtK(List<String> order, java.util.Set<String> relevant) {
        long hits = order.stream().limit(K).filter(relevant::contains).count();
        return (double) hits / K;
    }

    private static double mrr(List<String> order, java.util.Set<String> relevant) {
        if (relevant.isEmpty()) {
            return 1.0d;
        }
        for (int index = 0; index < order.size(); index++) {
            if (relevant.contains(order.get(index))) {
                return 1.0d / (index + 1);
            }
        }
        return 0.0d;
    }

    private Decision decide(List<ModeRun> runs, List<String> violations,
                            boolean exactReproducible) {
        List<String> reasons = new ArrayList<>();
        // Every quality gate applies per rerank policy; the winning policy still has to clear
        // all of them, and each violation names the policy it belongs to.
        for (ModeRun run : runs) {
            if (run.policy().equals("NO_RERANK")) {
                continue;
            }
            for (QueryRun query : run.queries()) {
                if (query.rerankedMrr() < query.baselineMrr()) {
                    violations.add(run.policy() + "/" + query.queryId()
                            + ": mrr regressed below baseline");
                }
                if (query.queryClass().equals("EXACT_TOKEN")) {
                    // Exact technical-token protection: an exact-token query may never lose
                    // ordering quality under reranking (stronger than the aggregate MRR gate).
                    if (query.rerankedMrr() < query.baselineMrr()) {
                        violations.add(run.policy() + "/" + query.queryId()
                                + ": exact technical-token query regressed under rerank");
                    }
                }
            }
        }
        if (!exactReproducible) {
            violations.add("deterministic policies must be exactly reproducible");
        }
        if (!violations.isEmpty()) {
            reasons.add("correctness gates failed (see violations)");
            return new Decision("NO-GO", reasons);
        }
        double baselineMean = runs.stream().filter(run -> run.policy().equals("NO_RERANK"))
                .findFirst().orElseThrow().queries().stream().mapToDouble(QueryRun::baselineMrr)
                .average().orElse(0.0d);
        double bestMean = runs.stream().filter(run -> !run.policy().equals("NO_RERANK"))
                .mapToDouble(run -> run.queries().stream().mapToDouble(QueryRun::rerankedMrr)
                        .average().orElse(0.0d)).max().orElse(0.0d);
        // 0.05 is a corpus-scale heuristic for this 15-query corpus (roughly three queries'
        // worth of one-rank improvement); it is deterministic and recorded as a corpus-scoped
        // heuristic, not a universal quality claim.
        if (bestMean > baselineMean + 0.05d) {
            reasons.add("reproducible ordering gain on zh-TW/technical queries (mean mrr "
                    + String.format("%.4f", baselineMean) + " -> "
                    + String.format("%.4f", bestMean)
                    + "); adoption issue must define typed routing, the exact-token gate, and a "
                    + "re-baselined #308 compaction benchmark");
            return new Decision("CONDITIONAL GO", reasons);
        }
        reasons.add("no reproducible aggregate ordering gain over the deterministic baseline; "
                + "keep the current fusion");
        return new Decision("NO-GO", reasons);
    }

    private void writeReports(List<ModeRun> runs, Decision decision, List<String> violations,
                              GraphProjectionStatusResponse projection,
                              List<String> corpusObservations, boolean exactReproducible)
            throws IOException {
        Path reports = Path.of("target", "quality-reports");
        Files.createDirectories(reports);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("corpus", RerankEvaluationCorpusV1.VERSION);
        json.put("branch", git("rev-parse", "--abbrev-ref", "HEAD"));
        json.put("headSha", git("rev-parse", "HEAD"));
        json.put("decision", decision.verdict());
        json.put("decisionReasons", decision.reasons());
        json.put("crossEncoderFeasibility", CROSS_ENCODER_DECISION);
        json.put("k", K);
        json.put("graphProjectionVersion", projection.projectionVersion());
        json.put("corpusObservations", corpusObservations);
        json.put("runs", runs.stream().map(run -> {
            Map<String, Object> runJson = new LinkedHashMap<>();
            runJson.put("policy", run.policy());
            runJson.put("rerankOverheadNanos", run.rerankOverheadNanos());
            runJson.put("retrievalNanos", run.retrievalNanos());
            runJson.put("queries", run.queries().stream().map(query -> {
                Map<String, Object> queryJson = new LinkedHashMap<>();
                queryJson.put("queryId", query.queryId());
                queryJson.put("queryClass", query.queryClass());
                queryJson.put("baselineMrr", query.baselineMrr());
                queryJson.put("rerankedMrr", query.rerankedMrr());
                queryJson.put("baselineRecallAtK", query.baselineRecallAtK());
                queryJson.put("rerankedRecallAtK", query.rerankedRecallAtK());
                queryJson.put("rerankedPrecisionAtK", query.rerankedPrecisionAtK());
                queryJson.put("baselineOrder", query.baseline());
                queryJson.put("rerankedOrder", query.reranked());
                queryJson.put("graphOnlyInBaseline", query.graphOnlyInBaseline());
                queryJson.put("graphAddedRankRetained", query.graphAddedRankRetained());
                return queryJson;
            }).toList());
            return runJson;
        }).toList());
        json.put("violations", violations);
        json.put("deterministic", exactReproducible);
        json.put("modelArtifactSize", "none (deterministic Java policies; no model)");
        json.put("offline", true);
        Files.writeString(reports.resolve("rerank-evaluation-v1.json"),
                new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(json) + "\n");
        Files.writeString(reports.resolve("rerank-evaluation-v1.md"), markdown(runs, decision,
                violations, projection, corpusObservations, exactReproducible));
    }

    private static String git(String... args) {
        try {
            Process process = new ProcessBuilder(
                    java.util.stream.Stream.concat(java.util.stream.Stream.of("git", "-C", "."),
                            java.util.Arrays.stream(args)).toList())
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).strip();
            process.waitFor();
            return output;
        } catch (Exception failure) {
            return "unavailable";
        }
    }

    private String markdown(List<ModeRun> runs, Decision decision, List<String> violations,
                            GraphProjectionStatusResponse projection,
                            List<String> corpusObservations, boolean exactReproducible) {
        StringBuilder report = new StringBuilder();
        report.append("# Second-stage reranking evaluation report (v1)\n\n");
        report.append("- corpus: `").append(RerankEvaluationCorpusV1.VERSION).append("`\n");
        report.append("- branch: `").append(git("rev-parse", "--abbrev-ref", "HEAD"))
                .append("`, HEAD: `").append(git("rev-parse", "HEAD")).append("`\n");
        report.append("- baseline: current production deterministic ranking (fusion policy ")
                .append(FusionRankingPolicy.production().version()).append(", k=")
                .append(K).append(")\n");
        report.append("- graph projection: `").append(projection.projectionVersion())
                .append("` generation ").append(projection.appliedGeneration()).append('\n');
        report.append("- local cross-encoder candidate: ").append(CROSS_ENCODER_DECISION)
                .append('\n');
        report.append("- deterministic/reproducible: ").append(exactReproducible).append("\n");
        report.append("- decision: **").append(decision.verdict()).append("**\n\n");
        report.append("## Decision reasons\n\n");
        decision.reasons().forEach(reason -> report.append("- ").append(reason).append('\n'));
        report.append("\n## Aggregate\n\n");
        report.append("| policy | mean mrr | mean recall@").append(K)
                .append(" | mean precision@").append(K).append(" | rerank overhead ms | retrieval ms |\n");
        report.append("| --- | --- | --- | --- | --- | --- |\n");
        for (ModeRun run : runs) {
            double mean = run.queries().stream().mapToDouble(QueryRun::rerankedMrr).average()
                    .orElse(0.0d);
            double meanRecall = run.queries().stream()
                    .mapToDouble(QueryRun::rerankedRecallAtK).average().orElse(0.0d);
            double meanPrecision = run.queries().stream()
                    .mapToDouble(QueryRun::rerankedPrecisionAtK).average().orElse(0.0d);
            report.append("| ").append(run.policy()).append(" | ")
                    .append(String.format("%.4f", mean)).append(" | ")
                    .append(String.format("%.4f", meanRecall)).append(" | ")
                    .append(String.format("%.4f", meanPrecision)).append(" | ")
                    .append(String.format("%.3f", run.rerankOverheadNanos() / 1_000_000.0d))
                    .append(" | ")
                    .append(String.format("%.3f", run.retrievalNanos() / 1_000_000.0d))
                    .append(" |\n");
        }
        report.append("\n## Per-query detail (baseline vs rerank)\n\n");
        report.append("| query | class | baseline mrr | rerank mrr | baseline recall@")
                .append(K).append(" | rerank recall@").append(K).append(" | precision@")
                .append(K).append(" | graph rank retained |\n");
        report.append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (ModeRun run : runs) {
            for (QueryRun query : run.queries()) {
                report.append("| ").append(run.policy()).append("/").append(query.queryId())
                        .append(" | ").append(query.queryClass()).append(" | ")
                        .append(String.format("%.4f", query.baselineMrr())).append(" | ")
                        .append(String.format("%.4f", query.rerankedMrr())).append(" | ")
                        .append(String.format("%.4f", query.baselineRecallAtK())).append(" | ")
                        .append(String.format("%.4f", query.rerankedRecallAtK())).append(" | ")
                        .append(String.format("%.4f", query.rerankedPrecisionAtK())).append(" | ")
                        .append(query.graphAddedRankRetained()).append(" |\n");
            }
        }
        report.append("\n## Per-query regression list\n\n");
        List<QueryRun> regressions = runs.stream()
                .filter(run -> !run.policy().equals("NO_RERANK"))
                .flatMap(run -> run.queries().stream()
                        .filter(query -> query.rerankedMrr() < query.baselineMrr()))
                .toList();
        if (regressions.isEmpty()) {
            report.append("(none)\n");
        } else {
            regressions.forEach(query -> report.append("- ").append(query.queryId())
                    .append(": ").append(query.baselineMrr()).append(" -> ")
                    .append(query.rerankedMrr()).append('\n'));
        }
        report.append("\n## Correctness violations\n\n").append(violations.isEmpty() ? "none\n"
                : violations.stream().map(v -> "- " + v + "\n").reduce("", String::concat));
        report.append("\n## Corpus window observations (candidate-generation signals)\n\n");
        if (corpusObservations.isEmpty()) {
            report.append("(none)\n");
        } else {
            corpusObservations.forEach(observation -> report.append("- ").append(observation)
                    .append('\n'));
        }
        report.append("\n## Operational cost\n\n");
        report.append("- deterministic Java policies: no model artifact, no network, offline\n");
        report.append("- rerank overhead is scoped to the rerank call only (retrieval time is\n")
                .append("  recorded separately in the aggregate table); cold start / memory are\n")
                .append("  not applicable without a model runtime\n");
        report.append("\n## Limitations\n\n");
        report.append("- This corpus measures second-stage reordering over the already-qualified\n")
                .append("  fused window; candidate-generation quality is measured by the #272/#280\n")
                .append("  corpora, and graph-added discovery reachability is owned by the #280\n")
                .append("  generalization gate. A future rerank adoption that changes the final\n")
                .append("  Evidence order must re-baseline the #308 compaction benchmark.\n");
        return report.toString();
    }
}
