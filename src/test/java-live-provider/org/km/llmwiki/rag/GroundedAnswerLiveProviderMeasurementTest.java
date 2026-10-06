package org.km.llmwiki.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.ai.answer.AnswerClient;
import org.km.llmwiki.ai.answer.AnswerClientException;
import org.km.llmwiki.ai.answer.AnswerContext;
import org.km.llmwiki.ai.answer.AnswerContextAssembler;
import org.km.llmwiki.ai.answer.AnswerContextBudget;
import org.km.llmwiki.ai.answer.AnswerContextCompactionPolicyRegistry;
import org.km.llmwiki.ai.answer.AnswerGenerationOptions;
import org.km.llmwiki.ai.answer.AnswerRequest;
import org.km.llmwiki.ai.answer.AnswerResult;
import org.km.llmwiki.ai.answer.ContextPolicyV1Current;
import org.km.llmwiki.ai.answer.EvidenceContextProjector;
import org.km.llmwiki.ai.answer.EvidenceContextProjectorService;
import org.km.llmwiki.ai.answer.GroundedAnswerPromptContract;
import org.km.llmwiki.ai.answer.provider.openai.OpenAiCompatibleAnswerClient;
import org.km.llmwiki.ai.answer.provider.openai.OpenAiCompatibleAnswerProperties;
import org.km.llmwiki.ai.provider.ProviderEndpointSecurityPolicy;
import org.km.llmwiki.graph.GraphCanonicalCurrentness;
import org.km.llmwiki.graph.GraphProjectionInputAssembler;
import org.km.llmwiki.graph.GraphProjectionLifecycleRepository;
import org.km.llmwiki.graph.GraphWorkspaceScope;
import org.km.llmwiki.processing.ProcessingJobRepository;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in live-provider measurement for Issue #682.
 *
 * <p>This source root is compiled only by the Maven live-provider profile. It uses the
 * repository-owned synthetic #546 corpus and the production OpenAI-compatible AnswerClient.
 * Reports never persist raw prompts, raw answer text, endpoint URLs, credentials or private paths.
 */
@Tag("live-provider")
class GroundedAnswerLiveProviderMeasurementTest extends IsolatedIntegrationTest {

    static final String PROCEDURE_VERSION = "grounded-answer-live-provider-v1";
    private static final Path REPORT_DIR = Path.of("target", "quality-reports");
    private static final Path JSON_REPORT =
            REPORT_DIR.resolve("grounded-answer-live-provider-v1.json");
    private static final Path MARKDOWN_REPORT =
            REPORT_DIR.resolve("grounded-answer-live-provider-v1.md");

    private static final RagQueryShapeCoverageCorpusV1 CORPUS =
            new RagQueryShapeCoverageCorpusV1();

    @TempDir
    Path temp;

    @Autowired AnswerClient answerClient;
    @Autowired OpenAiCompatibleAnswerProperties answerProperties;
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
    void measureLiveProviderCitationCompletenessOnSyntheticRequiredSets() throws Exception {
        assertLiveConfiguration();
        int repetitions = repetitions();

        GraphRetrievalQualityFixture fixture = new GraphRetrievalQualityFixture(
                db(), workspaces, searchService, publishedWikiRepository,
                publishedWikiContentReader, sourceAuthorityRepository, ftsRepository,
                embeddingRepository, embeddingReadiness, jobs, assembler,
                lifecycleRepository, currentness, paths);
        GraphRetrievalQualityFixture.CorpusMaterialization materialization =
                fixture.materializeCorpus(temp, "grounded-answer-live-provider", CORPUS.pages());
        GraphWorkspaceScope active = materialization.active();
        fixture.openWorkspace(active);

        List<Map<String, Object>> rows = new ArrayList<>();
        String decision = "NO_CHANGE";

        try (GraphRetrievalQualityFixture.LifecycleBundle lifecycle =
                     fixture.lifecycle(temp.resolve("grounded-answer-live-provider-graph"))) {
            FusedRetrievalOrchestrator orchestrator = fixture.orchestrator(
                    lifecycle.lifecycle(), lifecycle.factory(), FusionRankingPolicy.production());
            RetrievalService retrieval = fixture.retrievalService(orchestrator);
            EvidenceContextProjector projector = projector();

            for (RagQueryShapeCoverageCorpusV1.GoldenCase golden : CORPUS.cases()) {
                EvidenceBundle bundle = retrieval.retrieve(
                        RetrievalRequest.defaults(golden.text(), RetrievalMode.HYBRID_FTS));
                List<String> retrieved = bundle.items().stream()
                        .map(EvidenceItem::stableIdentity)
                        .toList();
                RagQueryShapeCoverageCorpusV1.CoverageAssessment retrievalCoverage =
                        CORPUS.assess(golden, retrieved);

                assertThat(bundle.insufficientEvidence())
                        .as(golden.id() + " retrieval must not be insufficient")
                        .isFalse();
                assertThat(retrievalCoverage.complete())
                        .as(golden.id() + " retrieval must be complete before provider measurement")
                        .isTrue();

                AnswerContext context = projector
                        .project(bundle, AnswerContextBudget.DEFAULT)
                        .context();
                List<String> contextIdentities = context.blocks().stream()
                        .map(block -> block.authorityIdentity())
                        .toList();
                assertThat(contextIdentities)
                        .as(golden.id() + " provider context must contain the whole required set")
                        .containsAll(golden.requiredEvidence());

                for (int attempt = 1; attempt <= repetitions; attempt++) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("caseId", golden.id());
                    row.put("queryClass", golden.queryClass());
                    row.put("attempt", attempt);
                    row.put("requiredCount", golden.requiredEvidence().size());
                    row.put("retrievalComplete", retrievalCoverage.complete());
                    row.put("contextEvidenceCount", context.blocks().size());

                    long started = System.nanoTime();
                    try {
                        AnswerResult result = answerClient.generate(new AnswerRequest(
                                golden.text(), context, AnswerGenerationOptions.defaults()));
                        long latencyMillis = (System.nanoTime() - started) / 1_000_000L;

                        GroundedAnswerCoverageEvaluator.Assessment assessment =
                                GroundedAnswerCoverageEvaluator.assess(
                                        golden, retrievalCoverage, context, result);
                        row.put("status", "MEASURED");
                        row.put("verdict", assessment.verdict().name());
                        row.put("citedRequiredCount", assessment.citedRequiredCount());
                        row.put("requiredRecall", assessment.requiredRecall());
                        row.put("missingRequired", assessment.missingRequired());
                        row.put("providerInsufficientEvidence",
                                assessment.providerInsufficientEvidence());
                        row.put("latencyMillis", latencyMillis);
                        row.put("provider", result.providerMetadata().provider());
                        row.put("model", result.providerMetadata().model());
                        row.put("usageStatus", result.usage().isPresent()
                                ? "AVAILABLE" : "UNAVAILABLE");
                        row.put("inputTokens", result.usage()
                                .map(usage -> usage.inputTokens()).orElse(null));
                        row.put("outputTokens", result.usage()
                                .map(usage -> usage.outputTokens()).orElse(null));
                        row.put("totalTokens", result.usage()
                                .map(usage -> usage.totalTokens()).orElse(null));

                        if (assessment.verdict() != GroundedAnswerCoverageVerdict.COMPLETE) {
                            decision = "ANSWER_FOLLOW_UP";
                        }
                    } catch (AnswerClientException failure) {
                        long latencyMillis = (System.nanoTime() - started) / 1_000_000L;
                        row.put("status", "PROVIDER_FAILURE");
                        row.put("failureType", failure.failureType().name());
                        row.put("latencyMillis", latencyMillis);
                        decision = "UNRESOLVED";
                    }
                    rows.add(row);
                }
            }
        } finally {
            writeReports(rows, repetitions, decision);
        }

        assertThat(rows).isNotEmpty();
        assertThat(rows)
                .as("provider transport/config must be healthy before quality interpretation")
                .allMatch(row -> "MEASURED".equals(row.get("status")));
        assertThat(rows)
                .as("retrieval-complete synthetic cases should cite the complete required set")
                .allMatch(row -> "COMPLETE".equals(row.get("verdict")));
        assertThat(rows)
                .as("provider must not falsely abstain when required evidence is present")
                .allMatch(row -> Boolean.FALSE.equals(row.get("providerInsufficientEvidence")));
    }

    private void assertLiveConfiguration() {
        assertThat(answerProperties.isEnabled())
                .as("APP_AI_ANSWER_ENABLED must be true for live measurement")
                .isTrue();
        assertThat(answerProperties.getModel())
                .as("APP_AI_ANSWER_MODEL must be configured")
                .isNotBlank();
        assertThat(answerProperties.getApiKey())
                .as("APP_AI_ANSWER_API_KEY must be configured")
                .isNotBlank();
        assertThat(answerClient)
                .as("live-provider profile must use the production provider adapter")
                .isInstanceOf(OpenAiCompatibleAnswerClient.class);
    }

    private int repetitions() {
        String raw = System.getenv().getOrDefault(
                "LLM_WIKI_LIVE_PROVIDER_REPETITIONS", "2");
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(
                    "LLM_WIKI_LIVE_PROVIDER_REPETITIONS must be an integer");
        }
        if (value < 1 || value > 5) {
            throw new IllegalArgumentException(
                    "LLM_WIKI_LIVE_PROVIDER_REPETITIONS must be between 1 and 5");
        }
        return value;
    }

    private void writeReports(List<Map<String, Object>> rows, int repetitions, String decision)
            throws Exception {
        Files.createDirectories(REPORT_DIR);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("procedureVersion", PROCEDURE_VERSION);
        report.put("measuredAt", Instant.now().toString());
        report.put("corpusVersion", RagQueryShapeCoverageCorpusV1.VERSION);
        report.put("corpusFingerprint", CORPUS.fingerprint());
        report.put("promptVersion", GroundedAnswerPromptContract.IDENTIFIER);
        report.put("provider", OpenAiCompatibleAnswerClient.PROVIDER);
        report.put("model", answerProperties.getModel());
        report.put("endpointClass", ProviderEndpointSecurityPolicy.classify(
                answerProperties.getBaseUrl(), answerProperties.isAllowInsecureTransport()).name());
        report.put("repetitionsPerCase", repetitions);
        report.put("rawPromptPersisted", false);
        report.put("rawAnswerPersisted", false);
        report.put("endpointPersisted", false);
        report.put("credentialPersisted", false);
        report.put("decision", decision);
        report.put("measurements", rows);

        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(JSON_REPORT.toFile(), report);

        StringBuilder markdown = new StringBuilder();
        markdown.append("# Grounded Answer live-provider measurement\n\n")
                .append("- procedure: `").append(PROCEDURE_VERSION).append("`\n")
                .append("- corpus: `").append(RagQueryShapeCoverageCorpusV1.VERSION).append("`\n")
                .append("- prompt: `").append(GroundedAnswerPromptContract.IDENTIFIER).append("`\n")
                .append("- provider: `").append(OpenAiCompatibleAnswerClient.PROVIDER).append("`\n")
                .append("- model: `").append(answerProperties.getModel()).append("`\n")
                .append("- endpoint class: `")
                .append(ProviderEndpointSecurityPolicy.classify(
                        answerProperties.getBaseUrl(),
                        answerProperties.isAllowInsecureTransport()).name())
                .append("`\n")
                .append("- repetitions per case: ").append(repetitions).append("\n")
                .append("- decision: **").append(decision).append("**\n\n")
                .append("| Case | Attempt | Status | Verdict | Required recall | Abstained | Latency ms | Usage |\n")
                .append("| --- | ---: | --- | --- | ---: | --- | ---: | --- |\n");

        for (Map<String, Object> row : rows) {
            markdown.append("| ").append(row.get("caseId"))
                    .append(" | ").append(row.get("attempt"))
                    .append(" | ").append(row.get("status"))
                    .append(" | ").append(row.getOrDefault("verdict",
                            row.getOrDefault("failureType", "N/A")))
                    .append(" | ").append(row.getOrDefault("requiredRecall", "N/A"))
                    .append(" | ").append(row.getOrDefault("providerInsufficientEvidence", "N/A"))
                    .append(" | ").append(row.getOrDefault("latencyMillis", "N/A"))
                    .append(" | ").append(row.getOrDefault("usageStatus", "N/A"))
                    .append(" |\n");
        }

        markdown.append("\nRaw prompt / answer / endpoint / credential are intentionally not persisted.\n");
        Files.writeString(MARKDOWN_REPORT, markdown.toString(), StandardCharsets.UTF_8);
    }

    private static EvidenceContextProjector projector() {
        return new EvidenceContextProjectorService(new AnswerContextAssembler(),
                new AnswerContextCompactionPolicyRegistry(List.of(new ContextPolicyV1Current()),
                        ContextPolicyV1Current.VERSION));
    }
}
