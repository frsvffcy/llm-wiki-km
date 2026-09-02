package org.km.llmwiki.ai.embedding;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("contract")
class EmbeddingContractTest {

    @Test
    void createsStableIdentityFromExactCanonicalText() {
        EmbeddingInput first = new EmbeddingInput("  CJK text 😀  ");
        EmbeddingInput second = new EmbeddingInput("  CJK text 😀  ");

        assertThat(first.identity()).isEqualTo(second.identity())
                .isEqualTo(EmbeddingInput.identityFor(first.text()));
        assertThat(first.identity()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(first.text()).isNotEqualTo(new EmbeddingInput("CJK text 😀").text());
    }

    @Test
    void acceptsSingleAndBatchInputsButCopiesBatchOrder() {
        EmbeddingRequest single = EmbeddingRequest.single("one");
        EmbeddingRequest batch = EmbeddingRequest.ofTexts(List.of("one", "two", "three"));

        assertThat(single.inputs()).extracting(EmbeddingInput::text).containsExactly("one");
        assertThat(batch.inputs()).extracting(EmbeddingInput::text)
                .containsExactly("one", "two", "three");
        assertThatThrownBy(() -> batch.inputs().add(new EmbeddingInput("four")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsUnboundedOrEmptyInputsAndNonFiniteVectors() {
        assertThatThrownBy(() -> EmbeddingRequest.single(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmbeddingRequest.single("x".repeat(EmbeddingInput.MAX_TEXT_CODE_POINTS + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmbeddingVector(new EmbeddingInput("x").identity(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmbeddingVector(new EmbeddingInput("x").identity(),
                List.of(Double.NaN)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmbeddingVector(new EmbeddingInput("x").identity(),
                List.of(Double.POSITIVE_INFINITY)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keepsVectorsImmutableAndRequiresOneDimension() {
        String first = new EmbeddingInput("one").identity();
        String second = new EmbeddingInput("two").identity();
        EmbeddingResult result = new EmbeddingResult(List.of(
                new EmbeddingVector(first, List.of(1d, 2d)),
                new EmbeddingVector(second, List.of(3d, 4d))),
                new EmbeddingProviderMetadata("openai-compatible", "model"),
                java.util.Optional.empty());

        assertThat(result.dimension()).isEqualTo(2);
        assertThatThrownBy(() -> result.vectors().getFirst().values().set(0, 9d))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new EmbeddingResult(List.of(
                new EmbeddingVector(first, List.of(1d)),
                new EmbeddingVector(second, List.of(1d, 2d))),
                new EmbeddingProviderMetadata("provider", "model"), java.util.Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
