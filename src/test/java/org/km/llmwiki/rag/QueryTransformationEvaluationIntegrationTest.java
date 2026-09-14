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
import org.km.llmwiki.rag.GraphRetrievalGoldenCorpus.GoldenPage;
import org.km.llmwiki.rag.GraphRetrievalGoldenCorpus.GoldenQuery;
import org.km.llmwiki.rag.QueryTransformationCandidates.Candidate;
import org.km.llmwiki.rag.QueryTransformationCandidates.RetrievalInput;
import org.km.llmwiki.rag.QueryTransformationCandidates.TransformationPlan;
import org.km.llmwiki.rag.RetrievalInspectionTrace.CandidateTrace;
import org.km.llmwiki.rag.RetrievalInspectionTrace.Disposition;
import org.km.llmwiki.rag.RetrievalInspectionTrace.ModalityTrace;
import org.km.llmwiki.rag.RetrievalInspectionTrace.SelectionTrace;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Query-side semantic transformation recall evaluation (#390, corpus
 * {@code query-transformation-evaluation-corpus-v1}).
 *
 * <p><strong>What is measured.</strong> The ORIGINAL_QUERY baseline (current production
 * behavior: the raw question is the only retrieval input) against SINGLE_REWRITE candidates on
 * the production-equivalent retrieval stack (real FTS with {@code cjk-bigram-v1}, deterministic
 * concept embeddings over the real vector candidate service, real ArcadeDB projection with
 * traversal/admission, canonical fusion, unchanged authority/currentness qualification).
 * Candidate-generation recall is measured on the pre-qualification channel-candidate pool
 * observed by the production {@link RetrievalInspectionCollector}, and is reported separately
 * from fused-window ordering (recall@8/MRR over the post-qualification bundle), so a rewrite
 * gain can never be confused with fusion/rerank ordering effects.
 *
 * <p><strong>Blocking correctness gates.</strong> No stale/foreign/deleted identity may enter
 * any variant's post-qualification evidence; every surfaced identity must belong to the
 * materialized canonical universe (no fabrication, no citation-identity drift); exact technical
 * tokens and graph-added discovery must be retained by the protected candidate; every provider
 * failure mode must deterministically fall back to the original query with a typed reason and
 * must never be mis-translated into {@code INSUFFICIENT_EVIDENCE}; the whole evaluation must be
 * exactly reproducible. Quality deltas are recorded per query in the report; the recorded
 * decision (GO TO ADOPTION ISSUE / CONDITIONAL GO / NO-GO / DEFER) drives any future adoption
 * issue, and this test never changes the production default, adds no public retrieval mode,
 * and requires no live provider.
 */
@Tag("integration")
class QueryTransformationEvaluationIntegrationTest extends IsolatedIntegrationTest {

    private static final int K = 8;
    private static final String ORIGINAL_QUERY = "ORIGINAL_QUERY";
    private static final String SINGLE_REWRITE = "SINGLE_REWRITE";
    private static final String UNPROTECTED_PROBE = "SINGLE_REWRITE_UNPROTECTED_PROBE";
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
    void evaluatesQueryTransformationCandidatesAgainstTheOriginalQueryBaseline() throws Exception {
        GraphRetrievalQualityFixture fixture = new GraphRetrievalQualityFixture(db(), workspaces,
                searchService, publishedWikiRepository, publishedWikiContentReader,
                sourceAuthorityRepository, ftsRepository, embeddingRepository, embeddingReadiness,
                jobs, assembler, lifecycleRepository, currentness, paths);
        GraphWorkspaceScope active = fixture.workspace(temp, "qte-eval-v1");
        GraphWorkspaceScope foreign = fixture.workspace(temp, "qte-eval-v1-foreign");
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

        Map<String, String> evidenceText = new LinkedHashMap<>();
        pages.forEach(page -> evidenceText.put("WIKI:" + page.knowledgeId(),
                page.title() + "\n" + page.body()));
        evidenceText.put(restoreChunkIdentity, RESTORE_CHUNK_TEXT);
        evidenceText.put(legacyChunkIdentity, "封存的歷史片段。");
        Set<String> graphOnlyIdentities = new LinkedHashSet<>();
        pages.stream().filter(page -> !page.embedded())
                .forEach(page -> graphOnlyIdentities.add("WIKI:" + page.knowledgeId()));
        graphOnlyIdentities.add(restoreChunkIdentity);
        graphOnlyIdentities.add(legacyChunkIdentity);

        try (GraphRetrievalQualityFixture.LifecycleBundle lifecycleFactory =
                     fixture.lifecycle(temp.resolve("qte-eval-graph"))) {
            FusedRetrievalOrchestrator orchestrator = fixture.orchestrator(
                    lifecycleFactory.lifecycle(), lifecycleFactory.factory(),
                    FusionRankingPolicy.production());
            RetrievalService retrievalService = fixture.retrievalService(orchestrator);
            GraphProjectionInput input = assembler.assemble(active);
            lifecycleFactory.lifecycle().rebuild(input);
            GraphProjectionStatusResponse projection = GraphProjectionStatusResponse.from(
                    lifecycleFactory.lifecycle().readiness(active));
            assertThat(projection.status()).isEqualTo("READY");

            List<Candidate> candidates = List.of(
                    QueryTransformationCandidates.originalQuery(),
                    QueryTransformationCandidates.singleRewrite(SINGLE_REWRITE,
                            QueryTransformationCandidates.corpusProtectedProvider()),
                    QueryTransformationCandidates.singleRewrite(UNPROTECTED_PROBE,
                            QueryTransformationCandidates.unprotectedProbeProvider()));

            Map<String, List<VariantQueryRun>> runs = new LinkedHashMap<>();
            Map<String, List<VariantQueryRun>> repeats = new LinkedHashMap<>();
            for (Candidate candidate : candidates) {
                runs.put(candidate.name(), runAll(candidate, retrievalService, queries));
                repeats.put(candidate.name(), runAll(candidate, retrievalService, queries));
            }
            List<String> violations = new ArrayList<>();
            compareReproducibility(runs, repeats, violations);

            List<VariantQueryRun> baselineRuns = runs.get(ORIGINAL_QUERY);
            Map<String, Map<String, MissClassification>> missTaxonomy =
                    classifyMisses(baselineRuns, queries, evidenceText, graphOnlyIdentities);
            attachDeltas(runs, queries);
            applyGates(runs, baselineRuns, queries, forbidden, canonicalUniverse, foreignIdentity,
                    violations);

            List<FallbackScenario> fallbackScenarios =
                    runFallbackScenarios(retrievalService, queries, baselineRuns, violations);

            // Unlock ladder: MULTI_QUERY_BOUNDED is only evaluated when the protected
            // SINGLE_REWRITE produced a reproducible recall gain (candidate-generation pool
            // gain or fused-window gain) and every blocking gate holds. HyDE stays locked: a
            // fixture pseudo-document would be corpus-crafted by construction (candidate-
            // biased) and requires live-provider controlled measurement instead.
            List<String> poolGainQueries = runs.get(SINGLE_REWRITE).stream()
                    .filter(run -> !run.additionalRelevantInPool().isEmpty())
                    .map(VariantQueryRun::queryId).toList();
            List<String> windowGainQueries = runs.get(SINGLE_REWRITE).stream()
                    .filter(run -> !run.additionalRelevantInBundle().isEmpty())
                    .map(VariantQueryRun::queryId).toList();
            boolean multiQueryUnlocked = violations.isEmpty()
                    && !(poolGainQueries.isEmpty() && windowGainQueries.isEmpty());
            if (multiQueryUnlocked) {
                Candidate multiQuery = QueryTransformationCandidates.multiQueryBounded(
                        QueryTransformationCandidates.corpusProtectedProvider(),
                        QueryTransformationCandidates.corpusAlternateProvider());
                List<VariantQueryRun> multiQueryRuns = runAll(multiQuery, retrievalService, queries);
                runs.put(multiQuery.name(), multiQueryRuns);
                applyGates(Map.of(multiQuery.name(), multiQueryRuns), baselineRuns, queries,
                        forbidden, canonicalUniverse, foreignIdentity, violations);
                Map<String, List<VariantQueryRun>> multiRepeat = Map.of(multiQuery.name(),
                        runAll(multiQuery, retrievalService, queries));
                compareReproducibility(Map.of(multiQuery.name(), multiQueryRuns), multiRepeat,
                        violations);
                attachDeltas(runs, queries);
            }

            // The degradation scenario removes the embedding projection from the active
            // workspace, so it must run after every measurement that needs the vector channel.
            List<DegradationScenario> degradation = runDegradationScenario(fixture, active,
                    retrievalService, queries, evidenceText, violations);

            List<String> overlapAudit = lexicalOverlapAudit(queries, evidenceText);
            Decision decision = decide(runs, violations, poolGainQueries, windowGainQueries,
                    missTaxonomy, multiQueryUnlocked);
            writeReports(runs, decision, violations, projection, missTaxonomy, fallbackScenarios,
                    degradation, poolGainQueries, windowGainQueries, multiQueryUnlocked,
                    overlapAudit);
            assertThat(violations)
                    .as("query transformation evaluation correctness gates (see "
                            + "target/quality-reports/query-transformation-evaluation-v1.md)")
                    .isEmpty();
        }
    }

    // ------------------------------------------------------------------ execution

    private record InputRun(RetrievalInput input, EvidenceBundle bundle,
                            RetrievalInspectionTrace trace) {
    }

    private List<VariantQueryRun> runAll(Candidate candidate, RetrievalService retrievalService,
                                         List<GoldenQuery> queries) {
        List<VariantQueryRun> runs = new ArrayList<>();
        for (GoldenQuery query : queries) {
            TransformationPlan plan = candidate.plan(query.id(), query.text());
            List<InputRun> inputRuns = new ArrayList<>();
            for (RetrievalInput retrievalInput : plan.inputs()) {
                inputRuns.add(executeOne(retrievalService, retrievalInput));
            }
            runs.add(buildRun(candidate.name(), query, plan, inputRuns));
        }
        return runs;
    }

    private InputRun executeOne(RetrievalService retrievalService, RetrievalInput input) {
        RetrievalInspectionCollector collector = new RetrievalInspectionCollector();
        EvidenceBundle bundle = retrievalService.retrieve(
                RetrievalRequest.defaults(input.queryText(), RetrievalMode.HYBRID_GRAPH),
                collector);
        return new InputRun(input, bundle, collector.toTrace());
    }

    private VariantQueryRun buildRun(String candidate, GoldenQuery query, TransformationPlan plan,
                                     List<InputRun> inputRuns) {
        Set<String> relevant = query.relevant();
        List<String> pool = poolUnion(inputRuns);
        List<String> mergedOrder = interleaveByRank(inputRuns.stream()
                .map(run -> run.bundle().items().stream()
                        .map(EvidenceItem::stableIdentity).toList()).toList());
        Map<String, List<String>> rejectionCodes = new LinkedHashMap<>();
        for (InputRun run : inputRuns) {
            for (ModalityTrace modality : run.trace().modalities()) {
                for (var rejected : modality.rejected()) {
                    rejectionCodes.computeIfAbsent(rejected.identity(),
                            ignored -> new ArrayList<>()).add(rejected.reasonCode());
                }
            }
            for (SelectionTrace selection : run.trace().selection()) {
                if (selection.disposition() == Disposition.REJECTED) {
                    rejectionCodes.computeIfAbsent(selection.identity(),
                            ignored -> new ArrayList<>()).add(selection.reasonCode());
                }
            }
        }
        boolean vectorUnavailable = inputRuns.stream()
                .anyMatch(run -> run.bundle().diagnostics().vectorUnavailable());
        boolean graphUnavailable = inputRuns.stream()
                .anyMatch(run -> run.bundle().diagnostics().graphUnavailable());
        List<VariantQueryRun.InputObservation> inputObservations = inputRuns.stream()
                .map(run -> new VariantQueryRun.InputObservation(run.input().origin(),
                        run.input().queryText(), channelCandidates(run.trace()),
                        run.bundle().items().stream().map(EvidenceItem::stableIdentity).toList()))
                .toList();
        return VariantQueryRun.intrinsic(candidate, query.id(), query.queryClass(),
                plan.inputs().stream().map(RetrievalInput::queryText).toList(),
                plan.inputs().stream().map(RetrievalInput::origin).toList(),
                plan.events().stream().map(event -> event.kind() + ": " + event.detail()).toList(),
                pool, mergedOrder,
                recallOver(pool, relevant), recallAtK(mergedOrder, relevant),
                mrr(mergedOrder, relevant), precisionAtK(mergedOrder, relevant),
                (int) mergedOrder.stream().filter(identity -> !relevant.contains(identity)).count(),
                inputRuns.stream().anyMatch(run -> run.bundle().insufficientEvidence()),
                plan.inputs().size(), plan.providerCallAttempts(),
                Map.copyOf(rejectionCodes), vectorUnavailable, graphUnavailable,
                List.copyOf(inputObservations));
    }

    /** Per-channel candidate identities for one retrieval input (empty channels omitted). */
    private static Map<String, List<String>> channelCandidates(RetrievalInspectionTrace trace) {
        Map<String, List<String>> channels = new LinkedHashMap<>();
        for (ModalityTrace modality : trace.modalities()) {
            if (!modality.candidates().isEmpty()) {
                channels.put(modality.modality().name(), modality.candidates().stream()
                        .map(CandidateTrace::identity).toList());
            }
        }
        return Map.copyOf(channels);
    }

    /** Pre-qualification channel-candidate pool: ordered union over all retrieval inputs. */
    private static List<String> poolUnion(List<InputRun> runs) {
        LinkedHashSet<String> pool = new LinkedHashSet<>();
        for (InputRun run : runs) {
            for (ModalityTrace modality : run.trace().modalities()) {
                for (var candidate : modality.candidates()) {
                    pool.add(candidate.identity());
                }
            }
        }
        return List.copyOf(pool);
    }

    /**
     * Evaluation-only merge for fan-out &gt; 1: rank interleave with the original-query run
     * first, deduplicated by identity. This is deliberately generous to the rewrite candidate
     * (its hits get the best deterministic slot the merge can give); pool-level recall is the
     * primary candidate-generation metric and does not depend on this merge. A production
     * adoption would have to define (and gate) its own merge policy.
     */
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

    private void attachDeltas(Map<String, List<VariantQueryRun>> runs, List<GoldenQuery> queries) {
        List<VariantQueryRun> baseline = runs.get(ORIGINAL_QUERY);
        for (Map.Entry<String, List<VariantQueryRun>> entry : runs.entrySet()) {
            if (entry.getKey().equals(ORIGINAL_QUERY)) {
                continue;
            }
            List<VariantQueryRun> variant = entry.getValue();
            for (int index = 0; index < variant.size(); index++) {
                VariantQueryRun run = variant.get(index);
                VariantQueryRun base = baseline.get(index);
                Set<String> relevant = queries.get(index).relevant();
                List<String> relevantInPool = run.pool().stream()
                        .filter(identity -> relevant.contains(identity)
                                && !base.pool().contains(identity)).toList();
                List<String> relevantInBundle = run.mergedOrder().stream()
                        .filter(identity -> relevant.contains(identity)
                                && !base.mergedOrder().contains(identity)).toList();
                List<String> extraNoise = run.mergedOrder().stream()
                        .filter(identity -> !relevant.contains(identity)
                                && !base.mergedOrder().contains(identity)).toList();
                variant.set(index, run.withDeltas(relevantInPool, relevantInBundle, extraNoise));
            }
        }
    }

    // ------------------------------------------------------------------ metrics

    private static double recallOver(List<String> identities, Set<String> relevant) {
        if (relevant.isEmpty()) {
            return 1.0d;
        }
        long hits = identities.stream().filter(relevant::contains).count();
        return (double) hits / relevant.size();
    }

    private static double recallAtK(List<String> order, Set<String> relevant) {
        if (relevant.isEmpty()) {
            return 1.0d;
        }
        long hits = order.stream().limit(K).filter(relevant::contains).count();
        return (double) hits / relevant.size();
    }

    private static double precisionAtK(List<String> order, Set<String> relevant) {
        long hits = order.stream().limit(K).filter(relevant::contains).count();
        return (double) hits / K;
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

    // ------------------------------------------------------------------ miss taxonomy

    record MissClassification(String identity, List<String> classes, String detail) {
    }

    /**
     * Typed, reproducible classification of every ORIGINAL_QUERY relevant-identity miss. A miss
     * is only attributable to query/corpus representation mismatch when candidate generation
     * never surfaced the identity; authority rejections, fusion/budget crowd-out, graph-only
     * reachability and backend unavailability are recorded as their own classes so a rewrite
     * gain can never be claimed for problems it cannot fix.
     */
    private Map<String, Map<String, MissClassification>> classifyMisses(
            List<VariantQueryRun> baseline, List<GoldenQuery> queries,
            Map<String, String> evidenceText, Set<String> graphOnlyIdentities) {
        Map<String, Map<String, MissClassification>> taxonomy = new LinkedHashMap<>();
        for (int index = 0; index < queries.size(); index++) {
            GoldenQuery query = queries.get(index);
            VariantQueryRun run = baseline.get(index);
            if (query.relevant().isEmpty()) {
                continue;
            }
            Set<String> queryTokens = Set.copyOf(
                    org.km.llmwiki.search.CjkBigramProjector.tokens(query.text()));
            Map<String, MissClassification> perIdentity = new LinkedHashMap<>();
            for (String identity : query.relevant()) {
                if (run.mergedOrder().contains(identity)) {
                    continue;
                }
                Set<String> targetTokens = Set.copyOf(
                        org.km.llmwiki.search.CjkBigramProjector.tokens(
                                evidenceText.getOrDefault(identity, "")));
                long overlap = targetTokens.stream().filter(queryTokens::contains).count();
                String overlapDetail = "projected-token overlap=" + overlap + "/"
                        + queryTokens.size();
                List<String> classes = new ArrayList<>();
                if (run.rejectionCodes().containsKey(identity)) {
                    classes.add("AUTHORITY_CURRENTNESS_REJECTION");
                } else if (run.pool().contains(identity)) {
                    classes.add("FUSION_BUDGET_CROWDOUT");
                } else if (graphOnlyIdentities.contains(identity)) {
                    classes.add("GRAPH_ONLY_REACHABILITY");
                } else if (overlap == 0) {
                    classes.add("CANDIDATE_GENERATION_SEMANTIC_PARAPHRASE_MISMATCH");
                } else if (overlap < queryTokens.size()) {
                    classes.add("CANDIDATE_GENERATION_LEXICAL_WORDING_MISMATCH");
                } else {
                    classes.add("CANDIDATE_GENERATION_FULL_OVERLAP_UNEXPECTED");
                }
                if (run.vectorUnavailable()) {
                    classes.add("BACKEND_UNAVAILABLE_CONTRIBUTION(vector)");
                }
                if (run.graphUnavailable()) {
                    classes.add("BACKEND_UNAVAILABLE_CONTRIBUTION(graph)");
                }
                perIdentity.put(identity,
                        new MissClassification(identity, List.copyOf(classes), overlapDetail));
            }
            if (!perIdentity.isEmpty()) {
                taxonomy.put(query.id(), perIdentity);
            }
        }
        return taxonomy;
    }

    // ------------------------------------------------------------------ gates

    private void applyGates(Map<String, List<VariantQueryRun>> runs,
                            List<VariantQueryRun> baselineRuns, List<GoldenQuery> queries,
                            List<String> forbidden, Set<String> canonicalUniverse,
                            String foreignIdentity, List<String> violations) {
        for (Map.Entry<String, List<VariantQueryRun>> entry : runs.entrySet()) {
            String candidate = entry.getKey();
            List<VariantQueryRun> variantRuns = entry.getValue();
            for (int index = 0; index < variantRuns.size(); index++) {
                VariantQueryRun run = variantRuns.get(index);
                GoldenQuery query = queries.get(index);
                for (String identity : run.mergedOrder()) {
                    if (forbidden.contains(identity)) {
                        violations.add(candidate + "/" + run.queryId()
                                + ": surfaced forbidden identity " + identity);
                    }
                    if (identity.equals(foreignIdentity)) {
                        violations.add(candidate + "/" + run.queryId()
                                + ": cross-workspace identity " + identity);
                    } else if (!canonicalUniverse.contains(identity)) {
                        violations.add(candidate + "/" + run.queryId()
                                + ": non-canonical (fabricated/drifted) identity " + identity);
                    }
                }
                for (String identity : run.pool()) {
                    if (identity.equals(foreignIdentity)) {
                        violations.add(candidate + "/" + run.queryId()
                                + ": cross-workspace identity in candidate pool " + identity);
                    } else if (!canonicalUniverse.contains(identity)) {
                        violations.add(candidate + "/" + run.queryId()
                                + ": non-canonical identity in candidate pool " + identity);
                    }
                }
                if (run.inputs().isEmpty()
                        || !run.inputOrigins().get(0).equals(RetrievalInput.ORIGIN_ORIGINAL)) {
                    violations.add(candidate + "/" + run.queryId()
                            + ": original query is not the first retrieval input");
                }
                if (run.fanOut() > QueryTransformationCandidates.MAX_FAN_OUT) {
                    violations.add(candidate + "/" + run.queryId()
                            + ": fan-out budget exceeded (" + run.fanOut() + ")");
                }
                if (candidate.equals(SINGLE_REWRITE)) {
                    VariantQueryRun base = baselineRuns.get(index);
                    for (String identity : query.relevant()) {
                        if (base.mergedOrder().contains(identity)
                                && !run.mergedOrder().contains(identity)
                                && (query.queryClass().equals("EXACT_TOKEN")
                                        || query.graphOnlyRelevant().contains(identity))) {
                            violations.add(candidate + "/" + run.queryId()
                                    + ": lost relevant identity " + identity
                                    + " (exact-token/graph-added retention gate)");
                        }
                    }
                }
            }
        }
    }

    private void compareReproducibility(Map<String, List<VariantQueryRun>> first,
                                        Map<String, List<VariantQueryRun>> second,
                                        List<String> violations) {
        for (Map.Entry<String, List<VariantQueryRun>> entry : first.entrySet()) {
            List<VariantQueryRun> firstRuns = entry.getValue();
            List<VariantQueryRun> secondRuns = second.get(entry.getKey());
            for (int index = 0; index < firstRuns.size(); index++) {
                VariantQueryRun a = firstRuns.get(index);
                VariantQueryRun b = secondRuns.get(index);
                if (!a.pool().equals(b.pool()) || !a.mergedOrder().equals(b.mergedOrder())
                        || !a.inputs().equals(b.inputs()) || !a.events().equals(b.events())) {
                    violations.add("non-reproducible run at " + entry.getKey() + "/"
                            + a.queryId());
                }
            }
        }
    }

    // ------------------------------------------------------------------ fallback scenarios

    private record FallbackScenario(String scenario, String queryId, List<String> events,
                                    List<String> inputs, boolean baselineRetained,
                                    boolean insufficientEvidence) {
    }

    /**
     * Deterministic failure semantics: every provider failure mode must fall back to the
     * original query, keep the fan-out at one, and produce exactly the original-query evidence
     * bundle — an operational failure must never be mis-translated into INSUFFICIENT_EVIDENCE
     * for a query the baseline can answer.
     */
    private List<FallbackScenario> runFallbackScenarios(RetrievalService retrievalService,
                                                        List<GoldenQuery> queries,
                                                        List<VariantQueryRun> baselineRuns,
                                                        List<String> violations) {
        List<FallbackScenario> scenarios = new ArrayList<>();
        record Case(String name, String queryId, Candidate candidate) {
        }
        List<Case> cases = List.of(
                new Case("provider-unavailable", "paraphrase",
                        QueryTransformationCandidates.singleRewrite("SINGLE_REWRITE_UNAVAILABLE",
                                QueryTransformationCandidates.unavailableProvider())),
                new Case("malformed-output", "property-token",
                        QueryTransformationCandidates.singleRewrite("SINGLE_REWRITE_MALFORMED",
                                QueryTransformationCandidates.malformedProvider())),
                new Case("duplicate-of-original", "property-token",
                        QueryTransformationCandidates.singleRewrite("SINGLE_REWRITE_DUPLICATE",
                                QueryTransformationCandidates.duplicateProvider())),
                new Case("over-limit-output", "property-token",
                        QueryTransformationCandidates.singleRewrite("SINGLE_REWRITE_OVER_LIMIT",
                                QueryTransformationCandidates.overLimitProvider())));
        for (Case testCase : cases) {
            GoldenQuery query = queries.stream()
                    .filter(q -> q.id().equals(testCase.queryId())).findFirst().orElseThrow();
            TransformationPlan plan = testCase.candidate().plan(query.id(), query.text());
            InputRun run = executeOne(retrievalService, plan.inputs().get(0));
            List<String> baselineOrder = baselineRuns.stream()
                    .filter(r -> r.queryId().equals(query.id())).findFirst()
                    .orElseThrow().mergedOrder();
            List<String> fallbackOrder = run.bundle().items().stream()
                    .map(EvidenceItem::stableIdentity).toList();
            // The fallback guarantee is identity-with-the-original-query behavior: the same
            // evidence order and the same sufficiency verdict the baseline produced — not a
            // guarantee that the baseline found every relevant identity.
            VariantQueryRun baselineRun = baselineRuns.stream()
                    .filter(r -> r.queryId().equals(query.id())).findFirst().orElseThrow();
            boolean baselineRetained = fallbackOrder.equals(baselineOrder)
                    && run.bundle().insufficientEvidence()
                            == baselineRun.insufficientEvidence();
            boolean fanOutIsOne = plan.inputs().size() == 1
                    && plan.inputs().get(0).origin().equals(RetrievalInput.ORIGIN_ORIGINAL);
            if (!baselineRetained) {
                violations.add("fallback scenario " + testCase.name()
                        + " did not preserve the original-query baseline (possible "
                        + "INSUFFICIENT_EVIDENCE mistranslation)");
            }
            if (!fanOutIsOne) {
                violations.add("fallback scenario " + testCase.name() + " changed the fan-out");
            }
            scenarios.add(new FallbackScenario(testCase.name(), query.id(),
                    plan.events().stream().map(event -> event.kind() + ": " + event.detail())
                            .toList(),
                    plan.inputs().stream().map(RetrievalInput::queryText).toList(),
                    baselineRetained, run.bundle().insufficientEvidence()));
        }
        return scenarios;
    }

    // ------------------------------------------------------------------ degradation scenario

    private record DegradationScenario(String queryId, boolean vectorUnavailable,
                                       List<String> missClasses, double baselinePoolRecall,
                                       double rewritePoolRecall, List<String> notes) {
    }

    /**
     * Backend-unavailable scenario: after the main measurement, the embedding projection rows
     * and readiness state are removed from the active workspace, so vector candidates disappear
     * for reasons that have nothing to do with query wording. The taxonomy must attribute the
     * miss to the backend (never to a wording mismatch), and any measured rewrite delta must be
     * reported as state-conditional rather than credited to rewriting. The test-isolated
     * database is reset per method, so no restoration is needed afterwards.
     */
    private List<DegradationScenario> runDegradationScenario(GraphRetrievalQualityFixture fixture,
                                                             GraphWorkspaceScope active,
                                                             RetrievalService retrievalService,
                                                             List<GoldenQuery> queries,
                                                             Map<String, String> evidenceText,
                                                             List<String> violations) {
        fixture.openWorkspace(active);
        db().sql("DELETE FROM embedding_projection WHERE workspace_id = :ws")
                .param("ws", active.id()).update();
        db().sql("DELETE FROM embedding_projection_readiness WHERE workspace_id = :ws")
                .param("ws", active.id()).update();

        List<DegradationScenario> scenarios = new ArrayList<>();
        for (String queryId : List.of("paraphrase", "property-token")) {
            GoldenQuery query = queries.stream()
                    .filter(q -> q.id().equals(queryId)).findFirst().orElseThrow();
            InputRun baselineRun = executeOne(retrievalService,
                    new RetrievalInput(query.text(), RetrievalInput.ORIGIN_ORIGINAL));
            Candidate rewrite = QueryTransformationCandidates.singleRewrite(SINGLE_REWRITE,
                    QueryTransformationCandidates.corpusProtectedProvider());
            TransformationPlan plan = rewrite.plan(query.id(), query.text());
            List<InputRun> rewriteRuns = new ArrayList<>();
            for (RetrievalInput retrievalInput : plan.inputs()) {
                rewriteRuns.add(executeOne(retrievalService, retrievalInput));
            }
            boolean vectorUnavailable = baselineRun.bundle().diagnostics().vectorUnavailable()
                    && rewriteRuns.stream()
                            .allMatch(run -> run.bundle().diagnostics().vectorUnavailable());
            Set<String> queryTokens = Set.copyOf(
                    org.km.llmwiki.search.CjkBigramProjector.tokens(query.text()));
            List<String> baselineOrder = baselineRun.bundle().items().stream()
                    .map(EvidenceItem::stableIdentity).toList();
            List<String> missClasses = new ArrayList<>();
            for (String identity : query.relevant()) {
                if (baselineOrder.contains(identity)) {
                    continue;
                }
                Set<String> targetTokens = Set.copyOf(
                        org.km.llmwiki.search.CjkBigramProjector.tokens(
                                evidenceText.getOrDefault(identity, "")));
                long overlap = targetTokens.stream().filter(queryTokens::contains).count();
                missClasses.add(identity + ": overlap=" + overlap + "/" + queryTokens.size()
                        + (vectorUnavailable ? " BACKEND_UNAVAILABLE_CONTRIBUTION(vector)" : ""));
            }
            double baselinePoolRecall = recallOver(poolUnion(List.of(baselineRun)),
                    query.relevant());
            double rewritePoolRecall = recallOver(poolUnion(rewriteRuns), query.relevant());
            if (!vectorUnavailable) {
                violations.add("degradation scenario " + queryId
                        + ": expected vector-unavailable diagnostics");
            }
            scenarios.add(new DegradationScenario(queryId, vectorUnavailable,
                    List.copyOf(missClasses), baselinePoolRecall, rewritePoolRecall,
                    List.of("measured under embedding-projection removal; any rewrite delta is "
                            + "state-conditional and must not be credited to rewriting")));
        }
        return scenarios;
    }

    // ------------------------------------------------------------------ unlock / decision

    private record Decision(String verdict, List<String> reasons) {
    }

    private Decision decide(Map<String, List<VariantQueryRun>> runs, List<String> violations,
                            List<String> poolGainQueries, List<String> windowGainQueries,
                            Map<String, Map<String, MissClassification>> missTaxonomy,
                            boolean multiQueryUnlocked) {
        List<String> reasons = new ArrayList<>();
        if (!violations.isEmpty()) {
            reasons.add("correctness gates failed (see violations)");
            return new Decision("NO-GO / DEFER", reasons);
        }
        List<String> wordingMisses = new ArrayList<>();
        List<String> nonWordingMisses = new ArrayList<>();
        for (Map.Entry<String, Map<String, MissClassification>> entry : missTaxonomy.entrySet()) {
            for (MissClassification classification : entry.getValue().values()) {
                boolean wording = classification.classes().stream().anyMatch(clazz ->
                        clazz.startsWith("CANDIDATE_GENERATION_"));
                (wording ? wordingMisses : nonWordingMisses).add(entry.getKey() + "/"
                        + classification.identity() + " [" + String.join(", ",
                                classification.classes()) + "]");
            }
        }
        if (!poolGainQueries.isEmpty()) {
            if (wordingMisses.size() >= nonWordingMisses.size()) {
                reasons.add("reproducible candidate-generation recall gain on "
                        + poolGainQueries.size() + " query(ies) " + poolGainQueries
                        + "; wording-mismatch misses dominate the baseline miss taxonomy; "
                        + "adoption issue must define versioned policy, typed applicability, "
                        + "exact-token protection, hard fan-out budget, provider egress "
                        + "disclosure and a live-provider controlled measurement before any "
                        + "default switch");
                return new Decision("GO TO ADOPTION ISSUE", reasons);
            }
            reasons.add("candidate-generation recall gain only on " + poolGainQueries
                    + " (narrow query shapes; wording-mismatch misses are not dominant: "
                    + wordingMisses.size() + " wording vs " + nonWordingMisses.size()
                    + " non-wording); adoption, if any, must be typed applicability/no-op and "
                    + "validated with live-provider measurement");
            return new Decision("CONDITIONAL GO", reasons);
        }
        if (!windowGainQueries.isEmpty()) {
            reasons.add("no pool-level candidate-generation gain (the deterministic fixture "
                    + "embeddings tie every same-concept page, so the pre-qualification pool "
                    + "already contains each non-graph-only target), but reproducible "
                    + "fused-window recall recovery on " + windowGainQueries.size()
                    + " query(ies) " + windowGainQueries + " — crowd-out misses whose root cause "
                    + "is lexical wording mismatch (question filler breaking the FTS AND match); "
                    + "any adoption must be typed applicability/no-op with exact-token "
                    + "protection, a hard fan-out budget, #323/#310 egress disclosure, a "
                    + "live-provider controlled measurement, and an explicit justification of "
                    + "the egress cost against provider-free fusion-side levers");
            return new Decision("CONDITIONAL GO", reasons);
        }
        reasons.add("no reproducible recall gain: baseline misses are "
                + (wordingMisses.isEmpty() && nonWordingMisses.isEmpty()
                        ? "absent on this corpus"
                        : "dominated by non-wording classes (" + nonWordingMisses.size()
                                + " non-wording vs " + wordingMisses.size()
                                + " wording) — fusion/budget crowd-out, graph-only reachability "
                                + "and authority rejection, which query rewriting cannot fix"));
        reasons.add("keep the ORIGINAL_QUERY baseline; corpus, harness and measurement remain "
                + "as future trigger evidence");
        return new Decision("NO-GO / DEFER", reasons);
    }

    // ------------------------------------------------------------------ overlap audit

    /**
     * Published audit trail against candidate-bias: for every rewrite fixture, the projected
     * token overlap with each labeled target's text, computed from the corpus only, so a
     * reviewer can see exactly how much of each rewrite is target-shaped.
     */
    private List<String> lexicalOverlapAudit(List<GoldenQuery> queries,
                                             Map<String, String> evidenceText) {
        List<String> audit = new ArrayList<>();
        audit.add("rewrite fixtures version: "
                + QueryTransformationEvaluationCorpusV1.REWRITE_FIXTURES_VERSION);
        for (GoldenQuery query : queries) {
            QueryTransformationEvaluationCorpusV1.RewriteFixture fixture =
                    QueryTransformationEvaluationCorpusV1.fixtureFor(query.id());
            if (fixture == null || fixture.protectedRewrite() == null) {
                continue;
            }
            Set<String> rewriteTokens = Set.copyOf(
                    org.km.llmwiki.search.CjkBigramProjector.tokens(fixture.protectedRewrite()));
            List<String> overlaps = new ArrayList<>();
            for (String identity : query.relevant()) {
                Set<String> targetTokens = Set.copyOf(
                        org.km.llmwiki.search.CjkBigramProjector.tokens(
                                evidenceText.getOrDefault(identity, "")));
                long shared = rewriteTokens.stream().filter(targetTokens::contains).count();
                overlaps.add(identity + "=" + shared + "/" + rewriteTokens.size());
            }
            audit.add(query.id() + " protected-rewrite overlap with targets: "
                    + String.join(", ", overlaps));
        }
        return List.copyOf(audit);
    }

    // ------------------------------------------------------------------ reports

    private void writeReports(Map<String, List<VariantQueryRun>> runs, Decision decision,
                              List<String> violations, GraphProjectionStatusResponse projection,
                              Map<String, Map<String, MissClassification>> missTaxonomy,
                              List<FallbackScenario> fallbackScenarios,
                              List<DegradationScenario> degradation, List<String> poolGainQueries,
                              List<String> windowGainQueries, boolean multiQueryUnlocked,
                              List<String> overlapAudit)
            throws IOException {
        Path reports = Path.of("target", "quality-reports");
        Files.createDirectories(reports);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("corpus", QueryTransformationEvaluationCorpusV1.VERSION);
        json.put("rewriteFixtures", QueryTransformationEvaluationCorpusV1.REWRITE_FIXTURES_VERSION);
        json.put("branch", git("rev-parse", "--abbrev-ref", "HEAD"));
        json.put("headSha", git("rev-parse", "HEAD"));
        json.put("originMainSha", git("rev-parse", "origin/main"));
        json.put("decision", decision.verdict());
        json.put("decisionReasons", decision.reasons());
        json.put("k", K);
        json.put("fusionPolicyVersion", FusionRankingPolicy.production().version());
        json.put("graphProjectionVersion", projection.projectionVersion());
        json.put("lexicalQueryProjection", org.km.llmwiki.search.CjkBigramProjector.VERSION
                + " (deterministic lexical projection, not semantic rewriting)");
        json.put("multiQueryUnlocked", multiQueryUnlocked);
        json.put("poolGainQueries", poolGainQueries);
        json.put("windowGainQueries", windowGainQueries);
        json.put("runs", runs.entrySet().stream().map(entry -> {
            Map<String, Object> runJson = new LinkedHashMap<>();
            runJson.put("candidate", entry.getKey());
            runJson.put("providerCallAttemptsPerAsk",
                    entry.getValue().get(0).providerCallAttempts());
            runJson.put("queries", entry.getValue().stream()
                    .map(QueryTransformationEvaluationIntegrationTest::runToJson).toList());
            return runJson;
        }).toList());
        json.put("missTaxonomy", missTaxonomy.entrySet().stream().map(entry -> {
            Map<String, Object> queryJson = new LinkedHashMap<>();
            queryJson.put("queryId", entry.getKey());
            queryJson.put("misses", entry.getValue().values().stream()
                    .map(classification -> Map.of("identity", classification.identity(),
                            "classes", classification.classes(), "detail",
                            classification.detail())).toList());
            return queryJson;
        }).toList());
        json.put("fallbackScenarios", fallbackScenarios);
        json.put("degradationScenarios", degradation);
        json.put("lexicalOverlapAudit", overlapAudit);
        json.put("violations", violations);
        json.put("deterministic", true);
        json.put("offline", true);
        json.put("costModel", Map.of(
                "ORIGINAL_QUERY", "0 extra provider calls per ask; fan-out 1",
                "SINGLE_REWRITE", "+1 provider call per ask (rewrite); fan-out <= 2",
                "MULTI_QUERY_BOUNDED", "+2 provider calls per ask; fan-out <= 3 (hard budget)",
                "note", "fixture-mode latencies are not provider latencies; token usage and "
                        + "live latency require the documented manual controlled measurement"));
        Files.writeString(reports.resolve("query-transformation-evaluation-v1.json"),
                new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(json) + "\n");
        Files.writeString(reports.resolve("query-transformation-evaluation-v1.md"),
                markdown(runs, decision, violations, projection, missTaxonomy, fallbackScenarios,
                        degradation, poolGainQueries, windowGainQueries, multiQueryUnlocked,
                        overlapAudit));
    }

    private static Map<String, Object> runToJson(VariantQueryRun run) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("queryId", run.queryId());
        json.put("queryClass", run.queryClass());
        json.put("inputs", run.inputs());
        json.put("inputOrigins", run.inputOrigins());
        json.put("events", run.events());
        json.put("pool", run.pool());
        json.put("mergedOrder", run.mergedOrder());
        json.put("poolRecall", run.poolRecall());
        json.put("recallAtK", run.recallAtK());
        json.put("mrr", run.mrr());
        json.put("precisionAtK", run.precisionAtK());
        json.put("noiseCount", run.noiseCount());
        json.put("insufficientEvidence", run.insufficientEvidence());
        json.put("fanOut", run.fanOut());
        json.put("providerCallAttempts", run.providerCallAttempts());
        json.put("additionalRelevantInPool", run.additionalRelevantInPool());
        json.put("additionalRelevantInBundle", run.additionalRelevantInBundle());
        json.put("additionalNoiseInBundle", run.additionalNoiseInBundle());
        json.put("inputObservations", run.inputObservations().stream()
                .map(observation -> Map.of("origin", observation.origin(),
                        "queryText", observation.queryText(),
                        "channelCandidates", observation.channelCandidates(),
                        "bundleOrder", observation.bundleOrder()))
                .toList());
        return json;
    }

    private String markdown(Map<String, List<VariantQueryRun>> runs, Decision decision,
                            List<String> violations, GraphProjectionStatusResponse projection,
                            Map<String, Map<String, MissClassification>> missTaxonomy,
                            List<FallbackScenario> fallbackScenarios,
                            List<DegradationScenario> degradation, List<String> poolGainQueries,
                            List<String> windowGainQueries, boolean multiQueryUnlocked,
                            List<String> overlapAudit) {
        StringBuilder report = new StringBuilder();
        report.append("# Query transformation recall evaluation report (v1)\n\n");
        report.append("- corpus: `").append(QueryTransformationEvaluationCorpusV1.VERSION)
                .append("`, rewrite fixtures: `")
                .append(QueryTransformationEvaluationCorpusV1.REWRITE_FIXTURES_VERSION)
                .append("`\n");
        report.append("- branch: `").append(git("rev-parse", "--abbrev-ref", "HEAD"))
                .append("`, HEAD: `").append(git("rev-parse", "HEAD")).append("`, origin/main: `")
                .append(git("rev-parse", "origin/main")).append("`\n");
        report.append("- baseline: ORIGINAL_QUERY on the production-equivalent retrieval stack (")
                .append("fusion ").append(FusionRankingPolicy.production().version())
                .append(", graph ").append(projection.projectionVersion())
                .append(", lexical projection ")
                .append(org.km.llmwiki.search.CjkBigramProjector.VERSION).append(", k=")
                .append(K).append(")\n");
        report.append("- terminology: SQLite relational persistence = operational/control plane; ")
                .append("SQLite FTS5 = rebuildable lexical projection; sqlite-vec = vector ")
                .append("projection; ArcadeDB = derived graph projection; vault/ + canonical ")
                .append("records = authority. #129 cjk-bigram-v1 is a deterministic lexical ")
                .append("query projection, not semantic rewriting.\n");
        report.append("- decision: **").append(decision.verdict()).append("**\n\n");
        report.append("## Decision reasons\n\n");
        decision.reasons().forEach(reason -> report.append("- ").append(reason).append('\n'));
        report.append("\n## Aggregate\n\n");
        report.append("| candidate | mean pool recall | mean recall@").append(K)
                .append(" | mean mrr | mean precision@").append(K)
                .append(" | provider calls/ask | total fan-out |\n");
        report.append("| --- | --- | --- | --- | --- | --- | --- |\n");
        for (Map.Entry<String, List<VariantQueryRun>> entry : runs.entrySet()) {
            List<VariantQueryRun> queryRuns = entry.getValue();
            report.append("| ").append(entry.getKey()).append(" | ")
                    .append(fmt(queryRuns.stream().mapToDouble(VariantQueryRun::poolRecall)
                            .average().orElse(0))).append(" | ")
                    .append(fmt(queryRuns.stream().mapToDouble(VariantQueryRun::recallAtK)
                            .average().orElse(0))).append(" | ")
                    .append(fmt(queryRuns.stream().mapToDouble(VariantQueryRun::mrr)
                            .average().orElse(0))).append(" | ")
                    .append(fmt(queryRuns.stream().mapToDouble(VariantQueryRun::precisionAtK)
                            .average().orElse(0))).append(" | ")
                    .append(queryRuns.get(0).providerCallAttempts()).append(" | ")
                    .append(queryRuns.stream().mapToInt(VariantQueryRun::fanOut).sum())
                    .append(" |\n");
        }
        report.append("\n## Per-query detail\n\n");
        report.append("| candidate | query | class | pool recall | recall@").append(K)
                .append(" | mrr | noise | fan-out | events |\n");
        report.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (Map.Entry<String, List<VariantQueryRun>> entry : runs.entrySet()) {
            for (VariantQueryRun run : entry.getValue()) {
                report.append("| ").append(entry.getKey()).append(" | ").append(run.queryId())
                        .append(" | ").append(run.queryClass()).append(" | ")
                        .append(fmt(run.poolRecall())).append(" | ").append(fmt(run.recallAtK()))
                        .append(" | ").append(fmt(run.mrr())).append(" | ")
                        .append(run.noiseCount()).append(" | ").append(run.fanOut())
                        .append(" | ").append(String.join("; ", run.events())).append(" |\n");
            }
        }
        report.append("\n## Per-input channel observations (SINGLE_REWRITE)\n\n");
        report.append("Channel-level attribution of what each retrieval input surfaced; the ")
                .append("full per-channel lists for every candidate are in the JSON report.\n\n");
        report.append("| query | input | lexical | vector count | graph | bundle order |\n");
        report.append("| --- | --- | --- | --- | --- | --- |\n");
        for (VariantQueryRun run : runs.get(SINGLE_REWRITE)) {
            for (VariantQueryRun.InputObservation observation : run.inputObservations()) {
                List<String> lexical = observation.channelCandidates().getOrDefault("LEXICAL",
                        List.of());
                List<String> graph = observation.channelCandidates().getOrDefault("GRAPH",
                        List.of());
                report.append("| ").append(run.queryId()).append(" | ")
                        .append(observation.origin()).append(" | ")
                        .append(lexical.isEmpty() ? "(empty)" : String.join(", ", lexical))
                        .append(" | ")
                        .append(observation.channelCandidates()
                                .getOrDefault("VECTOR", List.of()).size())
                        .append(" | ")
                        .append(graph.isEmpty() ? "(empty)" : String.join(", ", graph))
                        .append(" | ")
                        .append(observation.bundleOrder().stream()
                                .map(identity -> identity.replaceFirst("^WIKI:", ""))
                                .reduce((a, b) -> a + ", " + b).orElse("(empty)"))
                        .append(" |\n");
            }
        }
        report.append("\n## Baseline miss taxonomy (ORIGINAL_QUERY)\n\n");
        boolean anyMiss = missTaxonomy.values().stream().anyMatch(misses -> !misses.isEmpty());
        if (!anyMiss) {
            report.append("(no relevant-identity misses on the baseline)\n");
        }
        for (Map.Entry<String, Map<String, MissClassification>> entry : missTaxonomy.entrySet()) {
            for (MissClassification classification : entry.getValue().values()) {
                report.append("- ").append(entry.getKey()).append(" → ")
                        .append(classification.identity()).append(": ")
                        .append(String.join(", ", classification.classes()))
                        .append(" (").append(classification.detail()).append(")\n");
            }
        }
        report.append("\n## Rewrite / target lexical-overlap audit (candidate-bias check)\n\n");
        overlapAudit.forEach(line -> report.append("- ").append(line).append('\n'));
        report.append("\n## Fallback / failure semantics\n\n");
        for (FallbackScenario scenario : fallbackScenarios) {
            report.append("- ").append(scenario.scenario()).append(" (").append(scenario.queryId())
                    .append("): baseline retained=").append(scenario.baselineRetained())
                    .append(", insufficientEvidence=").append(scenario.insufficientEvidence())
                    .append(", events=").append(scenario.events()).append('\n');
        }
        report.append("\n## Backend-degradation scenario (embedding projection removed)\n\n");
        for (DegradationScenario scenario : degradation) {
            report.append("- query: ").append(scenario.queryId())
                    .append(", vectorUnavailable=").append(scenario.vectorUnavailable())
                    .append(", baseline pool recall=").append(fmt(scenario.baselinePoolRecall()))
                    .append(", rewrite pool recall=").append(fmt(scenario.rewritePoolRecall()))
                    .append('\n');
            scenario.missClasses().forEach(clazz ->
                    report.append("  - ").append(clazz).append('\n'));
            scenario.notes().forEach(note ->
                    report.append("  - note: ").append(note).append('\n'));
        }
        report.append("\n## Unlock ladder\n\n");
        report.append("- pool-gain queries (SINGLE_REWRITE vs baseline): ")
                .append(poolGainQueries.isEmpty() ? "(none)" : poolGainQueries).append('\n');
        report.append("- fused-window gain queries (SINGLE_REWRITE vs baseline): ")
                .append(windowGainQueries.isEmpty() ? "(none)" : windowGainQueries).append('\n');
        report.append("- MULTI_QUERY_BOUNDED: ").append(multiQueryUnlocked
                ? "unlocked and measured"
                : "locked (no reproducible candidate-generation gain; the bounded fan-out "
                        + "mechanism exists in the harness but is not evaluated)").append('\n');
        report.append("- HyDE: locked (a fixture pseudo-document would be corpus-crafted by "
                + "construction; requires live-provider controlled measurement)\n");
        report.append("\n## Correctness violations\n\n").append(violations.isEmpty() ? "none\n"
                : violations.stream().map(v -> "- " + v + "\n").reduce("", String::concat));
        report.append("\n## Operational cost / egress boundary\n\n");
        report.append("- Ask read-only mutation semantics ≠ no provider egress: a production ")
                .append("rewrite call is additional provider egress and must reuse the #323 ")
                .append("egress disclosure and #310 usage semantics; this evaluation adds no ")
                .append("production UI or endpoint.\n");
        report.append("- deterministic/offline: no provider, no network, no model artifact; ")
                .append("fixture-mode latencies are not provider latencies.\n");
        report.append("\n## Limitations\n\n");
        report.append("- Fixture rewrites are deterministic upper-bound estimates of what a ")
                .append("live semantic rewriter would emit; live-provider controlled ")
                .append("measurement is a manual, separate activity and must precede any ")
                .append("adoption default switch.\n");
        report.append("- The corpus measures HYBRID_GRAPH candidate generation and fused-window ")
                .append("quality; second-stage rerank is reorder-only downstream and ")
                .append("variant-neutral, so it is intentionally not reapplied here (the #316 ")
                .append("gate would re-run on top of any adoption).\n");
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
