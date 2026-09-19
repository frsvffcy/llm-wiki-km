package org.km.llmwiki.rag;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.ai.answer.AnswerContext;
import org.km.llmwiki.ai.answer.AnswerContextBlock;
import org.km.llmwiki.ai.answer.AnswerContextProvenance;
import org.km.llmwiki.ai.answer.GroundedAnswerResponse;
import org.km.llmwiki.rag.EvidenceKind;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("contract")
class GroundedAnswerCoverageContractTest {

    private final RagQueryShapeCoverageCorpusV1 corpus = new RagQueryShapeCoverageCorpusV1();

    @Test
    void conflictingInfoCitesBothIsComplete() {
        RagQueryShapeCoverageCorpusV1.GoldenCase golden =
                corpus.caseById(RagQueryShapeCoverageCorpusV1.CONFLICTING_INFO);
        AnswerContext context = contextFor(golden);
        RagQueryShapeCoverageCorpusV1.CoverageAssessment retrieval =
                corpus.assess(golden, List.of(
                        "WIKI:" + RagQueryShapeCoverageCorpusV1.RELEASE_ORIGINAL,
                        "WIKI:" + RagQueryShapeCoverageCorpusV1.RELEASE_DELAYED));

        GroundedAnswerCoverageEvaluator.Assessment assessment =
                GroundedAnswerCoverageEvaluator.assess(
                        golden, retrieval, context, List.of("E1", "E2"), false);

        assertThat(assessment.verdict())
                .isEqualTo(GroundedAnswerCoverageVerdict.COMPLETE);
        assertThat(assessment.requiredCount()).isEqualTo(2);
        assertThat(assessment.citedRequiredCount()).isEqualTo(2);
        assertThat(assessment.requiredRecall()).isEqualTo(1.0d);
        assertThat(assessment.missingRequired()).isEmpty();
        assertThat(assessment.unknownCitations()).isEmpty();
        assertThat(assessment.providerInsufficientEvidence()).isFalse();
        assertThat(assessment.retrievalComplete()).isTrue();
        assertThat(assessment.retrievalMissing()).isEmpty();
        assertThat(assessment.citationEvidenceStrength())
                .isEqualTo(EvaluationTrustContract.EvidenceStrength.MEASURED);
    }

    @Test
    void conflictingInfoCitesOnlyOneIsPartialNotComplete() {
        RagQueryShapeCoverageCorpusV1.GoldenCase golden =
                corpus.caseById(RagQueryShapeCoverageCorpusV1.CONFLICTING_INFO);
        AnswerContext context = contextFor(golden);
        RagQueryShapeCoverageCorpusV1.CoverageAssessment retrieval =
                corpus.assess(golden, List.of(
                        "WIKI:" + RagQueryShapeCoverageCorpusV1.RELEASE_ORIGINAL,
                        "WIKI:" + RagQueryShapeCoverageCorpusV1.RELEASE_DELAYED));

        GroundedAnswerCoverageEvaluator.Assessment assessment =
                GroundedAnswerCoverageEvaluator.assess(
                        golden, retrieval, context, List.of("E1"), false);

        assertThat(assessment.verdict())
                .isEqualTo(GroundedAnswerCoverageVerdict.PARTIAL);
        assertThat(assessment.requiredCount()).isEqualTo(2);
        assertThat(assessment.citedRequiredCount()).isEqualTo(1);
        assertThat(assessment.requiredRecall()).isEqualTo(0.5d);
        assertThat(assessment.missingRequired())
                .containsExactly("WIKI:" + RagQueryShapeCoverageCorpusV1.RELEASE_DELAYED);
        assertThat(assessment.retrievalComplete()).isTrue();
    }

    @Test
    void completenessRequiredOneOfTwoIsPartial() {
        RagQueryShapeCoverageCorpusV1.GoldenCase golden =
                corpus.caseById(RagQueryShapeCoverageCorpusV1.COMPLETENESS_REQUIRED);
        AnswerContext context = contextFor(golden);
        RagQueryShapeCoverageCorpusV1.CoverageAssessment retrieval =
                corpus.assess(golden, List.of(
                        "WIKI:" + RagQueryShapeCoverageCorpusV1.DEPLOY_MIGRATION,
                        "WIKI:" + RagQueryShapeCoverageCorpusV1.DEPLOY_ROLLBACK));

        GroundedAnswerCoverageEvaluator.Assessment assessment =
                GroundedAnswerCoverageEvaluator.assess(
                        golden, retrieval, context, List.of("E2"), false);

        assertThat(assessment.verdict())
                .isEqualTo(GroundedAnswerCoverageVerdict.PARTIAL);
        assertThat(assessment.citedRequiredCount()).isEqualTo(1);
        assertThat(assessment.missingRequired())
                .containsExactly("WIKI:" + RagQueryShapeCoverageCorpusV1.DEPLOY_ROLLBACK);
        assertThat(assessment.retrievalComplete()).isTrue();
    }

    @Test
    void retrievalCoverageAndAnswerCoverageAreReportedSeparately() {
        RagQueryShapeCoverageCorpusV1.GoldenCase golden =
                corpus.caseById(RagQueryShapeCoverageCorpusV1.CONFLICTING_INFO);
        AnswerContext context = contextFor(golden);
        RagQueryShapeCoverageCorpusV1.CoverageAssessment retrievalComplete =
                corpus.assess(golden, List.of(
                        "WIKI:" + RagQueryShapeCoverageCorpusV1.RELEASE_ORIGINAL,
                        "WIKI:" + RagQueryShapeCoverageCorpusV1.RELEASE_DELAYED));
        RagQueryShapeCoverageCorpusV1.CoverageAssessment retrievalPartial =
                corpus.assess(golden, List.of(
                        "WIKI:" + RagQueryShapeCoverageCorpusV1.RELEASE_ORIGINAL));

        GroundedAnswerCoverageEvaluator.Assessment completeRetrievalPartialAnswer =
                GroundedAnswerCoverageEvaluator.assess(
                        golden, retrievalComplete, context, List.of("E1"), false);
        GroundedAnswerCoverageEvaluator.Assessment partialRetrievalPartialAnswer =
                GroundedAnswerCoverageEvaluator.assess(
                        golden, retrievalPartial, context, List.of("E1"), false);

        assertThat(completeRetrievalPartialAnswer.retrievalComplete()).isTrue();
        assertThat(completeRetrievalPartialAnswer.verdict())
                .isEqualTo(GroundedAnswerCoverageVerdict.PARTIAL);

        assertThat(partialRetrievalPartialAnswer.retrievalComplete()).isFalse();
        assertThat(partialRetrievalPartialAnswer.retrievalMissing())
                .containsExactly("WIKI:" + RagQueryShapeCoverageCorpusV1.RELEASE_DELAYED);
        assertThat(partialRetrievalPartialAnswer.verdict())
                .isEqualTo(GroundedAnswerCoverageVerdict.PARTIAL);
    }

    @Test
    void insufficientEvidenceWithoutCitationsIsAbstained() {
        RagQueryShapeCoverageCorpusV1.GoldenCase golden =
                corpus.caseById(RagQueryShapeCoverageCorpusV1.COMPLETENESS_REQUIRED);
        AnswerContext context = contextFor(golden);
        RagQueryShapeCoverageCorpusV1.CoverageAssessment retrieval =
                corpus.assess(golden, List.of(
                        "WIKI:" + RagQueryShapeCoverageCorpusV1.DEPLOY_MIGRATION,
                        "WIKI:" + RagQueryShapeCoverageCorpusV1.DEPLOY_ROLLBACK));

        GroundedAnswerCoverageEvaluator.Assessment assessment =
                GroundedAnswerCoverageEvaluator.assess(
                        golden, retrieval, context, List.of(), true);

        assertThat(assessment.verdict())
                .isEqualTo(GroundedAnswerCoverageVerdict.ABSTAINED);
        assertThat(assessment.providerInsufficientEvidence()).isTrue();
        assertThat(assessment.citedRequiredCount()).isZero();
        assertThat(assessment.retrievalComplete()).isTrue();
    }

    @Test
    void emptyCitationsWithoutAbstentionAndUnknownCitationsAreInvalid() {
        RagQueryShapeCoverageCorpusV1.GoldenCase golden =
                corpus.caseById(RagQueryShapeCoverageCorpusV1.CONFLICTING_INFO);
        AnswerContext context = contextFor(golden);
        RagQueryShapeCoverageCorpusV1.CoverageAssessment retrieval =
                corpus.assess(golden, List.of(
                        "WIKI:" + RagQueryShapeCoverageCorpusV1.RELEASE_ORIGINAL,
                        "WIKI:" + RagQueryShapeCoverageCorpusV1.RELEASE_DELAYED));

        assertThat(GroundedAnswerCoverageEvaluator.assess(
                        golden, retrieval, context, List.of(), false)
                        .verdict())
                .isEqualTo(GroundedAnswerCoverageVerdict.INVALID);

        GroundedAnswerCoverageEvaluator.Assessment unknown =
                GroundedAnswerCoverageEvaluator.assess(
                        golden, retrieval, context, List.of("E9"), false);
        assertThat(unknown.verdict())
                .isEqualTo(GroundedAnswerCoverageVerdict.INVALID);
        assertThat(unknown.unknownCitations()).containsExactly("E9");

        GroundedAnswerCoverageEvaluator.Assessment insufficientWithCitation =
                GroundedAnswerCoverageEvaluator.assess(
                        golden, retrieval, context, List.of("E1"), true);
        assertThat(insufficientWithCitation.verdict())
                .isEqualTo(GroundedAnswerCoverageVerdict.INVALID);
    }

    @Test
    void missingInputsAreUnobservedRatherThanScored() {
        RagQueryShapeCoverageCorpusV1.GoldenCase golden =
                corpus.caseById(RagQueryShapeCoverageCorpusV1.CONFLICTING_INFO);
        AnswerContext context = contextFor(golden);
        RagQueryShapeCoverageCorpusV1.CoverageAssessment retrieval =
                corpus.assess(golden, List.of(
                        "WIKI:" + RagQueryShapeCoverageCorpusV1.RELEASE_ORIGINAL,
                        "WIKI:" + RagQueryShapeCoverageCorpusV1.RELEASE_DELAYED));

        assertThat(GroundedAnswerCoverageEvaluator.assess(
                        golden, retrieval, null, List.of("E1"), false)
                        .verdict())
                .isEqualTo(GroundedAnswerCoverageVerdict.UNOBSERVED);
        assertThat(GroundedAnswerCoverageEvaluator.assess(
                        golden, retrieval, context, null, false)
                        .verdict())
                .isEqualTo(GroundedAnswerCoverageVerdict.UNOBSERVED);
        assertThat(GroundedAnswerCoverageEvaluator.assess(
                        golden, retrieval, context, (GroundedAnswerResponse) null)
                        .verdict())
                .isEqualTo(GroundedAnswerCoverageVerdict.UNOBSERVED);
    }

    @Test
    void conflictTextQualityIsAlwaysUnobservedWithoutKeywordMatching() {
        RagQueryShapeCoverageCorpusV1.GoldenCase golden =
                corpus.caseById(RagQueryShapeCoverageCorpusV1.CONFLICTING_INFO);
        AnswerContext context = contextFor(golden);
        RagQueryShapeCoverageCorpusV1.CoverageAssessment retrieval =
                corpus.assess(golden, List.of(
                        "WIKI:" + RagQueryShapeCoverageCorpusV1.RELEASE_ORIGINAL,
                        "WIKI:" + RagQueryShapeCoverageCorpusV1.RELEASE_DELAYED));

        GroundedAnswerCoverageEvaluator.Assessment complete =
                GroundedAnswerCoverageEvaluator.assess(
                        golden, retrieval, context, List.of("E1", "E2"), false);
        GroundedAnswerCoverageEvaluator.Assessment partial =
                GroundedAnswerCoverageEvaluator.assess(
                        golden, retrieval, context, List.of("E1"), false);

        assertThat(complete.conflictTextQuality())
                .isEqualTo(EvaluationTrustContract.EvidenceStrength.UNOBSERVED);
        assertThat(partial.conflictTextQuality())
                .isEqualTo(EvaluationTrustContract.EvidenceStrength.UNOBSERVED);
        assertThat(partial.conflictTextDetail()).contains("keyword");
    }

    @Test
    void singleCitationResponseIsRuntimeValidButBenchmarkPartial() {
        GroundedAnswerResponse response = new GroundedAnswerResponse(
                "Release answer", List.of("E1"), false);
        RagQueryShapeCoverageCorpusV1.GoldenCase golden =
                corpus.caseById(RagQueryShapeCoverageCorpusV1.CONFLICTING_INFO);
        AnswerContext context = contextFor(golden);
        RagQueryShapeCoverageCorpusV1.CoverageAssessment retrieval =
                corpus.assess(golden, List.of(
                        "WIKI:" + RagQueryShapeCoverageCorpusV1.RELEASE_ORIGINAL,
                        "WIKI:" + RagQueryShapeCoverageCorpusV1.RELEASE_DELAYED));

        GroundedAnswerCoverageEvaluator.Assessment assessment =
                GroundedAnswerCoverageEvaluator.assess(golden, retrieval, context, response);

        assertThat(response.insufficientEvidence()).isFalse();
        assertThat(response.citedEvidenceIds()).containsExactly("E1");
        assertThat(assessment.verdict())
                .isEqualTo(GroundedAnswerCoverageVerdict.PARTIAL);
        assertThat(assessment.citationEvidenceStrength())
                .isEqualTo(EvaluationTrustContract.EvidenceStrength.MEASURED);
    }

    @Test
    void evaluatorReusesQueryShapeCoverageTruthWithoutASecondAuthority() {
        assertThat(corpus.caseById(RagQueryShapeCoverageCorpusV1.CONFLICTING_INFO)
                        .requiredEvidence())
                .containsExactlyInAnyOrder(
                        "WIKI:" + RagQueryShapeCoverageCorpusV1.RELEASE_ORIGINAL,
                        "WIKI:" + RagQueryShapeCoverageCorpusV1.RELEASE_DELAYED);
        assertThat(corpus.caseById(RagQueryShapeCoverageCorpusV1.COMPLETENESS_REQUIRED)
                        .requiredEvidence())
                .containsExactlyInAnyOrder(
                        "WIKI:" + RagQueryShapeCoverageCorpusV1.DEPLOY_MIGRATION,
                        "WIKI:" + RagQueryShapeCoverageCorpusV1.DEPLOY_ROLLBACK);
        assertThat(corpus.fingerprint()).hasSize(64);
    }

    private static AnswerContext contextFor(RagQueryShapeCoverageCorpusV1.GoldenCase golden) {
        List<String> required = golden.requiredEvidence().stream().sorted().toList();
        java.util.List<AnswerContextBlock> blocks = new java.util.ArrayList<>();
        for (int index = 0; index < required.size(); index++) {
            String identity = required.get(index);
            blocks.add(new AnswerContextBlock(
                    "E" + (index + 1),
                    EvidenceKind.WIKI,
                    identity,
                    "content for " + identity,
                    false,
                    "hash-" + (index + 1),
                    new AnswerContextProvenance.Wiki("title-" + (index + 1), "vault/page.md", 1)));
        }
        return new AnswerContext(blocks);
    }
}
