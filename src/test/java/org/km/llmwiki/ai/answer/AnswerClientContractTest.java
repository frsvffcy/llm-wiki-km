package org.km.llmwiki.ai.answer;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.ai.LlmClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("contract")
class AnswerClientContractTest {

    @Test
    void exposesAnImmutableProviderNeutralRequestAndResultContract() {
        AnswerContextReference reference = new AnswerContextReference("WIKI:architecture", "hash-1");
        AnswerContext context = new AnswerContext(List.of(reference));
        AnswerRequest request = new AnswerRequest("What is the architecture?", context,
                AnswerGenerationOptions.defaults());
        AnswerProviderMetadata metadata = new AnswerProviderMetadata("stub", "test-model");
        AnswerResult result = new AnswerResult("The architecture is local-first.", metadata,
                Optional.of(new AnswerUsageMetadata(12, 8, 20)));

        assertThat(request.question()).isEqualTo("What is the architecture?");
        assertThat(request.context().references()).containsExactly(reference);
        assertThat(request.options().maxOutputCharacters()).isEqualTo(4_000);
        assertThat(result.providerMetadata()).isEqualTo(metadata);
        assertThat(result.usage()).contains(new AnswerUsageMetadata(12, 8, 20));

        assertThatThrownBy(() -> request.context().references().add(reference))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void permitsEmptyContextForTheBoundaryButKeepsTheContextBoundedAndUnique() {
        assertThat(AnswerContext.empty().references()).isEmpty();

        AnswerContextReference reference = new AnswerContextReference("SOURCE_CHUNK:41", "hash-41");
        assertThatThrownBy(() -> new AnswerContext(List.of(reference, reference)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique");
        assertThatThrownBy(() -> new AnswerContext(
                java.util.stream.IntStream.range(0, AnswerContext.MAX_REFERENCES + 1)
                        .mapToObj(index -> new AnswerContextReference("WIKI:" + index, "hash-" + index))
                        .toList()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not exceed");
    }

    @Test
    void rejectsInvalidRequestAndProviderMetadataAtTheDomainBoundary() {
        assertThatThrownBy(() -> new AnswerRequest(" ", AnswerContext.empty(),
                AnswerGenerationOptions.defaults()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("question must not be blank");
        assertThatThrownBy(() -> new AnswerRequest("question", AnswerContext.empty(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("options");
        assertThatThrownBy(() -> new AnswerGenerationOptions(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnswerProviderMetadata("provider", "model\nwith-header"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnswerUsageMetadata(-1, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inputTokens");
    }

    @Test
    void keepsAnswerClientIndependentFromTheDocumentAnalysisClient() {
        assertThat(AnswerClient.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .containsExactly("generate");
        assertThat(LlmClient.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .containsExactly("analyze");
    }
}
