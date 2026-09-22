package org.km.llmwiki.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("contract")
class McpTaskEvaluationContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> PLANES = Set.of(
            "TOOL_CONTRACT", "DISCOVERABILITY", "COMPOSITION");
    private static final Set<String> OUTCOMES = Set.of(
            "STATUS_REPORTED",
            "SEARCH_HIT",
            "SOURCE_LOCATED",
            "GRAPH_DEGRADED_EXPLAINED",
            "NO_EVIDENCE",
            "RETRIEVAL_UNAVAILABLE",
            "GROUNDED_ANSWER",
            "PROVIDER_CONFIGURATION_UNAVAILABLE",
            "ACTIVE_WORKSPACE_SCOPED");

    @Test
    void corpusIsBoundedRepositoryOwnedAndCoversEveryAdvertisedTool() throws Exception {
        Corpus corpus = corpus();

        assertThat(corpus.version()).isEqualTo("mcp-task-corpus-v1");
        assertThat(corpus.fixtureVersion()).isEqualTo("mcp-task-fixture-v1");
        assertThat(corpus.tasks()).hasSizeBetween(10, 15);
        assertThat(corpus.tasks().stream().map(RawTask::id).toList()).doesNotHaveDuplicates();

        long multiTool = corpus.tasks().stream().filter(RawTask::multiTool).count();
        assertThat(multiTool).isGreaterThanOrEqualTo((corpus.tasks().size() + 1L) / 2L);

        Set<String> requiredCoverage = new HashSet<>();
        for (RawTask raw : corpus.tasks()) {
            assertThat(raw.id()).isNotBlank();
            assertThat(raw.question()).isNotBlank();
            assertThat(raw.expectedObservable()).isIn(OUTCOMES);
            assertThat(raw.primaryPlane()).isIn(PLANES);
            assertThat(raw.multiTool())
                    .isEqualTo(raw.requiredToolSequence().size() > 1);
            assertThat(raw.requiredToolSequence()).isNotEmpty();
            assertThat(raw.acceptableTools())
                    .containsAll(raw.requiredToolSequence());
            assertThat(raw.acceptableTools())
                    .doesNotContainAnyElementsOf(raw.forbiddenTools());

            for (String tool : raw.requiredToolSequence()) {
                assertThat(McpCapabilityManifest.isKnown(tool))
                        .as("%s required tool %s must be in the current manifest", raw.id(), tool)
                        .isTrue();
                requiredCoverage.add(tool);
            }
            for (String tool : raw.acceptableTools()) {
                assertThat(McpCapabilityManifest.isKnown(tool))
                        .as("%s acceptable tool %s must be in the current manifest", raw.id(), tool)
                        .isTrue();
            }
            for (String tool : raw.forbiddenTools()) {
                assertThat(McpCapabilityManifest.isKnown(tool))
                        .as("%s forbidden tool %s must be a current capability", raw.id(), tool)
                        .isTrue();
            }
        }

        assertThat(requiredCoverage)
                .containsExactlyInAnyOrderElementsOf(McpCapabilityManifest.tools().keySet());
        assertThat(McpCapabilityManifest.tools().values())
                .allSatisfy(tool -> {
                    assertThat(tool.readOnly()).isTrue();
                    assertThat(tool.destructive()).isFalse();
                });

        assertThat(corpus.tasks()).anyMatch(task ->
                task.expectedObservable().equals("NO_EVIDENCE"));
        assertThat(corpus.tasks()).anyMatch(task ->
                task.expectedObservable().equals("PROVIDER_CONFIGURATION_UNAVAILABLE"));
        assertThat(corpus.tasks()).anyMatch(task ->
                task.expectedObservable().equals("GROUNDED_ANSWER"));
        assertThat(corpus.tasks()).anyMatch(task ->
                task.expectedObservable().equals("GRAPH_DEGRADED_EXPLAINED"));
    }

    @Test
    void canonicalTracesProducePerfectDeterministicMetrics() throws Exception {
        Corpus corpus = corpus();
        List<McpTaskEvaluationScorer.Task> tasks = corpus.tasks().stream()
                .map(this::task)
                .toList();
        List<McpTaskEvaluationScorer.Observation> observations = corpus.tasks().stream()
                .map(raw -> new McpTaskEvaluationScorer.Observation(
                        raw.id(),
                        raw.requiredToolSequence(),
                        raw.expectedObservable(),
                        false))
                .toList();

        McpTaskEvaluationScorer.Metrics metrics =
                McpTaskEvaluationScorer.summarize(tasks, observations);

        assertThat(metrics.taskCount()).isEqualTo(corpus.tasks().size());
        assertThat(metrics.taskPassRate()).isEqualTo(1.0d);
        assertThat(metrics.requiredToolSelectionRecall()).isEqualTo(1.0d);
        assertThat(metrics.unnecessaryToolCallRate()).isZero();
        assertThat(metrics.invalidToolCallRate()).isZero();
        assertThat(metrics.multiToolCompositionPassRate()).isEqualTo(1.0d);
        assertThat(metrics.typedOutcomeInterpretationPassRate()).isEqualTo(1.0d);
        assertThat(metrics.maxToolCalls()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void negativeCanariesFailForMissingCompositionHallucinatedToolAndTypedOutcomeConfusion()
            throws Exception {
        Corpus corpus = corpus();
        McpTaskEvaluationScorer.Task sourceComposition = task(find(corpus, "MCP-03"));
        McpTaskEvaluationScorer.Task noEvidence = task(find(corpus, "MCP-06"));

        var missingLocator = McpTaskEvaluationScorer.score(sourceComposition,
                new McpTaskEvaluationScorer.Observation(
                        "MCP-03", List.of("km_search"), "SOURCE_LOCATED", false));
        assertThat(missingLocator.requiredToolsSelected()).isFalse();
        assertThat(missingLocator.requiredSequenceObserved()).isFalse();
        assertThat(missingLocator.passed()).isFalse();

        var hallucinatedTool = McpTaskEvaluationScorer.score(sourceComposition,
                new McpTaskEvaluationScorer.Observation(
                        "MCP-03",
                        List.of("km_search", "km_publish", "km_source_locator"),
                        "SOURCE_LOCATED",
                        false));
        assertThat(hallucinatedTool.knownToolsOnly()).isFalse();
        assertThat(hallucinatedTool.invalidToolCallCount()).isEqualTo(1);
        assertThat(hallucinatedTool.passed()).isFalse();

        var wrongTypedMeaning = McpTaskEvaluationScorer.score(noEvidence,
                new McpTaskEvaluationScorer.Observation(
                        "MCP-06",
                        List.of("km_retrieval_inspect", "km_search"),
                        "RETRIEVAL_UNAVAILABLE",
                        false));
        assertThat(wrongTypedMeaning.expectedObservableMatched()).isFalse();
        assertThat(wrongTypedMeaning.passed()).isFalse();

        var inventedAnswer = McpTaskEvaluationScorer.score(noEvidence,
                new McpTaskEvaluationScorer.Observation(
                        "MCP-06",
                        List.of("km_retrieval_inspect", "km_search"),
                        "NO_EVIDENCE",
                        true));
        assertThat(inventedAnswer.noUnsupportedInvention()).isFalse();
        assertThat(inventedAnswer.passed()).isFalse();
    }

    private Corpus corpus() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/mcp-eval/task-corpus-v1.json")) {
            assertThat(input).isNotNull();
            return JSON.readValue(input, Corpus.class);
        }
    }

    private RawTask find(Corpus corpus, String id) {
        return corpus.tasks().stream()
                .filter(task -> task.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private McpTaskEvaluationScorer.Task task(RawTask raw) {
        return new McpTaskEvaluationScorer.Task(
                raw.id(),
                List.copyOf(raw.requiredToolSequence()),
                Set.copyOf(raw.acceptableTools()),
                Set.copyOf(raw.forbiddenTools()),
                raw.expectedObservable(),
                raw.multiTool());
    }

    private record Corpus(String version, String fixtureVersion, List<RawTask> tasks) {
    }

    private record RawTask(String id,
                           String primaryPlane,
                           String question,
                           String expectedObservable,
                           List<String> requiredToolSequence,
                           List<String> acceptableTools,
                           List<String> forbiddenTools,
                           boolean multiTool) {
    }
}
