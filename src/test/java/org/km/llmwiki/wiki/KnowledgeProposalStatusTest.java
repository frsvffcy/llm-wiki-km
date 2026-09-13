package org.km.llmwiki.wiki;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Table-driven authority test for the proposal transition machine (#370): the enum's
 * {@link KnowledgeProposalStatus#allowedTransitions()} is the single source every
 * consumer (validation and the REST capability projection) reads, so any domain change
 * is a one-place edit and the Browser render contract follows automatically.
 */
@Tag("unit")
class KnowledgeProposalStatusTest {

    @Test
    void allowedTransitionsAreDerivedFromTheSingleDomainAuthority() {
        assertThat(KnowledgeProposalStatus.DRAFT.allowedTransitions())
                .containsExactly(KnowledgeProposalStatus.REVIEW, KnowledgeProposalStatus.REJECTED);
        assertThat(KnowledgeProposalStatus.REVIEW.allowedTransitions())
                .containsExactly(KnowledgeProposalStatus.APPROVED, KnowledgeProposalStatus.REJECTED);
        assertThat(KnowledgeProposalStatus.APPROVED.allowedTransitions()).isEmpty();
        assertThat(KnowledgeProposalStatus.REJECTED.allowedTransitions()).isEmpty();
    }

    @Test
    void transitionValidationAgreesWithTheSameAuthority() {
        for (KnowledgeProposalStatus status : KnowledgeProposalStatus.values()) {
            for (KnowledgeProposalStatus target : KnowledgeProposalStatus.values()) {
                boolean expected = status.allowedTransitions().contains(target);
                assertThat(status.canTransitionTo(target))
                        .as("%s -> %s", status, target)
                        .isEqualTo(expected);
            }
            assertThat(status.canTransitionTo(null)).isFalse();
        }
    }

    @Test
    void requireTransitionToThrowsTheTypedIllegalArgumentForIllegalMoves() {
        assertThatThrownBy(
                () -> KnowledgeProposalStatus.APPROVED.requireTransitionTo(KnowledgeProposalStatus.REVIEW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Illegal knowledge proposal status transition");
        org.assertj.core.api.Assertions.assertThatCode(
                () -> KnowledgeProposalStatus.DRAFT.requireTransitionTo(KnowledgeProposalStatus.REVIEW))
                .doesNotThrowAnyException();
    }
}
