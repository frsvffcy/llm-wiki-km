package org.km.llmwiki.rag;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("contract")
class RagQueryShapeCoverageContractTest {

    private final RagQueryShapeCoverageCorpusV1 corpus = new RagQueryShapeCoverageCorpusV1();

    @Test
    void conflictingInfoIsNonEmptyButIncompleteUntilBothCurrentEvidenceItemsArePresent() {
        RagQueryShapeCoverageCorpusV1.GoldenCase golden =
                corpus.caseById(RagQueryShapeCoverageCorpusV1.CONFLICTING_INFO);

        RagQueryShapeCoverageCorpusV1.CoverageAssessment partial =
                corpus.assess(golden, List.of("WIKI:" + RagQueryShapeCoverageCorpusV1.RELEASE_ORIGINAL));

        assertThat(partial.anyRequiredEvidence()).isTrue();
        assertThat(partial.complete()).isFalse();
        assertThat(partial.missingEvidence())
                .containsExactly("WIKI:" + RagQueryShapeCoverageCorpusV1.RELEASE_DELAYED);

        RagQueryShapeCoverageCorpusV1.CoverageAssessment complete =
                corpus.assess(golden, golden.requiredEvidence().stream().sorted().toList());

        assertThat(complete.complete()).isTrue();
        assertThat(complete.missingEvidence()).isEmpty();
    }

    @Test
    void completenessRequiredIsNonEmptyButIncompleteWhenOnlyOneRequiredIdentityIsPresent() {
        RagQueryShapeCoverageCorpusV1.GoldenCase golden =
                corpus.caseById(RagQueryShapeCoverageCorpusV1.COMPLETENESS_REQUIRED);

        RagQueryShapeCoverageCorpusV1.CoverageAssessment partial =
                corpus.assess(golden, List.of("WIKI:" + RagQueryShapeCoverageCorpusV1.DEPLOY_MIGRATION));

        assertThat(partial.anyRequiredEvidence()).isTrue();
        assertThat(partial.complete()).isFalse();
        assertThat(partial.missingEvidence())
                .containsExactly("WIKI:" + RagQueryShapeCoverageCorpusV1.DEPLOY_ROLLBACK);
    }

    @Test
    void infoNotFoundReusesTheExistingNoEvidenceTruthInsteadOfCreatingAParallelCase() {
        assertThat(corpus.infoNotFoundBaselineQueryId())
                .isEqualTo(QueryTransformationEvaluationCorpusV1.NO_EVIDENCE_QUERY_ID);

        assertThat(QueryTransformationEvaluationCorpusV1.queries(
                "SOURCE_CHUNK:fixture", "WIKI:fixture"))
                .anyMatch(query -> query.id().equals(corpus.infoNotFoundBaselineQueryId())
                        && query.relevant().isEmpty());
    }

    @Test
    void corpusFingerprintIsDeterministicAndContentOwned() {
        assertThat(corpus.fingerprint()).isEqualTo(corpus.fingerprint());
        assertThat(corpus.fingerprint()).hasSize(64);
    }
}
