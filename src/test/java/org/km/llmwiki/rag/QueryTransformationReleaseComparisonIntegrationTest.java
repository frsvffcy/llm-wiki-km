package org.km.llmwiki.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.km.llmwiki.rag.GraphRetrievalGoldenCorpus.GoldenPage;
import org.km.llmwiki.rag.GraphRetrievalGoldenCorpus.GoldenQuery;
import org.km.llmwiki.rag.QueryTransformationCandidates.Candidate;
import org.km.llmwiki.rag.QueryTransformationCandidates.RetrievalInput;
import org.km.llmwiki.rag.QueryTransformationCandidates.TransformationPlan;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Release-decision comparison for issue #408.
 *
 * <p>Compares three arms on the same versioned corpus
 * ({@code query-transformation-evaluation-corpus-v1}, 17 queries) using the same
 * production-equivalent retrieval stack as #390 (real FTS with {@code cjk-bigram-v1},
 * deterministic concept embeddings, real ArcadeDB projection, production fusion/budget/
 * authority):
 *
 * <ol>
 *   <li>{@code BASELINE} — current production behaviour: original question, fused window
 *       {@code k=8}, zero provider calls (policy {@code query-transform-disabled-v1}).</li>
 *   <li>{@code SINGLE_REWRITE} — bounded provider rewrite fixture (protected,
 *       {@code query-rewrite-fixtures-v1}), original always first, fan-out {@code <= 2},
 *       merged window {@code k=8} ({@code query-transform-single-rewrite-v1} shape).</li>
 *   <li>{@code PROVIDER_FREE_WINDOW_12} — evaluation-only bounded window expansion:
 *       original question only, fused window {@code k=12}, zero provider calls, no production
 *       default change.</li>
 * </ol>
 *
 * <p>The window arm is deliberately narrow: it tests whether the single reproducible
 * #390 window recovery ({@code property-token} crowd-out) can be achieved without any
 * provider egress, and at what noise/context cost. It is not a production proposal and
 * never changes the default budget.
 *
 * <p>Blocking gates mirror #390: no forbidden/stale/foreign/deleted identity in any arm,
 * canonical-universe containment (pool and window), original-first for the rewrite arm,
 * fan-out budgets, exact-token/graph-added retention for the protected rewrite, and
 * two-pass determinism. Per-query deltas are reported; aggregates never mask a
 * query-class regression.
 */
@Tag("integration")
class QueryTransformationReleaseComparisonIntegrationTest extends IsolatedIntegrationTest {

    private static final String BASELINE = "BASELINE_ORIGINAL_K8";
    private static final String SINGLE_REWRITE = "SINGLE_REWRITE_K8";
    private static final String WINDOW_12 = "PROVIDER_FREE_WINDOW_12";
    private static final String COMPARISON_VERSION = "query-transformation-release-comparison-v1";
    private static final String RESTORE_CHUNK_TEXT =
            "還原演練的執行節奏：每季一次，先在影子環境完整還原。";

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
    void comparesBaselineRewriteAndProviderFreeWindowOnTheSameCorpus() throws Exception {
        GraphRetrievalQualityFixture fixture = new GraphRetrievalQualityFixture(db(), workspaces,
                searchService, publishedWikiRepository, publishedWikiContentReader,
                sourceAuthorityRepository, ftsRepository, embeddingRepository, embeddingReadiness,
                jobs, assembler, lifecycleRepository, currentness, paths);
        GraphWorkspaceScope active = fixture.workspace(temp, "qt-release-compare-v1");
        GraphWorkspaceScope foreign = fixture.workspace(temp, "qt-release-compare-v1-foreign");
        fixture.openWorkspace(active);
        fixture.writeForeignGoldenPages(foreign);

        long restoreDoc = documents.insert(active.id(), "restore-runbook.txt", "restore-runbook.txt",
                "txt", "inbox/restore-runbook.txt",
                WikiContentHash.sha256("restore-runbook.txt".getBytes(StandardCharsets.UTF_8)),
                100L, "text/plain", GraphRetrievalQualityFixture.NOW, "PROCESSED", null, null);
        documents.markExtractionSucceeded(restoreDoc,
                WikiContentHash.sha256("restore-extracted".getBytes(StandardCharsets.UTF_8)));
        fixture.insertChunk(restoreDoc, 1, RESTORE_CHUNK_TEXT);
        String restoreChunkIdentity = "SOURCE_CHUNK:" + fixture.sourceChunkId(restoreDoc, 1);

        long legacyDoc = documents.insert(active.id(), "legacy-archive.txt", "legacy-archive.txt",
                "txt", "inbox/legacy-archive.txt",
                WikiContentHash.sha256("legacy-archive.txt".getBytes(StandardCharsets.UTF_8)),
                100L, "text/plain", GraphRetrievalQualityFixture.NOW, "DELETED", null, null);
        fixture.insertChunk(legacyDoc, 1, "封存的歷史片段。");
        String legacyChunkIdentity = "SOURCE_CHUNK:" + fixture.sourceChunkId(legacyDoc, 1);

        List<GoldenPage> pages = QueryTransformationEvaluationCorpusV1.pages(restoreDoc);
        fixture.materializeInto(active, foreign, pages);
        GoldenPage stalePage = pages.stream()
                .filter(page -> page.knowledgeId().equals(RerankEvaluationCorpusV1.STALE_PAGE))
                .findFirst().orElseThrow();
        fixture.mutateStaleHash(active, stalePage);

        String foreignIdentity = new GraphRetrievalGoldenCorpus().foreignIdentity();
        List<GoldenQuery> queries = QueryTransformationEvaluationCorpusV1.queries(
                restoreChunkIdentity, "WIKI:" + RerankEvaluationCorpusV1.CACHE_INVALIDATION_TARGET);
        List<String> forbidden = RerankEvaluationCorpusV1.safetyIdentities(legacyChunkIdentity,
                foreignIdentity);

        Set<String> canonicalUniverse = new LinkedHashSet<>();
        pages.forEach(page -> canonicalUniverse.add("WIKI:" + page.knowledgeId()));
        canonicalUniverse.add(restoreChunkIdentity);
        canonicalUniverse.add(legacyChunkIdentity);

        try (GraphRetrievalQualityFixture.LifecycleBundle lifecycleFactory =
                     fixture.lifecycle(temp.resolve("qt-release-compare-graph"))) {
            FusedRetrievalOrchestrator orchestrator = fixture.orchestrator(
                    lifecycleFactory.lifecycle(), lifecycleFactory.factory(),
                    FusionRankingPolicy.production());
            RetrievalService retrievalService = fixture.retrievalService(orchestrator);
            GraphProjectionInput input = assembler.assemble(active);
            lifecycleFactory.lifecycle().rebuild(input);
            GraphProjectionStatusResponse projection = GraphProjectionStatusResponse.from(
                    lifecycleFactory.lifecycle().readiness(active));
            assertThat(projection.status()).isEqualTo("READY");

            Candidate rewrite = QueryTransformationCandidates.singleRewrite(SINGLE_REWRITE,
                    QueryTransformationCandidates.corpusProtectedProvider());

            Map<String, List<ArmRun>> first = runAllArms(retrievalService, queries, rewrite);
            Map<String, List<ArmRun>> second = runAllArms(retrievalService, queries, rewrite);

            List<String> violations = new ArrayList<>();
            compareReproducibility(first, second, violations);
            applyGates(first, queries, forbidden, canonicalUniverse, foreignIdentity, violations);

            writeReports(first, queries, projection, violations);
            assertThat(violations)
                    .as("release comparison blocking gates (see "
                            + "target/quality-reports/query-transformation-release-comparison-v1.md)")
                    .isEmpty();
        }
    }

    private record ArmRun(String arm, String queryId, String queryClass, List<String> inputs,
                          List<String> inputOrigins, List<String> events, List<String> order,
                          int budgetItems, double recallAt8, double recallAt12, double mrr,
                          int noiseAt8, int noiseAt12, int codePoints, int providerCalls,
                          int fanOut, boolean insufficientEvidence) {
    }

    private Map<String, List<ArmRun>> runAllArms(RetrievalService retrievalService,
                                                 List<GoldenQuery> queries, Candidate rewrite) {
        List<ArmRun> baseline = new ArrayList<>();
        List<ArmRun> rewritten = new ArrayList<>();
        List<ArmRun> windowed = new ArrayList<>();
        for (GoldenQuery query : queries) {
            baseline.add(runBaseline(retrievalService, query));
            rewritten.add(runRewrite(retrievalService, query, rewrite));
            windowed.add(runWindow12(retrievalService, query));
        }
        Map<String, List<ArmRun>> arms = new LinkedHashMap<>();
        arms.put(BASELINE, List.copyOf(baseline));
        arms.put(SINGLE_REWRITE, List.copyOf(rewritten));
        arms.put(WINDOW_12, List.copyOf(windowed));
        return arms;
    }

    private ArmRun runBaseline(RetrievalService retrievalService, GoldenQuery query) {
        RetrievalInspectionCollector collector = new RetrievalInspectionCollector();
        EvidenceBundle bundle = retrievalService.retrieve(
                RetrievalRequest.defaults(query.text(), RetrievalMode.HYBRID_GRAPH), collector);
        return toRun(BASELINE, query, List.of(query.text()), List.of("ORIGINAL"), List.of(),
                bundle, 8, 0, 1);
    }

    private ArmRun runRewrite(RetrievalService retrievalService, GoldenQuery query,
                              Candidate rewrite) {
        TransformationPlan plan = rewrite.plan(query.id(), query.text());
        List<EvidenceBundle> bundles = new ArrayList<>();
        for (RetrievalInput retrievalInput : plan.inputs()) {
            EvidenceBundle bundle = retrievalService.retrieve(
                    RetrievalRequest.defaults(retrievalInput.queryText(),
                            RetrievalMode.HYBRID_GRAPH),
                    new RetrievalInspectionCollector());
            bundles.add(bundle);
        }
        List<String> merged = interleaveByRank(bundles.stream()
                .map(bundle -> bundle.items().stream().map(EvidenceItem::stableIdentity)
                        .toList()).toList());
        // Merged bundle semantics mirror the #390 harness (rank interleave, original first,
        // deduplicated). Noise/context are measured on the merged k=8 window.
        EvidenceBundle primary = bundles.get(0);
        int codePoints = merged.stream().limit(8)
                .mapToInt(identity -> contentLength(primary, bundles, identity)).sum();
        boolean insufficient = bundles.stream().anyMatch(EvidenceBundle::insufficientEvidence)
                && merged.isEmpty();
        return new ArmRun(SINGLE_REWRITE, query.id(), query.queryClass(),
                plan.inputs().stream().map(RetrievalInput::queryText).toList(),
                plan.inputs().stream().map(RetrievalInput::origin).toList(),
                plan.events().stream().map(event -> event.kind() + ": " + event.detail())
                        .toList(),
                List.copyOf(merged), 8,
                recallAtK(merged, query.relevant(), 8), recallAtK(merged, query.relevant(), 12),
                mrr(merged, query.relevant()),
                noiseAtK(merged, query.relevant(), 8), noiseAtK(merged, query.relevant(), 12),
                codePoints, plan.providerCallAttempts(), plan.inputs().size(), insufficient);
    }

    private ArmRun runWindow12(RetrievalService retrievalService, GoldenQuery query) {
        RetrievalRequest expanded = new RetrievalRequest(query.text(), RetrievalMode.HYBRID_GRAPH,
                12, 12_000);
        EvidenceBundle bundle = retrievalService.retrieve(expanded,
                new RetrievalInspectionCollector());
        return toRun(WINDOW_12, query, List.of(query.text()), List.of("ORIGINAL"), List.of(),
                bundle, 12, 0, 1);
    }

    private ArmRun toRun(String arm, GoldenQuery query, List<String> inputs,
                         List<String> origins, List<String> events, EvidenceBundle bundle,
                         int budgetItems, int providerCalls, int fanOut) {
        List<String> order = bundle.items().stream().map(EvidenceItem::stableIdentity).toList();
        int codePoints = bundle.items().stream()
                .mapToInt(item -> item.content().codePointCount(0, item.content().length()))
                .sum();
        return new ArmRun(arm, query.id(), query.queryClass(), List.copyOf(inputs),
                List.copyOf(origins), List.copyOf(events), List.copyOf(order), budgetItems,
                recallAtK(order, query.relevant(), 8), recallAtK(order, query.relevant(), 12),
                mrr(order, query.relevant()),
                noiseAtK(order, query.relevant(), 8), noiseAtK(order, query.relevant(), 12),
                codePoints, providerCalls, fanOut, bundle.insufficientEvidence());
    }

    private static int contentLength(EvidenceBundle primary, List<EvidenceBundle> bundles,
                                     String identity) {
        for (EvidenceBundle bundle : bundles) {
            for (EvidenceItem item : bundle.items()) {
                if (item.stableIdentity().equals(identity)) {
                    return item.content().codePointCount(0, item.content().length());
                }
            }
        }
        for (EvidenceItem item : primary.items()) {
            if (item.stableIdentity().equals(identity)) {
                return item.content().codePointCount(0, item.content().length());
            }
        }
        return 0;
    }

    private static List<String> interleaveByRank(List<List<String>> orders) {
        List<String> merged = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        int max = orders.stream().mapToInt(List::size).max().orElse(0);
        for (int rank = 0; rank < max; rank++) {
            for (List<String> order : orders) {
                if (rank < order.size() && seen.add(order.get(rank))) {
                    merged.add(order.get(rank));
                }
            }
        }
        return List.copyOf(merged);
    }

    private static double recallAtK(List<String> order, Set<String> relevant, int k) {
        if (relevant.isEmpty()) {
            return 1.0d;
        }
        long hits = order.stream().limit(k).filter(relevant::contains).count();
        return (double) hits / relevant.size();
    }

    private static int noiseAtK(List<String> order, Set<String> relevant, int k) {
        return (int) order.stream().limit(k).filter(identity -> !relevant.contains(identity))
                .count();
    }

    private static double mrr(List<String> order, Set<String> relevant) {
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

    private void compareReproducibility(Map<String, List<ArmRun>> first,
                                        Map<String, List<ArmRun>> second,
                                        List<String> violations) {
        for (Map.Entry<String, List<ArmRun>> entry : first.entrySet()) {
            List<ArmRun> a = entry.getValue();
            List<ArmRun> b = second.get(entry.getKey());
            for (int index = 0; index < a.size(); index++) {
                if (!a.get(index).order().equals(b.get(index).order())
                        || !a.get(index).inputs().equals(b.get(index).inputs())) {
                    violations.add("non-reproducible run at " + entry.getKey() + "/"
                            + a.get(index).queryId());
                }
            }
        }
    }

    private void applyGates(Map<String, List<ArmRun>> arms, List<GoldenQuery> queries,
                            List<String> forbidden, Set<String> canonicalUniverse,
                            String foreignIdentity, List<String> violations) {
        for (Map.Entry<String, List<ArmRun>> entry : arms.entrySet()) {
            String arm = entry.getKey();
            List<ArmRun> runs = entry.getValue();
            for (int index = 0; index < runs.size(); index++) {
                ArmRun run = runs.get(index);
                GoldenQuery query = queries.get(index);
                for (String identity : run.order()) {
                    if (forbidden.contains(identity)) {
                        violations.add(arm + "/" + run.queryId()
                                + ": surfaced forbidden identity " + identity);
                    }
                    if (identity.equals(foreignIdentity)) {
                        violations.add(arm + "/" + run.queryId()
                                + ": cross-workspace identity " + identity);
                    } else if (!canonicalUniverse.contains(identity)) {
                        violations.add(arm + "/" + run.queryId()
                                + ": non-canonical identity " + identity);
                    }
                }
                if (arm.equals(SINGLE_REWRITE)) {
                    if (run.inputOrigins().isEmpty()
                            || !run.inputOrigins().get(0)
                                    .equals(RetrievalInput.ORIGIN_ORIGINAL)) {
                        violations.add(arm + "/" + run.queryId()
                                + ": original query is not the first retrieval input");
                    }
                    if (run.fanOut() > 2) {
                        violations.add(arm + "/" + run.queryId()
                                + ": fan-out budget exceeded (" + run.fanOut() + ")");
                    }
                    ArmRun base = arms.get(BASELINE).get(index);
                    for (String identity : query.relevant()) {
                        if (base.order().contains(identity) && !run.order().contains(identity)
                                && (query.queryClass().equals("EXACT_TOKEN")
                                        || query.graphOnlyRelevant().contains(identity))) {
                            violations.add(arm + "/" + run.queryId()
                                    + ": lost relevant identity " + identity
                                    + " (exact-token/graph-added retention gate)");
                        }
                    }
                }
                if (arm.equals(WINDOW_12)) {
                    if (run.fanOut() != 1 || run.providerCalls() != 0) {
                        violations.add(arm + "/" + run.queryId()
                                + ": provider-free arm must stay single-input with zero calls");
                    }
                    if (run.budgetItems() != 12) {
                        violations.add(arm + "/" + run.queryId()
                                + ": window arm must use the bounded k=12 budget");
                    }
                }
                if (arm.equals(BASELINE) && run.budgetItems() != 8) {
                    violations.add(arm + "/" + run.queryId() + ": baseline must use k=8");
                }
            }
        }
    }

    private void writeReports(Map<String, List<ArmRun>> arms, List<GoldenQuery> queries,
                              GraphProjectionStatusResponse projection, List<String> violations)
            throws IOException {
        Path reports = Path.of("target", "quality-reports");
        Files.createDirectories(reports);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("comparison", COMPARISON_VERSION);
        json.put("corpus", QueryTransformationEvaluationCorpusV1.VERSION);
        json.put("rewriteFixtures", QueryTransformationEvaluationCorpusV1.REWRITE_FIXTURES_VERSION);
        json.put("branch", git("rev-parse", "--abbrev-ref", "HEAD"));
        json.put("headSha", git("rev-parse", "HEAD"));
        json.put("originMainSha", git("rev-parse", "origin/main"));
        json.put("fusionPolicyVersion", FusionRankingPolicy.production().version());
        json.put("graphProjectionVersion", projection.projectionVersion());
        json.put("lexicalQueryProjection",
                org.km.llmwiki.search.CjkBigramProjector.VERSION
                        + " (deterministic lexical projection, not semantic rewriting)");
        json.put("offline", true);
        json.put("deterministic", true);
        json.put("liveProvider", "UNAVAILABLE (offline fixture run; token usage and live latency "
                + "require query-rewrite-live-measurement-v1 controlled measurement)");
        json.put("arms", arms.entrySet().stream().map(entry -> {
            Map<String, Object> armJson = new LinkedHashMap<>();
            armJson.put("arm", entry.getKey());
            armJson.put("queries", entry.getValue().stream().map(run -> {
                Map<String, Object> queryJson = new LinkedHashMap<>();
                queryJson.put("queryId", run.queryId());
                queryJson.put("queryClass", run.queryClass());
                queryJson.put("inputs", run.inputs());
                queryJson.put("inputOrigins", run.inputOrigins());
                queryJson.put("events", run.events());
                queryJson.put("order", run.order());
                queryJson.put("budgetItems", run.budgetItems());
                queryJson.put("recallAt8", run.recallAt8());
                queryJson.put("recallAt12", run.recallAt12());
                queryJson.put("mrr", run.mrr());
                queryJson.put("noiseAt8", run.noiseAt8());
                queryJson.put("noiseAt12", run.noiseAt12());
                queryJson.put("codePoints", run.codePoints());
                queryJson.put("providerCalls", run.providerCalls());
                queryJson.put("fanOut", run.fanOut());
                queryJson.put("insufficientEvidence", run.insufficientEvidence());
                return queryJson;
            }).toList());
            return armJson;
        }).toList());
        json.put("violations", violations);
        Files.writeString(reports.resolve("query-transformation-release-comparison-v1.json"),
                new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(json)
                        + "\n");
        Files.writeString(reports.resolve("query-transformation-release-comparison-v1.md"),
                markdown(arms, queries, projection, violations));
    }

    private String markdown(Map<String, List<ArmRun>> arms, List<GoldenQuery> queries,
                            GraphProjectionStatusResponse projection, List<String> violations) {
        StringBuilder report = new StringBuilder();
        report.append("# Query transformation release comparison (v1, #408)\n\n");
        report.append("- comparison: `").append(COMPARISON_VERSION).append("`, corpus: `")
                .append(QueryTransformationEvaluationCorpusV1.VERSION).append("`\n");
        report.append("- branch: `").append(git("rev-parse", "--abbrev-ref", "HEAD"))
                .append("`, HEAD: `").append(git("rev-parse", "HEAD"))
                .append("`, origin/main: `").append(git("rev-parse", "origin/main"))
                .append("`\n");
        report.append("- stack: fusion `").append(FusionRankingPolicy.production().version())
                .append("`, graph `").append(projection.projectionVersion())
                .append("`, lexical `")
                .append(org.km.llmwiki.search.CjkBigramProjector.VERSION).append("`\n");
        report.append("- live provider: `UNAVAILABLE` (offline fixture run; see ")
                .append("`query-rewrite-live-measurement-v1` procedure)\n\n");
        report.append("## Aggregate (mean over ").append(queries.size())
                .append(" queries)\n\n");
        report.append("| arm | mean recall@8 | mean recall@12 | mean MRR | mean noise@8 | "
                + "mean noise@12 | provider calls/ask | fan-out total |\n");
        report.append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (Map.Entry<String, List<ArmRun>> entry : arms.entrySet()) {
            List<ArmRun> runs = entry.getValue();
            report.append("| ").append(entry.getKey()).append(" | ")
                    .append(fmt(runs.stream().mapToDouble(ArmRun::recallAt8).average()
                            .orElse(0))).append(" | ")
                    .append(fmt(runs.stream().mapToDouble(ArmRun::recallAt12).average()
                            .orElse(0))).append(" | ")
                    .append(fmt(runs.stream().mapToDouble(ArmRun::mrr).average().orElse(0)))
                    .append(" | ")
                    .append(fmt(runs.stream().mapToInt(ArmRun::noiseAt8).average().orElse(0)))
                    .append(" | ")
                    .append(fmt(runs.stream().mapToInt(ArmRun::noiseAt12).average().orElse(0)))
                    .append(" | ").append(runs.get(0).providerCalls()).append(" | ")
                    .append(runs.stream().mapToInt(ArmRun::fanOut).sum()).append(" |\n");
        }
        report.append("\n## Per-query detail\n\n");
        report.append("| arm | query | class | recall@8 | recall@12 | mrr | noise@8 | "
                + "noise@12 | codePoints | events |\n");
        report.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (Map.Entry<String, List<ArmRun>> entry : arms.entrySet()) {
            for (ArmRun run : entry.getValue()) {
                report.append("| ").append(entry.getKey()).append(" | ").append(run.queryId())
                        .append(" | ").append(run.queryClass()).append(" | ")
                        .append(fmt(run.recallAt8())).append(" | ").append(fmt(run.recallAt12()))
                        .append(" | ").append(fmt(run.mrr())).append(" | ")
                        .append(run.noiseAt8()).append(" | ").append(run.noiseAt12())
                        .append(" | ").append(run.codePoints()).append(" | ")
                        .append(String.join("; ", run.events())).append(" |\n");
            }
        }
        report.append("\n## Cost / operational reading\n\n");
        report.append("- BASELINE: 0 extra provider calls, fan-out 1, window k=8.\n");
        report.append("- SINGLE_REWRITE: +1 fixture-modelled provider call per ask, fan-out ")
                .append("<= 2, window k=8; live token/latency/failure distribution is ")
                .append("`UNAVAILABLE` offline and must come from the controlled measurement.\n");
        report.append("- PROVIDER_FREE_WINDOW_12: 0 provider calls, fan-out 1, window k=12; ")
                .append("cost is extra window noise + downstream context code points, no egress.\n");
        report.append("\n## Blocking gates\n\n");
        report.append(violations.isEmpty() ? "none\n"
                : violations.stream().map(v -> "- " + v + "\n").reduce("", String::concat));
        return report.toString();
    }

    private static String fmt(double value) {
        return String.format("%.4f", value);
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
}
