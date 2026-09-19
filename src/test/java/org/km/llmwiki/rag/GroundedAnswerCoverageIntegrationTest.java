package org.km.llmwiki.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.ai.answer.AnswerContext;
import org.km.llmwiki.ai.answer.AnswerContextAssembler;
import org.km.llmwiki.ai.answer.AnswerContextBudget;
import org.km.llmwiki.ai.answer.AnswerContextCompactionPolicyRegistry;
import org.km.llmwiki.ai.answer.AnswerProviderMetadata;
import org.km.llmwiki.ai.answer.AnswerResult;
import org.km.llmwiki.ai.answer.ContextPolicyV1Current;
import org.km.llmwiki.ai.answer.EvidenceContextProjector;
import org.km.llmwiki.ai.answer.EvidenceContextProjectorService;
import org.km.llmwiki.ai.answer.GroundedAnswerPromptContract;
import org.km.llmwiki.ai.answer.GroundedAnswerResponse;
import org.km.llmwiki.ai.answer.GroundedAnswerResponseContract;
import org.km.llmwiki.ai.answer.StubAnswerClient;
import org.km.llmwiki.ai.ask.AskRequest;
import org.km.llmwiki.ai.ask.AskResult;
import org.km.llmwiki.ai.ask.AskService;
import org.km.llmwiki.ai.ask.AskStatus;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Production-equivalent proof that runtime-valid {@code ANSWERED} does not imply
 * benchmark-complete for the #546 conflict/completeness shapes (#551 Scope C).
 *
 * <p>The test deliberately does not modify production prompt, response contract, or retrieval.
 * It retrieves the #546 corpus through the real workspace/FTS path, projects the real
 * {@link EvidenceBundle} into an application-owned {@link AnswerContext}, then drives
 * {@link AskService} with a deterministic stub provider that cites only one of two required
 * evidence items.
 */
@Tag("integration")
class GroundedAnswerCoverageIntegrationTest extends IsolatedIntegrationTest {

    private static final RagQueryShapeCoverageCorpusV1 CORPUS =
            new RagQueryShapeCoverageCorpusV1();
    private static final AnswerProviderMetadata METADATA =
            new AnswerProviderMetadata("stub", "offline-model");

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
    void fullContextSingleCitationIsAnsweredButBenchmarkPartial() throws Exception {
        GraphRetrievalQualityFixture fixture = new GraphRetrievalQualityFixture(
                db(), workspaces, searchService, publishedWikiRepository,
                publishedWikiContentReader, sourceAuthorityRepository, ftsRepository,
                embeddingRepository, embeddingReadiness, jobs, assembler,
                lifecycleRepository, currentness, paths);
        GraphRetrievalQualityFixture.CorpusMaterialization materialization =
                fixture.materializeCorpus(temp, "grounded-answer-coverage", CORPUS.pages());
        GraphWorkspaceScope active = materialization.active();
        fixture.openWorkspace(active);

        List<EvidenceBundle> bundles = new ArrayList<>();
        List<GroundedAnswerCoverageEvaluator.Assessment> partialAssessments = new ArrayList<>();
        List<GroundedAnswerCoverageEvaluator.Assessment> completeAssessments = new ArrayList<>();

        try (GraphRetrievalQualityFixture.LifecycleBundle lifecycle =
                     fixture.lifecycle(temp.resolve("grounded-answer-coverage-graph"))) {
            FusedRetrievalOrchestrator orchestrator = fixture.orchestrator(
                    lifecycle.lifecycle(), lifecycle.factory(), FusionRankingPolicy.production());
            RetrievalService retrieval = fixture.retrievalService(orchestrator);
            EvidenceContextProjector projector = projector();

            for (RagQueryShapeCoverageCorpusV1.GoldenCase golden : CORPUS.cases()) {
                EvidenceBundle bundle = retrieval.retrieve(
                        RetrievalRequest.defaults(golden.text(), RetrievalMode.HYBRID_FTS));
                bundles.add(bundle);

                List<String> retrieved = bundle.items().stream()
                        .map(EvidenceItem::stableIdentity)
                        .toList();
                RagQueryShapeCoverageCorpusV1.CoverageAssessment retrievalCoverage =
                        CORPUS.assess(golden, retrieved);

                assertThat(bundle.insufficientEvidence()).isFalse();
                assertThat(retrieved).containsAll(golden.requiredEvidence());
                assertThat(retrievalCoverage.complete())
                        .as(golden.id() + " retrieval must stay complete")
                        .isTrue();

                AnswerContext context = projector
                        .project(bundle, AnswerContextBudget.DEFAULT)
                        .context();
                List<String> contextIdentities = context.blocks().stream()
                        .map(block -> block.authorityIdentity())
                        .toList();
                assertThat(contextIdentities).containsAll(golden.requiredEvidence());

                String singleRequiredCitation = context.blocks().stream()
                        .filter(block -> golden.requiredEvidence().contains(block.authorityIdentity()))
                        .map(block -> block.citationId())
                        .sorted()
                        .findFirst()
                        .orElseThrow();
                String missingRequired = golden.requiredEvidence().stream()
                        .filter(identity -> !context.blocks().stream()
                                .filter(block -> block.citationId().equals(singleRequiredCitation))
                                .map(block -> block.authorityIdentity())
                                .toList()
                                .contains(identity))
                        .sorted()
                        .findFirst()
                        .orElseThrow();

                GroundedAnswerResponseContract contract =
                        new GroundedAnswerResponseContract(new ObjectMapper());
                GroundedAnswerResponse parsed = contract.parse(
                        """
                        {"answerText":"deterministic answer","citedEvidenceIds":["%s"],
                        "insufficientEvidence":false}
                        """.formatted(singleRequiredCitation),
                        context);
                assertThat(parsed.citedEvidenceIds()).containsExactly(singleRequiredCitation);
                assertThat(parsed.insufficientEvidence()).isFalse();

                AskResult singleCitationResult = askWithStub(
                        bundle, new AnswerResult(
                                "deterministic answer", List.of(singleRequiredCitation), false,
                                METADATA, Optional.empty()));
                assertThat(singleCitationResult.status()).isEqualTo(AskStatus.ANSWERED);
                assertThat(singleCitationResult.citations()).hasSize(1);

                GroundedAnswerCoverageEvaluator.Assessment partial =
                        GroundedAnswerCoverageEvaluator.assess(
                                golden, retrievalCoverage, context, singleCitationResult);
                assertThat(partial.verdict())
                        .as(golden.id() + " single citation must be PARTIAL")
                        .isEqualTo(GroundedAnswerCoverageVerdict.PARTIAL);
                assertThat(partial.requiredCount()).isEqualTo(2);
                assertThat(partial.citedRequiredCount()).isEqualTo(1);
                assertThat(partial.requiredRecall()).isEqualTo(0.5d);
                assertThat(partial.missingRequired()).containsExactly(missingRequired);
                assertThat(partial.retrievalComplete()).isTrue();
                assertThat(partial.conflictTextQuality())
                        .isEqualTo(EvaluationTrustContract.EvidenceStrength.UNOBSERVED);
                partialAssessments.add(partial);

                List<String> bothCitations = context.blocks().stream()
                        .filter(block -> golden.requiredEvidence().contains(block.authorityIdentity()))
                        .map(block -> block.citationId())
                        .sorted()
                        .toList();
                assertThat(bothCitations).hasSize(2);

                AskResult bothCitationsResult = askWithStub(
                        bundle, new AnswerResult(
                                "deterministic complete answer", bothCitations, false,
                                METADATA, Optional.empty()));
                assertThat(bothCitationsResult.status()).isEqualTo(AskStatus.ANSWERED);

                GroundedAnswerCoverageEvaluator.Assessment complete =
                        GroundedAnswerCoverageEvaluator.assess(
                                golden, retrievalCoverage, context, bothCitationsResult);
                assertThat(complete.verdict())
                        .as(golden.id() + " full citation must be COMPLETE")
                        .isEqualTo(GroundedAnswerCoverageVerdict.COMPLETE);
                assertThat(complete.citedRequiredCount()).isEqualTo(2);
                assertThat(complete.missingRequired()).isEmpty();
                assertThat(complete.retrievalComplete()).isTrue();
                completeAssessments.add(complete);
            }
        }

        assertThat(partialAssessments)
                .allMatch(assessment -> assessment.verdict()
                        == GroundedAnswerCoverageVerdict.PARTIAL);
        assertThat(completeAssessments)
                .allMatch(assessment -> assessment.verdict()
                        == GroundedAnswerCoverageVerdict.COMPLETE);

        String fingerprint = CORPUS.fingerprint();
        boolean lexicalTouched = bundles.stream()
                .allMatch(bundle -> bundle.diagnostics().lexicalSignalUsed());
        EvaluationTrustContract.Assessment trust = EvaluationTrustContract.assess(
                new EvaluationTrustContract.EnvironmentStamp(
                        RagQueryShapeCoverageCorpusV1.VERSION,
                        GroundedAnswerPromptContract.IDENTIFIER,
                        List.of("LEXICAL"),
                        "N/A",
                        null,
                        "stub",
                        "offline-model",
                        "N/A",
                        true,
                        "integration",
                        fingerprint),
                fingerprint,
                List.of(new EvaluationTrustContract.ChannelObservation(
                        "LEXICAL",
                        true,
                        lexicalTouched,
                        lexicalTouched,
                        "both #551 golden cases execute the current lexical retrieval path "
                                + "with grounded-answer@v2 baseline stamps")));

        assertThat(trust.findings()).isEmpty();
        assertThat(trust.evidenceStrength())
                .isEqualTo(EvaluationTrustContract.EvidenceStrength.MEASURED);
        assertThat(trust.environment().requiresAaFloor()).isFalse();
    }

    private static AskResult askWithStub(EvidenceBundle bundle, AnswerResult stubbed) {
        RetrievalService retrieval = mock(RetrievalService.class);
        when(retrieval.retrieve(any())).thenReturn(bundle);
        return new AskService(retrieval, projector(), noopRerank(),
                StubAnswerClient.returning(stubbed))
                .ask(AskRequest.defaults(bundle.query(), RetrievalMode.HYBRID_FTS));
    }

    private static EvidenceContextProjector projector() {
        return new EvidenceContextProjectorService(new AnswerContextAssembler(),
                new AnswerContextCompactionPolicyRegistry(List.of(new ContextPolicyV1Current()),
                        ContextPolicyV1Current.VERSION));
    }

    private static SecondStageRerankService noopRerank() {
        return new SecondStageRerankService(
                new SecondStageRerankPolicyRegistry(
                        List.of(new SecondStageRerankPolicy.NoOp()),
                        SecondStageRerankPolicy.NoOp.VERSION));
    }
}
