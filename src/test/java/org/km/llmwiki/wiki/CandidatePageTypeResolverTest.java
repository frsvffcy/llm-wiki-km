package org.km.llmwiki.wiki;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.ai.KnowledgeCandidateType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class CandidatePageTypeResolverTest {

    private final CandidatePageTypeResolver resolver = new CandidatePageTypeResolver();

    @Test
    void usesExplicitPolicyInsteadOfCandidateEnumNameMapping() {
        assertThat(resolver.resolve(KnowledgeCandidateType.CONCEPT, null)).isEqualTo(WikiPageType.CONCEPT);
        assertThat(resolver.resolve(KnowledgeCandidateType.CONCEPT, WikiPageType.TECHNOLOGY))
                .isEqualTo(WikiPageType.TECHNOLOGY);
        assertThat(resolver.resolve(KnowledgeCandidateType.DECISION, null)).isEqualTo(WikiPageType.DECISION);
        assertThat(resolver.resolve(KnowledgeCandidateType.REFERENCE, WikiPageType.TECHNOLOGY))
                .isEqualTo(WikiPageType.TECHNOLOGY);
    }

    @Test
    void factAndProcedureRequireIntentionalResolution() {
        assertThatThrownBy(() -> resolver.resolve(KnowledgeCandidateType.FACT, null))
                .isInstanceOf(WikiDraftValidationException.class)
                .extracting(error -> ((WikiDraftValidationException) error).reason())
                .isEqualTo(WikiDraftValidationException.Reason.AMBIGUOUS_CANDIDATE_MAPPING);
        assertThatThrownBy(() -> resolver.resolve(KnowledgeCandidateType.PROCEDURE, null))
                .isInstanceOf(WikiDraftValidationException.class)
                .extracting(error -> ((WikiDraftValidationException) error).reason())
                .isEqualTo(WikiDraftValidationException.Reason.AMBIGUOUS_CANDIDATE_MAPPING);

        assertThat(resolver.resolve(KnowledgeCandidateType.FACT, WikiPageType.PROJECT))
                .isEqualTo(WikiPageType.PROJECT);
        assertThat(resolver.resolve(KnowledgeCandidateType.PROCEDURE, WikiPageType.HOWTO))
                .isEqualTo(WikiPageType.HOWTO);
        assertThat(resolver.resolve(KnowledgeCandidateType.PROCEDURE, WikiPageType.TROUBLESHOOTING))
                .isEqualTo(WikiPageType.TROUBLESHOOTING);
    }

    @Test
    void rejectsUnsupportedCandidateAndPageTypeCombination() {
        assertThatThrownBy(() -> resolver.resolve(KnowledgeCandidateType.FACT, WikiPageType.HOWTO))
                .isInstanceOf(WikiDraftValidationException.class)
                .extracting(error -> ((WikiDraftValidationException) error).reason())
                .isEqualTo(WikiDraftValidationException.Reason.UNSUPPORTED_CANDIDATE_MAPPING);
        assertThatThrownBy(() -> resolver.resolve(KnowledgeCandidateType.DECISION, WikiPageType.CONCEPT))
                .isInstanceOf(WikiDraftValidationException.class)
                .extracting(error -> ((WikiDraftValidationException) error).reason())
                .isEqualTo(WikiDraftValidationException.Reason.UNSUPPORTED_CANDIDATE_MAPPING);
    }
}
