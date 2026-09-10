package org.km.llmwiki.ai.answer;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.rag.EvidenceBundle;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class AnswerContextCompactionPolicyRegistryTest {

    @Test
    void resolvesTheActivePolicyAndItsVersion() {
        ContextPolicyV1Current policy = new ContextPolicyV1Current();
        AnswerContextCompactionPolicyRegistry registry = new AnswerContextCompactionPolicyRegistry(
                List.of(policy), ContextPolicyV1Current.VERSION);

        assertThat(registry.active()).isSameAs(policy);
        assertThat(registry.activeVersion()).isEqualTo(ContextPolicyV1Current.VERSION);
    }

    @Test
    void unknownActiveVersionFailsFast() {
        assertThatThrownBy(() -> new AnswerContextCompactionPolicyRegistry(
                List.of(new ContextPolicyV1Current()), "context-policy-v9-unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知的 context compaction policy version");
    }

    @Test
    void duplicatePolicyVersionsAreRejected() {
        assertThatThrownBy(() -> new AnswerContextCompactionPolicyRegistry(
                List.of(new ContextPolicyV1Current(), new ContextPolicyV1Current()),
                ContextPolicyV1Current.VERSION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重複的 context compaction policy version");
    }

    @Test
    void blankPolicyVersionsAreRejected() {
        AnswerContextCompactionPolicy unnamed = new AnswerContextCompactionPolicy() {
            @Override
            public String version() {
                return " ";
            }

            @Override
            public PolicyProjection project(EvidenceBundle ignored, AnswerContext baseline,
                                             AnswerContextBudget budget) {
                throw new AssertionError("must not be resolved");
            }
        };
        assertThatThrownBy(() -> new AnswerContextCompactionPolicyRegistry(List.of(unnamed),
                ContextPolicyV1Current.VERSION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("context policy version must not be blank");
    }
}
