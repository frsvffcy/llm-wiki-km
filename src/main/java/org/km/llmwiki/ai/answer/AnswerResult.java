package org.km.llmwiki.ai.answer;

import java.util.Objects;
import java.util.Optional;

/** Immutable generated answer and non-secret provider metadata. */
public record AnswerResult(
        String answerText,
        AnswerProviderMetadata providerMetadata,
        Optional<AnswerUsageMetadata> usage
) {

    private static final int MAX_ANSWER_CHARACTERS = 100_000;

    public AnswerResult {
        if (answerText == null || answerText.isBlank()) {
            throw new IllegalArgumentException("answerText must not be blank");
        }
        if (answerText.length() > MAX_ANSWER_CHARACTERS) {
            throw new IllegalArgumentException("answerText exceeds the bounded result size");
        }
        providerMetadata = Objects.requireNonNull(providerMetadata, "providerMetadata must not be null");
        usage = usage == null ? Optional.empty() : usage;
    }

    public AnswerResult(String answerText, AnswerProviderMetadata providerMetadata) {
        this(answerText, providerMetadata, Optional.empty());
    }
}
