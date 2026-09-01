package org.km.llmwiki.ai.ask;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.rag.RetrievalMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("contract")
class AskApiContractTest {

    @Test
    void requestBoundsQuestionByUnicodeCodePoints() {
        AskApiRequest request = new AskApiRequest("  中文😀  ", RetrievalMode.HYBRID_FTS);

        assertThat(request.question()).isEqualTo("中文😀");
        assertThat(request.toApplicationRequest().retrievalMode()).isEqualTo(RetrievalMode.HYBRID_FTS);
        assertThat(request.toApplicationRequest().question()).isEqualTo("中文😀");
    }

    @Test
    void rejectsBlankAndOversizedUnicodeQuestion() {
        assertThatThrownBy(() -> new AskApiRequest(" \t", RetrievalMode.WIKI_ONLY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AskApiRequest("😀".repeat(4_001), RetrievalMode.WIKI_ONLY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unicode code points");
    }

    @Test
    void responseProjectionOmitsEvidenceContentHashesAndUnsafeWikiPaths() {
        AskCitation citation = new AskCitation("E1", org.km.llmwiki.rag.EvidenceKind.WIKI,
                "WIKI:secret", "hash-secret",
                new org.km.llmwiki.ai.answer.AnswerContextProvenance.Wiki(
                        "Security", "/Users/private/vault/security.md", 3));
        AskResult result = new AskResult(AskStatus.ANSWERED,
                java.util.Optional.of("safe answer"), java.util.List.of(citation),
                java.util.List.of(citation), java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.empty(), new AskExecutionMetadata(1, 1, 4, false));

        AskApiResponse response = AskApiResponse.from(result);

        assertThat(response.citations()).hasSize(1);
        assertThat(response.citations().getFirst().provenance().path()).isNull();
        assertThat(response.citations().getFirst().provenance().title()).isEqualTo("Security");
    }

    @Test
    void answeredResultRequiresAtLeastOneCitation() {
        assertThatThrownBy(() -> new AskResult(AskStatus.ANSWERED,
                java.util.Optional.of("ungrounded answer"), java.util.List.of(),
                java.util.List.of(), java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.empty(), new AskExecutionMetadata(1, 1, 8, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one citation");
    }
}
