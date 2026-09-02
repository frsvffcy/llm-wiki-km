package org.km.llmwiki.ai.embedding;

import java.util.List;
import java.util.Objects;

/** Immutable provider-neutral request for one or more embedding inputs. */
public record EmbeddingRequest(List<EmbeddingInput> inputs) {

    public static final int MAX_INPUTS = 128;
    public static final int MAX_TOTAL_TEXT_CODE_POINTS = 64_000;

    public EmbeddingRequest {
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("embedding inputs must not be empty");
        }
        if (inputs.size() > MAX_INPUTS) {
            throw new IllegalArgumentException("embedding input count exceeds the bounded limit");
        }
        if (inputs.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("embedding inputs must not contain null values");
        }
        long totalCodePoints = inputs.stream()
                .mapToLong(input -> input.text().codePointCount(0, input.text().length()))
                .sum();
        if (totalCodePoints > MAX_TOTAL_TEXT_CODE_POINTS) {
            throw new IllegalArgumentException("embedding input batch exceeds the bounded size");
        }
        inputs = List.copyOf(inputs);
    }

    public static EmbeddingRequest single(String text) {
        return new EmbeddingRequest(List.of(new EmbeddingInput(text)));
    }

    public static EmbeddingRequest ofTexts(List<String> texts) {
        if (texts == null) {
            throw new IllegalArgumentException("embedding texts must not be null");
        }
        return new EmbeddingRequest(texts.stream().map(EmbeddingInput::new).toList());
    }
}
