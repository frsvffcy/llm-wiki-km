package org.km.llmwiki.rag;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.graph.GraphCanonicalCurrentness;
import org.km.llmwiki.graph.GraphEntity;
import org.km.llmwiki.graph.GraphEntityIdentity;
import org.km.llmwiki.graph.GraphEntityType;
import org.km.llmwiki.graph.GraphProjectionInput;
import org.km.llmwiki.graph.GraphProjectionInputAssembler;
import org.km.llmwiki.graph.GraphProjectionLifecycleRepository;
import org.km.llmwiki.graph.GraphProjectionStatusResponse;
import org.km.llmwiki.graph.GraphRelation;
import org.km.llmwiki.graph.GraphRelationType;
import org.km.llmwiki.graph.GraphWorkspaceScope;
import org.km.llmwiki.processing.ProcessingJobRepository;
import org.km.llmwiki.rag.GraphRetrievalGoldenCorpus.GoldenQuery;
import org.km.llmwiki.rag.GraphRetrievalQualityBenchmark.Evaluation;
import org.km.llmwiki.rag.GraphRetrievalQualityBenchmark.QueryMetrics;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #689 relation-loss sensitivity evaluation. It reuses the v2 production-equivalent graph
 * fixture and removes exactly one admitted relation from the projection input at a time. The
 * relation loss must be attributable to the graph-only evidence that depends on that path while
 * lexical/vector results and unrelated graph scenarios stay unchanged.
 */
@Tag("integration")
class GraphRelationLossSensitivityEvaluationTest extends IsolatedIntegrationTest {

    private static final GraphRetrievalEvaluationCorpusV2 CORPUS =
            new GraphRetrievalEvaluationCorpusV2();
    private static final String VERSION = "graph-relation-loss-sensitivity-v1";
    private static final int K = 8;

    @TempDir Path temp;
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
    void targetedRelationLossDoesNotDamageBaselineChannels() throws Exception {
        GraphRetrievalQualityFixture fixture = new GraphRetrievalQualityFixture(db(), workspaces,
                searchService, publishedWikiRepository, publishedWikiContentReader,
                sourceAuthorityRepository, ftsRepository, embeddingRepository, embeddingReadiness,
                jobs, assembler, lifecycleRepository, currentness, paths);
        GraphWorkspaceScope active = fixture.workspace(temp, "relation-loss-eval");
        GraphWorkspaceScope foreign = fixture.workspace(temp, "relation-loss-eval-foreign");
        fixture.openWorkspace(active);
        fixture.writeForeignGoldenPages(foreign);

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
        fixture.mutateStaleHash(active, CORPUS.pages(restoreDoc).stream()
                .filter(page -> page.knowledgeId().equals(GraphRetrievalEvaluationCorpusV2.STALE_PAGE))
                .findFirst().orElseThrow());

        List<GoldenQuery> queries = CORPUS.queries(restoreChunkIdentity);
        List<String> forbidden = new ArrayList<>(CORPUS.safetyIdentities(legacyChunkIdentity));
        forbidden.add(new GraphRetrievalGoldenCorpus().foreignIdentity());

        try (GraphRetrievalQualityFixture.LifecycleBundle lifecycleFactory =
                     fixture.lifecycle(temp.resolve("relation-loss-graph"))) {
            GraphProjectionInput baselineInput = assembler.assemble(active);
            lifecycleFactory.lifecycle().rebuild(baselineInput);
            Evaluation baseline = evaluate(fixture, lifecycleFactory, queries, forbidden,
                    readyProjection(lifecycleFactory, active));
            assertThat(baseline.trust().findings()).isEmpty();
            assertThat(baseline.safetyViolations()).isEmpty();

            List<LossScenario> scenarios = List.of(
                    new LossScenario("link-1hop-direct", "link-1hop", GraphRelationType.LINKS_TO,
                            "分散式鎖診斷", "容錯切換手冊", null, null),
                    new LossScenario("link-2hop-tail", "wiki-2hop", GraphRelationType.LINKS_TO,
                            "容量盤點手札", "鎖競爭診斷", null, null),
                    new LossScenario("multi-target-one-edge", "multi-target",
                            GraphRelationType.LINKS_TO, "快取搜尋策略", "災難復原手冊",
                            null, null),
                    new LossScenario("source-chunk-contains", "source-chunk-2hop",
                            GraphRelationType.CONTAINS, null, null,
                            GraphEntityType.SOURCE_DOCUMENT, GraphEntityType.SOURCE_CHUNK));

            List<String> reportRows = new ArrayList<>();
            for (LossScenario scenario : scenarios) {
                RelationRemoval removal = removeExactlyOne(baselineInput, scenario);
                lifecycleFactory.lifecycle().rebuild(removal.input());
                Evaluation fault = evaluate(fixture, lifecycleFactory, queries, forbidden,
                        readyProjection(lifecycleFactory, active));

                assertThat(fault.trust().findings()).isEmpty();
                assertThat(fault.safetyViolations()).isEmpty();
                assertBaselineModesEqual(baseline, fault);
                assertUnrelatedGraphQueriesEqual(baseline, fault, scenario.queryId());

                QueryMetrics baselineGraph = metric(baseline, scenario.queryId(), "HYBRID_GRAPH");
                QueryMetrics faultGraph = metric(fault, scenario.queryId(), "HYBRID_GRAPH");
                assertThat(baselineGraph.graphOnlyFound()).contains(removal.lostEvidenceIdentity());
                assertThat(faultGraph.graphOnlyFound())
                        .doesNotContain(removal.lostEvidenceIdentity());

                Set<String> expectedRemaining = new LinkedHashSet<>(baselineGraph.graphOnlyFound());
                expectedRemaining.remove(removal.lostEvidenceIdentity());
                assertThat(new LinkedHashSet<>(faultGraph.graphOnlyFound()))
                        .isEqualTo(expectedRemaining);

                GoldenQuery targetQuery = queries.stream()
                        .filter(query -> query.id().equals(scenario.queryId()))
                        .findFirst().orElseThrow();
                FusedRetrievalOrchestrator orchestrator = fixture.orchestrator(
                        lifecycleFactory.lifecycle(), lifecycleFactory.factory(),
                        FusionRankingPolicy.production());
                EvidenceBundle observed = orchestrator.retrieveFused(
                        RetrievalRequest.defaults(targetQuery.text(), RetrievalMode.HYBRID_GRAPH));
                assertThat(observed.diagnostics().graphUnavailable()).isFalse();
                assertThat(observed.diagnostics().graphDegraded()).isFalse();
                assertThat(observed.diagnostics().graphSignalUsed()).isTrue();

                reportRows.add("| " + scenario.id() + " | " + scenario.queryId() + " | "
                        + removal.lostEvidenceIdentity() + " | "
                        + format(baselineGraph.recallAtK()) + " | "
                        + format(faultGraph.recallAtK()) + " | "
                        + format(baselineGraph.mrr()) + " | "
                        + format(faultGraph.mrr()) + " | true | true | "
                        + "GRAPH_DEGRADED_BY_RELATION_LOSS |");
            }

            lifecycleFactory.lifecycle().rebuild(baselineInput);
            Evaluation restored = evaluate(fixture, lifecycleFactory, queries, forbidden,
                    readyProjection(lifecycleFactory, active));
            assertAllModesEqual(baseline, restored);
            writeReport(baseline, reportRows);
        }
    }

    private GraphProjectionStatusResponse readyProjection(
            GraphRetrievalQualityFixture.LifecycleBundle lifecycleFactory,
            GraphWorkspaceScope active) {
        GraphProjectionStatusResponse projection = GraphProjectionStatusResponse.from(
                lifecycleFactory.lifecycle().readiness(active));
        assertThat(projection.status()).isEqualTo("READY");
        return projection;
    }

    private Evaluation evaluate(GraphRetrievalQualityFixture fixture,
                                GraphRetrievalQualityFixture.LifecycleBundle lifecycleFactory,
                                List<GoldenQuery> queries, List<String> forbidden,
                                GraphProjectionStatusResponse projection) {
        FusedRetrievalOrchestrator orchestrator = fixture.orchestrator(
                lifecycleFactory.lifecycle(), lifecycleFactory.factory(),
                FusionRankingPolicy.production());
        RetrievalService retrieval = fixture.retrievalService(orchestrator);
        Map<String, Function<String, EvidenceBundle>> runners = Map.of(
                "HYBRID_FTS", text -> retrieval.retrieve(
                        RetrievalRequest.defaults(text, RetrievalMode.HYBRID_FTS)),
                "HYBRID_VECTOR", text -> retrieval.retrieve(
                        RetrievalRequest.defaults(text, RetrievalMode.HYBRID_VECTOR)),
                "HYBRID_GRAPH", text -> orchestrator.retrieveFused(
                        RetrievalRequest.defaults(text, RetrievalMode.HYBRID_GRAPH)));
        return GraphRetrievalQualityBenchmark.evaluate(VERSION,
                FusionRankingPolicy.production().version(), queries, runners, K,
                projection.projectionVersion(), projection.appliedGeneration(),
                forbidden, null, Set.of());
    }

    private RelationRemoval removeExactlyOne(GraphProjectionInput baseline, LossScenario scenario) {
        Map<GraphEntityIdentity, GraphEntity> entities = baseline.entities().stream()
                .collect(Collectors.toMap(GraphEntity::identity, entity -> entity));
        List<GraphRelation> matches = baseline.relations().stream()
                .filter(relation -> matches(relation, entities, scenario)).toList();
        assertThat(matches).as("scenario " + scenario.id()).hasSize(1);

        GraphRelation removed = matches.get(0);
        String lostIdentity = evidenceIdentity(entities.get(removed.target()));
        assertThat(lostIdentity).isNotBlank();

        List<GraphRelation> retained = baseline.relations().stream()
                .filter(relation -> !relation.identity().equals(removed.identity())).toList();
        return new RelationRemoval(new GraphProjectionInput(baseline.workspace(),
                baseline.projectionVersion(), baseline.entities(), retained),
                removed.identity().stableId(), lostIdentity);
    }

    private boolean matches(GraphRelation relation, Map<GraphEntityIdentity, GraphEntity> entities,
                            LossScenario scenario) {
        if (relation.type() != scenario.type()) {
            return false;
        }
        GraphEntity source = entities.get(relation.source());
        GraphEntity target = entities.get(relation.target());
        if (source == null || target == null) {
            return false;
        }
        if (scenario.sourceType() != null) {
            return source.identity().type() == scenario.sourceType()
                    && target.identity().type() == scenario.targetType();
        }
        return source.displayName().equals(scenario.sourceDisplayName())
                && target.displayName().equals(scenario.targetDisplayName());
    }

    private String evidenceIdentity(GraphEntity entity) {
        return switch (entity.identity().type()) {
            case WIKI_PAGE -> "WIKI:" + entity.provenance().authority().stableId();
            case SOURCE_CHUNK -> "SOURCE_CHUNK:" + entity.provenance().authority().stableId();
            default -> "";
        };
    }

    private void assertBaselineModesEqual(Evaluation baseline, Evaluation fault) {
        for (String mode : List.of("HYBRID_FTS", "HYBRID_VECTOR")) {
            for (QueryMetrics expected : baseline.metrics().stream()
                    .filter(metric -> metric.mode().equals(mode)).toList()) {
                assertMetricEqual(expected, metric(fault, expected.queryId(), mode));
            }
        }
    }

    private void assertUnrelatedGraphQueriesEqual(Evaluation baseline, Evaluation fault,
                                                  String targetQueryId) {
        for (QueryMetrics expected : baseline.metrics().stream()
                .filter(metric -> metric.mode().equals("HYBRID_GRAPH"))
                .filter(metric -> !metric.queryId().equals(targetQueryId)).toList()) {
            assertMetricEqual(expected,
                    metric(fault, expected.queryId(), "HYBRID_GRAPH"));
        }
    }

    private void assertAllModesEqual(Evaluation baseline, Evaluation restored) {
        assertThat(restored.trust().findings()).isEmpty();
        assertThat(restored.safetyViolations()).isEmpty();
        for (QueryMetrics expected : baseline.metrics()) {
            assertMetricEqual(expected,
                    metric(restored, expected.queryId(), expected.mode()));
        }
    }

    private void assertMetricEqual(QueryMetrics expected, QueryMetrics actual) {
        assertThat(actual.retrieved()).isEqualTo(expected.retrieved());
        assertThat(actual.recallAtK()).isEqualTo(expected.recallAtK());
        assertThat(actual.mrr()).isEqualTo(expected.mrr());
        assertThat(actual.graphOnlyFound()).isEqualTo(expected.graphOnlyFound());
    }

    private QueryMetrics metric(Evaluation evaluation, String queryId, String mode) {
        return evaluation.metrics().stream()
                .filter(value -> value.queryId().equals(queryId) && value.mode().equals(mode))
                .findFirst().orElseThrow();
    }

    private void writeReport(Evaluation baseline, List<String> rows) throws Exception {
        Path reports = Path.of("target", "quality-reports");
        Files.createDirectories(reports);
        StringBuilder report = new StringBuilder();
        report.append("# Graph relation-loss sensitivity report\\n\\n")
                .append("- evaluation: ").append(VERSION).append("\\n")
                .append("- corpus: ").append(GraphRetrievalEvaluationCorpusV2.VERSION).append("\\n")
                .append("- ranking policy: ").append(FusionRankingPolicy.production().version())
                .append("\\n")
                .append("- baseline graph recall: ")
                .append(format(baseline.aggregates().get("HYBRID_GRAPH").recallAtK())).append("\\n")
                .append("- baseline graph MRR: ")
                .append(format(baseline.aggregates().get("HYBRID_GRAPH").mrr())).append("\\n")
                .append("- decision: KEEP_GRAPH_OPTIONAL_AND_MEASURE_PROJECTION_COMPLETENESS\\n\\n")
                .append("| scenario | query | lost evidence | baseline recall | fault recall | ")
                .append("baseline mrr | fault mrr | FTS retained | vector retained | outcome |\\n")
                .append("| --- | --- | --- | ---: | ---: | ---: | ---: | --- | --- | --- |\\n");
        rows.forEach(row -> report.append(row).append("\\n"));
        report.append("\\nInterpretation: targeted admitted-relation loss removes only the ")
                .append("graph-only evidence whose path depends on that relation. FTS/vector ")
                .append("results and unrelated graph scenarios remain unchanged. This proves ")
                .append("scenario-specific Graph value and projection-completeness sensitivity; ")
                .append("it does not establish real-world relation-loss frequency and does not ")
                .append("justify a production ranking or GraphRAG expansion by itself.\\n");
        Files.writeString(reports.resolve("graph-relation-loss-sensitivity-v1.md"),
                report.toString());
    }

    private String format(double value) {
        return String.format("%.4f", value);
    }

    private record LossScenario(String id, String queryId, GraphRelationType type,
                                String sourceDisplayName, String targetDisplayName,
                                GraphEntityType sourceType, GraphEntityType targetType) {
    }

    private record RelationRemoval(GraphProjectionInput input, String relationStableId,
                                   String lostEvidenceIdentity) {
    }
}
