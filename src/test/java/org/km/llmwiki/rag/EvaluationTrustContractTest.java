package org.km.llmwiki.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationTrustContractTest {

    @Test
    void refusesStarvedRequiredSubstrate() {
        String fingerprint = EvaluationTrustContract.fingerprint(List.of("corpus-v1", "graph"));
        EvaluationTrustContract.Assessment assessment = EvaluationTrustContract.assess(
                stamp(fingerprint, true),
                fingerprint,
                List.of(new EvaluationTrustContract.ChannelObservation(
                        "GRAPH", true, false, false, "projection has no eligible relations")));

        assertThat(assessment.evidenceStrength())
                .isEqualTo(EvaluationTrustContract.EvidenceStrength.UNOBSERVED);
        assertThat(assessment.findings())
                .anyMatch(finding -> finding.startsWith("EVAL_REFUSED GRAPH"));
    }

    @Test
    void marksLiveButUntouchedChannelUnobserved() {
        String fingerprint = EvaluationTrustContract.fingerprint(List.of("corpus-v1", "rewrite"));
        EvaluationTrustContract.Assessment assessment = EvaluationTrustContract.assess(
                stamp(fingerprint, true),
                fingerprint,
                List.of(new EvaluationTrustContract.ChannelObservation(
                        "QUERY_TRANSFORMATION", true, true, false,
                        "all plans degraded to ORIGINAL without a rewrite input")));

        assertThat(assessment.evidenceStrength())
                .isEqualTo(EvaluationTrustContract.EvidenceStrength.UNOBSERVED);
        assertThat(assessment.findings())
                .anyMatch(finding -> finding.startsWith("UNOBSERVED QUERY_TRANSFORMATION"));
    }

    @Test
    void refusesStaleOrMismatchedEnvironmentFingerprint() {
        String recorded = EvaluationTrustContract.fingerprint(List.of("fixture", "v1"));
        String expected = EvaluationTrustContract.fingerprint(List.of("fixture", "v2"));

        EvaluationTrustContract.Assessment assessment = EvaluationTrustContract.assess(
                stamp(recorded, true),
                expected,
                List.of(new EvaluationTrustContract.ChannelObservation(
                        "LEXICAL", true, true, true, "deterministic fixture")));

        assertThat(assessment.findings())
                .contains("EVAL_REFUSED stale/mismatched environment stamp: fixture fingerprint mismatch");
    }

    @Test
    void aaFloorIsRequiredOnlyForStochasticEnvironments() {
        String fingerprint = EvaluationTrustContract.fingerprint(List.of("fixture", "v1"));

        assertThat(stamp(fingerprint, true).requiresAaFloor()).isFalse();
        assertThat(stamp(fingerprint, false).requiresAaFloor()).isTrue();
    }

    private EvaluationTrustContract.EnvironmentStamp stamp(String fingerprint,
                                                           boolean deterministic) {
        return new EvaluationTrustContract.EnvironmentStamp(
                "corpus-v1",
                "policy-v1",
                List.of("LEXICAL"),
                "N/A",
                null,
                "N/A",
                "N/A",
                "N/A",
                deterministic,
                "release-quality",
                fingerprint);
    }
}
