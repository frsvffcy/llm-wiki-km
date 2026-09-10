package org.km.llmwiki.ai.answer;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.rag.EvidenceBundle;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the production default policy: {@code context-policy-v1-current} is the identity
 * projection of the bounded baseline (byte-equivalent to the pre-#309 Ask context packing)
 * and can never emit extractive or no-op projection kinds.
 */
@Tag("unit")
class ContextPolicyV1CurrentTest {

    @Test
    void versionIsTheStableCurrentBaselineVersion() {
        assertThat(new ContextPolicyV1Current().version())
                .isEqualTo("context-policy-v1-current");
    }

    @Test
    void projectionIsByteEquivalentToTheBoundedBaselineAcrossTheCompactionCorpus() {
        ContextPolicyV1Current policy = new ContextPolicyV1Current();
        for (AnswerContextCompactionCorpusV1.CompactionCase compactionCase
                : AnswerContextCompactionCorpusV1.cases()) {
            EvidenceBundle bundle =
                    AnswerContextCompactionCorpusV1.bundle(compactionCase.evidences());
            AnswerContext baseline =
                    new AnswerContextAssembler().assemble(bundle, AnswerContextBudget.DEFAULT);

            AnswerContextCompactionPolicy.PolicyProjection projection =
                    policy.project(bundle, baseline, AnswerContextBudget.DEFAULT);

            assertThat(projection.blocks())
                    .as("case %s: v1 projection must equal the baseline", compactionCase.caseId())
                    .containsExactlyElementsOf(baseline.blocks());
        }
    }

    @Test
    void projectionKindsAreOnlyVerbatimOrTruncated() {
        ContextPolicyV1Current policy = new ContextPolicyV1Current();
        List<AnswerContextCompactionCorpusV1.CompactionCase> cases =
                AnswerContextCompactionCorpusV1.cases();
        List<ProjectionKind> observed = new ArrayList<>();
        for (AnswerContextCompactionCorpusV1.CompactionCase compactionCase : cases) {
            EvidenceBundle bundle =
                    AnswerContextCompactionCorpusV1.bundle(compactionCase.evidences());
            AnswerContext baseline =
                    new AnswerContextAssembler().assemble(bundle, AnswerContextBudget.DEFAULT);
            observed.addAll(policy.project(bundle, baseline,
                    AnswerContextBudget.DEFAULT).kinds());
        }

        assertThat(observed).isNotEmpty();
        assertThat(observed).contains(ProjectionKind.VERBATIM, ProjectionKind.TRUNCATED);
        assertThat(observed).doesNotContain(ProjectionKind.EXTRACTIVE, ProjectionKind.NO_OP);
    }

    @Test
    void insufficientEvidenceKeepsTheEmptyBaselineSemantics() {
        ContextPolicyV1Current policy = new ContextPolicyV1Current();
        EvidenceBundle bundle =
                AnswerContextCompactionCorpusV1.bundle(List.of());
        AnswerContext baseline =
                new AnswerContextAssembler().assemble(bundle, AnswerContextBudget.DEFAULT);

        AnswerContextCompactionPolicy.PolicyProjection projection = policy.project(bundle,
                baseline, AnswerContextBudget.DEFAULT);

        assertThat(baseline.blocks()).isEmpty();
        assertThat(projection.blocks()).isEmpty();
        assertThat(projection.kinds()).isEmpty();
    }
}
